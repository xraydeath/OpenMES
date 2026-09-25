package ru.openmes.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ScheduleDiffTest {

    private val day = LocalDate.of(2026, 9, 28)

    private fun slot(
        id: String,
        start: String,
        subject: String,
        room: String? = "204",
        teacher: String? = "Иванов",
        distance: Boolean = false,
    ) = SlotSnapshot(
        id = id,
        date = day,
        start = LocalTime.parse(start),
        end = LocalTime.parse(start).plusMinutes(90),
        subject = subject,
        room = room,
        teacher = teacher,
        distance = distance,
    )

    @Test
    fun `без изменений`() {
        val s = listOf(slot("1", "09:00", "Математика"), slot("2", "10:40", "Физика"))
        assertTrue(diffSchedules(s, s).isEmpty())
    }

    @Test
    fun `отмена и новая пара`() {
        val changes = diffSchedules(
            listOf(slot("1", "09:00", "Математика")),
            listOf(slot("2", "12:20", "История", room = "305")),
        ).map { it.text }
        assertEquals(listOf("09:00 отменена: Математика", "12:20 новая пара: История (каб. 305)"), changes)
    }

    @Test
    fun `замена предмета с новым id`() {
        val changes = diffSchedules(
            listOf(slot("1", "09:00", "Математика")),
            listOf(slot("7", "09:00", "Физика")),
        ).map { it.text }
        assertEquals(listOf("09:00 замена: Математика → Физика"), changes)
    }

    @Test
    fun `перенос и кабинет`() {
        val changes = diffSchedules(
            listOf(slot("1", "09:00", "Математика")),
            listOf(slot("1", "10:40", "Математика", room = "310")),
        ).map { it.text }
        assertEquals(listOf("09:00 Математика: перенос 09:00 → 10:40, кабинет 204 → 310"), changes)
    }

    @Test
    fun `кабинет можно не отслеживать`() {
        val changes = diffSchedules(
            listOf(slot("1", "09:00", "Математика")),
            listOf(slot("1", "09:00", "Математика", room = "310", teacher = "Петров")),
            includeRooms = false,
            includeTeachers = false,
        )
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `стала дистанционной`() {
        val changes = diffSchedules(
            listOf(slot("1", "09:00", "Математика")),
            listOf(slot("1", "09:00", "Математика", room = null, distance = true)),
        ).map { it.text }
        assertEquals(listOf("09:00 Математика: теперь дистанционно"), changes)
    }

    @Test
    fun `снимок кодируется и читается`() {
        val s = slot("1", "09:00", "Математика", room = null)
        assertEquals(s, SlotSnapshot.decode(s.encode()))
    }
}
