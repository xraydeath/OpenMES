package ru.openmes.core.network.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import ru.openmes.core.network.MesEnvironment

/**
 * SUDIR OAuth API (login.mos.ru) — флоу восстановлен реверсом оригинала:
 * динамическая регистрация (RFC 7591) → PKCE authorization → token exchange.
 */
interface SudirApi {

    /**
     * Шаг 1: динамическая регистрация OAuth-клиента.
     * POST /sps/oauth/register
     *   Authorization: Bearer <initialAccessToken>
     *   { software_id: "diary-po", device_type: "android_phone", software_statement: <JWT> }
     */
    @POST("sps/oauth/register")
    suspend fun registerClient(
        @Body body: ClientRegistrationRequest,
        @Header("Authorization") initialAccessToken: String =
            "Bearer ${MesEnvironment.INITIAL_ACCESS_TOKEN}",
    ): ClientRegistrationResponse

    /**
     * Шаг 3: token endpoint.
     * POST /sps/oauth/te
     *   Authorization: Basic base64(client_id:client_secret)
     *   form: grant_type=authorization_code&code=…&redirect_uri=…&code_verifier=…
     */
    @FormUrlEncoded
    @POST("sps/oauth/te")
    suspend fun token(
        @Header("Authorization") basicAuth: String,
        @FieldMap form: Map<String, String>,
    ): TokenResponse
}

@Serializable
data class ClientRegistrationRequest(
    @SerialName("software_id") val softwareId: String,
    @SerialName("device_type") val deviceType: String,
    @SerialName("software_statement") val softwareStatement: String,
)

@Serializable
data class ClientRegistrationResponse(
    @SerialName("client_id") val clientId: String = "",
    @SerialName("client_secret") val clientSecret: String = "",
    @SerialName("registration_access_token") val registrationAccessToken: String? = null,
    @SerialName("registration_client_uri") val registrationClientUri: String? = null,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String? = "Bearer",
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("scope") val scope: String? = null,
)

// ---------------------------------------------------------------------------
// Шаг 3a/3b и refresh — бэкенд school.mos.ru
// ---------------------------------------------------------------------------

/**
 * МЭШ-авторизация: обмен SUDIR-токена на МЭШ-токены (mesh_access_token = aupd_token).
 */
interface MeshAuthApi {

    /** POST /v3/auth/sudir/auth → mesh_access_token + mesh_refresh_token. */
    @POST("v3/auth/sudir/auth")
    suspend fun authSudir(
        @Body body: SudirAuthRequest,
    ): SudirAuthResponse

    /** POST /v3/token/refresh (201) → новые access/refresh. */
    @FormUrlEncoded
    @POST("v3/token/refresh")
    suspend fun refreshToken(
        @FieldMap form: Map<String, String>,
    ): MeshRefreshResponse

    /** GET acl/api/users/profile_info → массив профилей (как OctoDiary-py, dnevnik.mos.ru). */
    @GET("acl/api/users/profile_info")
    suspend fun getProfileInfo(
        @Header("Auth-Token") authToken: String,
        @Header("Authorization") authorization: String,
        @Header("partner-source-id") partnerSourceId: String = "MOBILE",
    ): List<ProfileInfoDto>
}



@Serializable
data class SudirAuthRequest(
    @SerialName("user_authentication_for_mobile_request")
    val userAuthenticationForMobileRequest: MosAccessTokenPayload,
) {
    @Serializable
    data class MosAccessTokenPayload(
        @SerialName("mos_access_token") val mosAccessToken: String,
    )
}

@Serializable
data class SudirAuthResponse(
    @SerialName("user_authentication_for_mobile_response")
    val userAuthenticationForMobileResponse: MeshTokens? = null,
) {
    @Serializable
    data class MeshTokens(
        @SerialName("mesh_access_token") val meshAccessToken: String = "",
        @SerialName("mesh_refresh_token") val meshRefreshToken: String = "",
    )
}

@Serializable
data class MeshRefreshResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
)

@Serializable
data class ProfileInfoDto(
    @SerialName("id") val id: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("roles") val roles: List<String> = emptyList(),
)
