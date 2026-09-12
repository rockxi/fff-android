package ru.rockxi.fff.data.calories

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MealType { BREAKFAST, LUNCH, DINNER, SNACK }

@Entity(tableName = "calorie_profile")
data class CalorieProfileEntity(
    @PrimaryKey val id: Int = 1,
    val dailyCaloriesKcal: Int,
    val proteinTargetMg: Long,
    val fatTargetMg: Long,
    val carbTargetMg: Long,
    val updatedAt: Long,
)

@Entity(tableName = "calorie_foods", indices = [Index("name")])
data class FoodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val caloriesPer100gKcal: Int,
    val proteinPer100gMg: Long,
    val fatPer100gMg: Long,
    val carbPer100gMg: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "calorie_diary_entries",
    foreignKeys = [ForeignKey(
        entity = FoodEntity::class,
        parentColumns = ["id"],
        childColumns = ["foodId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("localDate"), Index("foodId"), Index(value = ["localDate", "mealType"])],
)
data class DiaryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localDate: String,
    val mealType: MealType,
    val foodId: Long?,
    val displayNameSnapshot: String,
    val amountGramsMg: Long?,
    val caloriesKcal: Int,
    val proteinMg: Long,
    val fatMg: Long,
    val carbMg: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

data class NutritionTotals(
    val caloriesKcal: Long = 0,
    val proteinMg: Long = 0,
    val fatMg: Long = 0,
    val carbMg: Long = 0,
)

data class DailyNutritionSummary(
    val total: NutritionTotals,
    val byMeal: Map<MealType, NutritionTotals>,
)

data class RecentFood(
    val food: FoodEntity,
    val defaultAmountGramsMg: Long,
    val lastUsedAt: Long,
)
