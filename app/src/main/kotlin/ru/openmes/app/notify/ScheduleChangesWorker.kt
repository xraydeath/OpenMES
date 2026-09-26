package ru.openmes.app.notify

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ru.openmes.core.common.humanize
import ru.openmes.core.common.runSuspendCatching
import ru.openmes.core.data.DiaryRepository
import ru.openmes.core.data.SettingsRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.concurrent.TimeUnit

/**
 * Изменения в расписании: раз в ~30 минут берём расписание с сервера (теми же месяцами, что экран, —
 * заодно обновляется кэш) и сравниваем ближайшие дни с прошлым снимком. Первый запуск только запоминает.
 */
class ScheduleChangesWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val diaryRepository: DiaryRepository by inject()
    private val settingsRepository: SettingsRepository by inject()

    override suspend fun doWork(): Result {
        val settings = settingsRepository.settings.first()
        if (!settings.scheduleChangeNotifications) return Result.success()
        val childId = currentChildId() ?: return Result.success()

        val today = LocalDate.now()
        val until = today.plusDays(settings.scheduleChangesDays.toLong() - 1)
        val months = generateSequence(YearMonth.from(today)) { it.plusMonths(1) }.takeWhile { !it.atDay(1).isAfter(until) }.toList()
        val lessons = runSuspendCatching {
            months.flatMap { diaryRepository.getSchedule(childId, it.atDay(1), it.atEndOfMonth()) }
        }.getOrElse { return Result.retry() }

        val now = LocalDateTime.now()
        val current = lessons
            .filter { !it.date.isBefore(today) && !it.date.isAfter(until) }
            .distinctBy { it.id to it.date }
            .map(SlotSnapshot::of)

        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "$KEY_PREFIX$childId"
        val previous = prefs.getString(key, null)?.lineSequence()?.mapNotNull(SlotSnapshot::decode)?.toList()
        val diff = previous?.let {
            diffWindow(it, current, today, until, settings.scheduleChangesRooms, settings.scheduleChangesRooms)
        }
        // Пустой ответ при непустом прошлом снимке — снимок не трогаем и молчим.
        if (previous != null && diff == null) return Result.success()
        prefs.edit { putString(key, current.joinToString("\n") { it.encode() }) }
        if (diff == null) return Result.success()

        val changes = diff
            // Прошедшие пары сегодня не интересны.
            .filter { c -> c.date != today || c.start == null || c.start.isAfter(now.toLocalTime()) }
        if (changes.isEmpty()) return Result.success()

        changes.groupBy { it.date }.forEach { (date, dayChanges) ->
            val lines = dayChanges.map { it.text }
            val builder = Notifications.builder(applicationContext, Notifications.Channel.SCHEDULE)
                .setContentTitle("Изменения в расписании: ${date.humanize().replaceFirstChar(Char::lowercase)}")
                .setContentText(lines.first() + if (lines.size > 1) " и ещё ${lines.size - 1}" else "")
                .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
                .setGroup(Notifications.Channel.SCHEDULE.id)
            Notifications.post(applicationContext, ID_BASE + date.dayOfYear, builder)
        }
        // Напоминания о парах — по новому расписанию.
        runSuspendCatching { LessonReminders.reschedule(applicationContext) }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "schedule_changes"
        private const val PREFS = "schedule_changes"
        private const val KEY_PREFIX = "snapshot_"
        private const val ID_BASE = 8000

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScheduleChangesWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            // При повторном включении — снова с базы, без «изменений» за всё время выключения.
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
        }
    }
}
