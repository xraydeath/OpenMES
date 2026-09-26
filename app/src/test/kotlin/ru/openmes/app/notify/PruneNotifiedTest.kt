package ru.openmes.app.notify

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PruneNotifiedTest {

    @Test
    fun `выбрасываются ключи прошедших дней и мусор, сегодняшние и будущие остаются`() {
        val today = LocalDate.of(2026, 9, 28)
        val keys = setOf("2026-09-27_1", "2026-09-28_2", "2026-10-01_3", "broken")
        assertEquals(setOf("2026-09-28_2", "2026-10-01_3"), LessonReminders.pruneNotified(keys, today))
    }

    @Test
    fun `много ключей на будущее не теряются`() {
        val today = LocalDate.of(2026, 9, 28)
        val keys = (0L until 100).map { "${today.plusDays(it / 5)}_$it" }.toSet()
        assertEquals(keys, LessonReminders.pruneNotified(keys, today))
    }
}
