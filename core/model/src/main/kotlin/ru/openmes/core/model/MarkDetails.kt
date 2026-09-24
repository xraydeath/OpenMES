package ru.openmes.core.model

import java.time.LocalDate

/** Детали оценки (marks/{id}). */
data class MarkDetails(
    val id: Long,
    val value: String,
    val subjectName: String,
    val controlFormName: String?,
    val comment: String?,
    val weight: Int,
    val teacherName: String?,
    val lessonTopic: String?,
    val date: LocalDate?,
    val createdAt: String?,
    val criteria: List<CriteriaItem>,
    val classResults: ClassResults?,
) {
    data class CriteriaItem(val name: String, val value: String)
    data class ClassResults(
        val totalStudents: Int,
        val distributions: List<Distribution>,
    ) {
        data class Distribution(
            val value: Double?,
            val students: Int,
            val percentage: Int,
        )
    }
}

/** Зачётная книжка (attestation) — сгруппирована по курсам (учебным годам). */
data class GradeBook(
    /** Курсы по возрастанию: «1 курс», «2 курс»… — только те, по которым есть данные. */
    val courses: List<Course>,
) {
    data class Course(
        val name: String,
        val semesters: List<Semester>,
    )

    data class Semester(
        val name: String,
        val forms: List<Form>,
    )

    data class Form(
        val formName: String,
        val subjects: List<Subject>,
    )

    data class Subject(
        val subjectName: String,
        val hours: Int?,
        /** Оценка числом (null = не аттестован). */
        val value: Int?,
        /** «хорошо», «отлично», «зачёт». */
        val description: String?,
        val attested: Boolean,
        val academicDebt: Boolean,
        val date: LocalDate?,
        val teachers: List<String>,
        val theme: String?,
    )
}
