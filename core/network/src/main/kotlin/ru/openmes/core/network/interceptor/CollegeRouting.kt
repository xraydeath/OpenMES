package ru.openmes.core.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Роутинг колледжа (СПО) — рабочий перехватчик из OctoDiary-kt (наработка):
 * пути family/mobile/ej/eventcalendar переписываются на profeducation,
 * ставится подсистема familypom.
 *
 * «Public College remote config v372: mapi_host_config + Moscow apiMapiUrl».
 */
object CollegeRouting : Interceptor {
    /** Заголовок-метка: не переписывать путь (только для консоли API). */
    const val RAW_HEADER = "X-OpenMES-Raw"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        // Не трогаем login.mos.ru и внешние запросы.
        if (url.host != "school.mos.ru") return chain.proceed(request)
        // Отладочная консоль: запрос «как есть», без переписывания путей.
        if (request.header(RAW_HEADER) != null) {
            return chain.proceed(request.newBuilder().removeHeader(RAW_HEADER).build())
        }

        val path = url.encodedPath
        val collegePath = when {
            path == "/api/family/mobile" || path.startsWith("/api/family/mobile/") ->
                "/api/profeducation/family/mobile" + path.removePrefix("/api/family/mobile")

            path == "/acl/api/users/profile_info" ->
                "/api/profeducation/acl/v1/mod-acl/users/profile_info"

            path == "/api/ej" || path.startsWith("/api/ej/") ->
                "/api/profeducation" + path.removePrefix("/api/ej")

            path.startsWith("/api/eventcalendar/") ->
                "/api/profeducation/eventcalendar/" + path.removePrefix("/api/eventcalendar/")

            path == "/family/ispp" || path.startsWith("/family/ispp/") ->
                "/profeducation" + path

            else -> path
        }
        return chain.proceed(
            request.newBuilder()
                .url(url.newBuilder().encodedPath(collegePath).build())
                .header("X-Mes-Subsystem", "familypom")
                .build(),
        )
    }
}
