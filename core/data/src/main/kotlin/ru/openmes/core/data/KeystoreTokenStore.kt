package ru.openmes.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Хранилище токенов: файл, шифрованный AES-256-GCM ключом из Android Keystore.
 *
 * В оригинальном приложении ключ шифрования MMKV зашит в бандл —
 * здесь ключ генерируется на устройстве и никогда не покидает Keystore.
 */
interface TokenStore {
    fun save(tokens: AuthTokens)
    fun load(): AuthTokens?
    fun clear()

    /**
     * Атомарное чтение-изменение-запись: [transform] получает текущие токены (null — их нет)
     * и возвращает новые; null или тот же объект — ничего не записывать.
     * @return токены в хранилище после операции.
     */
    fun update(transform: (AuthTokens?) -> AuthTokens?): AuthTokens?
}

class KeystoreTokenStore(context: Context) : TokenStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val file: File = File(context.filesDir, "openmes_tokens.bin")
    private val lock = Any()

    /** Расшифрованные токены в памяти: сетевой слой читает их на каждый запрос. */
    @Volatile
    private var cached: AuthTokens? = null

    @Volatile
    private var cacheLoaded = false

    @Volatile
    private var key: SecretKey? = null

    @Synchronized
    private fun masterKey(): SecretKey {
        key?.let { return it }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it.also { key = it } }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE)
                .build(),
        )
        return generator.generateKey().also { key = it }
    }

    override fun save(tokens: AuthTokens) = synchronized(lock) {
        write(tokens)
        cached = tokens
        cacheLoaded = true
    }

    override fun load(): AuthTokens? {
        if (cacheLoaded) return cached
        return synchronized(lock) {
            if (!cacheLoaded) {
                cached = read()
                cacheLoaded = true
            }
            cached
        }
    }

    override fun clear() = synchronized(lock) {
        file.delete()
        cached = null
        cacheLoaded = true
    }

    override fun update(transform: (AuthTokens?) -> AuthTokens?): AuthTokens? = synchronized(lock) {
        val current = load()
        val updated = transform(current)
        if (updated != null && updated !== current) save(updated)
        load()
    }

    private fun write(tokens: AuthTokens) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, masterKey()) }
        val iv = cipher.iv
        val encrypted = cipher.doFinal(json.encodeToString(tokens).toByteArray(Charsets.UTF_8))
        // Атомарная запись с fsync: временный файл → сброс на диск → rename.
        val tmp = File(file.absolutePath + ".tmp")
        tmp.outputStream().use { out ->
            out.write(iv)
            out.write(encrypted)
            out.flush()
            (out as? java.io.FileOutputStream)?.fd?.sync()
        }
        if (tmp.renameTo(file)) {
            file.parentFile?.let { runCatching { (java.io.FileOutputStream(it.absolutePath, true)).fd.sync() } }
        }
    }

    private fun read(): AuthTokens? {
        if (!file.exists()) return null
        return runCatching {
            val bytes = file.readBytes()
            require(bytes.size > IV_SIZE) { "corrupted token file" }
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val payload = bytes.copyOfRange(IV_SIZE, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, iv))
            }
            json.decodeFromString<AuthTokens>(String(cipher.doFinal(payload), Charsets.UTF_8))
        }.getOrNull()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "openmes_master_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE = 256
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
