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
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

/**
 * Офлайн-кэш ответов МЭШ: удачные GET-ответы пишутся на диск, а без сети (или при 5xx)
 * отдаются из кэша. [offlineDataTime] — время сохранения показанных данных, пока сети нет.
 * Что и сколько хранить — [policy] (настройки пользователя); файлы названы по разделу
 * («marks_exact_…»), чтобы выключенный раздел можно было стереть целиком.
 */
class OfflineCache(private val dir: File) {

    private val _offlineDataTime = MutableStateFlow<Long?>(null)

    /** null — последние запросы прошли по сети; иначе — когда были сохранены показанные данные. */
    val offlineDataTime: StateFlow<Long?> = _offlineDataTime.asStateFlow()

    private val lock = Any()

    private val servedCount = AtomicLong()

    /**
     * Сколько раз с запуска ответ отдали из кэша вместо сети. Кэш в памяти сравнивает значение
     * до и после загрузки: изменилось — данные могли прийти из кэша, как свежие их не держим.
     */
    val servedFromCacheCount: Long get() = servedCount.get()

    @Volatile
    var policy: CachePolicy = CachePolicy()

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
        servedCount.incrementAndGet()
        // Показываем самое старое из отданного — чтобы не приукрашивать свежесть.
        _offlineDataTime.value = _offlineDataTime.value?.let { minOf(it, savedAt) } ?: savedAt
    }

    /** Двоичные данные вне HTTP-кэша (QR билета, аватар) — раздел [CacheSection.PROFILE]. */
    fun readBlob(name: String): ByteArray? = synchronized(lock) {
        if (!policy.allows(CacheSection.PROFILE)) return null
        runCatching {
            File(dir, blobName(name)).takeIf { it.exists() }?.readBytes()
        }.getOrNull()
    }

    fun writeBlob(name: String, bytes: ByteArray) {
        if (!policy.allows(CacheSection.PROFILE)) return
        synchronized(lock) {
            runCatching {
                dir.mkdirs()
                val tmp = File(dir, blobName(name) + ".tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(File(dir, blobName(name)))
            }
        }
    }

    private fun blobName(name: String) =
        CacheSection.PROFILE.key + "_blob_" + name.replace(Regex("[^A-Za-z0-9_-]"), "_")

    /** Очистка при выходе из аккаунта (или вручную: [keepSession] оставляет профиль для входа без сети). */
    fun clear(keepSession: Boolean = false) = synchronized(lock) {
        dir.listFiles()
            ?.filter { !keepSession || sectionOf(it.name) != CacheSection.SESSION }
            ?.forEach { it.delete() }
        _offlineDataTime.value = null
    }

    /**
     * Приведение диска к [policy]: удаляет выключенные разделы и файлы старого формата
     * (без раздела в имени). Вызывать не с главного потока.
     */
    fun cleanup() = synchronized(lock) {
        val current = policy
        dir.listFiles()?.forEach { file ->
            val section = sectionOf(file.name)
            if (section == null || !current.allows(section)) file.delete()
        }
    }

    fun stats(): CacheStats = synchronized(lock) {
        val files = dir.listFiles().orEmpty()
        CacheStats(files = files.size, bytes = files.sumOf { it.length() })
    }

    private fun sectionOf(fileName: String): CacheSection? =
        CacheSection.entries.firstOrNull { fileName.startsWith(it.key + "_") }

    /** Свежий ответ перезаписывает старый по тому же ключу; копятся лишь неактуальные ключи — их вытесняет лимит. */
    private fun prune() {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_ENTRIES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_ENTRIES)
            .forEach { it.delete() }
    }

    companion object {
        private const val MAX_ENTRIES = 400

        /** Заголовок, которым помечены ответы из офлайн-кэша. */
        const val HEADER_FROM_CACHE = "X-OpenMES-Offline-Cache"
    }
}

