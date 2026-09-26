package ru.openmes.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.openmes.core.common.runSuspendCatching
import ru.openmes.core.model.FinalMark
import ru.openmes.core.model.FinalMarksYear
import ru.openmes.core.model.NewsBlock
import ru.openmes.core.model.NewsItem
import ru.openmes.core.model.NewsPage
import ru.openmes.core.model.Portfolio
import ru.openmes.core.model.PortfolioEvent
import ru.openmes.core.model.PortfolioReward
import ru.openmes.core.model.ProfCollege
import ru.openmes.core.model.ProfEvent
import ru.openmes.core.model.ProfIndustry
import ru.openmes.core.model.Proforientation
import ru.openmes.core.model.SchoolBranch
import ru.openmes.core.model.SchoolInfo
import ru.openmes.core.network.api.MesApi
import ru.openmes.core.network.api.NewsDto
import ru.openmes.core.network.api.PortalApi
import ru.openmes.core.network.api.ProfEventListsDto
import ru.openmes.core.network.interceptor.OfflineCache
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/** Сервисы колледжа вокруг дневника: новости, сведения об организации, профориентация, портфолио, QR билета. */
interface CollegeRepository {

    /** Выполнить [block] только по офлайн-кэшу, без сети; null — в кэше нет. */
    suspend fun <T> cachedOnly(block: suspend CollegeRepository.() -> T): T?

    // force = true — мимо кэша в памяти (pull-to-refresh).
    suspend fun getNews(page: Int, force: Boolean = false): NewsPage

    suspend fun getNewsItem(id: Long, force: Boolean = false): NewsItem

    suspend fun getSchoolInfo(force: Boolean = false): SchoolInfo

    suspend fun getProforientation(force: Boolean = false): Proforientation

    suspend fun getPortfolio(force: Boolean = false): Portfolio

    /** Годовые оценки по учебным годам, свежие сверху. */
    suspend fun getFinalMarks(force: Boolean = false): List<FinalMarksYear>

    /** PNG QR-кода студенческого билета. */
    suspend fun getStudentCardQr(studentId: String): ByteArray

    /** Последний полученный QR (с диска) — показывается сразу и без сети. */
    suspend fun getSavedStudentCardQr(studentId: String): ByteArray?
}

