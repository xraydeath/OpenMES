package ru.openmes.core.model

import java.time.LocalDate
import java.time.LocalTime

/** Учебный год с периодами (четвертями/модулями). */
data class AcademicYear(
    val id: String,
    val name: String,
    val start: LocalDate,
    val end: LocalDate,
    val periods: List<Period> = emptyList(),
    /** Календарь года (для переносов дней). */
    val calendarId: Long? = null,
    val isCurrent: Boolean = false,
)

data class Period(
    val id: String,
    val index: Int,
    val name: String,
    val start: LocalDate,
    val end: LocalDate,
)

/** Урок в расписании. */
data class Lesson(
    val id: String,
    val date: LocalDate,
    val number: Int,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
    val subjectName: String,
    val subjectId: Long? = null,
    val room: String? = null,
    val teacherName: String? = null,
    val marks: List<Mark> = emptyList(),
    val homework: Homework? = null,
    /** Дистанционное занятие (есть ссылка на подключение). */
    val isDistance: Boolean = false,
    /** Ссылка на подключение к дистанционному занятию (link_to_join). */
    val joinUrl: String? = null,
    /** Источник события: PLAN (плановый урок) | EC | AE | EVENTS — цвет карточки. */
    val source: String? = null,
    /** Форма занятия («Практическое занятие»). */
    val lessonForm: String? = null,
)

/** Оценка. value — строка: «5», «4.5», «Зачёт», «Н» и т.д. */
data class Mark(
    val id: String,
    val value: String,
    val weight: Int? = null,
    val typeName: String? = null,
    val date: LocalDate? = null,
    val comment: String? = null,
)

/** Домашнее задание. */
data class Homework(
    val id: String,
    val date: LocalDate,
    val subjectName: String,
    val task: String,
    val isDone: Boolean = false,
    val materialsCount: Int = 0,
    /** homework_entry_id — нужен для запуска ЭОР (homeworks/launch). */
    val entryId: Long? = null,
    val materials: List<HomeworkMaterial> = emptyList(),
)

/** Материал ДЗ: файл (прямая ссылка) или ЭОР (ссылка через homeworks/launch). */
data class HomeworkMaterial(
    val uuid: String?,
    val title: String,
    val typeName: String?,
    /** learn | execute. */
    val mode: String?,
    /** Прямая ссылка на файл (для type=attachments). */
    val fileUrl: String?,
) {
    val isFile: Boolean get() = fileUrl != null
}

/** Посещаемость за день: уроки с пропусками/статусами. */
data class AttendanceEntry(
    val date: LocalDate,
    val lessons: List<AttendanceLesson>,
) {
    val missedLessons: Int get() = lessons.size
}

data class AttendanceLesson(
    val subjectName: String,
    val beginTime: LocalTime?,
    val endTime: LocalTime?,
    val reason: AbsenceReason?,
    /** SICK | SICK_WITH_INFECTION | EXEMPT. */
    val healthStatus: String?,
    val notified: Boolean,
)

/** Официальный ReasonIdEnum из бандла (12 причин). */
enum class AbsenceReason(val id: Int, val title: String) {
    SICK(1, "По болезни"),
    FEELING_UNWELL(2, "По самочувствию"),
    FAMILY(3, "По семейным обстоятельствам"),
    NOT_NOTIFIED(4, "Без уведомления"),
    EMIAS_SICK(5, "По болезни (ЕМИАС)"),
    PARENTS_CLAIM(6, "По заявлению родителей"),
    QUARANTINE(7, "Карантин"),
    UNKNOWN(8, "Причина неизвестна"),
    STUDENTS_CLAIM(9, "По заявлению студента"),
    INDIVIDUAL_CURRICULUM(10, "ИУП"),
    PARENTS_HOLIDAY(11, "Отпуск родителей"),
    STUDENT_HOLIDAY(12, "Каникулы");

    /** Уважительная причина (всё, кроме «без уведомления» и «неизвестно»). */
    val isExcused: Boolean get() = this != NOT_NOTIFIED && this != UNKNOWN

    companion object {
        fun byId(id: Int?): AbsenceReason? = entries.firstOrNull { it.id == id }
    }
}

/** Электронный студенческий билет. */
data class StudentCard(
    val fullName: String,
    val cardNumber: String?,
    val issueDate: String?,
    val validUntil: String?,
    val educationForm: String?,
    val course: String?,
    val educationLevel: String?,
    val specialty: String?,
    val enrollmentOrder: String?,
    val schoolName: String?,
    val founderName: String?,
)

/** Тип дня по календарю дневника (periods_schedules). */
enum class DayKind { WORKDAY, HOLIDAY, VACATION }

/**
 * День календаря: тип, период («Теоретическое обучение», «Производственная практика»)
 * и перенос («рабочий день по расписанию понедельника»).
 */
data class DayInfo(
    val kind: DayKind,
    val title: String? = null,
    val note: String? = null,
)

/** Модуль (тема) предмета с датами действия. */
data class LessonModule(
    val id: Long,
    val name: String,
    val subjectId: Long?,
    val start: LocalDate?,
    val end: LocalDate?,
) {
    fun covers(date: LocalDate): Boolean =
        (start == null || !date.isBefore(start)) && (end == null || !date.isAfter(end))
}

/** Контрольное/зачётное занятие (test_lessons). */
data class TestLesson(
    val date: LocalDate?,
    val lessonId: Long?,
    val subjectId: Long?,
    val subjectName: String?,
    /** Форма контроля: «Контрольная работа», «Зачёт»… */
    val name: String?,
)

/** Проходы через турникеты за день. */
data class VisitDay(
    val date: LocalDate,
    val visits: List<Visit>,
)

data class Visit(
    val entered: LocalTime?,
    val left: LocalTime?,
    /** «3 ч.43 мин.» — как отдаёт сервер. */
    val duration: String?,
    val place: String?,
    /** Нет отметки входа или выхода. */
    val incomplete: Boolean,
)

/**
 * Пороги округления среднего балла в итоговую: итог «5» — от [five], «4» — от [four], «3» — от [three].
 * В колледжах бывают разные (4,5 или 4,67 до пятёрки), сервер их не отдаёт — задаёт пользователь.
 */
data class RoundingRules(
    val five: Double = 4.5,
    val four: Double = 3.5,
    val three: Double = 2.5,
) {
    fun markFor(average: Double): Int = when {
        average >= five - EPS -> 5
        average >= four - EPS -> 4
        average >= three - EPS -> 3
        else -> 2
    }

    /** Пороги по убыванию и в диапазоне 2..5. */
    val isValid: Boolean get() = five in 2.0..5.0 && four in 2.0..5.0 && three in 2.0..5.0 && five > four && four > three

    companion object {
        val STANDARD = RoundingRules()
        val STRICT = RoundingRules(4.67, 3.67, 2.67)
        val SOFT = RoundingRules(4.6, 3.6, 2.6)
        private const val EPS = 1e-9
    }
}
