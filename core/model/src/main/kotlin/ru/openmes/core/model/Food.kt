package ru.openmes.core.model

import java.time.LocalDate
import java.time.LocalTime

/** Приём пищи комплекса (kind в meals v3). */
enum class MealKind(val title: String) {
    Breakfast("Завтрак"),
    SecondBreakfast("Второй завтрак"),
    Lunch("Обед"),
    AfternoonSnack("Полдник"),
    Dinner("Ужин"),
    SecondDinner("Второй ужин"),
    Water("Питьевая вода"),
    ;

    companion object {
        fun of(code: Int?): MealKind? = code?.let { entries.getOrNull(it) }
    }
}

/** Блюдо (комплекса или буфета). Цена в рублях, 0 — входит в комплекс. */
data class Dish(
    val id: Long,
    val name: String,
    val price: Double,
    val category: String? = null,
    val ingredients: String? = null,
    val weightGrams: String? = null,
    val calories: Double? = null,
    val protein: Double? = null,
    val fat: Double? = null,
    val carbohydrates: Double? = null,
)

data class FoodComplex(
    val id: Long,
    val name: String,
    val kind: MealKind?,
    val price: Double,
    val isPreferential: Boolean,
    val isPaid: Boolean,
    val preorderAllowed: Boolean,
    val dishes: List<Dish>,
)

data class Buffet(
    val isOpen: Boolean?,
    val openAt: LocalTime?,
    val closeAt: LocalTime?,
    val dishes: List<Dish>,
)

/** Меню на день: комплексы столовой + буфет. */
data class FoodDay(
    val date: LocalDate,
    val complexes: List<FoodComplex>,
    val buffet: Buffet?,
    val organization: String? = null,
    val address: String? = null,
)
