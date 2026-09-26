package ru.openmes.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import ru.openmes.core.common.toHM
import ru.openmes.core.data.SettingsRepository
import ru.openmes.core.model.Lesson
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Напоминание за N минут до пары. Держим один будильник — на ближайшую пару; сработав,
 * он показывает уведомление и ставит следующий. Пересчёт — при старте, уходе приложения в фон,
 * фоновом обновлении кэша, смене настроек и перезагрузке.
 */
object LessonReminders {

    private const val PREFS = "lesson_reminders"
    private const val KEY_NOTIFIED = "notified"

    suspend fun reschedule(context: Context) {
        val settings = GlobalContext.getOrNull()?.get<SettingsRepository>()?.settings?.first() ?: return
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(pendingIntent(context, null))
        if (!settings.lessonReminders) return

        val notified = prefs(context).getStringSet(KEY_NOTIFIED, emptySet()).orEmpty()
        val now = LocalDateTime.now()
        val next = upcomingLessons()
            .filter { it.startTime != null && (!settings.lessonRemindersDistanceOnly || it.isDistance) }
            .filter { it.key() !in notified }
            .map { it to LocalDateTime.of(it.date, it.startTime).minusMinutes(settings.lessonReminderMinutes.toLong()) }
            // Пропущенное (телефон был выключен) ещё показываем, пока пара не началась.
            .firstOrNull { (lesson, _) -> LocalDateTime.of(lesson.date, lesson.startTime) > now }
            ?: return
        val (lesson, at) = next
        context.setAlarm(maxOf(at, now.plusSeconds(5)), pendingIntent(context, lesson))
    }

    /** Показ уведомления (из будильника). */
    internal fun show(context: Context, intent: Intent, remember: Boolean = true) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val subject = intent.getStringExtra(EXTRA_SUBJECT).orEmpty()
        val start = intent.getStringExtra(EXTRA_START)?.let(LocalTime::parse) ?: return
        val end = intent.getStringExtra(EXTRA_END)?.let(LocalTime::parse)
        val joinUrl = intent.getStringExtra(EXTRA_JOIN_URL)

        if (remember) prefs(context).edit {
            val old = prefs(context).getStringSet(KEY_NOTIFIED, emptySet()).orEmpty()
            putStringSet(KEY_NOTIFIED, pruneNotified(old + key, LocalDate.now()))
        }

        val minutes = Duration.between(LocalTime.now(), start).toMinutes().coerceAtLeast(0)
        val title = if (minutes > 0) "Через $minutes мин: $subject" else "Начинается: $subject"
        val text = listOfNotNull(
            start.toHM() + (end?.let { "–${it.toHM()}" } ?: ""),
            "Дистанционно".takeIf { joinUrl != null || intent.getBooleanExtra(EXTRA_DISTANCE, false) },
            intent.getStringExtra(EXTRA_ROOM)?.let { "каб. $it" },
            intent.getStringExtra(EXTRA_TEACHER),
        ).joinToString(" · ")

        val builder = Notifications.builder(context, Notifications.Channel.LESSONS)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        end?.let { Duration.between(LocalTime.now(), it).toMillis() }?.takeIf { it > 0 }?.let(builder::setTimeoutAfter)
        if (joinUrl != null) {
            val join = PendingIntent.getActivity(
                context,
                key.hashCode(),
                Intent(Intent.ACTION_VIEW, Uri.parse(joinUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Подключиться", join)
        }
        Notifications.post(context, key.hashCode(), builder)
    }

    /** Пример из настроек: ближайшая пара из сохранённого расписания, если её нет — выдуманная. */
    fun showPreview(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val minutes = GlobalContext.getOrNull()?.get<SettingsRepository>()?.settings?.first()?.lessonReminderMinutes ?: 10
            val start = LocalTime.now().plusMinutes(minutes.toLong()).plusSeconds(30)
            val lesson = upcomingLessons().firstOrNull { it.startTime != null }
            val intent = Intent()
                .putExtra(EXTRA_KEY, "preview")
                .putExtra(EXTRA_SUBJECT, lesson?.subjectName ?: "Математика")
                .putExtra(EXTRA_START, start.toString())
                .putExtra(EXTRA_END, start.plusMinutes(90).toString())
                .putExtra(EXTRA_ROOM, lesson?.room ?: "204")
                .putExtra(EXTRA_TEACHER, lesson?.teacherName)
                .putExtra(EXTRA_DISTANCE, lesson?.isDistance ?: false)
                .putExtra(EXTRA_JOIN_URL, lesson?.joinUrl)
            show(context, intent, remember = false)
        }
    }

    private fun pendingIntent(context: Context, lesson: Lesson?): PendingIntent {
        val intent = Intent(context, LessonReminderReceiver::class.java)
        if (lesson != null) {
            intent.putExtra(EXTRA_KEY, lesson.key())
                .putExtra(EXTRA_SUBJECT, lesson.subjectName)
                .putExtra(EXTRA_START, lesson.startTime.toString())
                .putExtra(EXTRA_END, lesson.endTime?.toString())
                .putExtra(EXTRA_ROOM, lesson.room)
                .putExtra(EXTRA_TEACHER, lesson.teacherName)
                .putExtra(EXTRA_DISTANCE, lesson.isDistance)
                .putExtra(EXTRA_JOIN_URL, lesson.joinUrl)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun Lesson.key() = "${date}_${id}"

    /** Ключи «дата_id» прошедших дней больше не нужны (пары до сегодня не напоминаем) — выбрасываем по дате. */
    internal fun pruneNotified(keys: Set<String>, today: LocalDate): Set<String> =
        keys.filterTo(mutableSetOf()) { key ->
            val date = runCatching { LocalDate.parse(key.substringBefore('_')) }.getOrNull()
            date != null && !date.isBefore(today)
        }

    private const val EXTRA_KEY = "key"
    private const val EXTRA_SUBJECT = "subject"
    private const val EXTRA_START = "start"
    private const val EXTRA_END = "end"
    private const val EXTRA_ROOM = "room"
    private const val EXTRA_TEACHER = "teacher"
    private const val EXTRA_DISTANCE = "distance"
    private const val EXTRA_JOIN_URL = "join_url"
}

class LessonReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LessonReminders.show(context, intent)
        // Следующий будильник может потребовать сеть — фоновой работой, а не в goAsync.
        Reschedule.all(context)
    }
}
