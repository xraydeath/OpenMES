package ru.openmes.core.network.interceptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CacheSectionTest {

    private val guid = "c0ffee00-0000-4000-8000-000000000000"

    @Test
    fun `разделы портфолио определяются мимо guid`() {
        assertEquals(CacheSection.PORTFOLIO, CacheSection.of("portfolio/app/persons/$guid/events/list"))
        assertEquals(CacheSection.PORTFOLIO, CacheSection.of("portfolio/app/persons/$guid/sport-rewards/list"))
        assertEquals(CacheSection.MARKS, CacheSection.of("portfolio/app/persons/$guid/academic-performance/final-mark"))
        assertEquals(CacheSection.PROFILE, CacheSection.of("portfolio/app/persons/$guid/proforientation/getResults"))
    }

    @Test
    fun `звёздочка не перескакивает через сегменты`() {
        assertEquals(CacheSection.PROFILE, CacheSection.of("portfolio/app/persons/$guid/extra/events/list"))
    }

    @Test
    fun `новые адреса попадают в свои разделы`() {
        assertEquals(CacheSection.ATTENDANCE, CacheSection.of("api/ej/core/family/v1/emias_medical_recommendations"))
        assertEquals(CacheSection.FOOD, CacheSection.of("api/food/meals/v3/transactions"))
        assertNull(CacheSection.of("api/unknown/v1/thing"))
    }
}