class MesCollegeRepository(
    private val mesApi: MesApi,
    private val portalApi: PortalApi,
    private val tokenStore: TokenStore,
    private val offlineCache: OfflineCache? = null,
    /** Тот же репозиторий поверх кэш-only API (без сети). */
    private val cached: CollegeRepository? = null,
    /** false — без кэша в памяти (репозиторий поверх кэш-only API). */
    memoryCache: Boolean = true,
) : CollegeRepository {

    override suspend fun <T> cachedOnly(block: suspend CollegeRepository.() -> T): T? {
        val repo = cached ?: return null
        return runSuspendCatching { repo.block() }.getOrNull()
    }

    private val memory = MemoryCache(ttlMillis = 10 * 60_000L, offlineCache = offlineCache, enabled = memoryCache)

    override suspend fun getNews(page: Int, force: Boolean): NewsPage = memory.get("news_$page", force) { getNewsRemote(page) }

    private suspend fun getNewsRemote(page: Int): NewsPage = apiCall {
        val dto = portalApi.getNews(page)
        NewsPage(
            items = dto.data.map { it.toModel() },
            page = dto.pagination?.page ?: page,
            pageCount = dto.pagination?.pageCount ?: page,
        )
    }

    override suspend fun getNewsItem(id: Long, force: Boolean): NewsItem =
        memory.get("news_item_$id", force) { apiCall { portalApi.getNewsItem(id).toModel() } }

    override suspend fun getSchoolInfo(force: Boolean): SchoolInfo {
        val tokens = tokenStore.load() ?: error("Нет активного профиля — войдите заново")
        return memory.get("school_${tokens.profileId}_${tokens.studentId}", force) { getSchoolInfoRemote(tokens) }
    }

    private suspend fun getSchoolInfoRemote(tokens: AuthTokens): SchoolInfo = apiCall {
        val profileId = tokens.profileId?.toLongOrNull() ?: error("Нет активного профиля — войдите заново")
        // school_id и class_unit_id есть только в family profile.
        val children = mesApi.getFamilyProfile(
            profileId = profileId,
            roleId = tokens.roleId?.toIntOrNull() ?: 32,
            rowLimit = profileId.toString().sumOf { it.digitToInt() }.toString(),
        ).children
        val child = children.firstOrNull { it.id?.toString() == tokens.studentId } ?: children.firstOrNull()
        val schoolId = child?.school?.id ?: error("В профиле не указана организация")
        val classUnitId = child.classUnitId ?: error("В профиле не указана группа")

        val dto = mesApi.getSchoolInfo(classUnitId = classUnitId, schoolId = schoolId)
        SchoolInfo(
            name = dto.name ?: child.school?.name.orEmpty(),
            principal = dto.principal?.takeIf { it.isNotBlank() },
            curators = dto.classroomTeachers.map { t ->
                listOfNotNull(t.lastName, t.firstName, t.middleName).joinToString(" ").trim()
            }.filter { it.isNotEmpty() },
            address = dto.address?.address?.takeIf { it.isNotBlank() },
            phone = dto.phone?.takeIf { it.isNotBlank() },
            email = dto.email?.takeIf { it.isNotBlank() },
            website = dto.websiteLink?.takeIf { it.isNotBlank() },
            branches = dto.branches
                .map { b ->
                    SchoolBranch(
                        name = b.name?.trim().orEmpty(),
                        address = b.address?.trim(),
                        isMain = b.isMainBuilding,
                        isStudentBuilding = b.isStudentBuilding,
                    )
                }
                .sortedWith(compareByDescending<SchoolBranch> { it.isStudentBuilding }.thenByDescending { it.isMain }),
        )
    }

    override suspend fun getProforientation(force: Boolean): Proforientation {
        val guid = tokenStore.load()?.personGuid ?: error("Нет активного профиля — войдите заново")
        return memory.get("prof_$guid", force) { getProforientationRemote(guid) }
    }

    private suspend fun getProforientationRemote(guid: String): Proforientation = apiCall {
        val dto = portalApi.getProforientation(guid).data ?: return@apiCall Proforientation()
        Proforientation(
            testUrl = dto.testing?.takeIf { it.found }?.url,
            testDate = dto.testing?.takeIf { it.found }?.createdDate
                ?.let { runCatching { LocalDateTime.parse(it.take(19)) }.getOrNull() },
            detailsUrl = dto.detailResultsUrl,
            industries = dto.industry?.takeIf { it.found }?.data.orEmpty().map { i ->
                ProfIndustry(
                    name = i.industry,
                    atlasUrl = i.urlAtlas,
                    specialties = i.specs?.spec.orEmpty().map { s -> listOfNotNull(s.code, s.name).joinToString(" ") },
                    colleges = i.colleges?.college.orEmpty().map { ProfCollege(it.name.unescapeHtml(), it.urlAtlas) },
                )
            },
            upcoming = dto.events?.registration?.data.toEvents().sortedBy { it.date },
            history = dto.events?.history?.data.toEvents().sortedByDescending { it.date },
        )
    }

    override suspend fun getPortfolio(force: Boolean): Portfolio {
        val guid = personGuid()
        return memory.get("portfolio_$guid", force) { getPortfolioRemote(guid) }
    }

    private suspend fun getPortfolioRemote(guid: String): Portfolio = apiCall {
        val rewards = portalApi.getPortfolioRewards(guid).data.filterNot { it.isDelete }
        // Награда за олимпиаду ссылается на мероприятие: показываем её прямо на нём.
        val eventRewards = rewards.filter { it.entityType == "event" && it.entityId != null }
            .associate { it.entityId to it.name }
        val events = portalApi.getPortfolioEvents(guid).data.filterNot { it.isDelete }.map { e ->
            PortfolioEvent(
                name = e.name.trim(),
                date = parseIsoDate(e.startDate) ?: parseIsoDate(e.endDate),
                stage = e.stageEvent?.takeIf { it.isNotBlank() },
                format = e.format?.value,
                subjects = e.subjects.mapNotNull { it.value?.takeIf(String::isNotBlank) },
                category = e.category?.value,
                score = e.result?.toDoubleOrNull(),
                maxScore = e.maxScore?.takeIf { it > 0 },
                reward = eventRewards[e.id.toString()],
            )
        }
        val sport = portalApi.getSportRewards(guid).data.filterNot { it.isDelete }.map { r ->
            PortfolioReward(
                name = r.name.trim(),
                date = parseIsoDate(r.date),
                sport = true,
                source = r.type?.value,
                details = r.ageLimit?.value,
                number = r.rewardNumber?.takeIf { it.isNotBlank() },
                expireDate = parseIsoDate(r.expireDate),
            )
        }
        Portfolio(
            events = events.sortedByDescending { it.date },
            rewards = (sport + rewards.map { r ->
                PortfolioReward(
                    name = r.name.trim(),
                    date = parseIsoDate(r.date),
                    source = r.source?.value,
                )
            }).sortedByDescending { it.date },
        )
    }

    override suspend fun getFinalMarks(force: Boolean): List<FinalMarksYear> {
        val guid = personGuid()
        return memory.get("final_marks_$guid", force) { getFinalMarksRemote(guid) }
    }

    private suspend fun getFinalMarksRemote(guid: String): List<FinalMarksYear> = apiCall {
        portalApi.getFinalMarks(guid).data.map { y ->
            FinalMarksYear(
                title = y.yearTitle?.takeIf { it.isNotBlank() },
                year = y.year?.toIntOrNull(),
                level = y.educationLevel?.takeIf { it.isNotBlank() },
                average = y.averageAllSubjects?.takeIf { it > 0 },
                marks = y.subjects.mapNotNull { m ->
                    val raw = m.yearValue?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val credit = m.gradeSystemType?.value?.contains("Зачет", ignoreCase = true) == true
                    FinalMark(
                        subject = m.name.trim(),
                        value = when {
                            credit && raw == "1" -> "зачёт"
                            credit && raw == "0" -> "незачёт"
                            else -> raw
                        },
                        numeric = if (credit) null else m.yearFivePointValue ?: raw.toIntOrNull(),
                        gradeSystem = m.gradeSystemType?.value,
                    )
                }.sortedBy { it.subject },
            )
        }.sortedWith(compareByDescending<FinalMarksYear> { it.title }.thenByDescending { it.year })
    }

    private suspend fun personGuid(): String =
        tokenStore.load()?.personGuid ?: error("Нет активного профиля — войдите заново")

    private fun parseIsoDate(value: String?): LocalDate? =
        value?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    override suspend fun getStudentCardQr(studentId: String): ByteArray = apiCall {
        val base64 = mesApi.getStudentCardQr(studentId).qrCode ?: error("Сервер не вернул QR-код")
        Base64.getMimeDecoder().decode(base64.substringAfter("base64,"))
            .also { offlineCache?.writeBlob(qrBlob(studentId), it) }
    }

    override suspend fun getSavedStudentCardQr(studentId: String): ByteArray? =
        withContext(Dispatchers.IO) { offlineCache?.readBlob(qrBlob(studentId)) }

    private fun qrBlob(studentId: String) = "student_card_qr_$studentId"

    private fun ProfEventListsDto?.toEvents(): List<ProfEvent> {
        if (this == null) return emptyList()
        return listOf(
            "День открытых дверей" to dod,
            "Мастер-класс" to masterstvo,
            "Мероприятие партнёров" to partners,
            "Профтестирование" to proftesting,
        ).flatMap { (kind, events) ->
            events.map { e ->
                ProfEvent(
                    name = e.name.unescapeHtml(),
                    kind = kind,
                    date = e.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                    time = e.time,
                    address = e.address?.takeIf { it.isNotBlank() },
                    organizer = e.organizer?.unescapeHtml(),
                    visited = e.visited,
                )
            }
        }
    }

    private fun NewsDto.toModel() = NewsItem(
        id = id,
        title = name.trim(),
        channel = channel?.name,
        channelLogo = channel?.logo,
        publishedAt = (publishedAt ?: createdAt)
            ?.let { runCatching { LocalDateTime.parse(it, NEWS_DATE) }.getOrNull() },
        coverUrl = cover?.takeIf { it.isNotBlank() },
        views = views,
        tags = tags.mapNotNull { it.name },
        blocks = content.mapNotNull { c ->
            when (c.type) {
                "TEXT" -> NewsBlock.Text(c.value).takeIf { c.value.isNotBlank() }
                "IMAGE" -> NewsBlock.Image(c.value).takeIf { c.value.isNotBlank() }
                "VIDEO" -> NewsBlock.Video(c.value).takeIf { c.value.isNotBlank() }
                else -> null
            }
        },
    )

    private fun String.unescapeHtml() =
        replace("&quot;", "\"").replace("&laquo;", "«").replace("&raquo;", "»").replace("&amp;", "&")

    private companion object {
        val NEWS_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
