package ru.openmes.core.data

/** Обёртка: HTTP-ошибки превращаются в исключение с телом ответа (видно на экране). */
internal suspend fun <T> apiCall(block: suspend () -> T): T = try {
    block()
} catch (e: retrofit2.HttpException) {
    val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
    throw IllegalStateException(
        "HTTP ${e.code()} ${e.message()}" + (body?.take(300)?.let { ": $it" } ?: ""),
        e,
    )
}
