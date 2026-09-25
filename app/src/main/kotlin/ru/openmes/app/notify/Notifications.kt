package ru.openmes.app.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import ru.openmes.app.MainActivity
import ru.openmes.app.R
import ru.openmes.core.common.runSuspendCatching
import ru.openmes.core.data.DiaryRepository
import ru.openmes.core.data.Session
import ru.openmes.core.data.SessionRepository
import ru.openmes.core.model.Lesson
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/** Каналы уведомлений и общий показ. */
object Notifications {

    enum class Channel(val id: String, val title: String, val importance: Int) {
        /** id исторический — канал уже создан у тех, кто включал уведомления об оценках. */
        MARKS("data_update", "Новые оценки", NotificationManager.IMPORTANCE_DEFAULT),
        LESSONS("lessons", "Начало пар", NotificationManager.IMPORTANCE_HIGH),
        SCHEDULE("schedule_changes", "Изменения в расписании", NotificationManager.IMPORTANCE_HIGH),
        EVENING("evening", "ДЗ и контрольные на завтра", NotificationManager.IMPORTANCE_DEFAULT),
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun builder(context: Context, channel: Channel): NotificationCompat.Builder {
        ensureChannel(context, channel)
        return NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_stat_mark)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
    }

    fun post(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (!canPost(context)) return
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureChannel(context: Context, channel: Channel) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(channel.id) != null) return
        manager.createNotificationChannel(NotificationChannel(channel.id, channel.title, channel.importance))
    }
}

/** Будильник в [at]: точный, если система разрешает, иначе — приблизительный (может опоздать на несколько минут). */
internal fun Context.setAlarm(at: LocalDateTime, intent: PendingIntent) {
    val alarms = getSystemService(AlarmManager::class.java)
    val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    if (canUseExactAlarms()) {
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
    } else {
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
    }
}

internal fun Context.canUseExactAlarms(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

/** Текущий студент или null (сессия ещё не восстановлена / выход из аккаунта). */
internal suspend fun currentChildId(): String? {
    val sessions = GlobalContext.getOrNull()?.get<SessionRepository>() ?: return null
    val session = withTimeoutOrNull(30_000) { sessions.session.first { it !is Session.Loading } }
    return (session as? Session.LoggedIn)?.currentChild?.id
}

/**
 * Пары с сегодняшнего дня (на ~месяц вперёд): сначала сохранённое расписание — теми же диапазонами,
 * что и экран (по месяцам или половинам), иначе сеть на неделю.
 */
internal suspend fun upcomingLessons(): List<Lesson> {
    val repository = GlobalContext.getOrNull()?.get<DiaryRepository>() ?: return emptyList()
    val today = LocalDate.now()
    val months = listOf(YearMonth.from(today), YearMonth.from(today).plusMonths(1))
    return months.flatMap { month ->
        repository.cachedOnly { getSchedule("", month.atDay(1), month.atEndOfMonth()) }
            ?: repository.cachedOnly {
                val mid = month.atDay(15)
                getSchedule("", month.atDay(1), mid) + getSchedule("", mid.plusDays(1), month.atEndOfMonth())
            }
            ?: if (month == months.first()) {
                withTimeoutOrNull(15_000) {
                    runSuspendCatching { repository.getSchedule("", today, today.plusDays(7)) }.getOrNull()
                }.orEmpty()
            } else {
                emptyList()
            }
    }
        .distinctBy { it.id }
        .filter { !it.date.isBefore(today) }
        .sortedWith(compareBy({ it.date }, { it.startTime }))
}
