package ru.openmes.core.network.interceptor

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import ru.openmes.core.network.MesEnvironment

/**
 * Провайдер токенов, реализуется слоем данных (Keystore-хранилище).
 */
interface TokenProvider {
    fun currentAupdToken(): String?
    fun currentProfileId(): String?
}

/**
 * Базовые заголовки авторизации МЭШ (mesh_access_token):
 *
 *   auth-token: {mesh_access_token}
 *   Authorization: Bearer {mesh_access_token}
 *
 * Cookie НЕ нужны (проверено рабочим форком OctoDiary-kt для колледжа).
 * Подсистема ставится в CollegeRouting (familypom).
 * Питание (meals v3) принимает только Authorization — с auth-token отвечает 401.
 */
class AuthInterceptor(
    private val tokenProvider: TokenProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!original.url.host.endsWith("mos.ru") ||
            original.url.encodedPath.startsWith("/sps/")
        ) {
            return chain.proceed(original)
        }

        val builder = original.newBuilder()
        tokenProvider.currentAupdToken()?.let {
            if (!original.isBearerOnly()) builder.header(MesEnvironment.HEADER_AUTH_TOKEN, it)
            builder.header(MesEnvironment.HEADER_AUTHORIZATION, "Bearer $it")
        }

        return chain.proceed(builder.build())
    }
}

/** Сервисы, которые принимают только Authorization: Bearer (без auth-token). */
internal fun Request.isBearerOnly(): Boolean =
    url.encodedPath.startsWith("/api/food/") || url.encodedPath.startsWith("/api/pass/")

/**
 * Автообновление по 401: te(refresh) → sudir/auth → новый mesh (схема OctoDiary).
 */
class TokenAuthenticator(
    private val tokenProvider: TokenProvider,
    onUnauthorized: () -> Unit,
) : okhttp3.Authenticator {

    var refreshSuspend: suspend () -> Boolean = { false }
    var onUnauthorized: () -> Unit = onUnauthorized

    private val refreshMutex = Mutex()

    override fun authenticate(route: okhttp3.Route?, response: Response): Request? {
        if (responseCount(response) >= 3) {
            onUnauthorized()
            return null
        }

        val bearerOnly = response.request.isBearerOnly()
        // Второй 401 от питания/проходов после обновления токена — проблема сервиса, а не сессии: не разлогиниваем.
        if (bearerOnly && responseCount(response) >= 2) return null

        val requestToken = response.request.header(MesEnvironment.HEADER_AUTH_TOKEN)
            ?: response.request.header(MesEnvironment.HEADER_AUTHORIZATION)?.removePrefix("Bearer ")

        val refreshed = runBlocking {
            refreshMutex.withLock {
                val current = tokenProvider.currentAupdToken()
                if (current != null && current != requestToken) {
                    true // уже обновлён другим потоком
                } else {
                    refreshSuspend()
                }
            }
        }
        if (!refreshed) {
            onUnauthorized()
            return null
        }

        val token = tokenProvider.currentAupdToken()
        if (token == null) {
            onUnauthorized()
            return null
        }

        return response.request.newBuilder()
            .apply { if (!bearerOnly) header(MesEnvironment.HEADER_AUTH_TOKEN, token) }
            .header(MesEnvironment.HEADER_AUTHORIZATION, "Bearer $token")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
