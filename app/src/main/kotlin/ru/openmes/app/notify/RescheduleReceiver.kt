package ru.openmes.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ru.openmes.app.widget.ScheduleWidget
import ru.openmes.core.common.runSuspendCatching

/**
 * Будильники не переживают перезагрузку, а смена времени/пояса их сдвигает — ставим заново.
 * Сам пересчёт может уйти в сеть (дольше бюджета goAsync), поэтому здесь только ставим работу.
 */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Reschedule.ACTION_WIDGETS) {
            Reschedule.widgets(context)
        } else {
            Reschedule.all(context)
        }
    }
}

/** Пересчёт будильников и перерисовка виджетов — уникальной фоновой работой (последний запрос заменяет прежний). */
object Reschedule {

    /** Будильник виджетов на границе пары / полночь (см. WidgetRefresh). */
    internal const val ACTION_WIDGETS = "ru.openmes.app.action.REFRESH_WIDGETS"

    private const val WORK_ALL = "reschedule"
    private const val WORK_WIDGETS = "widgets_refresh"
    private const val KEY_WIDGETS_ONLY = "widgets_only"

    /** Напоминания о парах, вечерние и виджеты. */
    fun all(context: Context) = enqueue(context, WORK_ALL, widgetsOnly = false)

    /** Только перерисовать виджеты. */
    fun widgets(context: Context) = enqueue(context, WORK_WIDGETS, widgetsOnly = true)

    private fun enqueue(context: Context, name: String, widgetsOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<RescheduleWorker>()
            .setInputData(workDataOf(KEY_WIDGETS_ONLY to widgetsOnly))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    internal class RescheduleWorker(
        context: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(context, params) {

        override suspend fun doWork(): Result {
            ScheduleWidget.refresh(applicationContext)
            if (!inputData.getBoolean(KEY_WIDGETS_ONLY, false)) {
                runSuspendCatching { LessonReminders.reschedule(applicationContext) }
                runSuspendCatching { EveningReminders.reschedule(applicationContext) }
            }
            return Result.success()
        }
    }
}
