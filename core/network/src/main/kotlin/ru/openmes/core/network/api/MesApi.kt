package ru.openmes.core.network.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Family Mobile API (mapi) — по рабочему рецепту OctoDiary:
 * база https://school.mos.ru/, заголовки: auth-token/Authorization/Cookie/x-mes-subsystem.
 *
 * Расписание берётся через eventcalendar/v1/api/events (НЕ lesson_schedule_items).
 */
interface MesApi {

    // ---------------------------------------------------------------------
    // Профиль: children (student_id, person_guid, contract_id)
    // ---------------------------------------------------------------------

    /** GET /family/mobile/v1/profile — профиль + дети.
     *  Полный набор заголовков из рабочего форка (CollegeProfileRequest):
     *  Profile-Id + X-Mes-RoleId (32=студент СПО, 2=родитель) + client-type
     *  + Accept-Language + x-row-limit (сумма цифр Profile-Id — официальная LIMIT-подпись). */
    @GET("api/family/mobile/v1/profile")
    suspend fun getFamilyProfile(
        @Header("Profile-Id") profileId: Long,
        @Header("X-Mes-RoleId") roleId: Int,
        @Header("Accept") accept: String = "application/json",
        @Header("Content-Type") contentType: String = "application/json",
        @Header("Accept-Language") acceptLanguage: String = "ru",
        @Header("client-type") clientType: String = "diary-mobile",
        @Header("x-row-limit") rowLimit: String,
    ): FamilyProfileDto

    /** GET acl/api/users/profile_info — ФИНАЛЬНАЯ СТАДИЯ АВТОРИЗАЦИИ
     *  (токен активируется только этим запросом; CollegeRouting перепишет путь). */
    @GET("acl/api/users/profile_info")
    suspend fun getProfileInfo(
        @Header("auth-token") authToken: String,
        @Header("partner-source-id") partnerSourceId: String = "MOBILE",
    ): List<ProfileInfoDto>

    // ---------------------------------------------------------------------
    // Расписание — через eventcalendar (как OctoDiary get_events)
    // ---------------------------------------------------------------------

    /** GET /api/eventcalendar/v1/api/events — события/уроки за период.
     *  Требует X-Mes-Role: student + Client-Type (из рабочего форка). */
    @GET("api/eventcalendar/v1/api/events")
    suspend fun getEvents(
        @Query("person_ids") personIds: String,
        @Query("begin_date") beginDate: String,
        @Query("end_date") endDate: String,
        @Query("expand") expand: String = "homework,marks",
        @Header("X-Mes-Role") mesRole: String = "student",
        @Header("Client-Type") clientType: String = "diary-mobile",
    ): EventsResponse

    /** GET /family/mobile/v1/lesson_schedule_items/{lesson_id} — деталь урока. */
    @GET("api/family/mobile/v1/lesson_schedule_items/{lesson_id}")
    suspend fun getLessonScheduleItem(
        @Path("lesson_id") lessonId: Long,
        @Query("student_id") studentId: String,
        @Query("type") type: String = "PLAN",
    ): LessonScheduleItemDto

    // ---------------------------------------------------------------------
    // Оценки
    // ---------------------------------------------------------------------

    @GET("api/family/mobile/v1/marks")
    suspend fun getMarks(
        @Query("student_id") studentId: String,
        @Query("from") from: String,
        @Query("to") to: String,
    ): MarksByDateResponse

    @GET("api/family/mobile/v1/subject_marks")
    suspend fun getSubjectMarks(
        @Query("student_id") studentId: String,
    ): SubjectMarksResponse

    // ---------------------------------------------------------------------
    // Домашние задания
    // ---------------------------------------------------------------------

    @GET("api/family/mobile/v1/homeworks/short")
    suspend fun getHomeworksShort(
        @Query("student_id") studentId: String,
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("sort_column") sortColumn: String = "date",
        @Query("sort_direction") sortDirection: String = "asc",
    ): HomeworksShortResponse

    @GET("api/family/mobile/v1/marks/{mark_id}")
    suspend fun getMarkInfo(
        @Path("mark_id") markId: Long,
        @Query("student_id") studentId: String,
    ): MarkInfoDto

