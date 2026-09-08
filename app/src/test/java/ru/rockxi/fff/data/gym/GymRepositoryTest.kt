package ru.rockxi.fff.data.gym

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
class GymRepositoryTest {
    private lateinit var database: GymDatabase
    private lateinit var repository: GymRepository

    @Before fun setUp() {
        database = GymDatabase.inMemory(ApplicationProvider.getApplicationContext())
        repository = GymRepository(database)
    }

    @After fun tearDown() = database.close()

    @Test fun freshDatabaseSeedsSevenCategoriesIdempotently() = runBlocking {
        assertEquals(GymDatabase.defaultCategoryNames, repository.categories().map { it.name })

        database.openHelper.writableDatabase.execSQL(
            "INSERT OR IGNORE INTO gym_categories(name,position) VALUES ('Грудь',99)",
        )
        assertEquals(GymDatabase.defaultCategoryNames, repository.categories().map { it.name })
    }

    @Test fun exerciseCrudCanMoveBetweenCategoriesAndProtectsCategoryDeletion() = runBlocking {
        val categories = repository.categories().associateBy { it.name }
        val exercise = repository.createExercise(categories.getValue("Грудь").id, " Жим лёжа ")
        assertEquals("Жим лёжа", repository.exercises(categories.getValue("Грудь").id).single().name)

        assertFails { repository.deleteCategory(categories.getValue("Грудь").id) }
        repository.updateExercise(exercise, categories.getValue("Руки").id, "Жим узким хватом", 3)
        assertTrue(repository.exercises(categories.getValue("Грудь").id).isEmpty())
        assertEquals("Жим узким хватом", repository.exercises(categories.getValue("Руки").id).single().name)
    }

    @Test fun validatesExternalAndBodyWeightSetsAndSupportsEditing() = runBlocking {
        val exercise = repository.createExercise(repository.categories().first().id, "Жим")
        val day = LocalDate.of(2026, 9, 8)
        val external = repository.addExternalWeightSet(exercise, day, 8, 80_500)
        val body = repository.addBodyWeightSet(exercise, day, 12, 74_250)

        repository.updateExternalWeightSet(body, exercise, day, 10, 20_000)
        val sets = repository.exerciseSets(exercise, day).map { it.set }
        assertEquals(listOf(external, body), sets.map { it.id })
        assertEquals(listOf(GymSetMode.EXTERNAL_WEIGHT, GymSetMode.EXTERNAL_WEIGHT), sets.map { it.mode })
        assertEquals(20_000L, sets.last().weightGrams)
        assertEquals(null, sets.last().bodyWeightGrams)

        assertFails { repository.addExternalWeightSet(exercise, day, 0, 1_000) }
        assertFails { repository.addExternalWeightSet(exercise, day, 1, 0) }
        assertFails { repository.addBodyWeightSet(exercise, day, 1, -1) }
        assertFails { repository.addExternalWeightSet(Long.MAX_VALUE, day, 1, 1_000) }
    }

    @Test fun sqliteTriggerRejectsInvalidSetsOutsideRepository() = runBlocking {
        val exercise = repository.createExercise(repository.categories().first().id, "Тяга")
        assertFails {
            database.gymDao().insertSet(
                GymSetEntity(
                    exerciseId = exercise,
                    localDate = "2026-09-08",
                    repetitions = 5,
                    mode = GymSetMode.EXTERNAL_WEIGHT,
                    weightGrams = 100_000,
                    bodyWeightGrams = 75_000,
                ),
            )
        }
        assertFails {
            database.gymDao().insertSet(
                GymSetEntity(
                    exerciseId = exercise,
                    localDate = "08.09.2026",
                    repetitions = 5,
                    mode = GymSetMode.EXTERNAL_WEIGHT,
                    weightGrams = 100_000,
                ),
            )
        }
    }

