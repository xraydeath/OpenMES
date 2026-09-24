package ru.openmes.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.openmes.core.model.Buffet
import ru.openmes.core.model.Dish
import ru.openmes.core.model.FoodBalance
import ru.openmes.core.model.FoodComplex
import ru.openmes.core.model.FoodDay
import ru.openmes.core.model.MealKind
import ru.openmes.core.network.api.DishDto
import ru.openmes.core.network.api.MealsApi
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Питание: меню столовой и буфета, баланс. */
interface FoodRepository {

    /** Меню по дням за период (дни без комплексов и буфета не возвращаются). */
    suspend fun getMenu(from: LocalDate, to: LocalDate): List<FoodDay>

    /** Баланс лицевого счёта питания (null — счёта нет). */
    suspend fun getBalance(): FoodBalance?

    /** Название организации питания (оператора). */
    suspend fun getProvider(): String?
}

class MealsFoodRepository(
    private val mealsApi: MealsApi,
    private val tokenStore: TokenStore,
) : FoodRepository {

    private fun personGuid(): String =
        tokenStore.load()?.personGuid ?: error("Нет активного профиля — войдите заново")

    private suspend fun <T> apiCall(block: suspend () -> T): T = try {
        block()
    } catch (e: retrofit2.HttpException) {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        throw IllegalStateException(
            "HTTP ${e.code()} ${e.message()}" + (body?.take(300)?.let { ": $it" } ?: ""),
            e,
        )
    }

    override suspend fun getMenu(from: LocalDate, to: LocalDate): List<FoodDay> = apiCall {
        val person = personGuid()
        val complexes = mealsApi.getComplexes(person, from.toString(), to.toString())
            .associateBy { LocalDate.parse(it.onDate) }
        // Буфет — дополнение: его ошибка не должна прятать меню столовой.
        val buffets = runCatching { mealsApi.getBuffet(person, from.toString(), to.toString()) }
            .getOrDefault(emptyList())
            .associateBy { LocalDate.parse(it.onDate) }

        (complexes.keys + buffets.keys).sorted().mapNotNull { date ->
            val menu = complexes[date]
            val buffetAddress = buffets[date]?.addresses?.firstOrNull()
            val day = FoodDay(
                date = date,
                complexes = menu?.items.orEmpty()
                    .map { c ->
                        FoodComplex(
                            id = c.id,
                            name = c.name,
                            kind = MealKind.of(c.kind),
                            price = c.price / 100.0,
                            isPreferential = 0 in c.paymentTypes,
                            isPaid = 1 in c.paymentTypes,
                            preorderAllowed = c.preorderAllowed,
                            dishes = c.complexItems.map { it.dish.toDish() },
                        )
                    }
                    .sortedBy { it.kind?.ordinal ?: Int.MAX_VALUE },
                buffet = buffetAddress?.let { b ->
                    Buffet(
                        isOpen = b.buffetIsOpen,
                        openAt = b.buffetOpenAt.toTime(),
                        closeAt = b.buffetCloseAt.toTime(),
                        dishes = b.items.map { it.dish.toDish() },
                    )
                },
                organization = (menu?.address ?: buffetAddress?.address)?.organizationName,
                address = (menu?.address ?: buffetAddress?.address)?.text,
            )
            day.takeIf { it.complexes.isNotEmpty() || it.buffet != null }
        }
    }

    override suspend fun getBalance(): FoodBalance? = apiCall {
        val person = personGuid()
        val clientIds = buildJsonArray { add(buildJsonObject { put("personId", person) }) }
        mealsApi.getBalance(Json.encodeToString(clientIds)).firstOrNull()?.let {
            FoodBalance(personId = person, amount = (it.balance ?: 0) / 100.0, contractId = it.contractId)
        }
    }

    override suspend fun getProvider(): String? = apiCall {
        mealsApi.getFoodProvider(personGuid()).name?.takeIf { it.isNotBlank() }
    }

    private fun DishDto.toDish() = Dish(
        id = id,
        name = name,
        price = price / 100.0,
        category = categoryName,
        ingredients = ingredients?.trim()?.takeIf { it.isNotEmpty() && it != name },
        weightGrams = weight?.takeIf { it.isNotBlank() && it != "0" },
        calories = calories,
        protein = protein,
        fat = fat,
        carbohydrates = carbohydrates,
    )

    private fun String?.toTime(): LocalTime? =
        this?.let { runCatching { LocalDateTime.parse(it).toLocalTime() }.getOrNull() }
}
