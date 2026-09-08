package ru.rockxi.fff.data.gym

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [GymCategoryEntity::class, GymExerciseEntity::class, GymWorkoutDayEntity::class, GymSetEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(GymConverters::class)
internal abstract class GymDatabase : RoomDatabase() {
    internal abstract fun gymDao(): GymDao

    companion object {
        internal val defaultCategoryNames = listOf("Грудь", "Спина", "Плечи", "Ноги", "Руки", "Пресс", "Кардио")
        @Volatile private var instance: GymDatabase? = null

        private val callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                seedCategories(db)
                installValidationTriggers(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                seedCategories(db)
                installValidationTriggers(db)
            }
        }

        fun get(context: Context): GymDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, GymDatabase::class.java, "fff-gym.db")
                .addCallback(callback)
                .build()
                .also { instance = it }
        }

        fun inMemory(context: Context): GymDatabase = Room.inMemoryDatabaseBuilder(context.applicationContext, GymDatabase::class.java)
            .addCallback(callback)
            .build()

        private fun seedCategories(db: SupportSQLiteDatabase) {
            defaultCategoryNames.forEachIndexed { index, name ->
                db.execSQL("INSERT OR IGNORE INTO gym_categories(name,position) VALUES (?,?)", arrayOf(name, index))
            }
        }

        private fun installValidationTriggers(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS gym_workout_days_validate_insert BEFORE INSERT ON gym_workout_days BEGIN
                SELECT CASE WHEN length(NEW.localDate) != 10 OR strftime('%Y-%m-%d', NEW.localDate, '+0 days') IS NULL OR strftime('%Y-%m-%d', NEW.localDate, '+0 days') != NEW.localDate THEN RAISE(ABORT, 'invalid local date') END;
            END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS gym_workout_days_validate_update BEFORE UPDATE ON gym_workout_days BEGIN
                SELECT CASE WHEN length(NEW.localDate) != 10 OR strftime('%Y-%m-%d', NEW.localDate, '+0 days') IS NULL OR strftime('%Y-%m-%d', NEW.localDate, '+0 days') != NEW.localDate THEN RAISE(ABORT, 'invalid local date') END;
            END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS gym_sets_validate_insert BEFORE INSERT ON gym_sets BEGIN
                SELECT CASE WHEN length(NEW.localDate) != 10 OR strftime('%Y-%m-%d', NEW.localDate, '+0 days') IS NULL OR strftime('%Y-%m-%d', NEW.localDate, '+0 days') != NEW.localDate THEN RAISE(ABORT, 'invalid local date') END;
                SELECT CASE WHEN NEW.repetitions <= 0 THEN RAISE(ABORT, 'repetitions must be positive') END;
                SELECT CASE WHEN NEW.mode = 'EXTERNAL_WEIGHT' AND (NEW.weightGrams IS NULL OR NEW.weightGrams <= 0 OR NEW.bodyWeightGrams IS NOT NULL) THEN RAISE(ABORT, 'invalid external weight') END;
                SELECT CASE WHEN NEW.mode = 'BODY_WEIGHT' AND (NEW.bodyWeightGrams IS NULL OR NEW.bodyWeightGrams <= 0 OR NEW.weightGrams IS NOT NULL) THEN RAISE(ABORT, 'invalid body weight') END;
                SELECT CASE WHEN NEW.mode NOT IN ('EXTERNAL_WEIGHT','BODY_WEIGHT') THEN RAISE(ABORT, 'invalid set mode') END;
            END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS gym_sets_validate_update BEFORE UPDATE ON gym_sets BEGIN
                SELECT CASE WHEN length(NEW.localDate) != 10 OR strftime('%Y-%m-%d', NEW.localDate, '+0 days') IS NULL OR strftime('%Y-%m-%d', NEW.localDate, '+0 days') != NEW.localDate THEN RAISE(ABORT, 'invalid local date') END;
                SELECT CASE WHEN NEW.repetitions <= 0 THEN RAISE(ABORT, 'repetitions must be positive') END;
                SELECT CASE WHEN NEW.mode = 'EXTERNAL_WEIGHT' AND (NEW.weightGrams IS NULL OR NEW.weightGrams <= 0 OR NEW.bodyWeightGrams IS NOT NULL) THEN RAISE(ABORT, 'invalid external weight') END;
                SELECT CASE WHEN NEW.mode = 'BODY_WEIGHT' AND (NEW.bodyWeightGrams IS NULL OR NEW.bodyWeightGrams <= 0 OR NEW.weightGrams IS NOT NULL) THEN RAISE(ABORT, 'invalid body weight') END;
                SELECT CASE WHEN NEW.mode NOT IN ('EXTERNAL_WEIGHT','BODY_WEIGHT') THEN RAISE(ABORT, 'invalid set mode') END;
            END""")
        }
    }
}