    @Test fun exerciseHasOneRecordAcrossAllRepCountsAndEqualWeightPrefersMoreRepetitions() = runBlocking {
        val category = repository.categories().first().id
        val bench = repository.createExercise(category, "Жим")
        val other = repository.createExercise(category, "Другой жим")
        val earlier = LocalDate.of(2026, 8, 1)
        val today = LocalDate.of(2026, 9, 8)
        repository.addExternalWeightSet(bench, earlier, 8, 80_000)
        repository.addExternalWeightSet(bench, earlier, 10, 90_000)
        repository.addExternalWeightSet(other, earlier, 8, 200_000)
        repository.addBodyWeightSet(bench, today, 10, 95_000)
        repository.addBodyWeightSet(bench, today, 8, 95_000)
        repository.addExternalWeightSet(bench, today, 12, 94_000)

        val records = repository.exerciseSets(bench, today)
        assertEquals(listOf(true, false, false), records.map { it.isAllTimeRecord })
        assertEquals(1, records.count { it.isAllTimeRecord })
    }

    @Test fun exactRecordTieKeepsEarliestSetAndEditDeleteRecomputeWinner() = runBlocking {
        val exercise = repository.createExercise(repository.categories().first().id, "Тяга")
        val earlier = LocalDate.of(2026, 9, 7)
        val today = earlier.plusDays(1)
        val first = repository.addExternalWeightSet(exercise, earlier, 8, 100_000)
        val second = repository.addBodyWeightSet(exercise, today, 8, 100_000)

        assertTrue(repository.exerciseSets(exercise, earlier).single().isAllTimeRecord)
        assertFalse(repository.exerciseSets(exercise, today).single().isAllTimeRecord)

        repository.updateBodyWeightSet(second, exercise, today, 9, 100_000)
        assertFalse(repository.exerciseSets(exercise, earlier).single().isAllTimeRecord)
        assertTrue(repository.exerciseSets(exercise, today).single().isAllTimeRecord)

        repository.deleteSet(second)
        assertTrue(repository.exerciseSets(exercise, earlier).single().isAllTimeRecord)
        assertEquals(first, repository.exerciseSets(exercise, earlier).single().set.id)
    }

    @Test fun latestSetUsesNewestDateAndStableInsertionOrderWithinDay() = runBlocking {
        val exercise = repository.createExercise(repository.categories().first().id, "Присед")
        val earlier = LocalDate.of(2026, 9, 7)
        val latest = earlier.plusDays(1)
        repository.addExternalWeightSet(exercise, earlier, 10, 120_000)
        repository.addExternalWeightSet(exercise, latest, 8, 125_000)
        val newest = repository.addBodyWeightSet(exercise, latest, 12, 95_000)

        assertEquals(newest, repository.latestSet(exercise)?.id)
        assertEquals(null, repository.latestSet(repository.createExercise(repository.categories().first().id, "Пустое")))
        assertFails { repository.latestSet(Long.MAX_VALUE) }
    }

    @Test fun dayAndMonthProjectionsSupportTodayAndCalendarScreens() = runBlocking {
        val category = repository.categories().first().id
        val bench = repository.createExercise(category, "Жим")
        val fly = repository.createExercise(category, "Разводка")
        val first = LocalDate.of(2026, 9, 8)
        repository.addExternalWeightSet(bench, first, 8, 80_000)
        repository.addExternalWeightSet(bench, first, 7, 82_000)
        repository.addExternalWeightSet(fly, first, 12, 15_000)
        repository.addExternalWeightSet(bench, first.plusDays(1), 5, 90_000)

        val summary = repository.daySummary(first)
        assertEquals(listOf("Жим", "Разводка"), summary.map { it.exerciseName })
        assertEquals(listOf(2L, 1L), summary.map { it.setCount })
        assertEquals(listOf(15L, 12L), summary.map { it.totalRepetitions })

        val activity = repository.monthActivity(YearMonth.of(2026, 9))
        assertEquals(listOf("2026-09-08", "2026-09-09"), activity.map { it.localDate })
        assertEquals(listOf(3L, 1L), activity.map { it.setCount })
        assertEquals(listOf(2L, 1L), activity.map { it.exerciseCount })
    }

