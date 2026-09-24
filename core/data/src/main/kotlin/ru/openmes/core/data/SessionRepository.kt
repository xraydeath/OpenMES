package ru.openmes.core.data

import kotlinx.coroutines.flow.StateFlow
import ru.openmes.core.model.Person

/** Состояние сессии приложения. */
sealed interface Session {
    data object Loading : Session

    /** [error] — причина последней неудачной авторизации (для экрана входа). */
    data class LoggedOut(val error: String? = null) : Session

    data class LoggedIn(
        val person: Person,
        val children: List<Person>,
        val currentChild: Person?,
    ) : Session
}

/**
 * Управление входом/выходом: SUDIR OAuth (login.mos.ru), как в оригинале.
 */
interface SessionRepository {
    val session: StateFlow<Session>

    /** Регистрирует OAuth-клиент, если ещё не зарегистрирован (TTL ~50 минут). */
    suspend fun ensureOAuthClient()

    /** URL страницы входа login.mos.ru/sps/oauth/ae — открывается во внешнем браузере. */
    fun buildLoginUrl(): String

    /**
     * Обработка redirect из браузера (deeplink diary-po://oauth2redirect?code=…).
     * @return true, если URL распознан как OAuth-redirect и обработан.
     */
    suspend fun onLoginRedirect(url: String): Boolean

    /** Обработка deeplink из Activity (intent/onNewIntent) — неблокирующе. */
    fun handleDeepLink(url: String)

    /** Принудительное обновление access-токена. */
    suspend fun refreshTokens(): Boolean

    suspend fun selectChild(personId: String)

    suspend fun logout()
}
