package ru.openmes.core.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryCacheTest {

    @Test
    fun `clearAll стирает записи всех кэшей`() = runBlocking {
        val cache = MemoryCache(ttlMillis = 60_000L)
        var loads = 0
        cache.get("k", force = false) { ++loads }
        cache.get("k", force = false) { ++loads }
        assertEquals(1, loads)
        MemoryCache.clearAll()
        cache.get("k", force = false) { ++loads }
        assertEquals(2, loads)
    }

    @Test
    fun `выключенный кэш всегда грузит`() = runBlocking {
        val cache = MemoryCache(ttlMillis = 60_000L, enabled = false)
        var loads = 0
        repeat(3) { cache.get("k", force = false) { ++loads } }
        assertEquals(3, loads)
    }
}
