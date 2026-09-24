package ru.openmes.core.network.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Сервисы портала вне mapi: новости (api/news/v2) и профориентация (portfolio).
 * CollegeRouting эти пути не переписывает; авторизация — обычная (auth-token + Bearer).
 */
interface PortalApi {

    /** Лента новостей пользователя, по 30 на страницу (page с 1). */
    @GET("api/news/v2/news/users/v2")
    suspend fun getNews(@Query("page") page: Int): NewsPageDto

    @GET("api/news/v2/news/{id}")
    suspend fun getNewsItem(@Path("id") id: Long): NewsDto

    /** Результаты профориентации: тест, рекомендованные отрасли, мероприятия. */
    @GET("portfolio/app/persons/{personGuid}/proforientation/getResults")
    suspend fun getProforientation(@Path("personGuid") personGuid: String): ProforientationResponseDto
}

@Serializable
data class NewsPageDto(
    val pagination: NewsPaginationDto? = null,
    val data: List<NewsDto> = emptyList(),
)

@Serializable
data class NewsPaginationDto(
    val page: Int = 1,
    @SerialName("page_count") val pageCount: Int = 1,
)

@Serializable
data class NewsDto(
    val id: Long,
    val name: String = "",
    val views: Long? = null,
    /** "yyyy-MM-dd HH:mm:ss". */
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val channel: NewsChannelDto? = null,
    /** Блоки: TEXT (html), IMAGE (url), VIDEO (url). */
    val content: List<NewsContentDto> = emptyList(),
    @SerialName("short_content") val shortContent: String? = null,
    val cover: String? = null,
    val tags: List<NewsTagDto> = emptyList(),
    @SerialName("is_recommended") val isRecommended: Boolean = false,
)

@Serializable
data class NewsChannelDto(val name: String? = null, val logo: String? = null)

@Serializable
data class NewsContentDto(val type: String = "", val value: String = "")

@Serializable
data class NewsTagDto(val name: String? = null)

@Serializable
data class ProforientationResponseDto(val data: ProforientationDto? = null)

@Serializable
data class ProforientationDto(
    val testing: ProfTestingDto? = null,
    val industry: ProfIndustriesDto? = null,
    val events: ProfEventsDto? = null,
    val detailResultsUrl: String? = null,
)

@Serializable
data class ProfTestingDto(
    val found: Boolean = false,
    val url: String? = null,
    /** "2024-10-23T10:11:20.000000". */
    val createdDate: String? = null,
)

@Serializable
data class ProfIndustriesDto(
    val found: Boolean = false,
    val data: List<ProfIndustryDto> = emptyList(),
)

@Serializable
data class ProfIndustryDto(
    val industry: String = "",
    @SerialName("url_atlas") val urlAtlas: String? = null,
    val specs: ProfSpecsDto? = null,
    val colleges: ProfCollegesDto? = null,
)

@Serializable
data class ProfSpecsDto(val spec: List<ProfSpecDto> = emptyList())

@Serializable
data class ProfSpecDto(val code: String? = null, val name: String = "")

@Serializable
data class ProfCollegesDto(val college: List<ProfCollegeDto> = emptyList())

@Serializable
data class ProfCollegeDto(
    val name: String = "",
    @SerialName("url_atlas") val urlAtlas: String? = null,
)

@Serializable
data class ProfEventsDto(
    val history: ProfEventGroupsDto? = null,
    val registration: ProfEventGroupsDto? = null,
)

@Serializable
data class ProfEventGroupsDto(val data: ProfEventListsDto? = null)

@Serializable
data class ProfEventListsDto(
    val masterstvo: List<ProfEventDto> = emptyList(),
    val partners: List<ProfEventDto> = emptyList(),
    val dod: List<ProfEventDto> = emptyList(),
    val proftesting: List<ProfEventDto> = emptyList(),
)

@Serializable
data class ProfEventDto(
    @SerialName("name_event") val name: String = "",
    /** yyyy-MM-dd. */
    @SerialName("date_event") val date: String? = null,
    @SerialName("time_event") val time: String? = null,
    val address: String? = null,
    @SerialName("organizer_event") val organizer: String? = null,
    val visited: Boolean = false,
)
