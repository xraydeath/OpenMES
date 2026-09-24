package ru.openmes.core.data

import ru.openmes.core.network.interceptor.TokenProvider

/**
 * Мост: сетевой слой получает mesh-токен из Keystore-хранилища.
 */
class TokenProviderImpl(
    private val store: TokenStore,
) : TokenProvider {

    override fun currentAupdToken(): String? = store.load()?.meshAccessToken

    override fun currentProfileId(): String? = store.load()?.profileId
}
