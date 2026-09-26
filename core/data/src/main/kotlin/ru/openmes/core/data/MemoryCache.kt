package ru.openmes.core.data

import ru.openmes.core.network.interceptor.OfflineCache
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Кэш ответов в памяти процесса: повторное открытие экрана не ходит в сеть, пока запись свежая.
 * Данные, которые могли прийти из [offlineCache] вместо сети, не запоминаются: иначе старый
 * снимок жил бы в памяти как свежий весь TTL. [enabled] = false — кэш выключен (репозитории
 * поверх кэш-only API: свежее с диска важнее копии в памяти).
 */
internal class MemoryCache(
    private val ttlMillis: Long,
    private val offlineCache: OfflineCache? = null,
    private val enabled: Boolean = true,
) {

    private class Entry(val savedAt: Long, val value: Any)

    private val entries = ConcurrentHashMap<String, Entry>()

    init {
        synchronized(all) { all.add(this) }
    }

    suspend fun <T : Any> get(key: String, force: Boolean, load: suspend () -> T): T {
        if (!enabled) return load()
        val now = System.currentTimeMillis()
        if (!force) {
            entries[key]?.takeIf { now - it.savedAt < ttlMillis }?.let {
                @Suppress("UNCHECKED_CAST")
                return it.value as T
            }
        }
        val servedBefore = offlineCache?.servedFromCacheCount
        return load().also {
            if (offlineCache?.servedFromCacheCount == servedBefore) entries[key] = Entry(now, it)
        }
    }

    fun clear() = entries.clear()

    companion object {
        /** Все кэши процесса — чтобы стереть данные прошлого аккаунта при выходе. */
        private val all: MutableSet<MemoryCache> = Collections.newSetFromMap(WeakHashMap())

        fun clearAll() {
            synchronized(all) { all.toList() }.forEach { it.clear() }
        }
    }
}
