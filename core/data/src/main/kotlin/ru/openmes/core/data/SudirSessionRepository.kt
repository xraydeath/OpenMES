package ru.openmes.core.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.openmes.core.model.Person
import ru.openmes.core.network.MesEnvironment
import ru.openmes.core.network.api.ClientRegistrationRequest
import ru.openmes.core.network.api.MesApi
import ru.openmes.core.network.api.MeshAuthApi
import ru.openmes.core.network.api.SudirApi
import ru.openmes.core.network.interceptor.OfflineCache
import ru.openmes.core.network.api.SudirAuthRequest

/**
 * Вход через SUDIR — точная копия флоу оригинального приложения (сборка po),
 * с авторизацией во внешнем браузере и возвратом по deeplink:
 *
 *  1. POST /sps/oauth/register — динамическая регистрация (software_id=diary-po,
 *     software_statement=JWT, Bearer initialAccessToken); TTL регистрации ~1 час;
 *  2. GET  /sps/oauth/ae — браузер (Custom Tabs): PKCE S256, prompt=login,
 *     redirect diary-po://oauth2redirect;
 *  3. Deeplink diary-po://oauth2redirect?code=…&state=… — возврат в приложение;
 *  4. POST /sps/oauth/te — обмен кода (Basic auth, code_verifier);
 *  5. POST school.mos.ru/v3/auth/sudir/auth — mesh_access_token (aupd_token);
 *  6. GET  /api/profeducation/acl/v1/mod-acl/users/profile_info — профили;
 *  7. Все API-запросы: Auth-Token/Authorization/Profile-Id/x-mes-subsystem: familypom.
 */
