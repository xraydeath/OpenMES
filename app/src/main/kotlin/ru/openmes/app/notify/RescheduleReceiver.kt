package ru.openmes.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Будильники не переживают перезагрузку, а смена времени/пояса их сдвигает — ставим заново. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                rescheduleReminders(context)
            } finally {
                pending.finish()
            }
        }
    }
}

suspend fun rescheduleReminders(context: Context) {
    runCatching { LessonReminders.reschedule(context) }
    runCatching { EveningReminders.reschedule(context) }
}
