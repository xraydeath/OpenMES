package ru.openmes.core.data

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    /** Фоновая проверка новых оценок (WorkManager) с уведомлениями. */
    val marksNotifications: Boolean = false,
    /** Не показывать значение оценки в тексте уведомления. */
    val hideMarkValues: Boolean = false,
    /** Режим калькулятора оценок: кнопки или ввод строкой. */
    val calculatorKeyboard: Boolean = false,
    /** Включена блокировка PIN-кодом. */
    val pinEnabled: Boolean = false,
    /** Разблокировка биометрией (при включённом PIN). */
    val biometricEnabled: Boolean = false,
)

interface SettingsRepository {
    val settings: Flow<AppSettings>
    val currentChildId: Flow<String?>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setCurrentChildId(id: String?)
    suspend fun setMarksNotifications(enabled: Boolean)
    suspend fun setHideMarkValues(enabled: Boolean)
    suspend fun setCalculatorKeyboard(enabled: Boolean)

    /** Установить PIN (null — снять блокировку). Хранится только солёный SHA-256. */
    suspend fun setPin(pin: String?)
    suspend fun checkPin(pin: String): Boolean
    suspend fun setBiometricEnabled(enabled: Boolean)
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "openmes_settings")

class DataStoreSettingsRepository(
    private val context: Context,
) : SettingsRepository {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val CURRENT_CHILD = stringPreferencesKey("current_child_id")
        val MARKS_NOTIFICATIONS = booleanPreferencesKey("marks_notifications")
        val HIDE_MARK_VALUES = booleanPreferencesKey("hide_mark_values")
        val CALCULATOR_KEYBOARD = booleanPreferencesKey("calculator_keyboard")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val BIOMETRIC = booleanPreferencesKey("biometric")
    }

    override val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: false,
            marksNotifications = prefs[Keys.MARKS_NOTIFICATIONS] ?: false,
            hideMarkValues = prefs[Keys.HIDE_MARK_VALUES] ?: false,
            calculatorKeyboard = prefs[Keys.CALCULATOR_KEYBOARD] ?: false,
            pinEnabled = prefs[Keys.PIN_HASH] != null,
            biometricEnabled = prefs[Keys.PIN_HASH] != null && (prefs[Keys.BIOMETRIC] ?: false),
        )
    }

    override val currentChildId: Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.CURRENT_CHILD] }

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    override suspend fun setCurrentChildId(id: String?) {
        context.settingsDataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.CURRENT_CHILD) else prefs[Keys.CURRENT_CHILD] = id
        }
    }

    override suspend fun setMarksNotifications(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.MARKS_NOTIFICATIONS] = enabled }
    }

    override suspend fun setHideMarkValues(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.HIDE_MARK_VALUES] = enabled }
    }

    override suspend fun setCalculatorKeyboard(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.CALCULATOR_KEYBOARD] = enabled }
    }

    override suspend fun setPin(pin: String?) {
        context.settingsDataStore.edit { prefs ->
            if (pin == null) {
                prefs.remove(Keys.PIN_HASH)
                prefs.remove(Keys.PIN_SALT)
                prefs.remove(Keys.BIOMETRIC)
            } else {
                val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
                prefs[Keys.PIN_SALT] = salt
                prefs[Keys.PIN_HASH] = hashPin(pin, salt)
            }
        }
    }

    override suspend fun checkPin(pin: String): Boolean {
        val prefs = context.settingsDataStore.data.first()
        val hash = prefs[Keys.PIN_HASH] ?: return true
        val salt = prefs[Keys.PIN_SALT].orEmpty()
        return MessageDigest.isEqual(hash.toByteArray(), hashPin(pin, salt).toByteArray())
    }

    override suspend fun setBiometricEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.BIOMETRIC] = enabled }
    }

    private fun hashPin(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
