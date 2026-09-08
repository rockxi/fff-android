package ru.rockxi.fff.data.gym

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class GymSetMode { EXTERNAL_WEIGHT, BODY_WEIGHT }

@Entity(tableName = "gym_categories", indices = [Index(value = ["name"], unique = true)])
data class GymCategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int = 0,
)

@Entity(
    tableName = "gym_exercises",
    foreignKeys = [ForeignKey(
        entity = GymCategoryEntity::class,
        parentColumns = ["id"],
        childColumns = ["categoryId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("categoryId"), Index(value = ["categoryId", "name"], unique = true)],
)
data class GymExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val name: String,
    val position: Int = 0,
)

@Entity(tableName = "gym_workout_days")
data class GymWorkoutDayEntity(
    /** ISO-8601 calendar date (yyyy-MM-dd), independent of device timezone changes. */
    @PrimaryKey val localDate: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "gym_sets",
    foreignKeys = [
        ForeignKey(
            entity = GymExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = GymWorkoutDayEntity::class,
            parentColumns = ["localDate"],
            childColumns = ["localDate"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("exerciseId"), Index("localDate"), Index(value = ["exerciseId", "repetitions"])],
)
data class GymSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: Long,
    /** ISO-8601 calendar date (yyyy-MM-dd), independent of device timezone changes. */
    val localDate: String,
    val repetitions: Int,
    val mode: GymSetMode,
    /** External load in grams. Set only for [GymSetMode.EXTERNAL_WEIGHT]. */
    val weightGrams: Long? = null,
    /** Athlete's body weight in grams. Set only for [GymSetMode.BODY_WEIGHT]. */
    val bodyWeightGrams: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

data class GymDayExerciseSummary(
    val exerciseId: Long,
    val exerciseName: String,
    val categoryId: Long,
    val categoryName: String,
    val setCount: Long,
    val totalRepetitions: Long,
)

data class GymMonthActivity(
    val localDate: String,
    val exerciseCount: Long,
    val setCount: Long,
    val totalRepetitions: Long,
)

data class GymSetWithRecord(val set: GymSetEntity, val isAllTimeRecord: Boolean)