class SudirSessionRepository(
    private val sudirApi: SudirApi,
    private val meshAuthApi: MeshAuthApi,
    private val mesApi: MesApi,
    private val tokenStore: TokenStore,
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val offlineCache: OfflineCache,
) : SessionRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _session = MutableStateFlow<Session>(Session.Loading)
    override val session: StateFlow<Session> = _session.asStateFlow()

    init {
        scope.launch { restoreSession() }
    }

    // -------------------------------------------------------------------
    // Шаг 1: динамическая регистрация OAuth-клиента (TTL)
    // -------------------------------------------------------------------

    override suspend fun ensureOAuthClient() {
        val existing = tokenStore.load()
        val hasClient = !existing?.oauthClientId.isNullOrBlank() && !existing?.oauthClientSecret.isNullOrBlank()
        val registrationFresh = existing?.registeredAtMillis != null &&
            System.currentTimeMillis() - existing.registeredAtMillis!! < REGISTRATION_TTL_MS
        if (hasClient && registrationFresh) return

        val registration = try {
            sudirApi.registerClient(
                ClientRegistrationRequest(
                    softwareId = MesEnvironment.SOFTWARE_ID,
                    deviceType = MesEnvironment.DEVICE_TYPE_ANDROID_PHONE,
                    softwareStatement = MesEnvironment.SOFTWARE_STATEMENT,
                ),
            )
        } catch (e: retrofit2.HttpException) {
            val body = e.response()?.errorBody()?.string().orEmpty()
            val description = Regex("\"error_description\"\\s*:\\s*\"([^\"]+)\"")
                .find(body)?.groupValues?.get(1)
            throw IllegalStateException(
                "Регистрация клиента SUDIR отклонена (HTTP ${e.code()}): ${description ?: e.message()}",
            )
        }
        if (registration.clientId.isBlank()) {
            throw IllegalStateException("SUDIR вернул пустой client_id")
        }
        val current = tokenStore.load() ?: AuthTokens()
        tokenStore.save(
            current.copy(
                oauthClientId = registration.clientId,
                oauthClientSecret = registration.clientSecret,
                registeredAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    // -------------------------------------------------------------------
    // Шаг 2: URL авторизации (PKCE)
    // -------------------------------------------------------------------

    override fun buildLoginUrl(): String {
        val tokens = tokenStore.load()
        val clientId = tokens?.oauthClientId ?: error("OAuth-клиент не зарегистрирован — сначала ensureOAuthClient()")

        // PKCE: code_verifier 43..128 символов, challenge = BASE64URL(SHA-256(verifier)).
        val verifier = generateCodeVerifier()
        val state = generateState()
        val challenge = pkceChallenge(verifier)

        tokenStore.save(
            tokens.copy(pendingCodeVerifier = verifier, pendingState = state),
        )

        // Сборка URL как в оригинале (stringifyUrl, encode=false): scope — RAW-строка с '+'.
        return MesEnvironment.SUDIR_BASE_URL + "sps/oauth/ae" +
            "?scope=" + MesEnvironment.OAUTH_SCOPES +
            "&response_type=code" +
            "&access_type=offline" +
            "&state=" + state +
            "&client_id=" + clientId +
            "&redirect_uri=" + MesEnvironment.OAUTH_REDIRECT_URI +
            "&prompt=login" +
            "&code_challenge=" + challenge +
            "&code_challenge_method=S256"
    }

    // -------------------------------------------------------------------
    // Шаги 3-5: обработка redirect из браузера
    // -------------------------------------------------------------------

    override fun handleDeepLink(url: String) {
        if (!url.startsWith(MesEnvironment.OAUTH_REDIRECT_URI)) return
        scope.launch {
            runCatching { onLoginRedirect(url) }
                .onFailure { e ->
                    _session.value = Session.LoggedOut(error = e.message ?: "Ошибка авторизации")
                }
        }
    }

    override suspend fun onLoginRedirect(url: String): Boolean {
        // URL не логируем: в нём одноразовый code авторизации.
        if (!url.startsWith(MesEnvironment.OAUTH_REDIRECT_URI)) return false

        val uri = Uri.parse(url)
        if (uri.getQueryParameter("error") != null) {
            _session.value = Session.LoggedOut(error = uri.getQueryParameter("error_description"))
            return true
        }
        val code = uri.getQueryParameter("code") ?: run {
            _session.value = Session.LoggedOut(error = "Ссылка возврата без кода авторизации")
            return true
        }

        val tokens = tokenStore.load() ?: return true
        // Валидация state (stateMismatchError в оригинале).
        val expectedState = tokens.pendingState
        val actualState = uri.getQueryParameter("state")
        if (expectedState == null || expectedState != actualState) {
            _session.value = Session.LoggedOut(error = "state mismatch: ссылка не совпадает с запросом")
            return true
        }

        // Шаг 4: обмен кода на SUDIR-токены (Basic auth).
        // refresh_token — SUDIR-токен для повторного входа без пароля (15 суток).
        val basic = basicAuth(tokens.oauthClientId!!, tokens.oauthClientSecret!!)
        val codeVerifier: String = tokens.pendingCodeVerifier.orEmpty()
        val tokenResponse = sudirApi.token(
            basicAuth = basic,
            form = mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to MesEnvironment.OAUTH_REDIRECT_URI,
                "code_verifier" to codeVerifier,
            ),
        )
        val sudirAccessToken = tokenResponse.accessToken
        if (sudirAccessToken.isBlank()) {
            _session.value = Session.LoggedOut(error = "SUDIR не выдал access_token")
            return true
        }

        tokenStore.save(
            tokens.copy(
                sudirAccessToken = sudirAccessToken,
                sudirRefreshToken = tokenResponse.refreshToken,
                sudirExpiresAtMillis = System.currentTimeMillis() + (tokenResponse.expiresIn ?: 3600L) * 1000,
                pendingCodeVerifier = null,
                pendingState = null,
            ),
        )

        // Шаг 5: обмен SUDIR-токена на МЭШ-токен (mesh_access_token = API-токен).
        val meshAccessToken = exchangeSudirToken(sudirAccessToken)

        tokenStore.save(
            tokenStore.load()?.copy(meshAccessToken = meshAccessToken) ?: AuthTokens(meshAccessToken = meshAccessToken),
        )

        // Шаг 6: профили.
        loadProfiles(sudirAccessToken, meshAccessToken)
        return true
    }

    // -------------------------------------------------------------------
    // Рефреш: SUDIR /sps/oauth/te → новый access → /v3/auth/sudir/auth → новый mesh
    // (схема OctoDiary: API-токен — всегда свежий mesh_access_token)
    // -------------------------------------------------------------------

    override suspend fun refreshTokens(): Boolean {
        val tokens = tokenStore.load() ?: return false
        val refreshToken = tokens.sudirRefreshToken ?: return false
        if (refreshToken.isBlank() || tokens.oauthClientId.isNullOrBlank()) return false
        return runCatching {
            val basic = basicAuth(tokens.oauthClientId!!, tokens.oauthClientSecret.orEmpty())
            val tokenResponse = sudirApi.token(
                basicAuth = basic,
                form = mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                ),
            )
            val newAccess = tokenResponse.accessToken
            if (newAccess.isBlank()) return@runCatching false

            val newMesh = exchangeSudirToken(newAccess)

            tokenStore.save(
                tokens.copy(
                    sudirAccessToken = newAccess,
                    sudirRefreshToken = tokenResponse.refreshToken ?: refreshToken,
                    sudirExpiresAtMillis = System.currentTimeMillis() + (tokenResponse.expiresIn ?: 3600L) * 1000,
                    meshAccessToken = newMesh,
                ),
            )
            true
        }.getOrDefault(false)
    }

    /** POST /v3/auth/sudir/auth → mesh_access_token (API-токен МЭШ). */
    private suspend fun exchangeSudirToken(sudirAccessToken: String): String {
        val response = meshAuthApi.authSudir(
            SudirAuthRequest(
                userAuthenticationForMobileRequest = SudirAuthRequest.MosAccessTokenPayload(
                    mosAccessToken = sudirAccessToken,
                ),
            ),
        )
        val mesh = response.userAuthenticationForMobileResponse
            ?: throw IllegalStateException("МЭШ не выдал токен (нет учётной записи со СНИЛС?)")
        if (mesh.meshAccessToken.isBlank()) {
            throw IllegalStateException("МЭШ вернул пустой mesh_access_token")
        }
        return mesh.meshAccessToken
    }

    override suspend fun selectChild(personId: String) {
        val current = _session.value as? Session.LoggedIn ?: return
        val child = current.children.firstOrNull { it.id == personId } ?: return
        // student_id (mapi) и contingent_guid (eventcalendar) — от выбранного ребёнка;
        // profileId остаётся id ACL-профиля (он же в заголовке Profile-Id).
        tokenStore.load()?.let {
            tokenStore.save(it.copy(studentId = child.id, personGuid = child.personGuid))
        }
        _session.value = current.copy(currentChild = child)
    }

    override suspend fun logout() {
        withContext(Dispatchers.IO) {
            tokenStore.clear()
            offlineCache.clear()
        }
        _session.value = Session.LoggedOut()
    }

    // -------------------------------------------------------------------
    // Внутреннее
    // -------------------------------------------------------------------

    private suspend fun restoreSession() {
        android.util.Log.i("OpenMES-Session", "restoreSession: старт")
        val tokens = withContext(Dispatchers.IO) { tokenStore.load() }
        android.util.Log.i("OpenMES-Session", "restoreSession: tokens=${if (tokens == null) "null" else "есть, mesh=${tokens.meshAccessToken != null}, sudirRefresh=${tokens.sudirRefreshToken != null}"}")
        if (tokens == null) {
            _session.value = Session.LoggedOut()
            android.util.Log.i("OpenMES-Session", "restoreSession: → LoggedOut (нет токенов)")
            return
        }
        if (tokens.sudirRefreshToken != null && tokens.sudirRefreshToken.isNotBlank()) {
            // Обновляем SUDIR-токен и mesh за один заход (схема OctoDiary).
            android.util.Log.i("OpenMES-Session", "restoreSession: пробуем refresh")
            if (refreshTokens()) {
                android.util.Log.i("OpenMES-Session", "restoreSession: refresh OK, грузим профили")
                val fresh = withContext(Dispatchers.IO) { tokenStore.load() }
                if (fresh?.meshAccessToken != null) {
                    loadProfiles(fresh.sudirAccessToken.orEmpty(), fresh.meshAccessToken)
                    return
                }
            } else {
                android.util.Log.i("OpenMES-Session", "restoreSession: refresh FAILED")
            }
        }
        if (tokens.meshAccessToken != null) {
            android.util.Log.i("OpenMES-Session", "restoreSession: грузим профили со старым mesh")
            runCatching { loadProfiles(tokens.sudirAccessToken.orEmpty(), tokens.meshAccessToken) }
                .onFailure {
                    android.util.Log.i("OpenMES-Session", "restoreSession: профили упали: ${it.message}")
                    _session.value = Session.LoggedOut(error = "Сессия истекла — войдите заново")
                }
        } else {
            android.util.Log.i("OpenMES-Session", "restoreSession: → LoggedOut (нет mesh)")
            _session.value = Session.LoggedOut()
        }
    }

    /** GET /api/profeducation/acl/v1/mod-acl/users/profile_info → сессия.
     * Затем GET /api/family/mobile/v1/profile → student_id + имя + класс. */
    /** ACL-активация токена → collegeProfile → сессия (логика рабочего форка OctoDiary-kt). */
    private suspend fun loadProfiles(sudirToken: String, meshToken: String) {
        android.util.Log.i("OpenMES-Session", "loadProfiles: старт")
        runCatching {
            // 1. Активация: profile_info (токен ДО этого запроса недействителен для остальных!).
            val profiles = mesApi.getProfileInfo(authToken = meshToken)
            android.util.Log.i("OpenMES-Session", "loadProfiles: профилей=${profiles.size}")

            // 2. Первая поддерживаемая ACL-запись (StudentProfile→32, ParentProfile→2).
            val selected = profiles.firstOrNull { p ->
                val t = p.type.orEmpty()
                t == "StudentProfile" || t == "student" || t == "ParentProfile" || t == "parent"
            } ?: throw IllegalStateException("Нет поддерживаемой роли профиля")
            val profileId = selected.id?.toLongOrNull()
                ?: throw IllegalStateException("ACL-запись без id")
            val roleId = if (selected.type == "ParentProfile" || selected.type == "parent") 2 else 32
            // x-row-limit = сумма цифр Profile-Id (официальная LIMIT-подпись из форка).
            val rowLimit = if (profileId == 0L) "" else profileId.toString().sumOf { it.digitToInt() }.toString()

            // 3. Family profile с полным набором заголовков.
            val familyProfile = mesApi.getFamilyProfile(
                profileId = profileId,
                roleId = roleId,
                rowLimit = rowLimit,
            )
            android.util.Log.i("OpenMES-Session", "loadProfiles: children=${familyProfile.children.size}")

            val children = familyProfile.children.mapNotNull { c ->
                val id = c.id?.toString() ?: return@mapNotNull null
                Person(
                    id = id,
                    firstName = c.firstName.orEmpty(),
                    lastName = c.lastName.orEmpty(),
                    middleName = c.middleName,
                    className = c.className,
                    personGuid = c.contingentGuid,
                )
            }

            val tokens = tokenStore.load()
            // Сохраняем выбор ребёнка между запусками (у родителя их может быть несколько).
            val child = children.firstOrNull { it.id == tokens?.studentId } ?: children.firstOrNull()
            val self = familyProfile.profile
            val person = if (roleId == 32 || self == null) {
                child ?: Person(id = profileId.toString(), firstName = "", lastName = "")
            } else {
                Person(
                    id = profileId.toString(),
                    firstName = self.firstName.orEmpty(),
                    lastName = self.lastName.orEmpty(),
                )
            }

            tokenStore.save(
                (tokens ?: AuthTokens()).copy(
                    profileId = profileId.toString(),
                    studentId = child?.id ?: profileId.toString(),
                    personGuid = child?.personGuid,
                    roleId = roleId.toString(),
                    profileRole = selected.type,
                ),
            )
            _session.value = Session.LoggedIn(
                person = person,
                children = children,
                currentChild = child ?: person,
            )
            android.util.Log.i("OpenMES-Session", "loadProfiles: сессия LoggedIn, детей=${children.size}")
        }.onFailure { e ->
            android.util.Log.i("OpenMES-Session", "loadProfiles: ошибка: ${e.message}")
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Раньше тут была «пустая» LoggedIn-сессия без ребёнка: экраны молча ничего не грузили.
            _session.value = Session.LoggedOut(error = "Не удалось загрузить профиль: ${e.message}")
        }
    }

    private fun basicAuth(clientId: String, clientSecret: String): String {
        val raw = "$clientId:$clientSecret"
        return "Basic " + Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)
    }

    private fun generateCodeVerifier(): String {
        val length = MesEnvironment.CODE_VERIFIER_MIN_LENGTH +
            SecureRandom().nextInt(MesEnvironment.CODE_VERIFIER_MAX_LENGTH - MesEnvironment.CODE_VERIFIER_MIN_LENGTH + 1)
        val alphabet = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '_', '.', '~')
        return (1..length).map { alphabet[SecureRandom().nextInt(alphabet.size)] }.joinToString("")
    }

    private fun generateState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return buildString {
            bytes.forEach { b ->
                append(((b.toInt() and 0xFF) + 0x100).toString(16).substring(1))
            }
        }
    }

    /** BASE64URL(SHA-256(verifier)) без padding — PKCE S256. */
    private fun pkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private companion object {
        /** LEGAL_AUTH_REGISTRATION_PERIOD = 3600 c, с запасом 10 минут. */
        const val REGISTRATION_TTL_MS = 50L * 60 * 1000
    }
}