    @Test fun emptyWorkoutDayPersistsAndAppearsInMonthUntilExplicitlyDeleted() = runBlocking {
        val empty = LocalDate.of(2026, 9, 3)
        repository.ensureWorkoutDay(empty)
        repository.ensureWorkoutDay(empty)

        assertEquals(empty.toString(), repository.workoutDay(empty)?.localDate)
        val activity = repository.monthActivity(YearMonth.of(2026, 9)).single()
        assertEquals(0L, activity.exerciseCount)
        assertEquals(0L, activity.setCount)
        assertEquals(0L, activity.totalRepetitions)

        repository.deleteWorkoutDay(empty)
        assertEquals(null, repository.workoutDay(empty))
    }

    @Test fun deletingLastSetRetainsDayAndSetMoveCreatesTargetDay() = runBlocking {
        val exercise = repository.createExercise(repository.categories().first().id, "Жим")
        val first = LocalDate.of(2026, 9, 7)
        val second = first.plusDays(1)
        val set = repository.addExternalWeightSet(exercise, first, 8, 80_000)
        repository.updateExternalWeightSet(set, exercise, second, 8, 80_000)

        assertTrue(repository.workoutDay(first) != null)
        assertTrue(repository.workoutDay(second) != null)
        assertFails { repository.deleteWorkoutDay(second) }
        repository.deleteSet(set)
        assertTrue(repository.workoutDay(second) != null)
        assertEquals(setOf(first.toString(), second.toString()), repository.workoutDays(YearMonth.of(2026, 9)).map { it.localDate }.toSet())
    }

    @Test fun workoutDayForeignKeyRejectsOrphanSet() = runBlocking {
        val exercise = repository.createExercise(repository.categories().first().id, "Жим")
        assertFails {
            database.gymDao().insertSet(
                GymSetEntity(
                    exerciseId = exercise,
                    localDate = "2026-09-08",
                    repetitions = 8,
                    mode = GymSetMode.EXTERNAL_WEIGHT,
                    weightGrams = 80_000,
                ),
            )
        }
    }

    @Test fun sqliteRejectsNonCanonicalOrImpossibleWorkoutDayDates() = runBlocking {
        listOf("2026-9-01", "01.09.2026", "2026-02-30", "not-a-date").forEach { invalid ->
            assertFails { database.gymDao().insertWorkoutDay(GymWorkoutDayEntity(invalid)) }
        }
        database.gymDao().insertWorkoutDay(GymWorkoutDayEntity("2026-09-01"))
        assertEquals("2026-09-01", database.gymDao().workoutDay("2026-09-01")?.localDate)
    }

    @Test fun repetitionAggregatesUseLongBeyondIntRange() = runBlocking {
        val exercise = repository.createExercise(repository.categories().first().id, "Жим")
        val date = LocalDate.of(2026, 9, 8)
        repository.addExternalWeightSet(exercise, date, Int.MAX_VALUE, 1)
        repository.addExternalWeightSet(exercise, date, Int.MAX_VALUE, 1)

        assertEquals(4_294_967_294L, repository.daySummary(date).single().totalRepetitions)
        assertEquals(4_294_967_294L, repository.monthActivity(YearMonth.of(2026, 9)).single().totalRepetitions)
    }

    @Test fun deletingSetAndExerciseIsExplicit() = runBlocking {
        val exercise = repository.createExercise(repository.categories().first().id, "Жим")
        val set = repository.addExternalWeightSet(exercise, LocalDate.of(2026, 9, 8), 8, 80_000)
        repository.deleteSet(set)
        assertTrue(repository.exerciseSets(exercise, LocalDate.of(2026, 9, 8)).isEmpty())
        repository.addExternalWeightSet(exercise, LocalDate.of(2026, 9, 8), 8, 80_000)
        repository.deleteExercise(exercise)
        assertTrue(repository.exercises().isEmpty())
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("Expected operation to fail", failed)
    }
}
