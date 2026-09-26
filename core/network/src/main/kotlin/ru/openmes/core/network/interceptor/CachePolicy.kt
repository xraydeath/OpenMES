package ru.openmes.core.network.interceptor

/**
 * Разделы офлайн-кэша: какие ответы МЭШ к какому разделу относятся (по префиксу пути;
 * `*` — один сегмент, например guid в адресах портфолио). Побеждает первый подходящий раздел.
 */
enum class CacheSection(val key: String, val paths: List<String>) {
    /** Профиль для восстановления сессии: без него запуск без сети выкидывает из аккаунта. Кэшируется всегда. */
    SESSION(
        "session",
        listOf(
            "api/family/mobile/v1/profile",
            "acl/api/users/profile_info",
        ),
    ),
    SCHEDULE(
        "schedule",
        listOf(
            "api/eventcalendar/v1/api/events",
            "api/family/mobile/v1/lesson_schedule_items/",
            "api/ej/core/family/v1/academic_years",
        ),
    ),

    /** Каникулы, выходные и переносы рабочих дней. */
    CALENDAR(
        "calendar",
        listOf(
            "api/family/mobile/v1/periods_schedules",
            "api/ej/core/family/v1/calendars/",
        ),
    ),

    /** Модули и темы уроков, контрольные. */
    PLAN("plan", listOf("api/ej/plan/family/v1/")),
    MARKS(
        "marks",
        listOf(
            "api/family/mobile/v1/marks",
            "api/family/mobile/v1/subject_marks",
            "api/family/mobile/v1/attestation",
            // Годовые оценки из портфолио.
            "portfolio/app/persons/*/academic-performance/",
        ),
    ),
    HOMEWORK("homework", listOf("api/family/mobile/v1/homeworks")),
    ATTENDANCE(
        "attendance",
        listOf(
            "api/family/mobile/v1/attendance",
            "api/ej/core/family/v1/emias_medical_recommendations",
        ),
    ),

    /** Проходы через турникеты. */
    VISITS("visits", listOf("api/pass/entrances/")),
    FOOD(
        "food",
        listOf(
            "api/food/meals/v3/menu/",
            "api/food/meals/v3/clients/balance",
            "api/food/meals/v3/clients/food-provider",
            "api/food/meals/v3/transactions",
        ),
    ),
    NEWS("news", listOf("api/news/v2/news")),

    /** Олимпиады, мероприятия и награды из портфолио. */
    PORTFOLIO(
        "portfolio",
        listOf(
            "portfolio/app/persons/*/events/",
            "portfolio/app/persons/*/rewards/",
            "portfolio/app/persons/*/sport-rewards/",
        ),
    ),

    /** Студбилет (и его QR), сведения о колледже, профориентация, аватар; прочие разделы портфолио. */
    PROFILE(
        "profile",
        listOf(
            "api/family/mobile/v1/student-card",
            "api/family/mobile/v1/school_info",
            "portfolio/app/persons/",
            "api/avatarmanagement/v1/",
        ),
    ),
    ;

    val userToggleable: Boolean get() = this != SESSION

    companion object {
        fun of(path: String): CacheSection? = entries.firstOrNull { s -> s.paths.any { matches(path, it) } }

        private fun matches(path: String, pattern: String): Boolean {
            if ('*' !in pattern) return path.startsWith(pattern)
            val regex = patterns.getOrPut(pattern) {
                Regex(pattern.split('*').joinToString("[^/]+") { Regex.escape(it) })
            }
            return regex.matchesAt(path, 0)
        }

        private val patterns = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    }
}

/** Настройки офлайн-кэша (задаются пользователем, применяются в [OfflineCache]). */
data class CachePolicy(
    val enabled: Boolean = true,
    val sections: Set<CacheSection> = CacheSection.entries.toSet(),
    /** Сохранять только ответы, чей диапазон дат задевает ±windowDays от сегодня; null — без ограничения. */
    val windowDays: Int? = null,
) {
    fun allows(section: CacheSection): Boolean =
        section == CacheSection.SESSION || (enabled && section in sections)
}

/** Размер офлайн-кэша на диске. */
data class CacheStats(val files: Int, val bytes: Long)
