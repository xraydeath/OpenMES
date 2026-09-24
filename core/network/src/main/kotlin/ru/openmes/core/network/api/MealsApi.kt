package ru.openmes.core.network.api

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Питание — meals v3 (api/food/meals/v3), как в оригинальном «Колледже МЭШ».
 *
 * Авторизация: только `Authorization: Bearer {mesh_access_token}` + X-Mes-Subsystem;
 * с заголовком auth-token сервис отвечает 401 INVALID_TOKEN (AuthInterceptor его не ставит).
 * Клиент — personId = contingent GUID. Цены — в копейках.
 */
interface MealsApi {

    /** Меню комплексов за период (даты yyyy-MM-dd). withPreorder: in | ex | only. */
    @GET("api/food/meals/v3/menu/complexes")
    suspend fun getComplexes(
        @Query("personId") personId: String,
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("withPreorder") withPreorder: String = "in",
    ): List<ComplexMenuDto>

    /** Меню буфета за период. withFoodbox: in | ex | only. */
    @GET("api/food/meals/v3/menu/buffet")
    suspend fun getBuffet(
        @Query("personId") personId: String,
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("withFoodbox") withFoodbox: String = "in",
    ): List<BuffetMenuDto>

    /** Баланс: clientIds — JSON-массив `[{"personId":"…"}]`. */
    @GET("api/food/meals/v3/clients/balance")
    suspend fun getBalance(@Query("clientIds") clientIds: String): List<ClientBalanceDto>

    /** Организация питания (оператор) для клиента. */
    @GET("api/food/meals/v3/clients/food-provider")
    suspend fun getFoodProvider(@Query("personId") personId: String): FoodProviderDto
}

@Serializable
data class FoodProviderDto(
    val name: String? = null,
    val inn: String? = null,
)

@Serializable
data class ComplexMenuDto(
    val onDate: String,
    val items: List<ComplexDto> = emptyList(),
    val address: FoodAddressDto? = null,
)

@Serializable
data class ComplexDto(
    val id: Long,
    val name: String = "",
    val price: Long = 0,
    /** 0 завтрак, 1 второй завтрак, 2 обед, 3 полдник, 4 ужин, 5 второй ужин, 6 вода. */
    val kind: Int? = null,
    /** 0 — льготное, 1 — платное. */
    val paymentTypes: List<Int> = emptyList(),
    val preorderAllowed: Boolean = false,
    val specialDiet: Boolean = false,
    val complexItems: List<ComplexItemDto> = emptyList(),
)

@Serializable
data class ComplexItemDto(val dish: DishDto)

@Serializable
data class DishDto(
    val id: Long,
    val name: String = "",
    val price: Long = 0,
    val categoryName: String? = null,
    val subcategoryName: String? = null,
    val ingredients: String? = null,
    val calories: Double? = null,
    /** Граммы, строкой. */
    val weight: String? = null,
    val protein: Double? = null,
    val fat: Double? = null,
    val carbohydrates: Double? = null,
)

@Serializable
data class FoodAddressDto(
    val id: Long? = null,
    val organizationName: String? = null,
    val text: String? = null,
)

@Serializable
data class BuffetMenuDto(
    val onDate: String,
    val addresses: List<BuffetAddressDto> = emptyList(),
)

@Serializable
data class BuffetAddressDto(
    val items: List<BuffetItemDto> = emptyList(),
    val address: FoodAddressDto? = null,
    val buffetIsOpen: Boolean? = null,
    val buffetOpenAt: String? = null,
    val buffetCloseAt: String? = null,
)

@Serializable
data class BuffetItemDto(val dish: DishDto)

@Serializable
data class ClientBalanceDto(
    val contractId: Long? = null,
    /** Копейки. */
    val balance: Long? = null,
)
