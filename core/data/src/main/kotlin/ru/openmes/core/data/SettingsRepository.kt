package ru.openmes.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import ru.openmes.core.model.RoundingRules
import ru.openmes.core.network.interceptor.CachePolicy
import ru.openmes.core.network.interceptor.CacheSection

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    /** Фоновая проверка новых оценок (WorkManager) с уведомлениями. */
    val marksNotifications: Boolean = false,
    /** Не показывать значение оценки в тексте уведомления. */
    val hideMarkValues: Boolean = false,
    /** Напоминание перед началом пары. */
    val lessonReminders: Boolean = false,
    /** За сколько минут до начала пары напоминать. */
    val lessonReminderMinutes: Int = 10,
    /** Напоминать только о дистанционных парах (с кнопкой «Подключиться»). */
    val lessonRemindersDistanceOnly: Boolean = false,
    /** Вечером: несделанное ДЗ на завтра. */
    val homeworkReminders: Boolean = false,
    /** Вечером: контрольные завтра. */
    val testReminders: Boolean = false,
    /** Час вечернего напоминания (ДЗ и контрольные). */
    val eveningReminderHour: Int = 19,
    /** Уведомлять об изменениях в расписании (фоновая проверка). */
    val scheduleChangeNotifications: Boolean = false,
    /** На сколько дней вперёд (включая сегодня) следить за изменениями. */
    val scheduleChangesDays: Int = 7,
    /** Сообщать и о смене кабинета/преподавателя (не только об отмене, замене и переносе). */
    val scheduleChangesRooms: Boolean = true,
    /** Режим калькулятора оценок: кнопки или ввод строкой. */
    val calculatorKeyboard: Boolean = false,
    /** Включена блокировка PIN-кодом. */
    val pinEnabled: Boolean = false,
    /** Разблокировка биометрией (при включённом PIN). */
    val biometricEnabled: Boolean = false,
    /** Офлайн-кэш ответов: мгновенный показ сохранённого и работа без сети. */
    val cacheEnabled: Boolean = true,
    /** Какие разделы кэшировать. */
    val cacheSections: Set<CacheSection> = CacheSection.entries.toSet(),
    /** Сохранять данные только за ±столько дней от сегодня; null — без ограничения. */
    val cacheWindowDays: Int? = null,
    /** Обновлять кэш в фоне примерно раз в час. */
    val cacheBackgroundRefresh: Boolean = true,
    /** Пороги округления среднего в итоговую (калькулятор оценок). */
    val roundingRules: RoundingRules = RoundingRules.STANDARD,
) {
    val cachePolicy: CachePolicy
        get() = CachePolicy(
            enabled = cacheEnabled,
            sections = cacheSections,
            windowDays = cacheWindowDays,
        )
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    val currentChildId: Flow<String?>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setCurrentChildId(id: String?)
    suspend fun setMarksNotifications(enabled: Boolean)
    suspend fun setHideMarkValues(enabled: Boolean)
    suspend fun setCalculatorKeyboard(enabled: Boolean)
    suspend fun setLessonReminders(enabled: Boolean)
    suspend fun setLessonReminderMinutes(minutes: Int)
    suspend fun setLessonRemindersDistanceOnly(enabled: Boolean)
    suspend fun setHomeworkReminders(enabled: Boolean)
    suspend fun setTestReminders(enabled: Boolean)
    suspend fun setEveningReminderHour(hour: Int)
    suspend fun setScheduleChangeNotifications(enabled: Boolean)
    suspend fun setScheduleChangesDays(days: Int)
    suspend fun setScheduleChangesRooms(enabled: Boolean)

    /** Установить PIN (null — снять блокировку). Хранится только солёный PBKDF2-хэш. */
    suspend fun setPin(pin: String?)
    suspend fun checkPin(pin: String): Boolean
    suspend fun setBiometricEnabled(enabled: Boolean)

    suspend fun setCacheEnabled(enabled: Boolean)
    suspend fun setCacheSection(section: CacheSection, enabled: Boolean)
    suspend fun setCacheWindowDays(days: Int?)
    suspend fun setCacheBackgroundRefresh(enabled: Boolean)
    suspend fun setRoundingRules(rules: RoundingRules)
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
        val LESSON_REMINDERS = booleanPreferencesKey("lesson_reminders")
        val LESSON_REMINDER_MINUTES = intPreferencesKey("lesson_reminder_minutes")
        val LESSON_REMINDERS_DISTANCE_ONLY = booleanPreferencesKey("lesson_reminders_distance_only")
        val HOMEWORK_REMINDERS = booleanPreferencesKey("homework_reminders")
        val TEST_REMINDERS = booleanPreferencesKey("test_reminders")
        val EVENING_REMINDER_HOUR = intPreferencesKey("evening_reminder_hour")
        val SCHEDULE_CHANGES = booleanPreferencesKey("schedule_changes")
        val SCHEDULE_CHANGES_DAYS = intPreferencesKey("schedule_changes_days")
        val SCHEDULE_CHANGES_ROOMS = booleanPreferencesKey("schedule_changes_rooms")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val BIOMETRIC = booleanPreferencesKey("biometric")
        val CACHE_ENABLED = booleanPreferencesKey("cache_enabled")

        /** Храним выключенные разделы: новые разделы из обновлений включены по умолчанию. */
        val CACHE_DISABLED_SECTIONS = stringSetPreferencesKey("cache_disabled_sections")
        val CACHE_WINDOW_DAYS = intPreferencesKey("cache_window_days")
        val CACHE_BACKGROUND_REFRESH = booleanPreferencesKey("cache_background_refresh")
        val ROUND_FIVE = doublePreferencesKey("round_five")
        val ROUND_FOUR = doublePreferencesKey("round_four")
        val ROUND_THREE = doublePreferencesKey("round_three")
    }

    override val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: false,
            marksNotifications = prefs[Keys.MARKS_NOTIFICATIONS] ?: false,
            hideMarkValues = prefs[Keys.HIDE_MARK_VALUES] ?: false,
            calculatorKeyboard = prefs[Keys.CALCULATOR_KEYBOARD] ?: false,
            lessonReminders = prefs[Keys.LESSON_REMINDERS] ?: false,
            lessonReminderMinutes = prefs[Keys.LESSON_REMINDER_MINUTES] ?: 10,
            lessonRemindersDistanceOnly = prefs[Keys.LESSON_REMINDERS_DISTANCE_ONLY] ?: false,
            homeworkReminders = prefs[Keys.HOMEWORK_REMINDERS] ?: false,
            testReminders = prefs[Keys.TEST_REMINDERS] ?: false,
            eveningReminderHour = prefs[Keys.EVENING_REMINDER_HOUR] ?: 19,
            scheduleChangeNotifications = prefs[Keys.SCHEDULE_CHANGES] ?: false,
            scheduleChangesDays = prefs[Keys.SCHEDULE_CHANGES_DAYS] ?: 7,
            scheduleChangesRooms = prefs[Keys.SCHEDULE_CHANGES_ROOMS] ?: true,
            pinEnabled = prefs[Keys.PIN_HASH] != null,
            biometricEnabled = prefs[Keys.PIN_HASH] != null && (prefs[Keys.BIOMETRIC] ?: false),
            cacheEnabled = prefs[Keys.CACHE_ENABLED] ?: true,
            cacheSections = prefs[Keys.CACHE_DISABLED_SECTIONS].orEmpty().let { disabled ->
                CacheSection.entries.filterTo(mutableSetOf()) { it.key !in disabled }
            },
            cacheWindowDays = prefs[Keys.CACHE_WINDOW_DAYS],
            cacheBackgroundRefresh = prefs[Keys.CACHE_BACKGROUND_REFRESH] ?: true,
            roundingRules = RoundingRules(
                five = prefs[Keys.ROUND_FIVE] ?: RoundingRules.STANDARD.five,
                four = prefs[Keys.ROUND_FOUR] ?: RoundingRules.STANDARD.four,
                three = prefs[Keys.ROUND_THREE] ?: RoundingRules.STANDARD.three,
            ).takeIf { it.isValid } ?: RoundingRules.STANDARD,
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

    override suspend fun setLessonReminders(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.LESSON_REMINDERS] = enabled }
    }

    override suspend fun setLessonReminderMinutes(minutes: Int) {
        context.settingsDataStore.edit { it[Keys.LESSON_REMINDER_MINUTES] = minutes.coerceIn(1, 120) }
    }

    override suspend fun setLessonRemindersDistanceOnly(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.LESSON_REMINDERS_DISTANCE_ONLY] = enabled }
    }

    override suspend fun setHomeworkReminders(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.HOMEWORK_REMINDERS] = enabled }
    }

    override suspend fun setTestReminders(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.TEST_REMINDERS] = enabled }
    }

    override suspend fun setEveningReminderHour(hour: Int) {
        context.settingsDataStore.edit { it[Keys.EVENING_REMINDER_HOUR] = hour.coerceIn(0, 23) }
    }

    override suspend fun setScheduleChangeNotifications(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SCHEDULE_CHANGES] = enabled }
    }

    override suspend fun setScheduleChangesDays(days: Int) {
        context.settingsDataStore.edit { it[Keys.SCHEDULE_CHANGES_DAYS] = days.coerceIn(1, 31) }
    }

    override suspend fun setScheduleChangesRooms(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SCHEDULE_CHANGES_ROOMS] = enabled }
    }

    override suspend fun setPin(pin: String?) {
        if (pin == null) {
            context.settingsDataStore.edit { prefs ->
                prefs.remove(Keys.PIN_HASH)
                prefs.remove(Keys.PIN_SALT)
                prefs.remove(Keys.BIOMETRIC)
            }
            return
        }
        val salt = PinHasher.newSalt()
        val hash = withContext(Dispatchers.Default) { PinHasher.hash(pin, salt) }
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.PIN_SALT] = salt
            prefs[Keys.PIN_HASH] = hash
        }
    }

    override suspend fun checkPin(pin: String): Boolean {
        val prefs = context.settingsDataStore.data.first()
        val hash = prefs[Keys.PIN_HASH] ?: return true
        val salt = prefs[Keys.PIN_SALT].orEmpty()
        val ok = withContext(Dispatchers.Default) { PinHasher.verify(pin, salt, hash) }
        if (ok && PinHasher.needsRehash(hash)) {
            // Старый хэш (один SHA-256) — прозрачно переводим на PBKDF2, раз PIN известен.
            val newSalt = PinHasher.newSalt()
            val newHash = withContext(Dispatchers.Default) { PinHasher.hash(pin, newSalt) }
            context.settingsDataStore.edit { p ->
                // PIN могли сменить или снять, пока считали.
                if (p[Keys.PIN_HASH] == hash) {
                    p[Keys.PIN_SALT] = newSalt
                    p[Keys.PIN_HASH] = newHash
                }
            }
        }
        return ok
    }

    override suspend fun setBiometricEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.BIOMETRIC] = enabled }
    }

    override suspend fun setCacheEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.CACHE_ENABLED] = enabled }
    }

    override suspend fun setCacheSection(section: CacheSection, enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            val disabled = prefs[Keys.CACHE_DISABLED_SECTIONS].orEmpty()
            prefs[Keys.CACHE_DISABLED_SECTIONS] = if (enabled) disabled - section.key else disabled + section.key
        }
    }

    override suspend fun setCacheWindowDays(days: Int?) {
        context.settingsDataStore.edit { prefs ->
            if (days == null) prefs.remove(Keys.CACHE_WINDOW_DAYS) else prefs[Keys.CACHE_WINDOW_DAYS] = days
        }
    }

    override suspend fun setCacheBackgroundRefresh(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.CACHE_BACKGROUND_REFRESH] = enabled }
    }

    override suspend fun setRoundingRules(rules: RoundingRules) {
        if (!rules.isValid) return
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.ROUND_FIVE] = rules.five
            prefs[Keys.ROUND_FOUR] = rules.four
            prefs[Keys.ROUND_THREE] = rules.three
        }
    }

}
