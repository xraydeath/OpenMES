package ru.openmes.core.data

import ru.openmes.core.model.NewsBlock
import ru.openmes.core.model.NewsItem
import ru.openmes.core.model.NewsPage
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/** Сервисы колледжа вокруг дневника: новости, сведения об организации, профориентация, QR билета. */
interface CollegeRepository {

    // force = true — мимо кэша в памяти (pull-to-refresh).
    suspend fun getNews(page: Int, force: Boolean = false): NewsPage

    suspend fun getNewsItem(id: Long, force: Boolean = false): NewsItem

    suspend fun getSchoolInfo(force: Boolean = false): SchoolInfo

    suspend fun getProforientation(force: Boolean = false): Proforientation

    /** PNG QR-кода студенческого билета. */
    suspend fun getStudentCardQr(studentId: String): ByteArray
}

class MesCollegeRepository(
    private val mesApi: MesApi,
    private val portalApi: PortalApi,
    private val tokenStore: TokenStore,
) : CollegeRepository {

    private val memory = MemoryCache(ttlMillis = 10 * 60_000L)

    private suspend fun <T> apiCall(block: suspend () -> T): T = try {
        block()
    } catch (e: retrofit2.HttpException) {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        throw IllegalStateException(
            "HTTP ${e.code()} ${e.message()}" + (body?.take(300)?.let { ": $it" } ?: ""),
            e,
        )
    }

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

    override suspend fun getStudentCardQr(studentId: String): ByteArray = apiCall {
        val base64 = mesApi.getStudentCardQr(studentId).qrCode ?: error("Сервер не вернул QR-код")
        Base64.getMimeDecoder().decode(base64.substringAfter("base64,"))
    }

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