    @GET("api/family/mobile/v1/attestation")
    suspend fun getAttestation(
        @Query("student_id") studentId: String,
        @Query("attestation_forms") attestationForms: String = "EXAM,TEST,PRACTICE",
    ): AttestationResponse

    @GET("api/family/mobile/v1/homeworks")
    suspend fun getHomeworksFull(
        @Query("student_id") studentId: String,
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("sort_column") sortColumn: String = "date",
        @Query("sort_direction") sortDirection: String = "asc",
    ): HomeworksFullResponse

    @POST("api/family/mobile/v1/homeworks/{homework_entry_student_id}/done")
    suspend fun setHomeworkDone(
        @Path("homework_entry_student_id") homeworkEntryStudentId: Long,
    )

    @DELETE("api/family/mobile/v1/homeworks/{homework_entry_student_id}/done")
    suspend fun unsetHomeworkDone(
        @Path("homework_entry_student_id") homeworkEntryStudentId: Long,
    )

    // ---------------------------------------------------------------------
    // Посещаемость
    // ---------------------------------------------------------------------

    @GET("api/family/mobile/v1/attendance")
    suspend fun getAttendance(
        @Query("student_id") studentId: String,
        @Query("from") from: String,
        @Query("to") to: String,
    ): AttendanceListResponse

    /**
     * Справки ЕМИАС: по дню на запись (болеет / инфекция / освобождение), за всё время.
     * Постранично (page с 1, ~50 записей, страницы слегка перекрываются); даты сервис игнорирует.
     */
    @GET("api/ej/core/family/v1/emias_medical_recommendations")
    suspend fun getMedicalRecommendations(
        @Query("student_id") studentId: String,
        @Query("page") page: Int,
    ): List<MedicalRecommendationDto>

    // ---------------------------------------------------------------------
    // Учебные годы (профобразование-префикс po-сборки)
    // ---------------------------------------------------------------------

    @GET("api/ej/core/family/v1/academic_years")
    suspend fun getAcademicYears(): List<AcademicYearDto>

    // ---------------------------------------------------------------------
    // Материалы ДЗ: ссылка запуска ЭОР (OctoDiary launchMaterial).
    // /api/ej/... → CollegeRouting → /api/profeducation/partners/...
    // Ответ — URL строкой в теле (иногда в кавычках) либо редирект.
    // ---------------------------------------------------------------------

    @GET("api/ej/partners/v1/homeworks/launch")
    suspend fun launchHomeworkMaterial(
        @Query("homework_entry_id") homeworkEntryId: Long,
        @Query("material_id") materialId: String,
    ): Response<ResponseBody>

    // ---------------------------------------------------------------------
    // Электронный студенческий билет (из OpenAPI бандла, на живом API не проверен)
    // ---------------------------------------------------------------------

    /** Календарь учебного периода: выходные, праздники, каникулы (как официальное приложение). */
    @GET("api/family/mobile/v1/periods_schedules")
    suspend fun getPeriodsSchedules(
        @Query("student_id") studentId: String,
        @Query("from") from: String,
        @Query("to") to: String,
    ): List<PeriodScheduleDayDto>

    @GET("api/family/mobile/v1/student-card")
    suspend fun getStudentCard(
        @Query("student_id") studentId: String,
    ): StudentCardDto

    /** QR студенческого билета: PNG в base64 (только POST с пустым телом, GET → 500). */
    @POST("api/family/mobile/v1/student-card/qr")
    suspend fun getStudentCardQr(
        @Query("student_id") studentId: String,
        @Body body: JsonObject = JsonObject(emptyMap()),
    ): StudentCardQrDto

    /** Сведения об организации: директор, кураторы, контакты, корпуса. */
    @GET("api/family/mobile/v1/school_info")
    suspend fun getSchoolInfo(
        @Query("class_unit_id") classUnitId: Long,
        @Query("school_id") schoolId: Long,
    ): SchoolInfoDto

    // ---------------------------------------------------------------------
    // Планирование: модули/темы, контрольные; календарь переносов дней
    // (/api/ej/... → CollegeRouting → /api/profeducation/...)
    // ---------------------------------------------------------------------

