package ru.openmes.core.data

import kotlinx.serialization.Serializable

/** Токены сессии МЭШ (SUDIR + mesh/aupd). */
@Serializable
data class AuthTokens(
    // Динамически зарегистрированный OAuth-клиент SUDIR.
    val oauthClientId: String? = null,
    val oauthClientSecret: String? = null,
    // SUDIR access token (короткоживущий).
    val sudirAccessToken: String? = null,
    val sudirExpiresAtMillis: Long? = null,
    // МЭШ-токены (mesh_access_token = aupd_token).
    val meshAccessToken: String? = null,
    // Текущий профиль.
    val profileId: String? = null,
    /** Роль для заголовка X-Mes-RoleId (po: student=32, parent=2). */
    val roleId: String? = null,
    /** Роль-строка для заголовка Profile. */
    val profileRole: String? = null,
    /** SUDIR refresh-token (повторный вход без пароля, ~15 суток). */
    val sudirRefreshToken: String? = null,
    /** id ребёнка из family profile — именно он идёт как student_id в mapi. */
    val studentId: String? = null,
    /** contingent_guid ребёнка — идёт как person_ids в eventcalendar. */
    val personGuid: String? = null,
    // PKCE текущего входа.
    val pendingCodeVerifier: String? = null,
    val pendingState: String? = null,
    /** Момент регистрации OAuth-клиента (SUDIR-регистрация живёт ~1 час). */
    val registeredAtMillis: Long? = null,
)
