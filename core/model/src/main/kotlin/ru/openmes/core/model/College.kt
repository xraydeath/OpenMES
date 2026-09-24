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
