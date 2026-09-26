package ru.openmes.core.model

import java.time.LocalDate
import java.time.LocalDateTime

/** Сведения об образовательной организации (school_info). */
data class SchoolInfo(
    val name: String,
    val principal: String? = null,
    val curators: List<String> = emptyList(),
    val address: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val website: String? = null,
    val branches: List<SchoolBranch> = emptyList(),
)

data class SchoolBranch(
    val name: String,
    val address: String? = null,
    val isMain: Boolean = false,
    /** Корпус, к которому прикреплён студент. */
    val isStudentBuilding: Boolean = false,
)

/** Результаты профориентации (portfolio proforientation). */
data class Proforientation(
    val testUrl: String? = null,
    val testDate: LocalDateTime? = null,
    val detailsUrl: String? = null,
    val industries: List<ProfIndustry> = emptyList(),
    val upcoming: List<ProfEvent> = emptyList(),
    val history: List<ProfEvent> = emptyList(),
)

data class ProfIndustry(
    val name: String,
    val atlasUrl: String? = null,
    /** «Код Название». */
    val specialties: List<String> = emptyList(),
    val colleges: List<ProfCollege> = emptyList(),
)

data class ProfCollege(val name: String, val atlasUrl: String? = null)

data class ProfEvent(
    val name: String,
    val kind: String,
    val date: LocalDate? = null,
    val time: String? = null,
    val address: String? = null,
    val organizer: String? = null,
    val visited: Boolean = false,
)

/** Портфолио: олимпиады и мероприятия, награды (включая спортивные). */
data class Portfolio(
    val events: List<PortfolioEvent> = emptyList(),
    val rewards: List<PortfolioReward> = emptyList(),
)

data class PortfolioEvent(
    val name: String,
    val date: LocalDate? = null,
    /** «Школьный этап». */
    val stage: String? = null,
    /** «Очное» / «Заочное». */
    val format: String? = null,
    val subjects: List<String> = emptyList(),
    val category: String? = null,
    /** Набранные баллы (как пришли: "15.0"). */
    val score: Double? = null,
    val maxScore: Double? = null,
    /** Награда за это мероприятие: «Призер», «Победитель»… */
    val reward: String? = null,
)

data class PortfolioReward(
    val name: String,
    val date: LocalDate? = null,
    val sport: Boolean = false,
    /** Откуда: «МЭШ Олимпиады», «ГТО». */
    val source: String? = null,
    /** Ступень ГТО и т.п. */
    val details: String? = null,
    val number: String? = null,
    val expireDate: LocalDate? = null,
)

/** Годовые оценки за учебный год (портфолио). */
data class FinalMarksYear(
    /** «2023-2024»; null — сервис не указал. */
    val title: String?,
    /** Номер года обучения. */
    val year: Int? = null,
    val level: String? = null,
    val average: Double? = null,
    val marks: List<FinalMark> = emptyList(),
)

data class FinalMark(
    val subject: String,
    /** «5», «зачёт», «незачёт». */
    val value: String,
    /** Числовая оценка, если она есть (для цвета). */
    val numeric: Int? = null,
    val gradeSystem: String? = null,
)

/** Запись ЕМИАС за один день. */
data class MedicalRecord(
    val date: LocalDate,
    /** SICK, SICK_WITH_INFECTION, EXEMPT. */
    val type: String,
    /** Освобождение только от части предметов. */
    val partial: Boolean = false,
)
