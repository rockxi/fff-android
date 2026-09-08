package ru.rockxi.fff.data.gym

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface GymDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategories(values: List<GymCategoryEntity>): List<Long>

    @Insert suspend fun insertCategory(value: GymCategoryEntity): Long
    @Query("SELECT * FROM gym_categories WHERE id = :id") suspend fun category(id: Long): GymCategoryEntity?
    @Query("SELECT * FROM gym_categories ORDER BY position, name, id") suspend fun categories(): List<GymCategoryEntity>
    @Query("UPDATE gym_categories SET name = :name, position = :position WHERE id = :id") suspend fun updateCategory(id: Long, name: String, position: Int): Int
    @Query("DELETE FROM gym_categories WHERE id = :id") suspend fun deleteCategory(id: Long): Int

    @Insert suspend fun insertExercise(value: GymExerciseEntity): Long
    @Query("SELECT * FROM gym_exercises WHERE id = :id") suspend fun exercise(id: Long): GymExerciseEntity?
    @Query("SELECT * FROM gym_exercises ORDER BY position, name, id") suspend fun exercises(): List<GymExerciseEntity>
    @Query("SELECT * FROM gym_exercises WHERE categoryId = :categoryId ORDER BY position, name, id") suspend fun exercises(categoryId: Long): List<GymExerciseEntity>
    @Query("UPDATE gym_exercises SET categoryId = :categoryId, name = :name, position = :position WHERE id = :id") suspend fun updateExercise(id: Long, categoryId: Long, name: String, position: Int): Int
    @Query("DELETE FROM gym_exercises WHERE id = :id") suspend fun deleteExercise(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertWorkoutDay(value: GymWorkoutDayEntity): Long
    @Query("SELECT * FROM gym_workout_days WHERE localDate = :localDate") suspend fun workoutDay(localDate: String): GymWorkoutDayEntity?
    @Query("SELECT * FROM gym_workout_days WHERE localDate >= :fromDate AND localDate < :untilDate ORDER BY localDate")
    suspend fun workoutDays(fromDate: String, untilDate: String): List<GymWorkoutDayEntity>
    @Query("SELECT COUNT(*) FROM gym_sets WHERE localDate = :localDate") suspend fun workoutDaySetCount(localDate: String): Long
    @Query("DELETE FROM gym_workout_days WHERE localDate = :localDate") suspend fun deleteWorkoutDay(localDate: String): Int

    @Insert suspend fun insertSet(value: GymSetEntity): Long
    @Query("SELECT * FROM gym_sets WHERE id = :id") suspend fun set(id: Long): GymSetEntity?
    @Query("SELECT * FROM gym_sets WHERE exerciseId = :exerciseId AND localDate = :localDate ORDER BY createdAt, id")
    suspend fun sets(exerciseId: Long, localDate: String): List<GymSetEntity>
    @Query("SELECT * FROM gym_sets WHERE localDate = :localDate ORDER BY createdAt, id") suspend fun sets(localDate: String): List<GymSetEntity>
    @Query("UPDATE gym_sets SET exerciseId=:exerciseId, localDate=:localDate, repetitions=:repetitions, mode=:mode, weightGrams=:weightGrams, bodyWeightGrams=:bodyWeightGrams WHERE id=:id")
    suspend fun updateSet(id: Long, exerciseId: Long, localDate: String, repetitions: Int, mode: GymSetMode, weightGrams: Long?, bodyWeightGrams: Long?): Int
    @Query("DELETE FROM gym_sets WHERE id = :id") suspend fun deleteSet(id: Long): Int
    @Query("SELECT MAX(CASE WHEN mode='BODY_WEIGHT' THEN bodyWeightGrams ELSE weightGrams END) FROM gym_sets WHERE exerciseId=:exerciseId AND repetitions=:repetitions")
    suspend fun recordWeight(exerciseId: Long, repetitions: Int): Long?

    @Query("""SELECT e.id AS exerciseId, e.name AS exerciseName, c.id AS categoryId, c.name AS categoryName,
        COUNT(s.id) AS setCount, COALESCE(SUM(s.repetitions), 0) AS totalRepetitions
        FROM gym_sets s JOIN gym_exercises e ON e.id=s.exerciseId JOIN gym_categories c ON c.id=e.categoryId
        WHERE s.localDate=:localDate GROUP BY e.id ORDER BY MIN(s.createdAt), MIN(s.id)""")
    suspend fun daySummary(localDate: String): List<GymDayExerciseSummary>

    @Query("""SELECT d.localDate AS localDate, COUNT(DISTINCT s.exerciseId) AS exerciseCount, COUNT(s.id) AS setCount,
        COALESCE(SUM(s.repetitions), 0) AS totalRepetitions FROM gym_workout_days d
        LEFT JOIN gym_sets s ON s.localDate=d.localDate
        WHERE d.localDate >= :fromDate AND d.localDate < :untilDate GROUP BY d.localDate ORDER BY d.localDate""")
    suspend fun monthActivity(fromDate: String, untilDate: String): List<GymMonthActivity>
}
