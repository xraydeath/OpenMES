package ru.openmes.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.openmes.core.model.Lesson
import java.time.LocalDate
import java.time.LocalTime

class PairsCountTest {

    private fun lesson(start: String, subject: String, minutes: Long = 45) = Lesson(
        id = "$start-$subject",
        date = LocalDate.of(2026, 9, 28),
        number = 0,
        startTime = LocalTime.parse(start),
        endTime = LocalTime.parse(start).plusMinutes(minutes),
        subjectName = subject,
    )

    @Test
    fun `сдвоенные уроки одного предмета — одна пара`() {
        val lessons = listOf(
            lesson("09:00", "Математика"),
            lesson("09:50", "Математика"),
            lesson("10:45", "Физика"),
            lesson("11:35", "Физика"),
        )
        assertEquals(2, pairsCount(lessons))
    }

    @Test
    fun `три урока подряд — пара и ещё один`() {
        val lessons = listOf(
            lesson("09:00", "История"),
            lesson("09:50", "История"),
            lesson("10:40", "История"),
        )
        assertEquals(2, pairsCount(lessons))
    }

    @Test
    fun `большая перемена разрывает пару`() {
        val lessons = listOf(
            lesson("09:00", "Химия"),
            lesson("10:15", "Химия"),
        )
        assertEquals(2, pairsCount(lessons))
    }

    @Test
    fun `полуторачасовые занятия считаются по одному`() {
        val lessons = listOf(
            lesson("09:00", "Химия", 90),
            lesson("10:40", "Биология", 90),
        )
        assertEquals(2, pairsCount(lessons))
    }
}
