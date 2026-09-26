package ru.openmes.core.data

import ru.openmes.core.network.interceptor.TokenProvider

/**
 * Мост: сетевой слой получает mesh-токен из Keystore-хранилища (расшифрованная копия в памяти).
 */
class TokenProviderImpl(
    private val store: TokenStore,
) : TokenProvider {

    override fun currentAupdToken(): String? = store.load()?.meshAccessToken

    override fun tokenIssuedAtMillis(): Long? = store.load()?.meshIssuedAtMillis
}
