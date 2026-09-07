package ru.rockxi.fff.data.finance

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [AccountEntity::class, CategoryEntity::class, LedgerEntryEntity::class], version = 1, exportSchema = false)
@TypeConverters(FinanceConverters::class)
internal abstract class FinanceDatabase : RoomDatabase() {
    internal abstract fun financeDao(): FinanceDao

    companion object {
        @Volatile private var instance: FinanceDatabase? = null
        private val invariantCallback = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TRIGGER IF NOT EXISTS ledger_entries_validate_insert
                    BEFORE INSERT ON ledger_entries BEGIN
                      SELECT CASE WHEN NEW.kind NOT IN ('INCOME','EXPENSE','TRANSFER') THEN RAISE(ABORT, 'invalid entry kind') END;
                      SELECT CASE WHEN NEW.amountMinor <= 0 THEN RAISE(ABORT, 'amount must be positive') END;
                      SELECT CASE WHEN NEW.kind IN ('INCOME','EXPENSE') AND (NEW.categoryId IS NULL OR NEW.transferAccountId IS NOT NULL) THEN RAISE(ABORT, 'invalid categorized entry') END;
                      SELECT CASE WHEN NEW.kind IN ('INCOME','EXPENSE') AND NOT EXISTS (SELECT 1 FROM categories WHERE id = NEW.categoryId AND kind = NEW.kind) THEN RAISE(ABORT, 'category kind mismatch') END;
                      SELECT CASE WHEN NEW.kind = 'TRANSFER' AND (NEW.categoryId IS NOT NULL OR NEW.transferAccountId IS NULL OR NEW.accountId = NEW.transferAccountId) THEN RAISE(ABORT, 'invalid transfer') END;
                    END""".trimIndent(),
                )
            }
        }
        fun get(context: Context): FinanceDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, FinanceDatabase::class.java, "fff-finance.db")
                .addCallback(invariantCallback).build().also { instance = it }
        }

        fun inMemory(context: Context): FinanceDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, FinanceDatabase::class.java)
                .addCallback(invariantCallback).build()
    }
}