    /** Модули (темы) предметов за учебный год. */
    @GET("api/ej/plan/family/v1/lesson_modules")
    suspend fun getLessonModules(
        @Query("student_profile_id") studentProfileId: String,
        @Query("academic_year_id") academicYearId: Long,
    ): List<LessonModuleDto>

    /** Контрольные/зачётные занятия за период. Схема элементов у колледжа не подтверждена — разбираем гибко. */
    @GET("api/ej/plan/family/v1/test_lessons/period")
    suspend fun getTestLessons(
        @Query("student_profile_id") studentProfileId: String,
        @Query("student_person_id") studentPersonId: String,
        @Query("from") from: String,
        @Query("to") to: String,
    ): TestLessonsResponse

    /** Переносы дней календаря: праздники и рабочие дни «по расписанию другого дня». */
    @GET("api/ej/core/family/v1/calendars/{calendar_id}/calendar_transpositions")
    suspend fun getCalendarTranspositions(
        @Path("calendar_id") calendarId: Long,
    ): List<CalendarTranspositionDto>

    /** PDF расписания за период (не кэшируется: не JSON). */
    @Streaming
    @GET("api/eventcalendar/v1/api/events/schedule/pdf")
    suspend fun getSchedulePdf(
        @Query("person_ids") personIds: String,
        @Query("begin_date") beginDate: String,
        @Query("end_date") endDate: String,
        @Header("X-Mes-Role") mesRole: String = "student",
        @Header("Client-Type") clientType: String = "diary-mobile",
    ): ResponseBody

    /** Проходы через турникеты: не больше 7 дней за запрос, только Bearer. */
    @GET("api/pass/entrances/v1/visit_durations")
    suspend fun getVisitDurations(
        @Query("personId") personId: String,
        @Query("from") from: String,
        @Query("to") to: String,
    ): VisitDurationsResponse

    // ---------------------------------------------------------------------
    // Аватары (avatarmanagement; person_id = contingent_guid)
    // Картинка: https://school.mos.ru/avatars/{id} или поле url.
    // ---------------------------------------------------------------------

    @GET("api/avatarmanagement/v1/{person_id}")
    suspend fun getAvatars(
        @Path("person_id") personId: String,
    ): List<AvatarDto>
}

// ---------------------------------------------------------------------------
// DTO family profile
// ---------------------------------------------------------------------------

@Serializable
data class FamilyProfileDto(
    @SerialName("children") val children: List<FamilyChildDto> = emptyList(),
    @SerialName("profile") val profile: FamilyProfileSelfDto? = null,
)

@Serializable
data class FamilyProfileSelfDto(
    @SerialName("id") val id: Long? = null,
    @SerialName("user_id") val userId: Long? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
)

@Serializable
data class FamilyChildDto(
    @SerialName("id") val id: Long? = null,
    @SerialName("user_id") val userId: Long? = null,
    @SerialName("contingent_guid") val contingentGuid: String? = null,
    @SerialName("contract_id") val contractId: Long? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("middle_name") val middleName: String? = null,
    @SerialName("class_name") val className: String? = null,
    @SerialName("snils") val snils: String? = null,
    @SerialName("class_unit_id") val classUnitId: Long? = null,
    @SerialName("school") val school: FamilySchoolDto? = null,
)

@Serializable
data class FamilySchoolDto(
    @SerialName("id") val id: Long? = null,
    @SerialName("name") val name: String? = null,
)

@Serializable
data class StudentCardQrDto(@SerialName("qr_code") val qrCode: String? = null)

@Serializable
data class SchoolInfoDto(
    @SerialName("name") val name: String? = null,
    @SerialName("principal") val principal: String? = null,
    @SerialName("classroom_teachers") val classroomTeachers: List<SchoolPersonDto> = emptyList(),
    @SerialName("address") val address: SchoolAddressDto? = null,
    @SerialName("phone") val phone: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("website_link") val websiteLink: String? = null,
    @SerialName("branches") val branches: List<SchoolBranchDto> = emptyList(),
)

@Serializable
data class SchoolPersonDto(
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("middle_name") val middleName: String? = null,
)

@Serializable
data class SchoolAddressDto(@SerialName("address") val address: String? = null)

