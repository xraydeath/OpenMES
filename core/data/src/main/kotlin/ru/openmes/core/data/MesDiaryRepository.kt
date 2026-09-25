package ru.openmes.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.openmes.core.common.runSuspendCatching
import ru.openmes.core.model.DayKind
import ru.openmes.core.model.AcademicYear
import ru.openmes.core.model.AbsenceReason
import ru.openmes.core.model.AttendanceEntry
import ru.openmes.core.model.AttendanceLesson
import ru.openmes.core.model.GradeBook
import ru.openmes.core.model.Homework
import ru.openmes.core.model.HomeworkMaterial
import ru.openmes.core.model.Lesson
import ru.openmes.core.model.LessonDetails
import ru.openmes.core.model.Mark
import ru.openmes.core.model.MarkDetails
import ru.openmes.core.model.Person
import ru.openmes.core.model.StudentCard
import ru.openmes.core.model.SubjectMarks
import ru.openmes.core.model.SubjectMarksData
import ru.openmes.core.model.SubjectPeriod
import ru.openmes.core.network.MesEnvironment
import ru.openmes.core.network.api.HomeworkFullDto
import ru.openmes.core.network.api.MesApi
import ru.openmes.core.network.interceptor.OfflineCache
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Репозиторий дневника поверх Family Mobile API (family/mobile/v1).
 */
