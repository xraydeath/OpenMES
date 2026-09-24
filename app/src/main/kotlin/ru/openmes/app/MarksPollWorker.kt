package ru.openmes.app

import android.Manifest
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
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ru.openmes.core.data.DiaryRepository
import ru.openmes.core.data.Session
import ru.openmes.core.data.SessionRepository
import ru.openmes.core.data.SettingsRepository
import java.util.concurrent.TimeUnit

/**
 * Фоновая проверка новых оценок (аналог уведомлений OctoDiary-kt, но без пушей МЭШ):
 * раз в час сравниваем id оценок с сохранёнными. Первый запуск только запоминает базу.
 */
class MarksPollWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val sessionRepository: SessionRepository by inject()
    private val diaryRepository: DiaryRepository by inject()
    private val settingsRepository: SettingsRepository by inject()

    override suspend fun doWork(): Result {
        val session = withTimeoutOrNull(30_000) {
            sessionRepository.session.first { it !is Session.Loading }
        } as? Session.LoggedIn ?: return Result.success()
        val childId = session.currentChild?.id ?: return Result.success()

        val subjects = runCatching { diaryRepository.getSubjectMarks(childId) }
            .getOrElse { return Result.retry() }

        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "$KEY_MARKS_PREFIX$childId"
        val known = prefs.getStringSet(key, null)

        val marks = subjects.flatMap { subject ->
            subject.periods.flatMap { it.marks }.map { subject.subjectName to it }
        }
        prefs.edit { putStringSet(key, marks.map { it.second.id }.toSet()) }
        if (known == null) return Result.success()

        val hideValues = settingsRepository.settings.first().hideMarkValues
        marks.filter { it.second.id !in known }
            .take(MAX_NOTIFICATIONS)
            .forEach { (subject, mark) ->
                val text = if (hideValues) {
                    "Новая оценка"
                } else {
                    buildString {
                        append("Оценка ${mark.value}")
                        mark.weight?.takeIf { it > 1 }?.let { append(" (вес $it)") }
                        mark.typeName?.let { append(" · $it") }
                    }
                }
                notify(mark.id.hashCode(), subject, text)
            }
        return Result.success()
    }

    private fun notify(id: Int, title: String, text: String) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val intent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mark)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setGroup(CHANNEL_ID)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    companion object {
        private const val WORK_NAME = "marks_poll"
        private const val CHANNEL_ID = "data_update"
        private const val PREFS = "marks_poll"
        private const val KEY_MARKS_PREFIX = "known_marks_"
        private const val MAX_NOTIFICATIONS = 10

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MarksPollWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            // При повторном включении — снова начать с базы, без лавины старых оценок.
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Новые оценки", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}
