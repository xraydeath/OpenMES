package ru.openmes.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {

    @Test
    fun `PBKDF2-хэш проверяется и не требует перехэширования`() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash("1234", salt)
        assertTrue(hash.startsWith("pbkdf2$120000$"))
        assertTrue(PinHasher.verify("1234", salt, hash))
        assertFalse(PinHasher.verify("1235", salt, hash))
        assertFalse(PinHasher.needsRehash(hash))
    }

    @Test
    fun `старый SHA-256 принимается и помечается к перехэшированию`() {
        val salt = PinHasher.newSalt()
        val legacy = PinHasher.legacyHash("0000", salt)
        assertTrue(PinHasher.verify("0000", salt, legacy))
        assertFalse(PinHasher.verify("0001", salt, legacy))
        assertTrue(PinHasher.needsRehash(legacy))
    }

    @Test
    fun `меньшее число итераций помечается к перехэшированию`() {
        val salt = PinHasher.newSalt()
        val weak = PinHasher.hash("4321", salt, iterations = 1000)
        assertTrue(PinHasher.verify("4321", salt, weak))
        assertTrue(PinHasher.needsRehash(weak))
    }
}
