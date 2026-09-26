package ru.openmes.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PinLockoutTest {

    @Test
    fun `первые попытки без паузы, затем растущая пауза с потолком`() {
        (0 until FREE_PIN_ATTEMPTS).forEach { assertEquals(0L, pinLockoutMillis(it)) }
        assertEquals(30_000L, pinLockoutMillis(FREE_PIN_ATTEMPTS))
        assertEquals(60_000L, pinLockoutMillis(FREE_PIN_ATTEMPTS + 1))
        assertEquals(30 * 60_000L, pinLockoutMillis(FREE_PIN_ATTEMPTS + 20))
    }
}
