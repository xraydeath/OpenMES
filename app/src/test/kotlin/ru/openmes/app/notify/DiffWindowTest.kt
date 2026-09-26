package ru.openmes.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class DiffWindowTest {

    private val today = LocalDate.of(2026, 9, 28)

    private fun slot(id: String, day: Long, subject: String = "Математика") = SlotSnapshot(
        id = id,
        date = today.plusDays(day),
        start = LocalTime.of(9, 0),
        end = LocalTime.of(10, 30),
        subject = subject,
        room = "204",
        teacher = "Иванов",
        distance = false,
    )

    @Test
    fun `сокращение окна — дни за его краем не отменены`() {
        val previous = (0L..13).map { slot("l$it", it) }
        val current = (0L..6).map { slot("l$it", it) }
        val changes = diffWindow(previous, current, today, today.plusDays(6))
        assertEquals(emptyList<ScheduleChange>(), changes)
    }

    @Test
    fun `пустой ответ при непустом снимке — не сравниваем`() {
        val previous = listOf(slot("a", 0), slot("b", 1))
        assertNull(diffWindow(previous, emptyList(), today, today.plusDays(6)))
    }

    @Test
    fun `пустой ответ, когда и в окне прошлого снимка пусто, — сравнение без изменений`() {
        val previous = listOf(slot("a", -2))
        assertEquals(emptyList<ScheduleChange>(), diffWindow(previous, emptyList(), today, today.plusDays(6)))
    }

    @Test
    fun `новый день на краю окна — не новые пары, отмена внутри окна — отмена`() {
        val previous = listOf(slot("a", 0), slot("b", 1))
        val current = listOf(slot("a", 0), slot("c", 2))
        val changes = diffWindow(previous, current, today, today.plusDays(6))!!
        assertEquals(1, changes.size)
        assertTrue(changes.single().text.contains("отменена"))
        assertEquals(today.plusDays(1), changes.single().date)
    }
}
