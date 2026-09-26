package ru.openmes.core.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.openmes.core.network.MesEnvironment
import ru.openmes.core.network.interceptor.CollegeRouting
import ru.openmes.core.network.interceptor.OfflineCacheInterceptor
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Ответ отладочной консоли: как есть, с итоговым адресом после маршрутизации. */
data class ApiConsoleResponse(
    val code: Int,
    val url: String,
    val elapsedMillis: Long,
    val body: String,
)

/**
 * Сырые GET-запросы к school.mos.ru с токенами текущей сессии — чтобы посмотреть
 * настоящие ответы API, для которых ещё нет экранов. Офлайн-кэш в обход.
 */
class ApiConsoleRepository(
    authClient: OkHttpClient,
    private val tokenStore: TokenStore,
    private val foodRepository: FoodRepository,
) {
    private val client = authClient.newBuilder()
        .apply { interceptors().removeAll { it is OfflineCacheInterceptor } }
        .build()

    private val pretty = Json { prettyPrint = true }

    /**
     * «!» в начале пути — без роутинга колледжа (как есть на school.mos.ru).
     * [logLimit] — сколько символов ответа писать в logcat (разведка не должна переполнять буфер).
     */
    suspend fun get(path: String, logLimit: Int = Int.MAX_VALUE): ApiConsoleResponse = withContext(Dispatchers.IO) {
        val raw = path.trim().startsWith("!")
        val clean = path.trim().removePrefix("!")
        val url = MesEnvironment.SCHOOL_BASE_URL.trimEnd('/') + "/" + encodeQuery(substitute(clean).trimStart('/'))
        val started = System.nanoTime()
        val request = Request.Builder().url(url).get()
            .apply { if (raw) header(CollegeRouting.RAW_HEADER, "1") }
            .build()
        val call = client.newCall(request)
        val response = runCatching { call.execute() }
            .onFailure { Log.i(TAG, "GET $url -> ошибка: $it") }
            .getOrThrow()
        response.use {
            val raw = response.body?.string().orEmpty()
            ApiConsoleResponse(
                code = response.code,
                url = response.request.url.toString(),
                elapsedMillis = (System.nanoTime() - started) / 1_000_000,
                body = prettify(raw),
            ).also { log(it, logLimit) }
        }
    }

    /** Ответ целиком в logcat (`adb logcat -s OpenMES-API`); строки режем — logcat обрезает ~4 КБ. */
    private fun log(r: ApiConsoleResponse, limit: Int) {
        Log.i(TAG, "GET ${r.url} -> ${r.code} (${r.elapsedMillis} мс, ${r.body.length} симв.)")
        r.body.take(limit).chunked(3000).forEach { Log.i(TAG, it) }
        Log.i(TAG, "--- конец ответа ---")
    }

    private suspend fun substitute(path: String): String {
        if ('{' !in path) return path
        val tokens = tokenStore.load()
        val today = LocalDate.now()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val values = mapOf(
            "personGuid" to { tokens?.personGuid },
            "studentId" to { tokens?.studentId ?: tokens?.profileId },
            "profileId" to { tokens?.profileId },
            "today" to { today.toString() },
            "monday" to { monday.toString() },
            "sunday" to { monday.plusDays(6).toString() },
            "monthAgo" to { today.minusMonths(1).toString() },
        )
        var result = path
        for ((name, value) in values) {
            if ("{$name}" in result) result = result.replace("{$name}", value().orEmpty())
        }
        if ("{contractId}" in result) {
            val contract = runCatching { foodRepository.getBalance()?.contractId }.getOrNull()
            result = result.replace("{contractId}", contract?.toString().orEmpty())
        }
        return result
    }

    /** Tomcat отвечает 400 на «сырые» [ ] { } " в запросе — кодируем их, как это делает Retrofit. */
    private fun encodeQuery(path: String): String {
        val q = path.indexOf('?').takeIf { it >= 0 } ?: return path
        val query = path.substring(q + 1).map { c ->
            if (c in UNSAFE_QUERY_CHARS) "%%%02X".format(c.code) else c.toString()
        }.joinToString("")
        return path.substring(0, q + 1) + query
    }

    private fun prettify(raw: String): String =
        runCatching { pretty.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(raw)) }
            .getOrDefault(raw)

    private companion object {
        const val TAG = "OpenMES-API"
        const val UNSAFE_QUERY_CHARS = "[]{}\"|^`<> "
    }
}