class MesDiaryRepository(
    private val mesApi: MesApi,
    private val tokenStore: TokenStore,
    private val offlineCache: OfflineCache? = null,
    /** Тот же репозиторий поверх кэш-only MesApi (без сети). */
    private val cached: DiaryRepository? = null,
) : DiaryRepository {

    override suspend fun <T> cachedOnly(block: suspend DiaryRepository.() -> T): T? {
        val repo = cached ?: return null
        return runSuspendCatching { repo.block() }.getOrNull()
    }

    private val isoDate: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** Обёртка: HTTP-ошибки превращаются в исключение с телом ответа (видно на экране). */
    private suspend fun <T> apiCall(block: suspend () -> T): T = try {
        block()
    } catch (e: retrofit2.HttpException) {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        throw IllegalStateException(
            "HTTP ${e.code()} ${e.message()}" + (body?.take(300)?.let { ": $it" } ?: ""),
            e,
        )
    }

    /** student_id для API — из family profile children, не profile_id. */
    private suspend fun studentId(): String =
        tokenStore.load()?.studentId ?: tokenStore.load()?.profileId
            ?: error("Нет активного профиля — войдите заново")

    override suspend fun getChildren(): List<Person> {
        // Профили подгружаются сессией (profile_info); для mapi-запросов id профиля = student_id.
        val id = tokenStore.load()?.profileId ?: return emptyList()
        return listOf(Person(id = id, firstName = "", lastName = ""))
    }

    override suspend fun getAcademicYears(personId: String): List<AcademicYear> = apiCall {
        mesApi.getAcademicYears().map { dto ->
            AcademicYear(
                id = dto.id.toString(),
                name = dto.name,
                start = runCatching { LocalDate.parse(dto.beginDate.orEmpty()) }.getOrDefault(LocalDate.now()),
                end = runCatching { LocalDate.parse(dto.endDate.orEmpty()) }.getOrDefault(LocalDate.now()),
                periods = emptyList(),
            )
        }
    }

    override suspend fun getSchedule(personId: String, from: LocalDate, to: LocalDate): List<Lesson> {
        val tokens = tokenStore.load() ?: error("Нет сессии")
        // Расписание — через eventcalendar (как рабочий форк):
        // person_ids = contingent_guid, expand=homework,marks.
        val personIds = tokens.personGuid
            ?: tokens.studentId
            ?: tokens.profileId
            ?: error("Нет профиля ребёнка — войдите заново")
        val response = mesApi.getEvents(
            personIds = personIds,
            beginDate = from.format(isoDate),
            endDate = to.format(isoDate),
        )
        return response.events.map { it.toLesson() }
    }

    /** Сводные оценки по предметам с периодами и средним баллом (subject_marks). */
    override suspend fun getSubjectMarks(personId: String): List<SubjectMarksData> = apiCall {
        mesApi.getSubjectMarks(studentId = studentId()).payload.map { dto ->
            SubjectMarksData(
                subjectId = dto.subjectId ?: 0L,
                subjectName = dto.subjectName,
                yearMark = dto.yearMark,
                dynamic = dto.dynamic,
                periods = dto.periods.map { p ->
                    SubjectPeriod(
                        title = p.title,
                        start = p.startIso?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
                        end = p.endIso?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
                        value = p.value,
                        fixedValue = p.fixedValue,
                        dynamic = p.dynamic,
                        marks = p.marks.map { m ->
                            Mark(
                                id = m.id.toString(),
                                value = m.value,
                                weight = m.weight,
                                typeName = m.controlFormName,
                                date = m.date?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
                                comment = m.comment,
                            )
                        },
                    )
                },
            )
        }
    }

    override suspend fun getMarks(personId: String, from: LocalDate, to: LocalDate): List<SubjectMarks> = apiCall {
        val response = mesApi.getMarks(
            studentId = studentId(),
            from = from.format(isoDate),
            to = to.format(isoDate),
        )
        response.payload
            .groupBy { it.subjectName ?: "—" }
            .map { (subject, items) ->
                SubjectMarks(
                    subjectName = subject,
                    marks = items.map { dto ->
                        Mark(
                            id = dto.id.toString(),
                            value = dto.value,
                            weight = dto.weight,
                            typeName = dto.controlFormName,
                            date = dto.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                            comment = dto.comment,
                        )
                    },
                )
            }
            .sortedByDescending { it.marks.size }
    }

    /** Полные ДЗ (с материалами); если полный эндпоинт недоступен — короткий список. */
    override suspend fun getHomeworks(personId: String, from: LocalDate, to: LocalDate): List<Homework> = apiCall {
        val studentId = studentId()
        runCatching {
            mesApi.getHomeworksFull(
                studentId = studentId,
                from = from.format(isoDate),
                to = to.format(isoDate),
            ).payload.map { it.toHomework() }
        }.getOrElse { fullError ->
            if (fullError is kotlinx.coroutines.CancellationException) throw fullError
            mesApi.getHomeworksShort(
                studentId = studentId,
                from = from.format(isoDate),
                to = to.format(isoDate),
            ).payload.map { dto ->
                Homework(
                    id = dto.homeworkEntryStudentId.toString(),
                    date = parseDate(dto.date) ?: parseDate(dto.lessonDateTime) ?: LocalDate.now(),
                    subjectName = dto.subjectName,
                    task = dto.description,
                    isDone = dto.isDone,
                )
            }
        }
    }

    private fun HomeworkFullDto.toHomework(): Homework {
        val mapped = materials.map { m ->
            HomeworkMaterial(
                uuid = m.uuid,
                title = m.title?.takeIf { it.isNotBlank() } ?: m.typeName ?: "Материал",
                typeName = m.typeName,
                mode = m.selectedMode,
                fileUrl = if (m.type == "attachments") {
                    (m.urls.firstOrNull { it.type == "file_link" } ?: m.urls.firstOrNull())?.url
                } else {
                    null
                },
            )
        }
        return Homework(
            id = homeworkEntryStudentId.toString(),
            date = parseDate(date) ?: parseDate(lessonDateTime) ?: LocalDate.now(),
            subjectName = subjectName,
            task = description.ifBlank { homework.orEmpty() },
            isDone = isDone,
            materialsCount = mapped.size,
            entryId = homeworkEntryId.takeIf { it != 0L },
            materials = mapped,
        )
    }

    override suspend fun getHomeworkMaterialUrl(homeworkEntryId: Long, materialUuid: String): String = apiCall {
        val response = mesApi.launchHomeworkMaterial(homeworkEntryId, materialUuid)
        // Редирект, по которому OkHttp не пошёл (нестандартная схема или ссылка только в теле):
        // ссылку на ЭОР берём из Location либо из HTML/текста ответа.
        if (response.code() in 300..399) {
            val location = response.headers()["Location"]?.trim()
            val body = response.errorBody()?.string().orEmpty()
            val url = location?.let { response.raw().request.url.resolve(it)?.toString() ?: it }
                ?: LINK_IN_BODY.find(body)?.value?.replace("&amp;", "&")
                ?: error("Сервер не вернул ссылку на материал")
            return@apiCall url
        }
        if (!response.isSuccessful) throw retrofit2.HttpException(response)
        // OkHttp сам проходит редиректы: если нас увели с API — конечный URL и есть материал.
        val finalUrl = response.raw().request.url
        if (!finalUrl.encodedPath.contains("/homeworks/launch")) {
            response.body()?.close()
            return@apiCall finalUrl.toString()
        }
        val body = response.body()?.string().orEmpty().trim().removeSurrounding("\"")
        body.takeIf { it.startsWith("http") } ?: error("Сервер не вернул ссылку на материал")
    }

    override suspend fun setHomeworkDone(personId: String, homeworkId: String, isDone: Boolean) = apiCall {
        val id = homeworkId.toLongOrNull() ?: error("Некорректный id ДЗ: $homeworkId")
        if (isDone) {
            mesApi.setHomeworkDone(id)
        } else {
            mesApi.unsetHomeworkDone(id)
        }
    }

    override suspend fun getAttendance(personId: String, from: LocalDate, to: LocalDate): List<AttendanceEntry> =
        apiCall {
            mesApi.getAttendance(
                studentId = studentId(),
                from = from.format(isoDate),
                to = to.format(isoDate),
            ).attendance.mapNotNull { day ->
                val date = parseDate(day.date) ?: return@mapNotNull null
                AttendanceEntry(
                    date = date,
                    lessons = day.lessons.map { l ->
                        AttendanceLesson(
                            subjectName = l.subjectName.orEmpty().ifBlank { "Занятие" },
                            beginTime = parseTime(l.beginTime),
                            endTime = parseTime(l.endTime),
                            reason = AbsenceReason.byId(l.reasonId ?: l.absenceReasonId),
                            healthStatus = l.healthStatus,
                            notified = l.notified ?: false,
                        )
                    }.sortedBy { it.beginTime },
                )
            }.sortedByDescending { it.date }
        }

    override suspend fun getStudentCard(personId: String): StudentCard = apiCall {
        val dto = mesApi.getStudentCard(studentId = studentId())
        StudentCard(
            fullName = listOfNotNull(dto.lastName, dto.firstName, dto.middleName).joinToString(" "),
            cardNumber = dto.cardNumber,
            issueDate = dto.issueDate,
            validUntil = dto.validUntil,
            educationForm = dto.educationFormName,
            course = dto.classLevelName,
            educationLevel = dto.educationLevelName,
            specialty = dto.specialtyName,
            enrollmentOrder = listOfNotNull(
                dto.enrollmentOrderNumber?.let { "№ $it" },
                dto.enrollmentOrderDate?.let { "от $it" },
            ).joinToString(" ").ifBlank { null },
            schoolName = dto.school?.name,
            founderName = dto.school?.founderName,
        )
    }

    override suspend fun getAvatarUrl(personGuid: String): String? = apiCall {
        val avatars = mesApi.getAvatars(personGuid)
        val avatar = avatars.firstOrNull { it.default } ?: avatars.firstOrNull() ?: return@apiCall null
        avatar.url?.takeIf { it.startsWith("http") }
            ?: "${MesEnvironment.SCHOOL_BASE_URL}avatars/${avatar.id}"
    }

    override suspend fun getAvatar(personGuid: String): ByteArray? {
        val url = getAvatarUrl(personGuid) ?: return null
        return withContext(Dispatchers.IO) {
            URL(url).openStream().use { it.readBytes() }.also { offlineCache?.writeBlob(avatarBlob(personGuid), it) }
        }
    }

    override suspend fun getSavedAvatar(personGuid: String): ByteArray? =
        withContext(Dispatchers.IO) { offlineCache?.readBlob(avatarBlob(personGuid)) }

    private fun avatarBlob(personGuid: String) = "avatar_$personGuid"

    /** Детали оценки (учитель, форма контроля, критерии, распределение класса). */
    override suspend fun getMarkDetails(personId: String, markId: Long): MarkDetails = apiCall {
        val dto = mesApi.getMarkInfo(markId = markId, studentId = studentId())
        MarkDetails(
            id = dto.id,
            value = dto.value,
            subjectName = dto.subjectName.orEmpty(),
            controlFormName = dto.controlFormName,
            comment = dto.comment?.takeIf { it.isNotBlank() },
            weight = dto.weight,
            teacherName = dto.teacher?.let {
                listOfNotNull(it.lastName, it.firstName, it.middleName).joinToString(" ")
            },
            lessonTopic = dto.activity?.lessonTopic,
            date = dto.date?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
            createdAt = dto.createdAt,
            criteria = dto.criteria.orEmpty().mapNotNull { c ->
                c.name?.let { MarkDetails.CriteriaItem(it, c.value.orEmpty()) }
            },
            classResults = dto.classResults?.let { cr ->
                MarkDetails.ClassResults(
                    totalStudents = cr.totalStudents,
                    distributions = cr.distributions.map { d ->
                        MarkDetails.ClassResults.Distribution(
                            value = d.markValue?.five,
                            students = d.students,
                            percentage = d.percentage,
                        )
                    },
                )
            },
        )
    }

    /** Зачётная книжка: группировка семестров по учебным годам → курсы. */
    override suspend fun getGradeBook(personId: String): GradeBook = apiCall {
        val dto = mesApi.getAttestation(studentId = studentId())
        // Группируем семестры по academic_year_id → курсы (по 2 семестра в год).
        val byYear = dto.semesters.groupBy { it.academicYearId ?: 0L }
            .toSortedMap()
        val courses = byYear.entries.mapIndexed { index, (yearId, semesters) ->
            GradeBook.Course(
                name = "${index + 1} курс",
                semesters = semesters.map { sem ->
                    GradeBook.Semester(
                        name = sem.name,
                        forms = sem.forms.map { form ->
                            GradeBook.Form(
                                formName = form.formName,
                                subjects = form.subjects.map { subj ->
                                    GradeBook.Subject(
                                        subjectName = subj.subjectName,
                                        hours = subj.hours,
                                        value = subj.mark?.value,
                                        description = subj.mark?.description,
                                        attested = subj.mark?.attested ?: false,
                                        academicDebt = subj.mark?.academicDebt ?: false,
                                        date = subj.date?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
                                        teachers = subj.teachers,
                                        theme = subj.theme,
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
        GradeBook(courses = courses)
    }

    /** Детали урока (преподаватель, кабинет, ДЗ, оценки, статус здоровья). */
    override suspend fun getLessonDetails(personId: String, lessonId: Long): LessonDetails = apiCall {
        val dto = mesApi.getLessonScheduleItem(
            lessonId = lessonId,
            studentId = studentId(),
        )
        LessonDetails(
            id = dto.id,
            subjectName = dto.subjectName,
            date = dto.date?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
            beginTime = dto.beginTime?.let { runCatching { LocalTime.parse(it.take(8)) }.getOrNull() },
            endTime = dto.endTime?.let { runCatching { LocalTime.parse(it.take(8)) }.getOrNull() },
            room = listOfNotNull(dto.roomName, dto.roomNumber).joinToString(" · ").ifBlank { null },
            building = dto.buildingName,
            teacherName = dto.teacher?.let {
                listOfNotNull(it.lastName, it.firstName, it.middleName).joinToString(" ")
            },
            comment = dto.comment,
            diseaseStatusType = dto.diseaseStatusType,
            homework = dto.lessonHomeworks.firstOrNull()?.homework,
            homeworkDone = dto.lessonHomeworks.firstOrNull()?.isDone ?: false,
            marks = dto.marks.map { Mark(id = it.id.toString(), value = it.value, weight = it.weight) },
        )
    }

    override suspend fun getDayKinds(personId: String, from: LocalDate, to: LocalDate): Map<LocalDate, DayKind> = apiCall {
        mesApi.getPeriodsSchedules(studentId = studentId(), from = from.format(isoDate), to = to.format(isoDate))
            .mapNotNull { day ->
                val date = runCatching { LocalDate.parse(day.date.take(10)) }.getOrNull() ?: return@mapNotNull null
                val kind = when (day.type) {
                    "holiday" -> DayKind.HOLIDAY
                    "vacation" -> DayKind.VACATION
                    else -> DayKind.WORKDAY
                }
                date to kind
            }
            .toMap()
    }

    // -------------------------------------------------------------------
    // Маппинг DTO → домен
    // -------------------------------------------------------------------

    private fun ru.openmes.core.network.api.EventDto.toLesson(): Lesson {
        // start_at: "2026-09-23T08:30:00+03:00" → LocalDate + LocalTime
        val startDateTime = startAt?.let { runCatching {
            java.time.OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.of("Europe/Moscow")).toLocalDateTime()
        }.getOrNull() }
        val endDateTime = finishAt?.let { runCatching {
            java.time.OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.of("Europe/Moscow")).toLocalDateTime()
        }.getOrNull() }
        return Lesson(
            id = id.toString(),
            date = startDateTime?.toLocalDate()
                ?: runCatching { LocalDate.parse(startAt.orEmpty().take(10)) }.getOrDefault(LocalDate.now()),
            number = 0,
            startTime = startDateTime?.toLocalTime(),
            endTime = endDateTime?.toLocalTime(),
            subjectName = subjectName,
            room = listOfNotNull(roomName, roomNumber).joinToString(" · ").ifBlank { null },
            teacherName = lessonForm?.name, // «Практическое занятие» / «Теоретическое занятие»
            marks = marks.map { Mark(id = "${id}_${it.value}", value = it.value, weight = it.weight) },
            homework = homework?.takeIf { it.descriptions.isNotEmpty() }?.let { hw ->
                Homework(
                    id = "${id}_hw",
                    date = startDateTime?.toLocalDate() ?: LocalDate.now(),
                    subjectName = subjectName,
                    task = hw.descriptions.joinToString("\n"),
                    materialsCount = hw.totalCount,
                )
            },
            isDistance = linkToJoin != null,
            source = source,
            lessonForm = lessonForm?.name,
        )
    }

    private fun parseDate(raw: String?): LocalDate? =
        raw?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    private fun parseTime(raw: String?): LocalTime? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            if (raw.length >= 8) LocalTime.parse(raw.substring(0, 8)) else LocalTime.parse(raw)
        }.getOrNull()
    }
}

/** Первая ссылка в теле ответа-редиректа (href="…" или голый URL). */
private val LINK_IN_BODY = Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://[^\s"'<>]+""")
