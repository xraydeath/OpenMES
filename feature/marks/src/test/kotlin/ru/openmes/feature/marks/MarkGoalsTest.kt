package ru.openmes.feature.marks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.openmes.core.model.Mark
import ru.openmes.core.model.RoundingRules

class MarkGoalsTest {

    private fun marks(vararg values: Int) = values.mapIndexed { i, v -> Mark(id = "m$i", value = v.toString(), weight = 1) }

    @Test
    fun `пятёрки до пятёрки`() {
        // 4 4 → 4.0; с двумя пятёрками 4.5 → итог 5.
        assertEquals(2, marksNeeded(marks(4, 4), value = 5, target = 5))
    }

    @Test
    fun `четвёрками пятёрку не получить`() {
        assertNull(marksNeeded(marks(3), value = 4, target = 5))
    }

    @Test
    fun `уже достигнуто`() {
        assertEquals(0, marksNeeded(marks(5, 4), value = 5, target = 5))
    }

    @Test
    fun `запас двоек`() {
        // 5 5 5: +1 двойка → 4.25 (итог 4) — пятёрка не выдерживает ни одной.
        assertEquals(0, marksReserve(marks(5, 5, 5), value = 2, current = 5))
        // 5 5 5 5 5: +1 → 4.5 (5), +2 → 4.14 (4).
        assertEquals(1, marksReserve(marks(5, 5, 5, 5, 5), value = 2, current = 5))
    }

    @Test
    fun `строгий порог 4,67`() {
        val strict = RoundingRules.STRICT
        // 5 5 4 → 4.67 → «5» по строгому; 5 4 → 4.5 → «4».
        assertEquals(5, strict.markFor(marks(5, 5, 4).weightedAverage()))
        assertEquals(4, strict.markFor(marks(5, 4).weightedAverage()))
        // 4 4: при 4,5 хватает двух пятёрок (4.5), при 4,67 — четырёх (4.67).
        assertEquals(4, marksNeeded(marks(4, 4), value = 5, target = 5, rules = strict))
    }

    @Test
    fun `неверные пороги`() {
        assertEquals(false, RoundingRules(3.5, 4.5, 2.5).isValid)
        assertEquals(true, RoundingRules.STRICT.isValid)
    }
}
