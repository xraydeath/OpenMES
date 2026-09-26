package ru.openmes.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.openmes.core.common.runSuspendCatching
import ru.openmes.core.model.DayInfo
import ru.openmes.core.model.DayKind
import ru.openmes.core.model.LessonModule
import ru.openmes.core.model.TestLesson
import ru.openmes.core.model.Visit
import ru.openmes.core.model.VisitDay
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
import ru.openmes.core.model.MedicalRecord
import ru.openmes.core.model.Person
import ru.openmes.core.model.StudentCard
import ru.openmes.core.model.SubjectMarks
import ru.openmes.core.model.SubjectMarksData
import ru.openmes.core.model.SubjectPeriod
import ru.openmes.core.network.MesEnvironment
import ru.openmes.core.network.api.HomeworkFullDto
import ru.openmes.core.network.api.MedicalRecommendationDto
import ru.openmes.core.network.api.MesApi
import ru.openmes.core.network.interceptor.OfflineCache
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Предохранитель от бесконечного листания справок ЕМИАС. */
private const val MAX_MEDICAL_PAGES = 20

/**
 * Репозиторий дневника поверх Family Mobile API (family/mobile/v1).
 */
class MesDiaryRepository(
    private val mesApi: MesApi,
    private val tokenStore: TokenStore,
    private val offlineCache: OfflineCache? = null,
    /** Тот же репозиторий поверх кэш-only MesApi (без сети). */
    private val cached: DiaryRepository? = null,
    /** Клиент для скачивания файлов по прямым ссылкам (аватар): без МЭШ-заголовков. */
    private val httpClient: OkHttpClient? = null,
) : DiaryRepository {

    override suspend fun <T> cachedOnly(block: suspend DiaryRepository.() -> T): T? {
        val repo = cached ?: return null
        return runSuspendCatching { repo.block() }.getOrNull()
    }

    private val isoDate: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

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
                calendarId = dto.calendarId,
                isCurrent = dto.currentYear,
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
        runSuspendCatching {
            mesApi.getHomeworksFull(
                studentId = studentId,
                from = from.format(isoDate),
                to = to.format(isoDate),
            ).payload.map { it.toHomework() }
        }.getOrElse {
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

    override suspend fun getMedicalRecords(personId: String): List<MedicalRecord> = apiCall {
        val studentId = studentId()
        val records = LinkedHashMap<Long, MedicalRecommendationDto>()
        // Страницы до пустой или не принёсшей ничего нового (перекрываются — дедуп по id).
        for (page in 1..MAX_MEDICAL_PAGES) {
            val batch = mesApi.getMedicalRecommendations(studentId, page)
            if (batch.none { records.putIfAbsent(it.id, it) == null }) break
        }
        records.values.mapNotNull { r ->
            MedicalRecord(
                date = parseDate(r.date) ?: return@mapNotNull null,
                type = r.type?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                partial = r.subjectIds.isNotEmpty(),
            )
        }.sortedBy { it.date }
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
        val bytes = download(url)
        withContext(Dispatchers.IO) { offlineCache?.writeBlob(avatarBlob(personGuid), bytes) }
        return bytes
    }

    /** GET по прямой ссылке через OkHttp: таймауты клиента, отмена корутины отменяет запрос. */
    private suspend fun download(url: String): ByteArray {
        val client = httpClient ?: error("Нет HTTP-клиента для загрузки")
        val call = client.newCall(Request.Builder().url(url).build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) throw IOException("HTTP ${it.code} при загрузке файла")
                            it.body?.bytes() ?: throw IOException("Пустой ответ")
                        }
                    }
                    result.fold(cont::resume, cont::resumeWithException)
                }
            })
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
            isDistance = dto.isVirtual || dto.remoteLesson != null,
        )
    }

    override suspend fun getCalendar(personId: String, from: LocalDate, to: LocalDate): Map<LocalDate, DayInfo> = apiCall {
        val days = mesApi.getPeriodsSchedules(studentId = studentId(), from = from.format(isoDate), to = to.format(isoDate))
            .mapNotNull { day ->
                val date = parseDate(day.date) ?: return@mapNotNull null
                val kind = when (day.type) {
                    "holiday" -> DayKind.HOLIDAY
                    "vacation" -> DayKind.VACATION
                    else -> DayKind.WORKDAY
                }
                date to DayInfo(kind = kind, title = day.title?.takeIf { it.isNotBlank() })
            }
            .toMap(HashMap())
        // Переносы — дополнение: без них календарь всё равно полезен.
        runSuspendCatching { transpositions() }.getOrNull().orEmpty().forEach { t ->
            val date = parseDate(t.date) ?: return@forEach
            if (date.isBefore(from) || date.isAfter(to)) return@forEach
            val postponed = parseDate(t.postponedFrom)
            val weekday = t.scheduleForWeekday?.takeIf { it in 1..7 }?.let { java.time.DayOfWeek.of(it) }
            // Обычное воскресенье без пояснений — не перенос, а просто выходной.
            val trivial = t.isHoliday && t.note.isNullOrBlank() && postponed == null && weekday == null &&
                date.dayOfWeek == java.time.DayOfWeek.SUNDAY
            val note = if (trivial) null else buildString {
                append(if (t.isHoliday) "Выходной день" else "Рабочий день")
                weekday?.let { append(" по расписанию ").append(WEEKDAY_GEN[it.value - 1]) }
                postponed?.let { append(", перенос с ").append(it.format(SHORT_DATE)) }
                t.note?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it.trim()) }
            }
            val old = days[date]
            val kind = when {
                !t.isHoliday -> DayKind.WORKDAY
                old?.kind == DayKind.VACATION -> DayKind.VACATION
                else -> DayKind.HOLIDAY
            }
            days[date] = DayInfo(kind = kind, title = old?.title, note = note ?: old?.note)
        }
        days
    }

    private suspend fun currentYear() = mesApi.getAcademicYears().let { years ->
        years.firstOrNull { it.currentYear } ?: years.maxByOrNull { it.id }
    }

    private suspend fun transpositions() =
        currentYear()?.calendarId?.let { mesApi.getCalendarTranspositions(it) }.orEmpty()

    override suspend fun getLessonModules(personId: String): List<LessonModule> = apiCall {
        val year = currentYear() ?: return@apiCall emptyList()
        mesApi.getLessonModules(studentProfileId = studentId(), academicYearId = year.id).map { dto ->
            LessonModule(
                id = dto.id,
                name = dto.name,
                subjectId = dto.subjectId,
                start = dto.startDate.toLocalDate(),
                end = dto.endDate.toLocalDate(),
            )
        }
    }

    private fun List<Int>?.toLocalDate(): LocalDate? =
        this?.takeIf { it.size >= 3 }?.let { runCatching { LocalDate.of(it[0], it[1], it[2]) }.getOrNull() }

    override suspend fun getTestLessons(personId: String, from: LocalDate, to: LocalDate): List<TestLesson> = apiCall {
        val tokens = tokenStore.load() ?: error("Нет сессии")
        val personGuid = tokens.personGuid ?: return@apiCall emptyList()
        mesApi.getTestLessons(
            studentProfileId = studentId(),
            studentPersonId = personGuid,
            from = from.format(isoDate),
            to = to.format(isoDate),
        ).items.orEmpty().map { it.toTestLesson() }
    }

    /** Схема элементов test_lessons у колледжа не подтверждена: берём первое подходящее поле. */
    private fun kotlinx.serialization.json.JsonObject.toTestLesson(): TestLesson {
        fun str(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                ?.content?.takeIf { it.isNotBlank() }
        }
        fun nested(obj: String, key: String): String? =
            ((this[obj] as? kotlinx.serialization.json.JsonObject)?.get(key) as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
        return TestLesson(
            date = parseDate(str("date", "lesson_date", "begin_date", "start_at", "begin_time")),
            lessonId = str("schedule_item_id", "lesson_schedule_item_id", "lesson_id", "id")?.toLongOrNull(),
            subjectId = (str("subject_id") ?: nested("subject", "id"))?.toLongOrNull(),
            subjectName = str("subject_name") ?: nested("subject", "name"),
            name = str("test_form_name", "control_form_name", "lesson_type_name", "name", "title")
                ?: nested("control_form", "name") ?: nested("test_form", "name"),
        )
    }

    override suspend fun getVisits(personId: String, from: LocalDate, to: LocalDate): List<VisitDay> = apiCall {
        val tokens = tokenStore.load() ?: error("Нет сессии")
        val personGuid = tokens.personGuid ?: error("Нет профиля — войдите заново")
        // Сервис отдаёт не больше 7 дней за запрос.
        generateSequence(from) { it.plusDays(7) }.takeWhile { !it.isAfter(to) }.toList()
            .flatMap { chunkStart ->
                val chunkEnd = minOf(chunkStart.plusDays(6), to)
                mesApi.getVisitDurations(personGuid, chunkStart.format(isoDate), chunkEnd.format(isoDate)).payload
            }
            .mapNotNull { day ->
                val date = parseDate(day.date) ?: return@mapNotNull null
                VisitDay(
                    date = date,
                    visits = day.visits.map {
                        Visit(
                            entered = parseTime(it.entered),
                            left = parseTime(it.left),
                            duration = it.duration,
                            place = it.organizationShortName ?: it.kindName,
                            incomplete = it.isIncomplete,
                        )
                    },
                )
            }
            .sortedByDescending { it.date }
    }

    override suspend fun getSchedulePdf(personId: String, from: LocalDate, to: LocalDate): ByteArray = apiCall {
        val tokens = tokenStore.load() ?: error("Нет сессии")
        val personIds = tokens.personGuid ?: tokens.studentId ?: error("Нет профиля — войдите заново")
        withContext(Dispatchers.IO) {
            mesApi.getSchedulePdf(personIds, from.format(isoDate), to.format(isoDate)).use { it.bytes() }
        }
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
            subjectId = subjectId,
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
            isDistance = !linkToJoin.isNullOrBlank(),
            joinUrl = linkToJoin?.takeIf { it.isNotBlank() },
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

private val WEEKDAY_GEN = listOf(
    "понедельника", "вторника", "среды", "четверга", "пятницы", "субботы", "воскресенья",
)

private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")

/** Первая ссылка в теле ответа-редиректа (href="…" или голый URL). */
private val LINK_IN_BODY = Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://[^\s"'<>]+""")
