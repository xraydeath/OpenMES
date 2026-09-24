package ru.openmes.app

import android.content.Context
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
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.TimeUnit

/**
 * Раз в час (при наличии сети) подтягивает основные данные, чтобы офлайн-кэш
 * был свежим. Диапазоны — те же, что у экранов, иначе ключи кэша не совпадут.
 */
class CacheRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val sessionRepository: SessionRepository by inject()
    private val diaryRepository: DiaryRepository by inject()

    override suspend fun doWork(): Result {
        val session = withTimeoutOrNull(30_000) {
            sessionRepository.session.first { it !is Session.Loading }
        } as? Session.LoggedIn ?: return Result.success()
        val childId = session.currentChild?.id ?: return Result.success()

        val today = LocalDate.now()
        val month = YearMonth.from(today)
        val yearStart = LocalDate.of(if (today.monthValue >= 9) today.year else today.year - 1, 9, 1)
        val calls: List<suspend () -> Unit> = listOf(
            { diaryRepository.getSchedule(childId, month.atDay(1), month.atEndOfMonth()) },
            { diaryRepository.getSchedule(childId, month.plusMonths(1).atDay(1), month.plusMonths(1).atEndOfMonth()) },
            { diaryRepository.getSubjectMarks(childId) },
            { diaryRepository.getMarks(childId, today.minusDays(28), today) },
            { diaryRepository.getGradeBook(childId) },
            { diaryRepository.getHomeworks(childId, today.minusDays(2), today.plusDays(14)) },
            { diaryRepository.getAttendance(childId, yearStart, today) },
            { diaryRepository.getStudentCard(childId) },
            { diaryRepository.getDayKinds(childId, yearStart, yearStart.plusYears(1).minusDays(1)) },
        )
        // Каждый запрос сам по себе: одна упавшая ручка не мешает обновить остальные.
        val failed = calls.count { call -> runCatching { call() }.isFailure }
        return if (failed == calls.size) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "cache_refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CacheRefreshWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