@Serializable
data class SchoolBranchDto(
    @SerialName("name") val name: String? = null,
    @SerialName("address") val address: String? = null,
    @SerialName("is_main_building") val isMainBuilding: Boolean = false,
    @SerialName("is_student_building") val isStudentBuilding: Boolean = false,
)

// ---------------------------------------------------------------------------
// DTO eventcalendar (расписание) — реальная структура из ответа сервера
// ---------------------------------------------------------------------------

@Serializable
data class EventsResponse(
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("response") val events: List<EventDto> = emptyList(),
    @SerialName("errors") val errors: List<String>? = null,
)

@Serializable
data class EventDto(
    @SerialName("id") val id: Long = 0,
    @SerialName("source_id") val sourceId: String? = null,
    @SerialName("source") val source: String? = null,
    @SerialName("start_at") val startAt: String? = null,
    @SerialName("finish_at") val finishAt: String? = null,
    @SerialName("cancelled") val cancelled: Boolean = false,
    @SerialName("replaced") val replaced: Boolean = false,
    @SerialName("lesson_type") val lessonType: String? = null,
    @SerialName("course_lesson_type") val courseLessonType: String? = null,
    @SerialName("lesson_form") val lessonForm: LessonFormDto? = null,
    @SerialName("link_to_join") val linkToJoin: String? = null,
    @SerialName("subject_id") val subjectId: Long? = null,
    @SerialName("subject_name") val subjectName: String = "",
    @SerialName("room_name") val roomName: String? = null,
    @SerialName("room_number") val roomNumber: String? = null,
    @SerialName("marks") val marks: List<EventMarkDto> = emptyList(),
    @SerialName("homework") val homework: EventHomeworkDto? = null,
    @SerialName("absence_reason_id") val absenceReasonId: Long? = null,
    @SerialName("nonattendance_reason_id") val nonattendanceReasonId: Long? = null,
    @SerialName("is_missed_lesson") val isMissedLesson: Boolean = false,
    @SerialName("health_status") val healthStatus: String? = null,
)

@Serializable
data class LessonFormDto(
    @SerialName("id") val id: Long? = null,
    @SerialName("name") val name: String? = null,
)

@Serializable
data class EventMarkDto(
    @SerialName("value") val value: String = "",
    @SerialName("weight") val weight: Int? = null,
    @SerialName("is_exam") val isExam: Boolean = false,
    @SerialName("is_point") val isPoint: Boolean = false,
    @SerialName("comment") val comment: String? = null,
    @SerialName("original_grade_system_type") val gradeSystemType: String? = null,
)

