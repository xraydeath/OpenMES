package ru.openmes.core.network.interceptor

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import ru.openmes.core.network.MesEnvironment

/**
 * Провайдер токенов, реализуется слоем данных (Keystore-хранилище).
 */
interface TokenProvider {
    fun currentAupdToken(): String?

    /** Когда получен текущий mesh-токен (null — неизвестно). */
    fun tokenIssuedAtMillis(): Long? = null
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
 *
 * Обновление однопоточное внутри [refreshSuspend] (параллельные запросы ждут один и тот же
 * результат), поэтому своей блокировки здесь нет — нечему и зависнуть при повторном входе.
 * false от [refreshSuspend] — обновить не вышло: запрос завершается с 401 как обычная ошибка
 * (экраны покажут офлайн-кэш). Повтор с новым токеном — один: второй 401 значит, что отказывает
 * сам сервис, а не сессия. Выход из аккаунта при отзыве refresh-токена делает сам слой данных;
 * [onUnauthorized] — только если после обновления токена не осталось.
 */
class TokenAuthenticator(
    private val tokenProvider: TokenProvider,
    onUnauthorized: () -> Unit,
) : okhttp3.Authenticator {

    var refreshSuspend: suspend () -> Boolean = { false }
    var onUnauthorized: () -> Unit = onUnauthorized

    override fun authenticate(route: okhttp3.Route?, response: Response): Request? {
        // Запрос уже повторяли с новым токеном, а сервис снова ответил 401 — это отказ самого
        // сервиса (например, {"apikey":null}), а не отзыв сессии: не обновляем и не разлогиниваем.
        if (response.priorResponse != null) return null

        val bearerOnly = response.request.isBearerOnly()
        val requestToken = response.request.header(MesEnvironment.HEADER_AUTH_TOKEN)
            ?: response.request.header(MesEnvironment.HEADER_AUTHORIZATION)?.removePrefix("Bearer ")

        val current = tokenProvider.currentAupdToken()
        val refreshed = when {
            current != null && current != requestToken -> true // уже обновлён другим потоком
            // На заведомо свежий токен 401 — полное обновление SUDIR не поможет.
            tokenProvider.isFresh() -> false
            // Исключение здесь уронило бы поток OkHttp: сбой обновления — просто «не вышло».
            else -> runCatching { runBlocking { refreshSuspend() } }.getOrDefault(false)
        }
        if (!refreshed) return null

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

    private fun TokenProvider.isFresh(): Boolean {
        val issuedAt = tokenIssuedAtMillis() ?: return false
        return System.currentTimeMillis() - issuedAt in 0 until FRESH_TOKEN_MS
    }

    private companion object {
        /** Токен моложе минуты считаем заведомо действующим. */
        const val FRESH_TOKEN_MS = 60_000L
    }
}
