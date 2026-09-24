package ru.openmes.core.network

/**
 * Конфигурация окружения OpenMES — точные константы, восстановленные
 * реверсом оригинального приложения «Колледж МЭШ» (сборка po, ru.mes.diary.po).
 */
object MesEnvironment {

    /** SUDIR — система управления идентичностью mos.ru. */
    const val SUDIR_BASE_URL = "https://login.mos.ru/"

    /** Основной API школьных сервисов. */
    const val SCHOOL_BASE_URL = "https://school.mos.ru/"

    /** Family Mobile API (mapi). */
    const val MAPI_BASE_URL = "https://school.mos.ru/api/family/mobile"

    /** Версия mapi API. */
    const val MAPI_API_VERSION = "/v1"

    /** Dnevnik host (web-дневник, там живёт acl/api). */
    const val DNEVNIK_BASE_URL = "https://dnevnik.mos.ru/"

    // ---------------------------------------------------------------------
    // SUDIR OAuth-константы (дословно из oauthSettings оригинала, build po)
    // ---------------------------------------------------------------------

    /** Идентификатор ПО для динамической регистрации — колледж (po-сборка). */
    const val SOFTWARE_ID = "diary-po"

    /** Тип устройства для регистрации (getDeviceType: android_phone | android_tab). */
    const val DEVICE_TYPE_ANDROID_PHONE = "android_phone"
    const val DEVICE_TYPE_ANDROID_TAB = "android_tab"

    /**
     * Initial Access Token для динамической регистрации клиента
     * (из college.hasm — публичная prod-регистрация «Колледж МЭШ»).
     */
    const val INITIAL_ACCESS_TOKEN =
        "0WiYJIAXFbFC7_Gf0FQKbP76zcSXiYEn1gZxwyymXUOYiQsk8E3BIlOf2f6tFKtcuh20MZ3Frh6Ik_E7IRNsGA"

    /**
     * Software Statement (JWT, RS256) — метаданные клиента для регистрации.
     * payload: grant_types [authorization_code, client_credentials, refresh_token],
     * scope, redirect_uris ["diary-po:/"], aud ["diary-po"], iss login.mos.ru.
     */
    const val SOFTWARE_STATEMENT =
        "eyJ0eXAiOiJKV1QiLCJibGl0ejpraW5kIjoiU09GVF9TVE0iLCJhbGciOiJSUzI1NiJ9.eyJncmFudF90eXBlcyI6WyJhdXRob3JpemF0aW9uX2NvZGUiLCJjbGllbnRfY3JlZGVudGlhbHMiLCJyZWZyZXNoX3Rva2VuIl0sInNjb3BlIjoiYmlydGhkYXkgYmxpdHpfcXJfYXV0aCBjb250YWN0cyBvcGVuaWQgcHJvZmlsZSBzbmlscyIsImp0aSI6ImFhYTI1ZWU5LTM5YjAtNGExOC1hNGZjLTBlMjU4MjVhZGIwZCIsInNvZnR3YXJlX2lkIjoiZGlhcnktcG8iLCJzb2Z0d2FyZV92ZXJzaW9uIjoiMSIsInJlc3BvbnNlX3R5cGVzIjpbImNvZGUiXSwiaWF0IjoxNzIxMTI4MzU2LCJpc3MiOiJodHRwczovL2xvZ2luLm1vcy5ydSIsInJlZGlyZWN0X3VyaXMiOlsiZGlhcnktcG86LyJdLCJhdWQiOlsiZGlhcnktcG8iXX0.MjSC_9InwsAHJxPTELJyeAtgV0J_iC7JVGlYjjm-6DLokZRokyXR5hq1Bxf_e5RjeePbnYrxXpiqKZoPy0_-u-eolhUndJFakpoUy-rqE6QCia05Z5RLepvpiO2TGkNy8thUtbA0mf_IbFwIdjELbZLWEe5ipjT43TQOlI9Fh4zKBAQKxEP79pFy0Ljzqm67i17bO5QIUPw7aIE5n6D0ZoEHBMxa-nzYpuOE7eS0-rvlQvQ4vhHtSKvFBORW1XsPGCSupjcsKQtz0wMaIY-39ojK_NyqTN6lJs7F3TMQRZJGgTUGlzA9Az2opfsiifwIbS-luO-NEt83UPhwNE1Y1g"
    /** Скоупы (RAW-строка с '+' — так шлёт OctoDiary-py в ae-запросе). */
    const val OAUTH_SCOPES = "birthday+blitz_qr_auth+contacts+openid+profile+snils"

    /** Redirect URI OAuth (deeplink-схема + oauth2redirect, как OctoDiary). */
    const val OAUTH_REDIRECT_URI = "diary-po://oauth2redirect"
    const val OAUTH_SCHEME = "diary-po"

    /** PKCE: границы длины code_verifier. */
    const val CODE_VERIFIER_MIN_LENGTH = 43
    const val CODE_VERIFIER_MAX_LENGTH = 128

    // ---------------------------------------------------------------------
    // Заголовки API-запросов — минимальный набор OctoDiary
    // ---------------------------------------------------------------------

    /** Строковый идентификатор подсистемы (familymp — как OctoDiary). */
    const val X_MES_SUBSYSTEM = "familymp"

    /** Заголовки (дословно из SchoolMesApiServiceImpl OctoDiary). */
    const val HEADER_AUTH_TOKEN = "auth-token"
    const val HEADER_AUTHORIZATION = "Authorization"
    const val HEADER_SUBSYSTEM = "x-mes-subsystem"
}
