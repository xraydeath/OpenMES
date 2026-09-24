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

    private val homeworks = "https://school.mos.ru/api/family/mobile/v1/homeworks/short?student_id=1&from=2026-09-21&to=2026-10-07"

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
        val tomorrow = "https://school.mos.ru/api/family/mobile/v1/homeworks/short?student_id=1&from=2026-09-22&to=2026-10-08"
        assertEquals(200 to """{"v":1}""", client.get(tomorrow))
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
}
