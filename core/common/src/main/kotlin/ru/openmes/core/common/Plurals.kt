package ru.openmes.core.common

/** Форма слова для числа: pluralRu(3, "урок", "урока", "уроков") → «урока». */
fun pluralRu(n: Int, one: String, few: String, many: String): String {
    val abs = kotlin.math.abs(n)
    return when {
        abs % 100 in 11..14 -> many
        abs % 10 == 1 -> one
        abs % 10 in 2..4 -> few
        else -> many
    }
}