@Serializable
data class EventHomeworkDto(
    @SerialName("presence_status_id") val presenceStatusId: Int? = null,
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("execute_count") val executeCount: Int = 0,
    @SerialName("descriptions") val descriptions: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// DTO уроков
// ---------------------------------------------------------------------------

@Serializable
data class LessonScheduleItemDto(
    @SerialName("id") val id: Long = 0,
    @SerialName("date") val date: String = "",
    @SerialName("begin_time") val beginTime: String? = null,
    @SerialName("end_time") val endTime: String? = null,
    @SerialName("subject_id") val subjectId: Long? = null,
    @SerialName("subject_name") val subjectName: String = "",
    @SerialName("room_name") val roomName: String? = null,
    @SerialName("room_number") val roomNumber: String? = null,
    @SerialName("building_name") val buildingName: String? = null,
    @SerialName("teacher") val teacher: TeacherDto? = null,
    @SerialName("marks") val marks: List<LessonMarkDto> = emptyList(),
    @SerialName("lesson_homeworks") val lessonHomeworks: List<LessonHomeworkDto> = emptyList(),
    @SerialName("remote_lesson") val remoteLesson: RemoteLessonDto? = null,
    @SerialName("is_virtual") val isVirtual: Boolean = false,
    @SerialName("nonattendance_reason_id") val nonattendanceReasonId: Long? = null,
    @SerialName("comment") val comment: String? = null,
    @SerialName("disease_status_type") val diseaseStatusType: String? = null,
)

@Serializable
data class TeacherDto(
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("middle_name") val middleName: String? = null,
)

@Serializable
data class LessonMarkDto(
    @SerialName("id") val id: Long = 0,
    @SerialName("value") val value: String = "",
    @SerialName("weight") val weight: Int? = null,
)

@Serializable
data class LessonHomeworkDto(
    @SerialName("homework") val homework: String? = null,
    @SerialName("homework_id") val homeworkId: Long = 0,
    @SerialName("homework_entry_id") val homeworkEntryId: Long = 0,
    @SerialName("homework_entry_student_id") val homeworkEntryStudentId: Long = 0,
    @SerialName("is_done") val isDone: Boolean = false,
    @SerialName("attachments") val attachments: List<AttachmentMetaDto> = emptyList(),
)

@Serializable
data class AttachmentMetaDto(
    @SerialName("id") val id: Long = 0,
    @SerialName("name") val name: String? = null,
)

@Serializable
data class RemoteLessonDto(
    @SerialName("id") val id: Long? = null,
)

// ---------------------------------------------------------------------------
// DTO оценок
// ---------------------------------------------------------------------------

@Serializable
data class MarksByDateResponse(
    @SerialName("payload") val payload: List<MarkByDateItemDto> = emptyList(),
)

@Serializable
data class MarkByDateItemDto(
    @SerialName("id") val id: Long = 0,
    @SerialName("value") val value: String = "",
    @SerialName("weight") val weight: Int? = null,
    @SerialName("date") val date: String? = null,
    @SerialName("is_exam") val isExam: Boolean = false,
    @SerialName("is_point") val isPoint: Boolean = false,
    @SerialName("control_form_name") val controlFormName: String? = null,
    @SerialName("comment") val comment: String? = null,
    @SerialName("comment_exists") val commentExists: Boolean = false,
    @SerialName("subject_name") val subjectName: String? = null,
    @SerialName("subject_id") val subjectId: Long? = null,
    @SerialName("original_grade_system_type") val gradeSystemType: String? = null,
)

@Serializable
data class SubjectMarksResponse(
    @SerialName("payload") val payload: List<SubjectMarksDto> = emptyList(),
)

@Serializable
data class SubjectMarksDto(
    @SerialName("subject_id") val subjectId: Long? = null,
    @SerialName("subject_name") val subjectName: String = "",
    @SerialName("average") val average: Double? = null,
    @SerialName("average_by_all") val averageByAll: Double? = null,
    @SerialName("dynamic") val dynamic: String? = null,
    @SerialName("year_mark") val yearMark: String? = null,
    @SerialName("periods") val periods: List<SubjectPeriodDto> = emptyList(),
)

@Serializable
data class SubjectPeriodDto(
    @SerialName("title") val title: String = "",
    @SerialName("start_iso") val startIso: String? = null,
    @SerialName("end_iso") val endIso: String? = null,
    @SerialName("value") val value: String? = null,
    @SerialName("fixed_value") val fixedValue: String? = null,
    @SerialName("dynamic") val dynamic: String? = null,
    @SerialName("count") val count: Int? = null,
    @SerialName("marks") val marks: List<SubjectPeriodMarkDto> = emptyList(),
)

@Serializable
data class SubjectPeriodMarkDto(
    @SerialName("id") val id: Long = 0,
    @SerialName("value") val value: String = "",
    @SerialName("weight") val weight: Int = 1,
    @SerialName("date") val date: String? = null,
    @SerialName("control_form_name") val controlFormName: String? = null,
    @SerialName("comment") val comment: String? = null,
)

// ---------------------------------------------------------------------------
// DTO домашних заданий
// ---------------------------------------------------------------------------

@Serializable
data class HomeworksShortResponse(
    @SerialName("payload") val payload: List<HomeworksShortDto> = emptyList(),
)

@Serializable
data class HomeworksShortDto(
    @SerialName("description") val description: String = "",
    @SerialName("subject_name") val subjectName: String = "",
    @SerialName("subject_id") val subjectId: Long? = null,
    @SerialName("date") val date: String? = null,
    @SerialName("date_assigned_on") val dateAssignedOn: String? = null,
    @SerialName("homework_entry_student_id") val homeworkEntryStudentId: Long = 0,
    @SerialName("is_done") val isDone: Boolean = false,
    @SerialName("has_written_answer") val hasWrittenAnswer: Boolean = false,
    @SerialName("has_teacher_answer") val hasTeacherAnswer: Boolean = false,
    @SerialName("type") val type: String? = null,
    @SerialName("lesson_date_time") val lessonDateTime: String? = null,
)

// ---------------------------------------------------------------------------
// DTO посещаемости
// ---------------------------------------------------------------------------

/**
 * Формат сверен с живым эндпоинтом колледжа (OctoDiary-kt):
 * { attendance: [ {date, lessons[]} ], days_count, year_description } —
 * поле `attendance` — сразу список дней, без обёртки `attendances`.
 */
@Serializable
data class AttendanceListResponse(
    @SerialName("attendance") val attendance: List<AttendanceDayDto> = emptyList(),
    @SerialName("days_count") val daysCount: Int? = null,
    @SerialName("year_description") val yearDescription: String? = null,
)

@Serializable
data class AttendanceDayDto(
    @SerialName("date") val date: String = "",
    @SerialName("lessons") val lessons: List<AttendanceLessonDto> = emptyList(),
)

@Serializable
data class AttendanceLessonDto(
    @SerialName("id") val id: Long? = null,
    @SerialName("schedule_item_id") val scheduleItemId: Long? = null,
    @SerialName("subject_id") val subjectId: Long? = null,
    @SerialName("subject_name") val subjectName: String? = null,
    @SerialName("begin_time") val beginTime: String? = null,
    @SerialName("end_time") val endTime: String? = null,
    /** SICK | SICK_WITH_INFECTION | EXEMPT — «Статус здоровья». */
    @SerialName("health_status") val healthStatus: String? = null,
    /** Живой API шлёт reason_id, OpenAPI бандла — absence_reason_id: читаем оба. */
    @SerialName("reason_id") val reasonId: Int? = null,
    @SerialName("absence_reason_id") val absenceReasonId: Int? = null,
    @SerialName("notified") val notified: Boolean? = null,
)

// ---------------------------------------------------------------------------
// DTO учебных годов
// ---------------------------------------------------------------------------

@Serializable
data class AcademicYearDto(
    @SerialName("id") val id: Long = 0,
    @SerialName("name") val name: String = "",
    @SerialName("begin_date") val beginDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("current_year") val currentYear: Boolean = false,
    @SerialName("calendar_id") val calendarId: Long? = null,
)

// ---------------------------------------------------------------------------
// DTO деталей оценки (marks/{id})
// ---------------------------------------------------------------------------

@Serializable
data class MarkInfoDto(
    @SerialName("id") val id: Long = 0,
    @SerialName("value") val value: String = "",
    @SerialName("comment") val comment: String? = null,
    @SerialName("comment_exists") val commentExists: Boolean = false,
    @SerialName("weight") val weight: Int = 1,
    @SerialName("control_form_name") val controlFormName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("criteria") val criteria: List<CriteriaDto>? = null,
    @SerialName("subject_id") val subjectId: Long? = null,
    @SerialName("subject_name") val subjectName: String? = null,
    @SerialName("teacher") val teacher: TeacherDto? = null,
    @SerialName("date") val date: String? = null,
    @SerialName("activity") val activity: MarkActivityDto? = null,
    @SerialName("class_results") val classResults: ClassResultsDto? = null,
    @SerialName("is_exam") val isExam: Boolean = false,
    @SerialName("is_point") val isPoint: Boolean = false,
    @SerialName("original_grade_system_type") val gradeSystemType: String? = null,
)

@Serializable
data class CriteriaDto(
    @SerialName("name") val name: String? = null,
    @SerialName("value") val value: String? = null,
)

@Serializable
data class MarkActivityDto(
    @SerialName("schedule_item_id") val scheduleItemId: Long? = null,
    @SerialName("lesson_topic") val lessonTopic: String? = null,
)

@Serializable
data class ClassResultsDto(
    @SerialName("total_students") val totalStudents: Int = 0,
    @SerialName("marks_distributions") val distributions: List<MarksDistributionDto> = emptyList(),
)

@Serializable
data class MarksDistributionDto(
    @SerialName("percentage_of_students") val percentage: Int = 0,
    @SerialName("number_of_students") val students: Int = 0,
    @SerialName("mark_value") val markValue: MarkValueDto? = null,
)

@Serializable
data class MarkValueDto(
    @SerialName("five") val five: Double? = null,
    @SerialName("origin") val origin: String? = null,
)

// ---------------------------------------------------------------------------
// DTO зачётной книжки (attestation)
// ---------------------------------------------------------------------------

@Serializable
data class AttestationResponse(
    @SerialName("semesters") val semesters: List<AttestationSemesterDto> = emptyList(),
    @SerialName("years") val years: List<AttestationYearDto> = emptyList(),
)

@Serializable
data class AttestationSemesterDto(
    @SerialName("academic_year_id") val academicYearId: Long? = null,
    @SerialName("attestation_periods_id") val attestationPeriodsId: Long? = null,
    @SerialName("name") val name: String = "",
    @SerialName("attestation_forms") val forms: List<AttestationFormDto> = emptyList(),
)

@Serializable
data class AttestationFormDto(
    @SerialName("attestation_form_name") val formName: String = "",
    @SerialName("subjects") val subjects: List<AttestationSubjectDto> = emptyList(),
)

@Serializable
data class AttestationSubjectDto(
    @SerialName("subject_id") val subjectId: Long? = null,
    @SerialName("subject_name") val subjectName: String = "",
    @SerialName("hours") val hours: Int? = null,
    @SerialName("mark") val mark: AttestationMarkDto? = null,
    @SerialName("date") val date: String? = null,
    @SerialName("teachers") val teachers: List<String> = emptyList(),
    @SerialName("theme") val theme: String? = null,
)

@Serializable
data class AttestationMarkDto(
    @SerialName("final_mark_id") val finalMarkId: Long? = null,
    @SerialName("attested") val attested: Boolean = false,
    @SerialName("value") val value: Int? = null,
    @SerialName("grade_system_type") val gradeSystemType: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("academic_debt") val academicDebt: Boolean = false,
)

@Serializable
data class AttestationYearDto(
    @SerialName("academic_year_id") val academicYearId: Long? = null,
    @SerialName("attestation_forms") val forms: List<AttestationFormDto> = emptyList(),
)

// ---------------------------------------------------------------------------
// DTO полных ДЗ (homeworks, не short)
// ---------------------------------------------------------------------------

@Serializable
data class HomeworksFullResponse(
    @SerialName("payload") val payload: List<HomeworkFullDto> = emptyList(),
)

@Serializable
data class HomeworkFullDto(
    @SerialName("type") val type: String? = null,
    @SerialName("description") val description: String = "",
    @SerialName("homework") val homework: String? = null,
    @SerialName("homework_entry_student_id") val homeworkEntryStudentId: Long = 0,
    @SerialName("homework_id") val homeworkId: Long = 0,
    @SerialName("homework_entry_id") val homeworkEntryId: Long = 0,
    @SerialName("subject_id") val subjectId: Long? = null,
    @SerialName("subject_name") val subjectName: String = "",
    @SerialName("date") val date: String? = null,
    @SerialName("date_assigned_on") val dateAssignedOn: String? = null,
    @SerialName("date_prepared_for") val datePreparedFor: String? = null,
    @SerialName("lesson_date_time") val lessonDateTime: String? = null,
    @SerialName("is_done") val isDone: Boolean = false,
    @SerialName("has_teacher_answer") val hasTeacherAnswer: Boolean = false,
    @SerialName("materials") val materials: List<HomeworkMaterialDto> = emptyList(),
    // Формат вложений нестабилен (то объекты, то null) — они же приходят в materials
    // с type=attachments, поэтому здесь не парсим.
    @SerialName("attachments") val attachments: kotlinx.serialization.json.JsonElement? = null,
)

/** Материал ДЗ (формат homeworks2 из OctoDiary-kt). */
@Serializable
data class HomeworkMaterialDto(
    @SerialName("uuid") val uuid: String? = null,
    @SerialName("title") val title: String? = null,
    /** attachments — файл (ссылка file_link), иначе ЭОР через homeworks/launch. */
    @SerialName("type") val type: String? = null,
    @SerialName("type_name") val typeName: String? = null,
    /** learn | execute. */
    @SerialName("selected_mode") val selectedMode: String? = null,
    @SerialName("action_name") val actionName: String? = null,
    @SerialName("urls") val urls: List<HomeworkMaterialUrlDto> = emptyList(),
)

@Serializable
data class HomeworkMaterialUrlDto(
    @SerialName("type") val type: String? = null,
    @SerialName("url") val url: String? = null,
)

// ---------------------------------------------------------------------------
// DTO студенческого билета
// ---------------------------------------------------------------------------

@Serializable
data class StudentCardDto(
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("middle_name") val middleName: String? = null,
    @SerialName("student_card_number") val cardNumber: String? = null,
    @SerialName("student_card_issue_date") val issueDate: String? = null,
    @SerialName("education_form_name") val educationFormName: String? = null,
    @SerialName("class_level_name") val classLevelName: String? = null,
    @SerialName("education_level_name") val educationLevelName: String? = null,
    @SerialName("profession_specialty_name") val specialtyName: String? = null,
    @SerialName("enrollment_order_number") val enrollmentOrderNumber: String? = null,
    @SerialName("enrollment_order_date") val enrollmentOrderDate: String? = null,
    @SerialName("student_card_valid_until") val validUntil: String? = null,
    @SerialName("school") val school: StudentCardSchoolDto? = null,
)

@Serializable
data class StudentCardSchoolDto(
    @SerialName("name") val name: String? = null,
    @SerialName("founder_name") val founderName: String? = null,
)

// ---------------------------------------------------------------------------
// DTO аватаров
// ---------------------------------------------------------------------------

@Serializable
data class AvatarDto(
    @SerialName("id") val id: Long = 0,
    @SerialName("default") val default: Boolean = false,
    @SerialName("url") val url: String? = null,
)

/** День календаря учебного периода: type = workday | holiday | vacation. */
@Serializable
data class PeriodScheduleDayDto(
    val date: String,
    val type: String? = null,
    val title: String? = null,
)

/** Модуль (тема) предмета; даты — массивом [год, месяц, день]. */
@Serializable
data class LessonModuleDto(
    @SerialName("id") val id: Long = 0,
    @SerialName("name") val name: String = "",
    @SerialName("subject_id") val subjectId: Long? = null,
    @SerialName("start_date") val startDate: List<Int>? = null,
    @SerialName("end_date") val endDate: List<Int>? = null,
)

@Serializable
data class TestLessonsResponse(
    @SerialName("items") val items: List<JsonObject>? = null,
)

@Serializable
data class CalendarTranspositionDto(
    @SerialName("id") val id: Long = 0,
    @SerialName("is_holiday") val isHoliday: Boolean = false,
    @SerialName("date") val date: String? = null,
    @SerialName("postponed_from") val postponedFrom: String? = null,
    @SerialName("schedule_for_weekday") val scheduleForWeekday: Int? = null,
    @SerialName("note") val note: String? = null,
)

@Serializable
data class VisitDurationsResponse(
    @SerialName("payload") val payload: List<VisitDayDto> = emptyList(),
)

@Serializable
data class VisitDayDto(
    @SerialName("date") val date: String = "",
    @SerialName("visits") val visits: List<VisitDto> = emptyList(),
)

@Serializable
data class VisitDto(
    @SerialName("in") val entered: String? = null,
    @SerialName("out") val left: String? = null,
    @SerialName("duration") val duration: String? = null,
    @SerialName("kindName") val kindName: String? = null,
    @SerialName("isIncomplete") val isIncomplete: Boolean = false,
    @SerialName("organizationShortName") val organizationShortName: String? = null,
)

@Serializable
data class MedicalRecommendationDto(
    @SerialName("id") val id: Long = 0,
    @SerialName("date") val date: String? = null,
    /** SICK, SICK_WITH_INFECTION, EXEMPT. */
    @SerialName("type") val type: String? = null,
    @SerialName("subject_ids") val subjectIds: List<Long> = emptyList(),
)