/** Ответ отдан из офлайн-кэша, а не из сети. */
fun Response.isFromOfflineCache(): Boolean = header(OfflineCache.HEADER_FROM_CACHE) != null


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
        // null — ответ не кэшируется: чужой путь, не GET или раздел выключен в настройках.
        val section = sectionOf(request)?.takeIf { cache.policy.allows(it) }
        if (cacheOnly) return section?.let { cachedResponse(request, it) } ?: notCached(request)
        if (section == null) return chain.proceed(request)
        val exactKey = exactKey(request, section)
        val aliasKey = aliasKey(request, section)

        fun fromCache(): Response? = cachedResponse(request, section)?.also {
            cache.onServedFromCache(it.sentRequestAtMillis)
        }

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            // Отменённый запрос (ушли с экрана) — не «нет сети»: кэш и баннер офлайна ни к чему.
            if (chain.call().isCanceled()) throw e
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
        if (!inWindow(request.url, cache.policy.windowDays)) return response
        val bytes = body.bytes()
        cache.write(listOfNotNull(exactKey, aliasKey), bytes.decodeToString(), System.currentTimeMillis())
        return response.newBuilder().body(bytes.toResponseBody(contentType)).build()
    }

    private fun sectionOf(request: Request): CacheSection? =
        if (request.method == "GET") CacheSection.of(request.url.encodedPath.removePrefix("/")) else null

    private fun exactKey(request: Request, section: CacheSection): String =
        key(section, "exact", request.url.toString())

    /**
     * Для «плавающих» диапазонов (оценки за 28 дней, ДЗ на две недели) — ключ без дат.
     * Только для текущего диапазона (задевает сегодня): иначе просмотр прошлого месяца
     * перезаписал бы «текущие» данные, и без сети показался бы чужой период.
     */
    private fun aliasKey(request: Request, section: CacheSection): String? {
        val path = request.url.encodedPath.removePrefix("/")
        if (EXACT_ONLY.any { path.startsWith(it) }) return null
        val dates = datesOf(request.url)
        val today = LocalDate.now()
        if (dates.isNotEmpty() && (dates.min() > today || dates.max() < today)) return null
        return key(section, "alias", alias(request.url))
    }

    /** Диапазон дат запроса задевает ±[windowDays] от сегодня (запросы без дат проходят всегда). */
    private fun inWindow(url: HttpUrl, windowDays: Int?): Boolean {
        if (windowDays == null) return true
        val dates = datesOf(url)
        if (dates.isEmpty()) return true
        val today = LocalDate.now()
        return dates.max() >= today.minusDays(windowDays.toLong()) && dates.min() <= today.plusDays(windowDays.toLong())
    }

    private fun datesOf(url: HttpUrl): List<LocalDate> = url.queryParameterNames
        .flatMap { url.queryParameterValues(it) }
        .mapNotNull { value -> value?.takeIf(DATE::matches)?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } }

    /** Ответ из кэша; время сохранения — в sentRequestAtMillis. */
    private fun cachedResponse(request: Request, section: CacheSection): Response? {
        val (savedAt, body) = cache.read(exactKey(request, section))
            ?: aliasKey(request, section)?.let(cache::read) ?: return null
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK (offline cache)")
            .header(OfflineCache.HEADER_FROM_CACHE, "1")
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

    private fun key(section: CacheSection, prefix: String, value: String): String =
        section.key + "_" + prefix + "_" + MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val DATE = Regex("""\d{4}-\d{2}-\d{2}.*""")

        /** Расписание по месяцам: чужой месяц вместо нужного показывать нельзя. */
        val EXACT_ONLY = listOf(
            "api/eventcalendar/v1/api/events",
            "api/family/mobile/v1/lesson_schedule_items/",
            "api/family/mobile/v1/marks/",
            "api/food/meals/v3/menu/",
            "api/news/v2/news",
            "api/ej/plan/family/v1/test_lessons/",
            "api/pass/entrances/",
        )
    }
}
