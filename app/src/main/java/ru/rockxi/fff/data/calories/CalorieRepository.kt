package ru.rockxi.fff.data.calories

import androidx.room.withTransaction
import java.lang.Math.addExact
import java.lang.Math.multiplyExact
import java.time.LocalDate

internal class CalorieRepository(
    private val database: CalorieDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.calorieDao()

    suspend fun profile(): CalorieProfileEntity = requireNotNull(dao.profile())

    suspend fun updateProfile(dailyCaloriesKcal: Int, proteinTargetMg: Long, fatTargetMg: Long, carbTargetMg: Long) {
        validateProfile(dailyCaloriesKcal, proteinTargetMg, fatTargetMg, carbTargetMg)
        require(dao.updateProfile(CalorieProfileEntity(1, dailyCaloriesKcal, proteinTargetMg, fatTargetMg, carbTargetMg, now())) == 1)
    }

    suspend fun foods(query: String = ""): List<FoodEntity> =
        if (query.isBlank()) dao.foods() else dao.searchFoods(escapeLike(query.trim()))

    suspend fun food(id: Long): FoodEntity? = dao.food(id)

    suspend fun createFood(name: String, caloriesPer100gKcal: Int, proteinPer100gMg: Long, fatPer100gMg: Long, carbPer100gMg: Long): Long {
        val normalized = validateFood(name, caloriesPer100gKcal, proteinPer100gMg, fatPer100gMg, carbPer100gMg)
        val timestamp = now()
        return dao.insertFood(FoodEntity(name = normalized, caloriesPer100gKcal = caloriesPer100gKcal, proteinPer100gMg = proteinPer100gMg, fatPer100gMg = fatPer100gMg, carbPer100gMg = carbPer100gMg, createdAt = timestamp, updatedAt = timestamp))
    }

    suspend fun updateFood(id: Long, name: String, caloriesPer100gKcal: Int, proteinPer100gMg: Long, fatPer100gMg: Long, carbPer100gMg: Long) {
        val existing = requireNotNull(dao.food(id)) { "Продукт не найден" }
        val normalized = validateFood(name, caloriesPer100gKcal, proteinPer100gMg, fatPer100gMg, carbPer100gMg)
        require(dao.updateFood(existing.copy(name = normalized, caloriesPer100gKcal = caloriesPer100gKcal, proteinPer100gMg = proteinPer100gMg, fatPer100gMg = fatPer100gMg, carbPer100gMg = carbPer100gMg, updatedAt = now())) == 1)
    }

    /** Fails while diary history references the product (RESTRICT is also enforced by SQLite). */
    suspend fun deleteFood(id: Long) {
        requireNotNull(dao.food(id)) { "Продукт не найден" }
        require(dao.deleteFood(id) == 1) { "Сначала удалите записи дневника с этим продуктом" }
    }

    suspend fun entries(date: LocalDate): List<DiaryEntryEntity> = dao.entries(date.toString())
    suspend fun entry(id: Long): DiaryEntryEntity? = dao.entry(id)

    suspend fun addFoodEntry(foodId: Long, date: LocalDate, mealType: MealType, amountGramsMg: Long): Long = database.withTransaction {
        val food = requireNotNull(dao.food(foodId)) { "Продукт не найден" }
        val nutrition = calculate(food, amountGramsMg)
        val timestamp = now()
        dao.insertEntry(DiaryEntryEntity(localDate = date.toString(), mealType = mealType, foodId = food.id, displayNameSnapshot = food.name, amountGramsMg = amountGramsMg, caloriesKcal = nutrition.caloriesKcal.toInt(), proteinMg = nutrition.proteinMg, fatMg = nutrition.fatMg, carbMg = nutrition.carbMg, createdAt = timestamp, updatedAt = timestamp))
    }

    suspend fun addQuickEntry(name: String, date: LocalDate, mealType: MealType, caloriesKcal: Int, proteinMg: Long = 0, fatMg: Long = 0, carbMg: Long = 0): Long {
        val normalized = validateQuick(name, caloriesKcal, proteinMg, fatMg, carbMg)
        val timestamp = now()
        return dao.insertEntry(DiaryEntryEntity(localDate = date.toString(), mealType = mealType, foodId = null, displayNameSnapshot = normalized, amountGramsMg = null, caloriesKcal = caloriesKcal, proteinMg = proteinMg, fatMg = fatMg, carbMg = carbMg, createdAt = timestamp, updatedAt = timestamp))
    }

    suspend fun updateFoodEntry(id: Long, foodId: Long, date: LocalDate, mealType: MealType, amountGramsMg: Long) = database.withTransaction {
        val existing = requireNotNull(dao.entry(id)) { "Запись не найдена" }
        val food = requireNotNull(dao.food(foodId)) { "Продукт не найден" }
        val nutrition = calculate(food, amountGramsMg)
        require(dao.updateEntry(existing.copy(localDate = date.toString(), mealType = mealType, foodId = food.id, displayNameSnapshot = food.name, amountGramsMg = amountGramsMg, caloriesKcal = nutrition.caloriesKcal.toInt(), proteinMg = nutrition.proteinMg, fatMg = nutrition.fatMg, carbMg = nutrition.carbMg, updatedAt = now())) == 1)
    }

    suspend fun updateQuickEntry(id: Long, name: String, date: LocalDate, mealType: MealType, caloriesKcal: Int, proteinMg: Long = 0, fatMg: Long = 0, carbMg: Long = 0) {
        val existing = requireNotNull(dao.entry(id)) { "Запись не найдена" }
        val normalized = validateQuick(name, caloriesKcal, proteinMg, fatMg, carbMg)
        require(dao.updateEntry(existing.copy(localDate = date.toString(), mealType = mealType, foodId = null, displayNameSnapshot = normalized, amountGramsMg = null, caloriesKcal = caloriesKcal, proteinMg = proteinMg, fatMg = fatMg, carbMg = carbMg, updatedAt = now())) == 1)
    }

    suspend fun deleteEntry(id: Long) {
        require(dao.deleteEntry(id) == 1) { "Запись не найдена" }
    }

    suspend fun recentFoods(limit: Int = 12): List<RecentFood> {
        require(limit in 1..100) { "Некорректный размер списка" }
        return dao.recentFoods(limit).map { row ->
            RecentFood(
                FoodEntity(row.id, row.name, row.caloriesPer100gKcal, row.proteinPer100gMg, row.fatPer100gMg, row.carbPer100gMg, row.createdAt, row.updatedAt),
                row.defaultAmountGramsMg,
                row.lastUsedAt,
            )
        }
    }

    suspend fun dailySummary(date: LocalDate): DailyNutritionSummary {
        val byMeal = MealType.entries.associateWith { NutritionTotals() }.toMutableMap()
        var total = NutritionTotals()
        dao.entries(date.toString()).forEach { entry ->
            val value = NutritionTotals(entry.caloriesKcal.toLong(), entry.proteinMg, entry.fatMg, entry.carbMg)
            byMeal[entry.mealType] = add(byMeal.getValue(entry.mealType), value)
            total = add(total, value)
        }
        return DailyNutritionSummary(total, byMeal)
    }

    internal fun calculate(food: FoodEntity, amountGramsMg: Long): NutritionTotals {
        require(amountGramsMg in 1..MAX_AMOUNT_MG) { "Количество должно быть от 0,001 до 100 000 г" }
        val result = NutritionTotals(
            roundedProduct(food.caloriesPer100gKcal.toLong(), amountGramsMg),
            roundedProduct(food.proteinPer100gMg, amountGramsMg),
            roundedProduct(food.fatPer100gMg, amountGramsMg),
            roundedProduct(food.carbPer100gMg, amountGramsMg),
        )
        require(result.caloriesKcal <= MAX_CALORIES && result.proteinMg <= MAX_MACRO_MG && result.fatMg <= MAX_MACRO_MG && result.carbMg <= MAX_MACRO_MG) { "Порция слишком велика" }
        return result
    }

    private fun roundedProduct(per100g: Long, amountMg: Long): Long {
        val product = try { multiplyExact(per100g, amountMg) } catch (_: ArithmeticException) { throw IllegalArgumentException("Порция слишком велика") }
        val quotient = product / DIVISOR
        return if (product % DIVISOR >= DIVISOR / 2) addExact(quotient, 1) else quotient
    }

    private fun add(left: NutritionTotals, right: NutritionTotals) = try {
        NutritionTotals(addExact(left.caloriesKcal, right.caloriesKcal), addExact(left.proteinMg, right.proteinMg), addExact(left.fatMg, right.fatMg), addExact(left.carbMg, right.carbMg))
    } catch (_: ArithmeticException) {
        throw IllegalStateException("Сумма дневника слишком велика")
    }

    private fun validateProfile(calories: Int, protein: Long, fat: Long, carbs: Long) {
        require(calories in 1..MAX_CALORIES.toInt()) { "Цель калорий должна быть положительной" }
        listOf(protein, fat, carbs).forEach { require(it in 0..MAX_MACRO_MG) { "Некорректная цель макронутриента" } }
    }

    private fun validateFood(name: String, calories: Int, protein: Long, fat: Long, carbs: Long): String {
        val normalized = requiredName(name)
        require(calories in 0..MAX_CALORIES.toInt()) { "Некорректная калорийность" }
        listOf(protein, fat, carbs).forEach { require(it in 0..MAX_MACRO_MG) { "Некорректное значение макронутриента" } }
        require(calories > 0 || protein > 0 || fat > 0 || carbs > 0) { "Укажите пищевую ценность" }
        return normalized
    }

    private fun validateQuick(name: String, calories: Int, protein: Long, fat: Long, carbs: Long): String {
        val normalized = requiredName(name)
        require(calories in 1..MAX_CALORIES.toInt()) { "Калории должны быть положительными" }
        listOf(protein, fat, carbs).forEach { require(it in 0..MAX_MACRO_MG) { "Некорректное значение макронутриента" } }
        return normalized
    }

    private fun requiredName(value: String): String = value.trim().also { require(it.isNotEmpty() && it.length <= 120) { "Название должно содержать от 1 до 120 символов" } }
    private fun escapeLike(value: String) = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private companion object {
        const val DIVISOR = 100_000L
        const val MAX_AMOUNT_MG = 100_000_000L
        const val MAX_CALORIES = 1_000_000L
        const val MAX_MACRO_MG = 100_000_000L
    }
}
