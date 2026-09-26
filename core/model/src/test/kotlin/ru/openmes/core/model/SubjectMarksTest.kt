package ru.openmes.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SubjectMarksTest {

    private val subject = SubjectMarksData(
        subjectId = 1,
        subjectName = "Математика",
        periods = listOf(
            SubjectPeriod("1 семестр", LocalDate.of(2025, 9, 1), LocalDate.of(2025, 12, 28)),
            SubjectPeriod("2 семестр", LocalDate.of(2026, 1, 12), LocalDate.of(2026, 6, 30)),
        ),
    )

    @Test
    fun `current period by dates`() {
        assertEquals("1 семестр", subject.currentPeriod(LocalDate.of(2025, 10, 1))?.title)
        assertEquals("2 семестр", subject.currentPeriod(LocalDate.of(2026, 1, 12))?.title)
    }

    @Test
    fun `between periods - last started`() {
        assertEquals("1 семестр", subject.currentPeriod(LocalDate.of(2026, 1, 5))?.title)
    }

    @Test
    fun `after last period - last`() {
        assertEquals("2 семестр", subject.currentPeriod(LocalDate.of(2026, 8, 1))?.title)
    }

    @Test
    fun `before first period - first`() {
        assertEquals("1 семестр", subject.currentPeriod(LocalDate.of(2025, 8, 20))?.title)
    }

    @Test
    fun `no dates - first`() {
        val noDates = subject.copy(periods = listOf(SubjectPeriod("A"), SubjectPeriod("B")))
        assertEquals("A", noDates.currentPeriod(LocalDate.of(2026, 1, 5))?.title)
    }

    @Test
    fun `no periods - null`() {
        assertNull(subject.copy(periods = emptyList()).currentPeriod())
    }
}
