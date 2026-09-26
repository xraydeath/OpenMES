package ru.openmes.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.GlobalContext
import ru.openmes.core.common.pluralRu
import ru.openmes.core.common.runSuspendCatching
import ru.openmes.core.data.DiaryRepository
import ru.openmes.core.data.SettingsRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Вечернее напоминание в заданный час: несделанное ДЗ и контрольные на завтра. */
object EveningReminders {

    suspend fun reschedule(context: Context) {
        val settings = GlobalContext.getOrNull()?.get<SettingsRepository>()?.settings?.first() ?: return
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(pendingIntent(context))
        if (!settings.homeworkReminders && !settings.testReminders) return
        val now = LocalDateTime.now()
        val today = now.toLocalDate().atTime(LocalTime.of(settings.eveningReminderHour, 0))
        context.setAlarm(if (today > now) today else today.plusDays(1), pendingIntent(context))
    }

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, EveningReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Проверка сейчас (для кнопки в настройках и из будильника). */
    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "evening_reminder",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<EveningReminderWorker>().build(),
        )
    }
}

class EveningReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EveningReminders.runNow(context)
        // Следующий будильник — фоновой работой, а не в goAsync (бюджет ресивера ~10 с).
        Reschedule.all(context)
    }
}

/**
 * Сеть, а без неё — сохранённое. Диапазоны те же, что у фонового обновления кэша,
 * чтобы офлайн-ответ нашёлся.
 */
class EveningReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val diaryRepository: DiaryRepository by inject()
    private val settingsRepository: SettingsRepository by inject()

    override suspend fun doWork(): Result {
        val settings = settingsRepository.settings.first()
        val childId = currentChildId() ?: return Result.success()
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)

        if (settings.homeworkReminders) {
            val homeworks = fetch { getHomeworks(childId, today.minusDays(2), today.plusDays(14)) }
            val open = homeworks.orEmpty().filter { it.date == tomorrow && !it.isDone && it.task.isNotBlank() }
            if (open.isNotEmpty()) {
                val lines = open.map { "${it.subjectName}: ${it.task.lineSequence().first().take(80)}" }
                val builder = Notifications.builder(applicationContext, Notifications.Channel.EVENING)
                    .setContentTitle("На завтра не сделано: ${open.size} ${pluralRu(open.size, "задание", "задания", "заданий")}")
                    .setContentText(open.joinToString(", ") { it.subjectName })
                    .setStyle(NotificationCompat.InboxStyle().also { style -> lines.forEach(style::addLine) })
                Notifications.post(applicationContext, ID_HOMEWORK, builder)
            }
        }

        if (settings.testReminders) {
            val tests = fetch { getTestLessons(childId, today, today.plusDays(30)) }
                .orEmpty()
                .filter { it.date == tomorrow }
            if (tests.isNotEmpty()) {
                val lines = tests.map { t -> listOfNotNull(t.name ?: "Контрольная", t.subjectName).joinToString(" — ") }
                val builder = Notifications.builder(applicationContext, Notifications.Channel.EVENING)
                    .setContentTitle(if (tests.size == 1) "Завтра: ${lines.first()}" else "Завтра контрольных: ${tests.size}")
                    .setContentText(lines.joinToString(", "))
                    .setStyle(NotificationCompat.InboxStyle().also { style -> lines.forEach(style::addLine) })
                Notifications.post(applicationContext, ID_TESTS, builder)
            }
        }
        return Result.success()
    }

    private suspend fun <T> fetch(block: suspend DiaryRepository.() -> T): T? =
        runSuspendCatching { diaryRepository.block() }.getOrNull() ?: diaryRepository.cachedOnly(block)

    private companion object {
        const val ID_HOMEWORK = 7001
        const val ID_TESTS = 7002
    }
}
