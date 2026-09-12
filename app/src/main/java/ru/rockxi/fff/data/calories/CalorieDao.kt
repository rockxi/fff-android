package ru.rockxi.fff.data.calories

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

internal data class RecentFoodRow(
    val id: Long,
    val name: String,
    val caloriesPer100gKcal: Int,
    val proteinPer100gMg: Long,
    val fatPer100gMg: Long,
    val carbPer100gMg: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val defaultAmountGramsMg: Long,
    val lastUsedAt: Long,
)

@Dao
internal interface CalorieDao {
    @Insert suspend fun insertProfile(value: CalorieProfileEntity)
    @Query("SELECT * FROM calorie_profile WHERE id = 1") suspend fun profile(): CalorieProfileEntity?
    @Update suspend fun updateProfile(value: CalorieProfileEntity): Int

    @Insert suspend fun insertFood(value: FoodEntity): Long
    @Query("SELECT * FROM calorie_foods WHERE id = :id") suspend fun food(id: Long): FoodEntity?
    @Query("SELECT * FROM calorie_foods ORDER BY name COLLATE NOCASE, id") suspend fun foods(): List<FoodEntity>
    @Query("SELECT * FROM calorie_foods WHERE name LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY name COLLATE NOCASE, id")
    suspend fun searchFoods(query: String): List<FoodEntity>
    @Update suspend fun updateFood(value: FoodEntity): Int
    @Query("DELETE FROM calorie_foods WHERE id = :id") suspend fun deleteFood(id: Long): Int

    @Insert suspend fun insertEntry(value: DiaryEntryEntity): Long
    @Query("SELECT * FROM calorie_diary_entries WHERE id = :id") suspend fun entry(id: Long): DiaryEntryEntity?
    @Query("SELECT * FROM calorie_diary_entries WHERE localDate = :date ORDER BY mealType, createdAt, id")
    suspend fun entries(date: String): List<DiaryEntryEntity>
    @Update suspend fun updateEntry(value: DiaryEntryEntity): Int
    @Query("DELETE FROM calorie_diary_entries WHERE id = :id") suspend fun deleteEntry(id: Long): Int

    @Query("""SELECT f.id, f.name, f.caloriesPer100gKcal, f.proteinPer100gMg, f.fatPer100gMg,
        f.carbPer100gMg, f.createdAt, f.updatedAt,
        (SELECT e2.amountGramsMg FROM calorie_diary_entries e2 WHERE e2.foodId=f.id
            ORDER BY e2.createdAt DESC, e2.id DESC LIMIT 1) AS defaultAmountGramsMg,
        MAX(e.createdAt) AS lastUsedAt
        FROM calorie_foods f JOIN calorie_diary_entries e ON e.foodId=f.id
        GROUP BY f.id ORDER BY lastUsedAt DESC, f.id DESC LIMIT :limit""")
    suspend fun recentFoods(limit: Int): List<RecentFoodRow>
}
