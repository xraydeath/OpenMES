package ru.openmes.core.model

import java.time.LocalDateTime


/** Новость ленты school.mos.ru (api/news/v2). */
data class NewsItem(
    val id: Long,
    val title: String,
    val channel: String? = null,
    val channelLogo: String? = null,
    val publishedAt: LocalDateTime? = null,
    val coverUrl: String? = null,
    val views: Long? = null,
    val tags: List<String> = emptyList(),
    val blocks: List<NewsBlock> = emptyList(),
)

sealed interface NewsBlock {
    /** HTML-фрагмент. */
    data class Text(val html: String) : NewsBlock
    data class Image(val url: String) : NewsBlock
    data class Video(val url: String) : NewsBlock
}

/** Страница ленты новостей. */
data class NewsPage(val items: List<NewsItem>, val page: Int, val pageCount: Int)

/** Баланс питания (лицевой счёт meals). */
data class FoodBalance(
    val personId: String,
    val amount: Double,
    val currency: String = "RUB",
    val contractId: Long? = null,
    /** Дневной лимит трат, ₽ (null — не задан). */
    val dayLimit: Double? = null,
    /** Порог «мало денег на счёте», ₽ (null — не задан). */
    val lowBalanceThreshold: Double? = null,
)

/** Операция по счёту питания: покупка или пополнение. */
data class FoodTransaction(
    val date: LocalDateTime?,
    /** ₽; знак — как отдаёт сервер (null — суммы в ответе нет). */
    val amount: Double?,
    val title: String?,
    /** Тип операции как есть (покупка, пополнение, возврат…). */
    val type: String?,
)
