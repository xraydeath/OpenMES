package ru.openmes.core.data

import android.content.Context
import java.io.File
import ru.openmes.core.common.toHM
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Показанное уведомление — строка истории. */
@Serializable
data class NotificationRecord(
    val timeMillis: Long,
    /** id канала (Notifications.Channel: data_update, lessons, schedule_changes, evening). */
    val channelId: String,
    val title: String,
    val text: String,
    /** Структурированные детали события — для шторки по тапу на записи. */
    val details: NotificationDetails? = null,
)

/**
 * Краткая информация о событии из уведомления: предмет, преподаватель,
 * день (к которому относится/который изменился) и оценка, когда есть.
 */
@Serializable
data class NotificationDetails(
    val subject: String? = null,
    val teacher: String? = null,
    /** ISO-день события («2026-09-30»). */
    val dayIso: String? = null,
    /** Оценка: «5 (вес 2)» или «4 → 5» при изменении. */
    val mark: String? = null,
)

/**
 * История показанных уведомлений: JSON-файл в filesDir, последние [MAX] записей.
 * Запись — из Notifications.post (любой поток), чтение — экраном «История уведомлений».
 * Файл маленький, чтение при создании — синхронное, запись — фоновой.
 */
class NotificationHistory(context: Context) {

    private val file = File(context.filesDir, "notification_history.json")
    private val prefs = context.getSharedPreferences("notification_history", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _entries = MutableStateFlow<List<NotificationRecord>>(emptyList())
    val entries: StateFlow<List<NotificationRecord>> = _entries.asStateFlow()

    /** Момент последнего открытия истории: записи новее — непросмотренные (бейдж колокольчика). */
    private val _lastSeenMillis = MutableStateFlow(prefs.getLong(KEY_SEEN, 0L))
    val lastSeenMillis: StateFlow<Long> = _lastSeenMillis.asStateFlow()

    init {
        _entries.value = read()
    }

    fun append(record: NotificationRecord) {
        val updated = synchronized(lock) {
            (_entries.value + record).takeLast(MAX).also { _entries.value = it }
        }
        scope.launch { runCatching { write(updated) } }
    }

    fun markSeen() {
        val now = System.currentTimeMillis()
        if (now <= _lastSeenMillis.value) return
        _lastSeenMillis.value = now
        prefs.edit().putLong(KEY_SEEN, now).apply()
    }

    fun clear() {
        synchronized(lock) { _entries.value = emptyList() }
        scope.launch { runCatching { file.delete() } }
    }

    private fun read(): List<NotificationRecord> = runCatching {
        if (!file.exists()) return emptyList()
        json.decodeFromString(ListSerializer(NotificationRecord.serializer()), file.readText())
    }.getOrDefault(emptyList())

    private fun write(entries: List<NotificationRecord>) {
        // Атомарно: временный файл → rename.
        val tmp = File(file.absolutePath + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(NotificationRecord.serializer()), entries))
        tmp.renameTo(file)
    }

    private companion object {
        const val MAX = 200
        const val KEY_SEEN = "last_seen"
    }
}

/** День записи истории (для группировки). */
fun NotificationRecord.day(): java.time.LocalDate =
    Instant.ofEpochMilli(timeMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

/** День самого события (из [NotificationDetails]), если известен. */
fun NotificationRecord.eventDay(): java.time.LocalDate? =
    details?.dayIso?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }

/** Время записи истории «HH:mm». */
fun NotificationRecord.timeHm(): String =
    Instant.ofEpochMilli(timeMillis).atZone(java.time.ZoneId.systemDefault()).toLocalTime().toHM()
