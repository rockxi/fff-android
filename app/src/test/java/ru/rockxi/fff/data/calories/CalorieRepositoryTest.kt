package ru.rockxi.fff.data.calories

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class CalorieRepositoryTest {
    private lateinit var database: CalorieDatabase
    private lateinit var repository: CalorieRepository
    private var clock = 1_000L

    @Before fun setUp() {
        database = CalorieDatabase.inMemory(ApplicationProvider.getApplicationContext())
        repository = CalorieRepository(database) { clock++ }
    }

    @After fun tearDown() = database.close()

    @Test fun freshProfileIsSeededAndCanBeUpdated() = runBlocking {
        assertEquals(2_000, repository.profile().dailyCaloriesKcal)
        assertEquals(120_000L, repository.profile().proteinTargetMg)
        assertEquals(70_000L, repository.profile().fatTargetMg)
        assertEquals(230_000L, repository.profile().carbTargetMg)

        repository.updateProfile(2_400, 150_000, 80_000, 260_000)
        assertEquals(2_400, repository.profile().dailyCaloriesKcal)
        assertEquals(150_000L, repository.profile().proteinTargetMg)
    }

    @Test fun foodCrudTrimsNamesAndSearchEscapesWildcards() = runBlocking {
        val cottage = repository.createFood(" Творог ", 120, 18_000, 5_000, 3_000)
        val percent = repository.createFood("Йогурт 2%", 60, 4_000, 2_000, 5_000)
        assertEquals(listOf("Йогурт 2%", "Творог"), repository.foods().map { it.name }.sorted())
        assertEquals(percent, repository.foods("2%").single().id)

        repository.updateFood(cottage, "Творог мягкий", 100, 16_000, 4_000, 3_000)
        assertEquals("Творог мягкий", repository.food(cottage)?.name)
        repository.deleteFood(percent)
        assertNull(repository.food(percent))
    }

    @Test fun exact180GramOracleUsesIntegerHalfUpRounding() = runBlocking {
        val food = repository.createFood("Творог", 120, 18_000, 5_000, 3_000)
        val entryId = repository.addFoodEntry(food, LocalDate.of(2026, 9, 12), MealType.BREAKFAST, 180_000)
        val entry = requireNotNull(repository.entry(entryId))

        assertEquals(216, entry.caloriesKcal)
        assertEquals(32_400L, entry.proteinMg)
        assertEquals(9_000L, entry.fatMg)
        assertEquals(5_400L, entry.carbMg)
    }

    @Test fun halfValuesRoundUpWithoutFloatingPoint() = runBlocking {
        val food = repository.createFood("Половинка", 1, 1, 0, 0)
        val nutrition = repository.calculate(requireNotNull(repository.food(food)), 50_000)
        assertEquals(1L, nutrition.caloriesKcal)
        assertEquals(1L, nutrition.proteinMg)
    }

    @Test fun foodEditDoesNotRewriteSnapshotAndNewEntryUsesNewValues() = runBlocking {
        val date = LocalDate.of(2026, 9, 12)
        val food = repository.createFood("Творог", 120, 18_000, 5_000, 3_000)
        val historical = repository.addFoodEntry(food, date, MealType.BREAKFAST, 100_000)

        repository.updateFood(food, "Новый творог", 200, 20_000, 6_000, 4_000)
        val current = repository.addFoodEntry(food, date, MealType.LUNCH, 100_000)

        assertEquals("Творог", repository.entry(historical)?.displayNameSnapshot)
        assertEquals(120, repository.entry(historical)?.caloriesKcal)
        assertEquals("Новый творог", repository.entry(current)?.displayNameSnapshot)
        assertEquals(200, repository.entry(current)?.caloriesKcal)
    }

    @Test fun quickAndCatalogEntriesSupportEditAndDelete() = runBlocking {
        val firstDate = LocalDate.of(2026, 9, 11)
        val secondDate = firstDate.plusDays(1)
        val food = repository.createFood("Яблоко", 52, 300, 200, 14_000)
        val catalog = repository.addFoodEntry(food, firstDate, MealType.SNACK, 150_000)
        val quick = repository.addQuickEntry("Кофе", firstDate, MealType.BREAKFAST, 50, fatMg = 2_000)

        repository.updateFoodEntry(catalog, food, secondDate, MealType.LUNCH, 200_000)
        repository.updateQuickEntry(quick, "Кофе с молоком", secondDate, MealType.SNACK, 70, proteinMg = 3_000)
        assertTrue(repository.entries(firstDate).isEmpty())
        assertEquals(setOf("Яблоко", "Кофе с молоком"), repository.entries(secondDate).map { it.displayNameSnapshot }.toSet())

        repository.deleteEntry(quick)
        assertEquals(listOf(catalog), repository.entries(secondDate).map { it.id })
    }

    @Test fun referencedFoodDeletionIsRejectedUntilEntriesAreDeleted() = runBlocking {
        val food = repository.createFood("Творог", 120, 18_000, 5_000, 3_000)
        val entry = repository.addFoodEntry(food, LocalDate.of(2026, 9, 12), MealType.BREAKFAST, 100_000)
        assertFails { repository.deleteFood(food) }
        assertTrue(repository.food(food) != null)

        repository.deleteEntry(entry)
        repository.deleteFood(food)
        assertNull(repository.food(food))
    }

    @Test fun dayAggregationSeparatesMealsAndDates() = runBlocking {
        val today = LocalDate.of(2026, 9, 12)
        repository.addQuickEntry("Завтрак", today, MealType.BREAKFAST, 200, proteinMg = 10_000)
        repository.addQuickEntry("Обед", today, MealType.LUNCH, 300, fatMg = 12_000)
        repository.addQuickEntry("Вчера", today.minusDays(1), MealType.DINNER, 900)

        val summary = repository.dailySummary(today)
        assertEquals(500L, summary.total.caloriesKcal)
        assertEquals(10_000L, summary.total.proteinMg)
        assertEquals(12_000L, summary.total.fatMg)
        assertEquals(200L, summary.byMeal.getValue(MealType.BREAKFAST).caloriesKcal)
        assertEquals(300L, summary.byMeal.getValue(MealType.LUNCH).caloriesKcal)
        assertEquals(0L, summary.byMeal.getValue(MealType.DINNER).caloriesKcal)
    }

    @Test fun recentsAreLatestFirstAndSupplyLatestAmount() = runBlocking {
        val date = LocalDate.of(2026, 9, 12)
        val first = repository.createFood("Первый", 100, 1_000, 1_000, 1_000)
        val second = repository.createFood("Второй", 100, 1_000, 1_000, 1_000)
        repository.addFoodEntry(first, date, MealType.BREAKFAST, 120_000)
        repository.addFoodEntry(second, date, MealType.LUNCH, 150_000)
        repository.addFoodEntry(first, date, MealType.SNACK, 180_000)

        val recents = repository.recentFoods()
        assertEquals(listOf(first, second), recents.map { it.food.id })
        assertEquals(180_000L, recents.first().defaultAmountGramsMg)
        assertEquals(150_000L, recents.last().defaultAmountGramsMg)
    }

    @Test fun strictBoundsRejectInvalidWritesWithoutPartialPersistence() = runBlocking {
        val date = LocalDate.of(2026, 9, 12)
        assertFails { repository.createFood(" ", 1, 0, 0, 0) }
        assertFails { repository.createFood("Ноль", 0, 0, 0, 0) }
        assertFails { repository.createFood("Минус", -1, 0, 0, 0) }
        assertFails { repository.addQuickEntry("Quick", date, MealType.LUNCH, 0) }
        assertFails { repository.addQuickEntry("Quick", date, MealType.LUNCH, 1, proteinMg = -1) }
        assertFails { repository.updateProfile(0, 0, 0, 0) }

        val huge = repository.createFood("Очень плотный", 1_000_000, 100_000_000, 0, 0)
        assertFails { repository.addFoodEntry(huge, date, MealType.DINNER, 100_000_000) }
        assertFails { repository.addFoodEntry(huge, date, MealType.DINNER, 0) }
        assertTrue(repository.entries(date).isEmpty())
    }

    @Test fun sqliteRejectsImpossibleDateAndMismatchedQuickShape() = runBlocking {
        assertFails {
            database.calorieDao().insertEntry(
                DiaryEntryEntity(
                    localDate = "2026-02-30",
                    mealType = MealType.SNACK,
                    foodId = null,
                    displayNameSnapshot = "Ошибка",
                    amountGramsMg = 10,
                    caloriesKcal = 1,
                    proteinMg = 0,
                    fatMg = 0,
                    carbMg = 0,
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
        }
    }

    @Test fun sqliteRejectsUnknownMealTypeBeforeConverterCanReadIt() {
        assertFailsBlocking {
            database.openHelper.writableDatabase.execSQL(
                """INSERT INTO calorie_diary_entries
                    (localDate,mealType,foodId,displayNameSnapshot,amountGramsMg,caloriesKcal,proteinMg,fatMg,carbMg,createdAt,updatedAt)
                    VALUES ('2026-09-12','BRUNCH',NULL,'Бранч',NULL,100,0,0,0,1,1)""",
            )
        }
    }

    @Test fun sqliteRejectsUnknownMealTypeUpdateAndKeepsValidRowReadable() = runBlocking {
        val entryId = repository.addQuickEntry(
            "Обычная запись",
            LocalDate.of(2026, 9, 12),
            MealType.LUNCH,
            100,
        )

        assertFailsBlocking {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE calorie_diary_entries SET mealType='BRUNCH' WHERE id=?",
                arrayOf(entryId),
            )
        }

        val unchanged = requireNotNull(repository.entry(entryId))
        assertEquals(MealType.LUNCH, unchanged.mealType)
        assertEquals("Обычная запись", unchanged.displayNameSnapshot)
    }

    @Test fun sqliteRequiresCanonicalTrimmedStoredNames() = runBlocking {
        assertFails {
            database.calorieDao().insertFood(
                FoodEntity(
                    name = " Не trim ",
                    caloriesPer100gKcal = 1,
                    proteinPer100gMg = 0,
                    fatPer100gMg = 0,
                    carbPer100gMg = 0,
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
        }
    }

    @Test fun sqliteCanonicalNamesRejectTabsAndNewlinesAtBoundaries() = runBlocking {
        listOf("\tТаб", "Таб\t", "\nСтрока", "Строка\r\n").forEach { invalidName ->
            assertFails {
                database.calorieDao().insertFood(
                    FoodEntity(
                        name = invalidName,
                        caloriesPer100gKcal = 1,
                        proteinPer100gMg = 0,
                        fatPer100gMg = 0,
                        carbPer100gMg = 0,
                        createdAt = 1,
                        updatedAt = 1,
                    ),
                )
            }
        }

        listOf("\tСнимок", "Снимок\n").forEach { invalidSnapshot ->
            assertFails {
                database.calorieDao().insertEntry(
                    DiaryEntryEntity(
                        localDate = "2026-09-12",
                        mealType = MealType.SNACK,
                        foodId = null,
                        displayNameSnapshot = invalidSnapshot,
                        amountGramsMg = null,
                        caloriesKcal = 1,
                        proteinMg = 0,
                        fatMg = 0,
                        carbMg = 0,
                        createdAt = 1,
                        updatedAt = 1,
                    ),
                )
            }
        }
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("Expected operation to fail", failed)
    }

    private fun assertFailsBlocking(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("Expected operation to fail", failed)
    }
}
