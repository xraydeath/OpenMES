package ru.openmes.core.data

import java.util.concurrent.ConcurrentHashMap

/** Кэш ответов в памяти процесса: повторное открытие экрана не ходит в сеть, пока запись свежая. */
internal class MemoryCache(private val ttlMillis: Long) {

    private class Entry(val savedAt: Long, val value: Any)

    private val entries = ConcurrentHashMap<String, Entry>()

    suspend fun <T : Any> get(key: String, force: Boolean, load: suspend () -> T): T {
        val now = System.currentTimeMillis()
        if (!force) {
            entries[key]?.takeIf { now - it.savedAt < ttlMillis }?.let {
                @Suppress("UNCHECKED_CAST")
                return it.value as T
            }
        }
        return load().also { entries[key] = Entry(now, it) }
    }
}
