package ru.openmes.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import ru.openmes.app.notify.Reschedule
import ru.openmes.app.notify.RescheduleReceiver
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Система обновляет виджеты раз в 30 минут — мало: на начале/конце пары и в полночь картинка устаревает.
 * После каждой отрисовки ставим будильник на ближайшую такую границу; он перерисовывает виджеты фоновой работой.
 */
internal object WidgetRefresh {

    fun schedule(context: Context, day: WidgetDay?) {
        val at = nextWidgetBoundary(day, LocalDateTime.now())
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Неточный и без пробуждения: разрешение на точные будильники не нужно, а виджет виден только при включённом экране.
        context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, millis, pendingIntent(context))
    }

    /** Убрали последний виджет — будильник больше не нужен. */
    fun cancelIfUnused(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val placed = listOf(ScheduleWidgetReceiver::class.java, DayWidgetReceiver::class.java)
            .any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
        if (!placed) context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, RescheduleReceiver::class.java).setAction(Reschedule.ACTION_WIDGETS),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * Когда виджет устареет: ближайшие начало или конец пары показанного сегодняшнего дня, иначе полночь
 * (меняются «Сегодня/Завтра» и сам день). Небольшой запас — чтобы сравнения «уже началась» точно сработали.
 */
internal fun nextWidgetBoundary(day: WidgetDay?, now: LocalDateTime): LocalDateTime {
    val today = now.toLocalDate()
    val midnight = today.plusDays(1).atStartOfDay()
    val next = day?.takeIf { it.date == today }?.lessons
        ?.flatMap { listOfNotNull(it.startTime, it.endTime) }
        ?.filter { it > now.toLocalTime() }
        ?.minOrNull()
        ?.let(today::atTime)
    return (next ?: midnight).plusSeconds(BOUNDARY_MARGIN_SECONDS)
}

private const val BOUNDARY_MARGIN_SECONDS = 5L
