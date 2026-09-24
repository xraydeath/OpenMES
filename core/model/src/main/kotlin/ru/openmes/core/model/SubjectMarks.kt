package ru.openmes.core.model

import java.time.LocalDate

/** Предмет со списком оценок за период (из marks by date). */
data class SubjectMarks(
    val subjectName: String,
    val marks: List<Mark>,
) {
    val numericMarks: List<Double>
        get() = marks.mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }

    val average: Double?
        get() = numericMarks.takeIf { it.isNotEmpty() }?.average()
}

/** Домен: предмет с периодами и оценками (из subject_marks). */
data class SubjectMarksData(
    val subjectId: Long,
    val subjectName: String,
    val yearMark: String? = null,
    val dynamic: String? = null,
    val periods: List<SubjectPeriod> = emptyList(),
) {
    /** Период, активный сейчас (по start/end датам), либо первый. */
    fun currentPeriod(now: LocalDate = LocalDate.now()): SubjectPeriod? =
        periods.firstOrNull { p ->
            val start = p.start
            val end = p.end
            start != null && end != null && !now.isBefore(start) && !now.isAfter(end)
        } ?: periods.firstOrNull()
}

data class SubjectPeriod(
    val title: String,
    val start: LocalDate? = null,
    val end: LocalDate? = null,
    /** Средний балл за период строкой («5.00»). */
    val value: String? = null,
    /** Итоговая (фиксированная) оценка за период. */
    val fixedValue: String? = null,
    val dynamic: String? = null,
    val marks: List<Mark> = emptyList(),
)
