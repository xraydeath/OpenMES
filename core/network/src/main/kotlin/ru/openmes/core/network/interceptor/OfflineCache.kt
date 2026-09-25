package ru.openmes.core.network.interceptor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Офлайн-кэш ответов МЭШ: удачные GET-ответы пишутся на диск, а без сети (или при 5xx)
 * отдаются из кэша. [offlineDataTime] — время сохранения показанных данных, пока сети нет.
 */
class OfflineCache(private val dir: File) {

    private val _offlineDataTime = MutableStateFlow<Long?>(null)

    /** null — последние запросы прошли по сети; иначе — когда были сохранены показанные данные. */
    val offlineDataTime: StateFlow<Long?> = _offlineDataTime.asStateFlow()

    private val lock = Any()

    internal fun read(key: String): Pair<Long, String>? = synchronized(lock) {
        val file = File(dir, key)
        if (!file.exists()) return null
        runCatching {
            val text = file.readText()
            val newline = text.indexOf('\n')
            text.substring(0, newline).toLong() to text.substring(newline + 1)
        }.getOrNull()
    }

    internal fun write(keys: Collection<String>, body: String, savedAt: Long) = synchronized(lock) {
        runCatching {
            dir.mkdirs()
            keys.forEach { key ->
                val tmp = File(dir, "$key.tmp")
                tmp.writeText("$savedAt\n$body")
                tmp.renameTo(File(dir, key))
            }
            prune()
        }
    }

    internal fun onOnline() {
        _offlineDataTime.value = null
    }

    internal fun onServedFromCache(savedAt: Long) {
        // Показываем самое старое из отданного — чтобы не приукрашивать свежесть.
        _offlineDataTime.value = _offlineDataTime.value?.let { minOf(it, savedAt) } ?: savedAt
    }

    /** Двоичные данные вне HTTP-кэша (QR билета, аватар): тоже стираются при выходе. */
    fun readBlob(name: String): ByteArray? = synchronized(lock) {
        runCatching { File(dir, blobName(name)).takeIf { it.exists() }?.readBytes() }.getOrNull()
    }

    fun writeBlob(name: String, bytes: ByteArray) = synchronized(lock) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, blobName(name) + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(File(dir, blobName(name)))
        }
    }

    private fun blobName(name: String) = "blob_" + name.replace(Regex("[^A-Za-z0-9_-]"), "_")

    /** Очистка при выходе из аккаунта. */
    fun clear() = synchronized(lock) {
        dir.listFiles()?.forEach { it.delete() }
        _offlineDataTime.value = null
    }

    private fun prune() {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_ENTRIES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_ENTRIES)
            .forEach { it.delete() }
    }

    private companion object {
        const val MAX_ENTRIES = 400
    }
}

/**
 * Должен стоять первым в цепочке: видит исходные пути (до CollegeRouting).
 * С [cacheOnly] в сеть не ходит вовсе: отдаёт сохранённое или 504 (для мгновенного показа до загрузки).
 */
class OfflineCacheInterceptor(
    private val cache: OfflineCache,
    private val cacheOnly: Boolean = false,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath.removePrefix("/")
        val cacheable = request.method == "GET" && CACHED_PATHS.any { path.startsWith(it) }
        if (cacheOnly) return cachedResponse(request, cacheable) ?: notCached(request)
        if (!cacheable) return chain.proceed(request)
        val exactKey = exactKey(request)
        val aliasKey = aliasKey(request)

        fun fromCache(): Response? = cachedResponse(request, cacheable = true)?.also {
            cache.onServedFromCache(it.sentRequestAtMillis)
        }

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            return fromCache() ?: throw e
        }
        if (response.code >= 500) {
            fromCache()?.let {
                response.close()
                return it
            }
            return response
        }
        if (!response.isSuccessful) return response

        cache.onOnline()
        val body = response.body ?: return response
        val contentType = body.contentType()
        if (contentType?.subtype?.contains("json") != true) return response
        val bytes = body.bytes()
        cache.write(listOfNotNull(exactKey, aliasKey), bytes.decodeToString(), System.currentTimeMillis())
        return response.newBuilder().body(bytes.toResponseBody(contentType)).build()
    }

    private fun exactKey(request: Request): String = key("exact", request.url.toString())

    /** Для «плавающих» диапазонов (оценки за 28 дней, ДЗ на две недели) — ключ без дат. */
    private fun aliasKey(request: Request): String? {
        val path = request.url.encodedPath.removePrefix("/")
        return if (EXACT_ONLY.none { path.startsWith(it) }) key("alias", alias(request.url)) else null
    }

    /** Ответ из кэша; время сохранения — в sentRequestAtMillis. */
    private fun cachedResponse(request: Request, cacheable: Boolean): Response? {
        if (!cacheable) return null
        val (savedAt, body) = cache.read(exactKey(request)) ?: aliasKey(request)?.let(cache::read) ?: return null
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK (offline cache)")
            .sentRequestAtMillis(savedAt)
            .receivedResponseAtMillis(savedAt)
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private fun notCached(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(504)
        .message("Not in offline cache")
        .body("".toResponseBody(null))
        .build()

    private fun alias(url: HttpUrl): String = buildString {
        append(url.host).append(url.encodedPath)
        url.queryParameterNames.sorted().forEach { name ->
            val value = url.queryParameter(name).orEmpty()
            if (!DATE.matches(value)) append('&').append(name).append('=').append(value)
        }
    }

    private fun key(prefix: String, value: String): String =
        prefix + "_" + MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val DATE = Regex("""\d{4}-\d{2}-\d{2}.*""")

        val CACHED_PATHS = listOf(
            "api/family/mobile/v1/profile",
            "acl/api/users/profile_info",
            "api/eventcalendar/v1/api/events",
            "api/family/mobile/v1/lesson_schedule_items/",
            "api/family/mobile/v1/marks",
            "api/family/mobile/v1/subject_marks",
            "api/family/mobile/v1/homeworks",
            "api/family/mobile/v1/attestation",
            "api/family/mobile/v1/attendance",
            "api/family/mobile/v1/student-card",
            "api/family/mobile/v1/school_info",
            "api/news/v2/news",
            "portfolio/app/persons/",
            "api/family/mobile/v1/periods_schedules",
            "api/ej/core/family/v1/academic_years",
            "api/avatarmanagement/v1/",
            "api/food/meals/v3/menu/",
            "api/food/meals/v3/clients/balance",
            "api/food/meals/v3/clients/food-provider",
        )

        /** Расписание по месяцам: чужой месяц вместо нужного показывать нельзя. */
        val EXACT_ONLY = listOf(
            "api/eventcalendar/v1/api/events",
            "api/family/mobile/v1/lesson_schedule_items/",
            "api/family/mobile/v1/marks/",
            "api/food/meals/v3/menu/",
            "api/news/v2/news",
        )
    }
}
