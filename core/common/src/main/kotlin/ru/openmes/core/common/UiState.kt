package ru.openmes.core.common

/**
 * Универсальное состояние экрана.
 * В оригинале у каждого экрана свой redux-слайс — здесь один sealed-тип.
 */
sealed interface UiState<out T> {

    data object Loading : UiState<Nothing>

    data class Data<T>(val data: T) : UiState<T>

    data class Error(
        val message: String? = null,
        val retryable: Boolean = true,
    ) : UiState<Nothing>
}

inline fun <T> UiState<T>.onData(block: (T) -> Unit) {
    if (this is UiState.Data) block(data)
}

/** Безопасный запуск suspend-блока: не проглатывает CancellationException. */
suspend fun <T> runSuspendCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (ce: kotlinx.coroutines.CancellationException) {
    throw ce
} catch (t: Throwable) {
    Result.failure(t)
}
