package ru.openmes.core.model

import java.time.LocalDate
import java.time.LocalTime

/** Детали урока (lesson_schedule_items/{id}) — BottomSheet из расписания. */
data class LessonDetails(
    val id: Long,
    val subjectName: String,
    val date: LocalDate?,
    val beginTime: LocalTime?,
    val endTime: LocalTime?,
    val room: String?,
    val building: String?,
    val teacherName: String?,
    val comment: String?,
    /** Статус здоровья: SICK | SICK_WITH_INFECTION | EXEMPT (освобождение). */
    val diseaseStatusType: String?,
    val homework: String?,
    val homeworkDone: Boolean,
    val marks: List<Mark>,
    /** Дистанционное занятие (is_virtual / remote_lesson / link_to_join). */
    val isDistance: Boolean = false,
    /** Ссылка на подключение — приходит только в расписании (eventcalendar). */
    val joinUrl: String? = null,
    /** Модуль/тема предмета на дату урока (lesson_modules). */
    val module: String? = null,
    /** Форма контроля, если урок — контрольное занятие (test_lessons). */
    val testName: String? = null,
)
