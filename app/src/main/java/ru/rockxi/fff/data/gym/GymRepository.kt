package ru.rockxi.fff.data.gym

import androidx.room.withTransaction
import java.time.LocalDate
import java.time.YearMonth

internal class GymRepository(private val database: GymDatabase) {
    private val dao = database.gymDao()

    suspend fun categories(): List<GymCategoryEntity> = dao.categories()

    suspend fun createCategory(name: String, position: Int = Int.MAX_VALUE): Long {
        val normalized = requiredName(name)
        return dao.insertCategory(GymCategoryEntity(name = normalized, position = position))
    }

    suspend fun updateCategory(id: Long, name: String, position: Int = 0) {
        require(dao.updateCategory(id, requiredName(name), position) == 1) { "Категория не найдена" }
    }

    suspend fun deleteCategory(id: Long) {
        requireNotNull(dao.category(id)) { "Категория не найдена" }
        require(dao.exercises(id).isEmpty()) { "Сначала удалите упражнения категории" }
        require(dao.deleteCategory(id) == 1) { "Категория не найдена" }
    }

    suspend fun exercises(categoryId: Long? = null): List<GymExerciseEntity> =
        categoryId?.let { dao.exercises(it) } ?: dao.exercises()

    suspend fun createExercise(categoryId: Long, name: String, position: Int = Int.MAX_VALUE): Long {
        requireNotNull(dao.category(categoryId)) { "Категория не найдена" }
        return dao.insertExercise(GymExerciseEntity(categoryId = categoryId, name = requiredName(name), position = position))
    }

    suspend fun updateExercise(id: Long, categoryId: Long, name: String, position: Int = 0) {
        requireNotNull(dao.category(categoryId)) { "Категория не найдена" }
        require(dao.updateExercise(id, categoryId, requiredName(name), position) == 1) { "Упражнение не найдено" }
    }

    /** Deleting an exercise explicitly also deletes its sets through the database FK. */
    suspend fun deleteExercise(id: Long) {
        require(dao.deleteExercise(id) == 1) { "Упражнение не найдено" }
    }

    suspend fun ensureWorkoutDay(date: LocalDate): GymWorkoutDayEntity = database.withTransaction {
        val key = date.toString()
        dao.workoutDay(key) ?: run {
            dao.insertWorkoutDay(GymWorkoutDayEntity(key))
            requireNotNull(dao.workoutDay(key))
        }
    }

    suspend fun workoutDay(date: LocalDate): GymWorkoutDayEntity? = dao.workoutDay(date.toString())

    suspend fun workoutDays(month: YearMonth): List<GymWorkoutDayEntity> =
        dao.workoutDays(month.atDay(1).toString(), month.plusMonths(1).atDay(1).toString())

    suspend fun deleteWorkoutDay(date: LocalDate) = database.withTransaction {
        val key = date.toString()
        requireNotNull(dao.workoutDay(key)) { "День тренировки не найден" }
        require(dao.workoutDaySetCount(key) == 0L) { "Сначала удалите подходы этого дня" }
        require(dao.deleteWorkoutDay(key) == 1) { "День тренировки не найден" }
    }

    suspend fun addExternalWeightSet(exerciseId: Long, date: LocalDate, repetitions: Int, weightGrams: Long): Long =
        insertSet(exerciseId, date, repetitions, GymSetMode.EXTERNAL_WEIGHT, weightGrams, null)

    suspend fun addBodyWeightSet(exerciseId: Long, date: LocalDate, repetitions: Int, bodyWeightGrams: Long): Long =
        insertSet(exerciseId, date, repetitions, GymSetMode.BODY_WEIGHT, null, bodyWeightGrams)

    suspend fun updateExternalWeightSet(id: Long, exerciseId: Long, date: LocalDate, repetitions: Int, weightGrams: Long) =
        updateSet(id, exerciseId, date, repetitions, GymSetMode.EXTERNAL_WEIGHT, weightGrams, null)

    suspend fun updateBodyWeightSet(id: Long, exerciseId: Long, date: LocalDate, repetitions: Int, bodyWeightGrams: Long) =
        updateSet(id, exerciseId, date, repetitions, GymSetMode.BODY_WEIGHT, null, bodyWeightGrams)

    suspend fun deleteSet(id: Long) {
        require(dao.deleteSet(id) == 1) { "Подход не найден" }
    }

    suspend fun daySummary(date: LocalDate): List<GymDayExerciseSummary> = dao.daySummary(date.toString())

    /** Latest historical set, with persisted insertion order breaking same-day ties. */
    suspend fun latestSet(exerciseId: Long): GymSetEntity? {
        requireNotNull(dao.exercise(exerciseId)) { "Упражнение не найдено" }
        return dao.latestSet(exerciseId)
    }

    suspend fun exerciseSets(exerciseId: Long, date: LocalDate): List<GymSetWithRecord> {
        requireNotNull(dao.exercise(exerciseId)) { "Упражнение не найдено" }
        val recordSetId = dao.recordSetId(exerciseId)
        return dao.sets(exerciseId, date.toString()).map { set ->
            GymSetWithRecord(set, set.id == recordSetId)
        }
    }

    suspend fun monthActivity(month: YearMonth): List<GymMonthActivity> =
        dao.monthActivity(month.atDay(1).toString(), month.plusMonths(1).atDay(1).toString())

    private suspend fun insertSet(exerciseId: Long, date: LocalDate, repetitions: Int, mode: GymSetMode, weight: Long?, bodyWeight: Long?): Long = database.withTransaction {
        validateSet(exerciseId, repetitions, mode, weight, bodyWeight)
        ensureWorkoutDay(date)
        dao.insertSet(GymSetEntity(exerciseId = exerciseId, localDate = date.toString(), repetitions = repetitions, mode = mode, weightGrams = weight, bodyWeightGrams = bodyWeight))
    }

    private suspend fun updateSet(id: Long, exerciseId: Long, date: LocalDate, repetitions: Int, mode: GymSetMode, weight: Long?, bodyWeight: Long?) = database.withTransaction {
        requireNotNull(dao.set(id)) { "Подход не найден" }
        validateSet(exerciseId, repetitions, mode, weight, bodyWeight)
        ensureWorkoutDay(date)
        require(dao.updateSet(id, exerciseId, date.toString(), repetitions, mode, weight, bodyWeight) == 1) { "Подход не найден" }
    }

    private suspend fun validateSet(exerciseId: Long, repetitions: Int, mode: GymSetMode, weight: Long?, bodyWeight: Long?) {
        requireNotNull(dao.exercise(exerciseId)) { "Упражнение не найдено" }
        require(repetitions > 0) { "Количество повторений должно быть больше нуля" }
        when (mode) {
            GymSetMode.EXTERNAL_WEIGHT -> require(weight != null && weight > 0 && bodyWeight == null) { "Укажите вес упражнения" }
            GymSetMode.BODY_WEIGHT -> require(bodyWeight != null && bodyWeight > 0 && weight == null) { "Укажите собственный вес" }
        }
    }

    private fun requiredName(value: String): String = value.trim().also { require(it.isNotEmpty()) { "Название обязательно" } }
}
