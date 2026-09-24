package ru.openmes.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class DatesTest {

    private val today = LocalDate.of(2026, 9, 22) // вторник

    @Test
    fun `humanize today`() {
        assertEquals("Сегодня", today.humanize(today))
    }

    @Test
    fun `humanize tomorrow`() {
        assertEquals("Завтра", today.plusDays(1).humanize(today))
    }

    @Test
    fun `humanize yesterday`() {
        assertEquals("Вчера", today.minusDays(1).humanize(today))
    }

    @Test
    fun `humanize other date same year`() {
        assertEquals("1 сентября", LocalDate.of(2026, 9, 1).humanize(today))
    }

    @Test
    fun `humanize other year appends year`() {
        assertEquals("31 мая 2025", LocalDate.of(2025, 5, 31).humanize(today))
    }

    @Test
    fun `weekStart monday`() {
        assertEquals(LocalDate.of(2026, 9, 21), today.weekStart())
    }

    @Test
    fun `weekStart sunday`() {
        val sunday = LocalDate.of(2026, 9, 27)
        assertEquals(LocalDate.of(2026, 9, 21), sunday.weekStart())
    }

    @Test
    fun `weekDays seven_days starting monday`() {
        val days = today.weekDays()
        assertEquals(7, days.size)
        assertEquals(java.time.DayOfWeek.MONDAY, days.first().dayOfWeek)
        assertEquals(java.time.DayOfWeek.SUNDAY, days.last().dayOfWeek)
    }

    @Test
    fun `toDots formats`() {
        assertEquals("22.09.2026", today.toDots())
    }

    @Test
    fun `parseTime variants`() {
        assertEquals(LocalTime.of(8, 30), parseTimeOrNull("08:30:00"))
        assertEquals(LocalTime.of(8, 30), parseTimeOrNull("08:30"))
        assertNull(parseTimeOrNull(null))
        assertNull(parseTimeOrNull("мусор"))
    }

    @Test
    fun `toHM formats`() {
        assertEquals("08:30", LocalTime.of(8, 30).toHM())
        assertEquals("23:05", LocalTime.of(23, 5).toHM())
    }
}
