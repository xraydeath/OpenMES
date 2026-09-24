package ru.openmes.core.common

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RU = Locale("ru")

private val MONTHS_GENITIVE = mapOf(
    Month.JANUARY to "января",
    Month.FEBRUARY to "февраля",
    Month.MARCH to "марта",
    Month.APRIL to "апреля",
    Month.MAY to "мая",
    Month.JUNE to "июня",
    Month.JULY to "июля",
    Month.AUGUST to "августа",
    Month.SEPTEMBER to "сентября",
    Month.OCTOBER to "октября",
    Month.NOVEMBER to "ноября",
    Month.DECEMBER to "декабря",
)

private val DAY_OF_WEEK_SHORT = mapOf(
    DayOfWeek.MONDAY to "Пн",
    DayOfWeek.TUESDAY to "Вт",
    DayOfWeek.WEDNESDAY to "Ср",
    DayOfWeek.THURSDAY to "Чт",
    DayOfWeek.FRIDAY to "Пт",
    DayOfWeek.SATURDAY to "Сб",
    DayOfWeek.SUNDAY to "Вс",
)

private val DAY_OF_WEEK_FULL = mapOf(
    DayOfWeek.MONDAY to "понедельник",
    DayOfWeek.TUESDAY to "вторник",
    DayOfWeek.WEDNESDAY to "среда",
    DayOfWeek.THURSDAY to "четверг",
    DayOfWeek.FRIDAY to "пятница",
    DayOfWeek.SATURDAY to "суббота",
    DayOfWeek.SUNDAY to "воскресенье",
)

/** «Сегодня», «Завтра», «Вчера», иначе «12 сентября» (+год, если не текущий). */
fun LocalDate.humanize(now: LocalDate = LocalDate.now()): String = when (this) {
    now -> "Сегодня"
    now.plusDays(1) -> "Завтра"
    now.minusDays(1) -> "Вчера"
    else -> "${dayOfMonth} ${MONTHS_GENITIVE[month]}" + if (year != now.year) " ${year}" else ""
}

/** «12 сентября» / «12 сентября 2026». */
fun LocalDate.toRuDate(includeYear: Boolean = false): String =
    "${dayOfMonth} ${MONTHS_GENITIVE[month]}" + if (includeYear) " ${year}" else ""

fun DayOfWeek.toShortRu(): String = DAY_OF_WEEK_SHORT[this] ?: ""

fun DayOfWeek.toFullRu(): String = DAY_OF_WEEK_FULL[this] ?: ""

/** Понедельник недели, к которой относится дата. */
fun LocalDate.weekStart(): LocalDate = minusDays((dayOfWeek.value - 1).toLong())

fun LocalDate.weekDays(): List<LocalDate> = (0..6L).map { weekStart().plusDays(it) }

/** «12.09.2026». */
fun LocalDate.toDots(): String = format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))

/** Парсинг «HH:mm» и «HH:mm:ss». */
fun parseTimeOrNull(raw: String?): LocalTime? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        if (raw.length >= 8) LocalTime.parse(raw.substring(0, 8)) else LocalTime.parse(raw)
    }.getOrNull()
}

fun LocalTime.toHM(): String = format(DateTimeFormatter.ofPattern("HH:mm"))

/** «сейчас 14:35, 22 сентября» — приветствие на главной. */
fun LocalDateTime.greetingDateTime(): String = "$hour:$minute"
