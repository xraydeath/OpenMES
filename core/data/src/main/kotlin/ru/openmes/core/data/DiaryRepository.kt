package ru.openmes.core.data

import ru.openmes.core.model.DayInfo
import ru.openmes.core.model.LessonModule
import ru.openmes.core.model.TestLesson
import ru.openmes.core.model.VisitDay
import ru.openmes.core.model.AcademicYear
import ru.openmes.core.model.AttendanceEntry
import ru.openmes.core.model.Homework
import ru.openmes.core.model.Lesson
import ru.openmes.core.model.LessonDetails
import ru.openmes.core.model.Mark
import ru.openmes.core.model.MarkDetails
import ru.openmes.core.model.MedicalRecord
import ru.openmes.core.model.GradeBook
import ru.openmes.core.model.Person
import ru.openmes.core.model.SubjectMarks
import ru.openmes.core.model.StudentCard
import ru.openmes.core.model.SubjectMarksData
import java.time.LocalDate

/** Дневник: расписание, оценки, домашние задания, посещаемость. */
interface DiaryRepository {

    /**
     * Выполнить [block] только по офлайн-кэшу, без сети: сохранённые данные для мгновенного показа.
     * null — в кэше нет (или ошибка разбора).
     */
    suspend fun <T> cachedOnly(block: suspend DiaryRepository.() -> T): T?


    suspend fun getChildren(): List<Person>

    suspend fun getAcademicYears(personId: String): List<AcademicYear>

    suspend fun getSchedule(
        personId: String,
        from: LocalDate,
        to: LocalDate,
    ): List<Lesson>

    suspend fun getMarks(
        personId: String,
        from: LocalDate,
        to: LocalDate,
    ): List<SubjectMarks>

    /** Сводные оценки по предметам с периодами и средним баллом (subject_marks). */
    suspend fun getSubjectMarks(personId: String): List<SubjectMarksData>

    suspend fun getHomeworks(
        personId: String,
        from: LocalDate,
        to: LocalDate,
    ): List<Homework>

    suspend fun setHomeworkDone(
        personId: String,
        homeworkId: String,
        isDone: Boolean,
    )

    suspend fun getAttendance(
        personId: String,
        from: LocalDate,
        to: LocalDate,
    ): List<AttendanceEntry>

    /** Справки ЕМИАС (больничные, освобождения) за всё время, по дню на запись. */
    suspend fun getMedicalRecords(personId: String): List<MedicalRecord>

    /** Детали оценки (учитель, форма контроля, критерии, распределение класса). */
    suspend fun getMarkDetails(personId: String, markId: Long): MarkDetails

    /** Зачётная книжка. */
    suspend fun getGradeBook(personId: String): GradeBook

    /** Детали урока (преподаватель, кабинет, ДЗ, оценки). */
    suspend fun getLessonDetails(personId: String, lessonId: Long): LessonDetails

    /** Ссылка запуска ЭОР из ДЗ (homeworks/launch). */
    suspend fun getHomeworkMaterialUrl(homeworkEntryId: Long, materialUuid: String): String

    /** Электронный студенческий билет. */
    suspend fun getStudentCard(personId: String): StudentCard

    /** Календарь дней (рабочий / праздник / каникулы, период, переносы) за период. */
    suspend fun getCalendar(personId: String, from: LocalDate, to: LocalDate): Map<LocalDate, DayInfo>

    /** Модули (темы) предметов текущего учебного года. */
    suspend fun getLessonModules(personId: String): List<LessonModule>

    /** Контрольные/зачётные занятия за период. */
    suspend fun getTestLessons(personId: String, from: LocalDate, to: LocalDate): List<TestLesson>

    /** Проходы через турникеты за период (запрашиваются неделями). */
    suspend fun getVisits(personId: String, from: LocalDate, to: LocalDate): List<VisitDay>

    /** PDF расписания за период. */
    suspend fun getSchedulePdf(personId: String, from: LocalDate, to: LocalDate): ByteArray

    /** URL текущего аватара (null — аватар не загружен). */
    suspend fun getAvatarUrl(personGuid: String): String?

    /** Картинка аватара из сети (сохраняется на диск); null — аватара нет. */
    suspend fun getAvatar(personGuid: String): ByteArray?

    /** Последний сохранённый аватар — для мгновенного показа. */
    suspend fun getSavedAvatar(personGuid: String): ByteArray?
}
