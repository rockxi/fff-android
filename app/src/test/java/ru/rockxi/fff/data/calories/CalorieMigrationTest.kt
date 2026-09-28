package ru.rockxi.fff.data.calories

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class CalorieMigrationTest {
    @Test fun v1ToV2PreservesLocalHistoryAndAddsExternalTables() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "calorie-v1-v2-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS calorie_profile (id INTEGER NOT NULL PRIMARY KEY, dailyCaloriesKcal INTEGER NOT NULL, proteinTargetMg INTEGER NOT NULL, fatTargetMg INTEGER NOT NULL, carbTargetMg INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS calorie_foods (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, caloriesPer100gKcal INTEGER NOT NULL, proteinPer100gMg INTEGER NOT NULL, fatPer100gMg INTEGER NOT NULL, carbPer100gMg INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_calorie_foods_name ON calorie_foods (name)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS calorie_diary_entries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, localDate TEXT NOT NULL, mealType TEXT NOT NULL, foodId INTEGER, displayNameSnapshot TEXT NOT NULL, amountGramsMg INTEGER, caloriesKcal INTEGER NOT NULL, proteinMg INTEGER NOT NULL, fatMg INTEGER NOT NULL, carbMg INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(foodId) REFERENCES calorie_foods(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_calorie_diary_entries_localDate ON calorie_diary_entries (localDate)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_calorie_diary_entries_foodId ON calorie_diary_entries (foodId)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_calorie_diary_entries_localDate_mealType ON calorie_diary_entries (localDate, mealType)")
                        db.execSQL("INSERT INTO calorie_profile VALUES (1,2000,120000,70000,230000,1)")
                        db.execSQL("INSERT INTO calorie_foods VALUES (4,'Яблоко',52,300,200,14000,1,1)")
                        db.execSQL("INSERT INTO calorie_diary_entries VALUES (9,'2026-09-12','SNACK',4,'Яблоко',100000,52,300,200,14000,1,1)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        helper.writableDatabase
        helper.close()

        val migrated = Room.databaseBuilder(context, CalorieDatabase::class.java, name)
            .addMigrations(CalorieDatabase.MIGRATION_1_2).build()
        try {
            val repo = CalorieRepository(migrated)
            assertEquals(2000, repo.profile().dailyCaloriesKcal)
            assertEquals("Яблоко", repo.food(4)?.name)
            assertEquals(9L, repo.entries(LocalDate.of(2026, 9, 12)).single().id)
            assertTrue(repo.externalEntries(LocalDate.of(2026, 9, 12)).isEmpty())
            assertEquals(0L, repo.externalDayTotal(LocalDate.of(2026, 9, 12)).caloriesKcal)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
