package ru.openmes.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.openmes.core.model.Lesson
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class WidgetBoundaryTest {

    private val today = LocalDate.of(2026, 9, 28)

    private fun lesson(date: LocalDate, start: String, end: String) = Lesson(
        id = "$date-$start",
        date = date,
        number = 0,
        startTime = LocalTime.parse(start),
        endTime = LocalTime.parse(end),
        subjectName = "Математика",
    )

    private val day = WidgetDay(today, listOf(lesson(today, "09:00", "10:30"), lesson(today, "10:40", "12:10")))

    @Test
    fun `до начала пар — начало первой`() {
        assertEquals(today.atTime(9, 0, 5), nextWidgetBoundary(day, today.atTime(8, 0)))
    }

    @Test
    fun `во время пары — её конец`() {
        assertEquals(today.atTime(10, 30, 5), nextWidgetBoundary(day, today.atTime(9, 15)))
    }

    @Test
    fun `после последней пары и для другого дня — полночь`() {
        val midnight = today.plusDays(1).atStartOfDay().plusSeconds(5)
        assertEquals(midnight, nextWidgetBoundary(day, today.atTime(13, 0)))
        val tomorrow = WidgetDay(today.plusDays(1), listOf(lesson(today.plusDays(1), "09:00", "10:30")))
        assertEquals(midnight, nextWidgetBoundary(tomorrow, LocalDateTime.of(today, LocalTime.of(8, 0))))
        assertEquals(midnight, nextWidgetBoundary(null, today.atTime(8, 0)))
    }
}
