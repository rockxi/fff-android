package ru.rockxi.fff.data.calories

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CalorieProfileEntity::class, FoodEntity::class, DiaryEntryEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(CalorieConverters::class)
internal abstract class CalorieDatabase : RoomDatabase() {
    internal abstract fun calorieDao(): CalorieDao

    companion object {
        @Volatile private var instance: CalorieDatabase? = null

        private val callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                seedProfile(db)
                installValidationTriggers(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                seedProfile(db)
                installValidationTriggers(db)
            }
        }

        fun get(context: Context): CalorieDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                CalorieDatabase::class.java,
                "fff-calorie.db",
            ).addCallback(callback).build().also { instance = it }
        }

        fun inMemory(context: Context): CalorieDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, CalorieDatabase::class.java)
                .addCallback(callback)
                .build()

        private fun seedProfile(db: SupportSQLiteDatabase) {
            db.execSQL(
                """INSERT OR IGNORE INTO calorie_profile
                    (id,dailyCaloriesKcal,proteinTargetMg,fatTargetMg,carbTargetMg,updatedAt)
                    VALUES (1,2000,120000,70000,230000,0)""",
            )
        }

        private fun installValidationTriggers(db: SupportSQLiteDatabase) {
            listOf("INSERT" to "insert", "UPDATE" to "update").forEach { (operation, suffix) ->
                db.execSQL("""CREATE TRIGGER IF NOT EXISTS calorie_profile_validate_$suffix BEFORE $operation ON calorie_profile BEGIN
                    SELECT CASE WHEN NEW.id != 1 OR NEW.dailyCaloriesKcal <= 0 OR NEW.dailyCaloriesKcal > 1000000
                        OR NEW.proteinTargetMg < 0 OR NEW.proteinTargetMg > 100000000
                        OR NEW.fatTargetMg < 0 OR NEW.fatTargetMg > 100000000
                        OR NEW.carbTargetMg < 0 OR NEW.carbTargetMg > 100000000
                        THEN RAISE(ABORT, 'invalid calorie profile') END;
                END""")
                db.execSQL("""CREATE TRIGGER IF NOT EXISTS calorie_foods_validate_$suffix BEFORE $operation ON calorie_foods BEGIN
                    SELECT CASE WHEN NEW.name != trim(NEW.name, char(9)||char(10)||char(11)||char(12)||char(13)||' ')
                        OR length(trim(NEW.name, char(9)||char(10)||char(11)||char(12)||char(13)||' ')) < 1
                        OR length(trim(NEW.name, char(9)||char(10)||char(11)||char(12)||char(13)||' ')) > 120
                        OR NEW.caloriesPer100gKcal < 0 OR NEW.caloriesPer100gKcal > 1000000
                        OR NEW.proteinPer100gMg < 0 OR NEW.proteinPer100gMg > 100000000
                        OR NEW.fatPer100gMg < 0 OR NEW.fatPer100gMg > 100000000
                        OR NEW.carbPer100gMg < 0 OR NEW.carbPer100gMg > 100000000
                        OR (NEW.caloriesPer100gKcal = 0 AND NEW.proteinPer100gMg = 0 AND NEW.fatPer100gMg = 0 AND NEW.carbPer100gMg = 0)
                        THEN RAISE(ABORT, 'invalid food') END;
                END""")
                db.execSQL("""CREATE TRIGGER IF NOT EXISTS calorie_entries_validate_$suffix BEFORE $operation ON calorie_diary_entries BEGIN
                    SELECT CASE WHEN length(NEW.localDate) != 10 OR strftime('%Y-%m-%d', NEW.localDate, '+0 days') IS NULL
                        OR strftime('%Y-%m-%d', NEW.localDate, '+0 days') != NEW.localDate THEN RAISE(ABORT, 'invalid local date') END;
                    SELECT CASE WHEN NEW.mealType NOT IN ('BREAKFAST','LUNCH','DINNER','SNACK')
                        OR NEW.displayNameSnapshot != trim(NEW.displayNameSnapshot, char(9)||char(10)||char(11)||char(12)||char(13)||' ')
                        OR length(trim(NEW.displayNameSnapshot, char(9)||char(10)||char(11)||char(12)||char(13)||' ')) < 1
                        OR length(trim(NEW.displayNameSnapshot, char(9)||char(10)||char(11)||char(12)||char(13)||' ')) > 120
                        OR NEW.caloriesKcal < 0 OR NEW.caloriesKcal > 1000000
                        OR NEW.proteinMg < 0 OR NEW.proteinMg > 100000000
                        OR NEW.fatMg < 0 OR NEW.fatMg > 100000000
                        OR NEW.carbMg < 0 OR NEW.carbMg > 100000000
                        OR (NEW.foodId IS NULL AND (NEW.amountGramsMg IS NOT NULL OR NEW.caloriesKcal <= 0))
                        OR (NEW.foodId IS NOT NULL AND (NEW.amountGramsMg IS NULL OR NEW.amountGramsMg <= 0 OR NEW.amountGramsMg > 100000000))
                        THEN RAISE(ABORT, 'invalid diary entry') END;
                END""")
            }
        }
    }
}
