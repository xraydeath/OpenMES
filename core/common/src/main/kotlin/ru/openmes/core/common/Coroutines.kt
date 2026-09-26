package ru.openmes.core.common

/** Безопасный запуск suspend-блока: не проглатывает CancellationException. */
suspend fun <T> runSuspendCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (ce: kotlinx.coroutines.CancellationException) {
    throw ce
} catch (t: Throwable) {
    Result.failure(t)
}
