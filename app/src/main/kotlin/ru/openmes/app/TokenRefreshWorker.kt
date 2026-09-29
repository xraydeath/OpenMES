package ru.openmes.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ru.openmes.core.common.runSuspendCatching
import ru.openmes.core.data.SessionRepository

/**
 * Фоновое продление сессии: раз в 12 часов обновляем токены, чтобы
 * refresh-токены не истекали «в простое», пока приложение не открывают.
 * Без токенов refresh безопасно вернёт NO_SESSION — воркер всегда запланирован.
 */
class TokenRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val sessionRepository: SessionRepository by inject()

    override suspend fun doWork(): Result {
        runSuspendCatching { sessionRepository.refreshTokens() }
        // Отказ — не ошибка воркера: без сети или сессии просто ждём следующий запуск.
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "token_refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TokenRefreshWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
