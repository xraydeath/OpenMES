package ru.openmes.core.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.openmes.core.common.runSuspendCatching
import ru.openmes.core.model.Person
import ru.openmes.core.network.BuildConfig
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
        val existing = withContext(Dispatchers.IO) { tokenStore.load() }
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
        withContext(Dispatchers.IO) {
            tokenStore.update { current ->
                (current ?: AuthTokens()).copy(
                    oauthClientId = registration.clientId,
                    oauthClientSecret = registration.clientSecret,
                    registeredAtMillis = System.currentTimeMillis(),
                )
            }
        }
    }

    // -------------------------------------------------------------------
    // Шаг 2: URL авторизации (PKCE)
    // -------------------------------------------------------------------

    override fun buildLoginUrl(): String {
        // PKCE: code_verifier 43..128 символов, challenge = BASE64URL(SHA-256(verifier)).
        val verifier = generateCodeVerifier()
        val state = generateState()
        val challenge = pkceChallenge(verifier)

        val tokens = tokenStore.update { current ->
            current?.takeIf { it.oauthClientId != null }
                ?.copy(pendingCodeVerifier = verifier, pendingState = state)
        }
        val clientId = tokens?.oauthClientId?.takeIf { tokens.pendingState == state }
            ?: error("OAuth-клиент не зарегистрирован — сначала ensureOAuthClient()")

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
            runSuspendCatching { onLoginRedirect(url) }
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

        val tokens = withContext(Dispatchers.IO) { tokenStore.load() } ?: return true
        // Валидация state (stateMismatchError в оригинале).
        val expectedState = tokens.pendingState
        val actualState = uri.getQueryParameter("state")
        // Входа не начинали (ссылка из истории/повторная доставка intent) — молча игнорируем.
        if (expectedState == null) return true
        if (expectedState != actualState) {
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

        // Новый аккаунт: данные прошлого в памяти не нужны.
        MemoryCache.clearAll()
        withContext(Dispatchers.IO) {
            tokenStore.update { current ->
                (current ?: tokens).copy(
                    sudirAccessToken = sudirAccessToken,
                    sudirRefreshToken = tokenResponse.refreshToken,
                    sudirExpiresAtMillis = System.currentTimeMillis() + (tokenResponse.expiresIn ?: 3600L) * 1000,
                    pendingCodeVerifier = null,
                    pendingState = null,
                )
            }
        }

        // Шаг 5: обмен SUDIR-токена на МЭШ-токен (mesh_access_token = API-токен).
        val meshAccessToken = exchangeSudirToken(sudirAccessToken)

        withContext(Dispatchers.IO) {
            tokenStore.update { current ->
                (current ?: AuthTokens()).copy(
                    meshAccessToken = meshAccessToken,
                    meshIssuedAtMillis = System.currentTimeMillis(),
                )
            }
        }

        // Шаг 6: профили.
        loadProfiles(sudirAccessToken, meshAccessToken)
        return true
    }

    // -------------------------------------------------------------------
    // Рефреш: SUDIR /sps/oauth/te → новый access → /v3/auth/sudir/auth → новый mesh
    // (схема OctoDiary: API-токен — всегда свежий mesh_access_token)
    // -------------------------------------------------------------------

    private val refreshLock = Any()

    /** Идущее обновление: параллельные вызовы ждут его, а не запускают второй refresh_token grant. */
    private var refreshInFlight: Deferred<RefreshResult>? = null

    override suspend fun refreshTokens(): Boolean = refresh() == RefreshResult.OK

    /** Однопоточное обновление: живёт в [scope], отмена одного из ждущих его не прерывает. */
    private suspend fun refresh(): RefreshResult {
        val deferred = synchronized(refreshLock) {
            refreshInFlight?.takeIf { it.isActive }
                ?: scope.async {
                    runSuspendCatching { doRefresh() }.getOrDefault(RefreshResult.FAILED)
                }.also { refreshInFlight = it }
        }
        return deferred.await()
    }

    private suspend fun doRefresh(): RefreshResult {
        val tokens = tokenStore.load() ?: return RefreshResult.NO_SESSION
        val refreshToken = tokens.sudirRefreshToken
        val clientId = tokens.oauthClientId
        if (refreshToken.isNullOrBlank() || clientId.isNullOrBlank()) return RefreshResult.NO_SESSION

        val tokenResponse = try {
            sudirApi.token(
                basicAuth = basicAuth(clientId, tokens.oauthClientSecret.orEmpty()),
                form = mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val result = classifyRefreshError(e)
            log("refresh: SUDIR te → $result")
            // Refresh-токен отозван/истёк — сессию не вернуть; сеть/5xx — пробуем позже.
            if (result == RefreshResult.REJECTED) expireSession(refreshToken)
            return result
        }
        val newAccess = tokenResponse.accessToken
        if (newAccess.isBlank()) return RefreshResult.FAILED
        val newRefresh = tokenResponse.refreshToken?.takeIf { it.isNotBlank() } ?: refreshToken

        // SUDIR мог выдать новый refresh-токен (старый уже недействителен): сохраняем сразу,
        // даже если обмен на mesh ниже сорвётся. Только поверх текущих токенов и только если
        // за время запроса не вышли из аккаунта и не вошли заново.
        var applied = false
        tokenStore.update { current ->
            current?.takeIf { it.sudirRefreshToken == refreshToken }?.copy(
                sudirAccessToken = newAccess,
                sudirRefreshToken = newRefresh,
                sudirExpiresAtMillis = System.currentTimeMillis() + (tokenResponse.expiresIn ?: 3600L) * 1000,
            )?.also { applied = true }
        }
        if (!applied) return RefreshResult.FAILED

        val newMesh = runSuspendCatching { exchangeSudirToken(newAccess) }
            .getOrElse { return RefreshResult.FAILED }
        // Новый mesh недействителен, пока не вызван profile_info; его сбой не критичен.
        runSuspendCatching {
            meshAuthApi.getProfileInfo(authToken = newMesh, authorization = "Bearer $newMesh")
        }

        applied = false
        tokenStore.update { current ->
            current?.takeIf { it.sudirRefreshToken == newRefresh }?.copy(
                meshAccessToken = newMesh,
                meshIssuedAtMillis = System.currentTimeMillis(),
            )?.also { applied = true }
        }
        return if (applied) RefreshResult.OK else RefreshResult.FAILED
    }

    /** Выход после отказа SUDIR — если за это время не вошли заново с другим refresh-токеном. */
    private suspend fun expireSession(rejectedRefreshToken: String) {
        if (tokenStore.load()?.sudirRefreshToken != rejectedRefreshToken) return
        clearSession()
        _session.value = Session.LoggedOut(error = "Сессия истекла — войдите заново")
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
        withContext(Dispatchers.IO) {
            tokenStore.update { it?.copy(studentId = child.id, personGuid = child.personGuid) }
        }
        _session.value = current.copy(currentChild = child)
    }

    override suspend fun logout() {
        clearSession()
        _session.value = Session.LoggedOut()
    }

    /** Токены, офлайн-кэш и кэши в памяти — чтобы данные аккаунта не пережили выход. */
    private suspend fun clearSession() {
        withContext(Dispatchers.IO) {
            tokenStore.clear()
            offlineCache.clear()
        }
        MemoryCache.clearAll()
    }

    // -------------------------------------------------------------------
    // Внутреннее
    // -------------------------------------------------------------------

    private suspend fun restoreSession() {
        log("restoreSession: старт")
        val tokens = withContext(Dispatchers.IO) { tokenStore.load() }
        log("restoreSession: tokens=${if (tokens == null) "null" else "есть, mesh=${tokens.meshAccessToken != null}, sudirRefresh=${tokens.sudirRefreshToken != null}"}")
        if (tokens == null) {
            _session.value = Session.LoggedOut()
            log("restoreSession: → LoggedOut (нет токенов)")
            return
        }
        if (tokens.sudirRefreshToken != null && tokens.sudirRefreshToken.isNotBlank()) {
            // Обновляем SUDIR-токен и mesh за один заход (схема OctoDiary).
            log("restoreSession: пробуем refresh")
            when (refresh()) {
                RefreshResult.OK -> {
                    log("restoreSession: refresh OK, грузим профили")
                    val fresh = withContext(Dispatchers.IO) { tokenStore.load() }
                    if (fresh?.meshAccessToken != null) {
                        loadProfiles(fresh.sudirAccessToken.orEmpty(), fresh.meshAccessToken)
                        return
                    }
                }
                // Сессия уже сброшена в expireSession.
                RefreshResult.REJECTED -> return
                // Без сети — пробуем старый mesh (профиль найдётся в офлайн-кэше).
                else -> log("restoreSession: refresh FAILED")
            }
        }
        if (tokens.meshAccessToken != null) {
            log("restoreSession: грузим профили со старым mesh")
            runSuspendCatching { loadProfiles(tokens.sudirAccessToken.orEmpty(), tokens.meshAccessToken) }
                .onFailure {
                    log("restoreSession: профили упали: ${it.message}")
                    _session.value = Session.LoggedOut(error = "Сессия истекла — войдите заново")
                }
        } else {
            log("restoreSession: → LoggedOut (нет mesh)")
            _session.value = Session.LoggedOut()
        }
    }

    /** GET /api/profeducation/acl/v1/mod-acl/users/profile_info → сессия.
     * Затем GET /api/family/mobile/v1/profile → student_id + имя + класс. */
    /** ACL-активация токена → collegeProfile → сессия (логика рабочего форка OctoDiary-kt). */
    private suspend fun loadProfiles(sudirToken: String, meshToken: String) {
        log("loadProfiles: старт")
        runSuspendCatching {
            // 1. Активация: profile_info (токен ДО этого запроса недействителен для остальных!).
            val profiles = mesApi.getProfileInfo(authToken = meshToken)
            log("loadProfiles: профилей=${profiles.size}")

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
            log("loadProfiles: children=${familyProfile.children.size}")

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

            // Сохраняем выбор ребёнка между запусками (у родителя их может быть несколько).
            val savedStudentId = withContext(Dispatchers.IO) { tokenStore.load()?.studentId }
            val child = children.firstOrNull { it.id == savedStudentId } ?: children.firstOrNull()
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

            // Поверх текущих токенов; вышли из аккаунта, пока грузили, — сессию не воскрешаем.
            withContext(Dispatchers.IO) {
                tokenStore.update { current ->
                    current?.copy(
                        profileId = profileId.toString(),
                        studentId = child?.id ?: profileId.toString(),
                        personGuid = child?.personGuid,
                        roleId = roleId.toString(),
                        profileRole = selected.type,
                    )
                }
            } ?: return@runSuspendCatching
            _session.value = Session.LoggedIn(
                person = person,
                children = children,
                currentChild = child ?: person,
            )
            log("loadProfiles: сессия LoggedIn, детей=${children.size}")
        }.onFailure { e ->
            log("loadProfiles: ошибка: ${e.message}")
            // Раньше тут была «пустая» LoggedIn-сессия без ребёнка: экраны молча ничего не грузили.
            _session.value = Session.LoggedOut(error = "Не удалось загрузить профиль: ${e.message}")
        }
    }

    /** Диагностика входа — только в debug-сборке. */
    private fun log(message: String) {
        if (BuildConfig.DEBUG) android.util.Log.i("OpenMES-Session", message)
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

/** Итог обновления токенов. */
internal enum class RefreshResult {
    OK,

    /** SUDIR явно отверг refresh-токен (400/401, invalid_grant) — нужен повторный вход. */
    REJECTED,

    /** Сеть, таймаут, 5xx и прочее временное — сессию сохраняем. */
    FAILED,

    /** Нечего обновлять: нет токенов или OAuth-клиента. */
    NO_SESSION,
}

/** Разбор ошибки /sps/oauth/te: выход из аккаунта — только при явном отказе сервера. */
internal fun classifyRefreshError(e: Throwable): RefreshResult = when {
    e is retrofit2.HttpException && e.code() in REJECTED_CODES -> RefreshResult.REJECTED
    else -> RefreshResult.FAILED
}

private val REJECTED_CODES = setOf(400, 401)
