package ru.openmes.core.network.interceptor

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class OfflineCacheTest {

    private lateinit var dir: File
    private lateinit var cache: OfflineCache

    /** «Сервер»: отдаёт [body] или бросает IOException, если [online] = false. */
    private var online = true
    private var body = """{"v":1}"""
    private var networkCalls = 0

    private val fakeNetwork = Interceptor { chain ->
        networkCalls++
        if (!online) throw IOException("offline")
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private val client by lazy {
        OkHttpClient.Builder().addInterceptor(OfflineCacheInterceptor(cache)).addInterceptor(fakeNetwork).build()
    }
    private val cacheOnlyClient by lazy {
        OkHttpClient.Builder().addInterceptor(OfflineCacheInterceptor(cache, cacheOnly = true)).addInterceptor(fakeNetwork).build()
    }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("offline_cache").toFile()
        cache = OfflineCache(dir)
    }

    private fun OkHttpClient.get(url: String): Pair<Int, String> =
        newCall(Request.Builder().url(url).build()).execute().use { it.code to it.body!!.string() }

    private val today = java.time.LocalDate.now()

    private fun homeworksUrl(from: java.time.LocalDate, to: java.time.LocalDate) =
        "https://school.mos.ru/api/family/mobile/v1/homeworks/short?student_id=1&from=$from&to=$to"

    /** Текущий диапазон: задевает сегодня. */
    private val homeworks = homeworksUrl(today.minusDays(5), today.plusDays(11))

    @Test
    fun `новый ответ заменяет старый в кэше`() {
        client.get(homeworks)
        body = """{"v":2}"""
        client.get(homeworks)
        online = false
        assertEquals(200 to """{"v":2}""", client.get(homeworks))
        assertTrue(cache.offlineDataTime.value != null) // баннер «нет связи» показан
    }

    @Test
    fun `после появления сети признак офлайна сбрасывается`() {
        client.get(homeworks)
        online = false
        client.get(homeworks)
        online = true
        client.get(homeworks)
        assertNull(cache.offlineDataTime.value)
    }

    @Test
    fun `плавающий диапазон дат берётся по ключу без дат`() {
        client.get(homeworks)
        online = false
        val tomorrow = homeworksUrl(today.minusDays(4), today.plusDays(12))
        assertEquals(200 to """{"v":1}""", client.get(tomorrow))
    }

    @Test
    fun `прошлый период не перезаписывает текущий`() {
        client.get(homeworks)
        body = """{"v":"past"}"""
        val past = homeworksUrl(today.minusDays(60), today.minusDays(30))
        client.get(past)
        online = false
        // Текущий «плавающий» диапазон — по-прежнему из текущих данных.
        assertEquals(200 to """{"v":1}""", client.get(homeworksUrl(today.minusDays(4), today.plusDays(12))))
        // Сам прошлый период — по точному ключу.
        assertEquals(200 to """{"v":"past"}""", client.get(past))
    }

    @Test
    fun `чужой прошлый период без сети не подменяется текущим`() {
        client.get(homeworks)
        online = false
        val otherPast = homeworksUrl(today.minusDays(90), today.minusDays(70))
        assertEquals(504, cacheOnlyClient.get(otherPast).first)
    }

    @Test
    fun `отменённый запрос не берётся из кэша`() {
        client.get(homeworks)
        val cancelling = OkHttpClient.Builder()
            .addInterceptor(OfflineCacheInterceptor(cache))
            .addInterceptor { chain ->
                chain.call().cancel()
                throw IOException("Canceled")
            }
            .build()
        val error = runCatching { cancelling.get(homeworks) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertNull(cache.offlineDataTime.value)
        assertEquals(0L, cache.servedFromCacheCount)
    }

    @Test
    fun `ответ из кэша помечен`() {
        client.get(homeworks)
        online = false
        val response = client.newCall(Request.Builder().url(homeworks).build()).execute()
        response.use {
            assertTrue(it.isFromOfflineCache())
        }
        assertEquals(1L, cache.servedFromCacheCount)
        online = true
        client.newCall(Request.Builder().url(homeworks).build()).execute().use {
            assertTrue(!it.isFromOfflineCache())
        }
    }

    @Test
    fun `cache-only не ходит в сеть`() {
        assertEquals(504, cacheOnlyClient.get(homeworks).first)
        client.get(homeworks)
        val calls = networkCalls
        body = """{"v":2}"""
        assertEquals(200 to """{"v":1}""", cacheOnlyClient.get(homeworks))
        assertEquals(calls, networkCalls)
        assertNull(cache.offlineDataTime.value)
    }

    @Test
    fun `clear удаляет все записи`() {
        client.get(homeworks)
        assertTrue(dir.listFiles()!!.isNotEmpty())
        cache.clear()
        assertTrue(dir.listFiles()!!.isEmpty())
        online = false
        assertEquals(504, cacheOnlyClient.get(homeworks).first)
    }

    @Test
    fun `временные файлы не остаются`() {
        client.get(homeworks)
        body = """{"v":2}"""
        client.get(homeworks)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
        assertEquals(2, dir.listFiles()!!.size) // exact + alias
    }
    private val profile = "https://school.mos.ru/api/family/mobile/v1/profile?x=1"

    @Test
    fun `выключенный раздел не пишется и не читается`() {
        cache.policy = CachePolicy(sections = CacheSection.entries.toSet() - CacheSection.HOMEWORK)
        client.get(homeworks)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        online = false
        assertEquals(504, cacheOnlyClient.get(homeworks).first)
    }

    @Test
    fun `выключенный кэш сохраняет только профиль для входа`() {
        cache.policy = CachePolicy(enabled = false)
        client.get(homeworks)
        client.get(profile)
        assertEquals(504, cacheOnlyClient.get(homeworks).first)
        assertEquals(200, cacheOnlyClient.get(profile).first)
    }

    @Test
    fun `cleanup стирает выключенные разделы`() {
        client.get(homeworks)
        client.get(profile)
        cache.policy = CachePolicy(enabled = false)
        cache.cleanup()
        assertTrue(dir.listFiles()!!.all { it.name.startsWith("session_") })
        assertEquals(200, cacheOnlyClient.get(profile).first)
    }

    @Test
    fun `данные вне периода не сохраняются`() {
        cache.policy = CachePolicy(windowDays = 30)
        val today = java.time.LocalDate.now()
        val old = "https://school.mos.ru/api/family/mobile/v1/homeworks/short?student_id=1" +
            "&from=${today.minusDays(90)}&to=${today.minusDays(60)}"
        client.get(old)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        val recent = "https://school.mos.ru/api/family/mobile/v1/homeworks/short?student_id=1" +
            "&from=${today.minusDays(40)}&to=${today.plusDays(3)}"
        client.get(recent)
        assertEquals(200, cacheOnlyClient.get(recent).first)
    }

    @Test
    fun `ручная очистка оставляет профиль для входа`() {
        client.get(homeworks)
        client.get(profile)
        cache.clear(keepSession = true)
        assertEquals(504, cacheOnlyClient.get(homeworks).first)
        assertEquals(200, cacheOnlyClient.get(profile).first)
    }
}
