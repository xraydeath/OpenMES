package ru.openmes.core.data

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Хэш PIN-кода: PBKDF2-HMAC-SHA256 со случайной солью. Формат — «pbkdf2$итерации$hex»,
 * чтобы потом можно было поднять число итераций. Старый формат (голый hex одного
 * SHA-256(salt+pin)) ещё принимается [verify], но требует перехэширования ([needsRehash]).
 * Считать не с главного потока: одна проверка — сотни миллисекунд.
 */
internal object PinHasher {

    private const val PREFIX = "pbkdf2"
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    fun newSalt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()

    fun hash(pin: String, salt: String, iterations: Int = ITERATIONS): String =
        "$PREFIX$$iterations$" + pbkdf2(pin, salt, iterations).toHex()

    fun verify(pin: String, salt: String, stored: String): Boolean {
        val expected = if (stored.startsWith("$PREFIX$")) {
            val iterations = stored.split('$').getOrNull(1)?.toIntOrNull() ?: return false
            hash(pin, salt, iterations)
        } else {
            legacyHash(pin, salt)
        }
        return MessageDigest.isEqual(stored.toByteArray(), expected.toByteArray())
    }

    /** Хэш старого формата или с меньшим числом итераций. */
    fun needsRehash(stored: String): Boolean =
        !stored.startsWith("$PREFIX$$ITERATIONS$")

    private fun pbkdf2(pin: String, salt: String, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt.toByteArray(), iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Прежняя схема: один SHA-256(salt + pin). */
    internal fun legacyHash(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
