package ru.openmes.feature.marks

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.openmes.core.model.Mark
import ru.openmes.core.model.RoundingRules

class MarkCalculatorTest {

    private fun mark(value: Int, weight: Int?) = Mark(id = "m$value$weight", value = value.toString(), weight = weight)

    @Test
    fun `разбор оценок с весами`() {
        assertEquals(listOf(5 to 1, 4 to 2, 3 to 1), parseMarks("5 4^2 3"))
        assertEquals(listOf(5 to 1, 4 to 1, 3 to 10), parseMarks(" 5,4; 3^10 "))
    }

    @Test
    fun `мусор пропускается`() {
        assertEquals(emptyList<Pair<Int, Int>>(), parseMarks(""))
        assertEquals(listOf(4 to 1), parseMarks("abc 6 0 4 5^2^3"))
    }

    @Test
    fun `неполный или нулевой вес пропускается`() {
        assertEquals(emptyList<Pair<Int, Int>>(), parseMarks("^0"))
        assertEquals(emptyList<Pair<Int, Int>>(), parseMarks("5^"))
        assertEquals(emptyList<Pair<Int, Int>>(), parseMarks("5^0 4^11 ^2"))
    }

    @Test
    fun `взвешенный средний`() {
        // (5·2 + 3) / 3 = 4.333… → 4.33
        assertEquals(4.33, listOf(mark(5, 2), mark(3, 1)).weightedAverage(), 1e-9)
        assertEquals(0.0, emptyList<Mark>().weightedAverage(), 1e-9)
    }

    @Test
    fun `вес null и 0 считаются за 1`() {
        assertEquals(4.5, listOf(mark(5, null), mark(4, 1)).weightedAverage(), 1e-9)
        assertEquals(4.5, listOf(mark(5, 0), mark(4, 1)).weightedAverage(), 1e-9)
    }

    @Test
    fun `округление половины вверх`() {
        // 5 5 5 5 5 4 4 4 → 37 / 8 = 4.625 → 4.63 (half-even дал бы 4.62 и итог «4»).
        val marks = listOf(5, 5, 5, 5, 5, 4, 4, 4).map { mark(it, 1) }
        assertEquals(4.63, marks.weightedAverage(), 1e-9)
        assertEquals(5, RoundingRules(4.63, 3.5, 2.5).markFor(marks.weightedAverage()))
    }
}
