package ru.rockxi.fff.data.finance

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [AccountEntity::class, BudgetEntity::class, BudgetAllocationEntity::class, CategoryEntity::class, LedgerEntryEntity::class], version = 4, exportSchema = false)
@TypeConverters(FinanceConverters::class)
internal abstract class FinanceDatabase : RoomDatabase() {
    internal abstract fun financeDao(): FinanceDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `budgets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `currency` TEXT NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_budgets_name` ON `budgets` (`name`)")
                seedBudgets(db)
                db.execSQL("CREATE TABLE IF NOT EXISTS `budget_allocations` (`budgetId` INTEGER NOT NULL, `month` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, PRIMARY KEY(`budgetId`, `month`), FOREIGN KEY(`budgetId`) REFERENCES `budgets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_budget_allocations_budgetId` ON `budget_allocations` (`budgetId`)")
                db.execSQL("DROP TRIGGER IF EXISTS ledger_entries_validate_insert")
                db.execSQL("ALTER TABLE ledger_entries RENAME TO ledger_entries_v1")
                db.execSQL("ALTER TABLE categories RENAME TO categories_v1")
                db.execSQL("INSERT OR IGNORE INTO budgets(name,currency) SELECT 'Общие (' || a.currency || ')', a.currency FROM ledger_entries_v1 e JOIN accounts a ON a.id=e.accountId WHERE e.kind='EXPENSE' AND e.categoryId IS NOT NULL AND a.currency != 'RUB' GROUP BY a.currency")
                db.execSQL("CREATE TEMP TABLE category_primary AS SELECT c.id AS oldCategoryId, COALESCE((SELECT a.currency FROM ledger_entries_v1 e JOIN accounts a ON a.id=e.accountId WHERE e.categoryId=c.id AND e.kind='EXPENSE' ORDER BY CASE WHEN a.currency='RUB' THEN 0 ELSE 1 END, a.currency LIMIT 1),'RUB') AS currency FROM categories_v1 c")
                db.execSQL("CREATE TEMP TABLE category_extra(seq INTEGER PRIMARY KEY AUTOINCREMENT, oldCategoryId INTEGER NOT NULL, currency TEXT NOT NULL, newCategoryId INTEGER)")
                db.execSQL("INSERT INTO category_extra(oldCategoryId,currency) SELECT c.id,a.currency FROM categories_v1 c JOIN ledger_entries_v1 e ON e.categoryId=c.id AND e.kind='EXPENSE' JOIN accounts a ON a.id=e.accountId JOIN category_primary p ON p.oldCategoryId=c.id WHERE a.currency != p.currency GROUP BY c.id,a.currency ORDER BY c.id,a.currency")
                allocateCloneIds(db)
                db.execSQL("CREATE TABLE categories (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `archived` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `budgetId` INTEGER NOT NULL DEFAULT 2, FOREIGN KEY(`budgetId`) REFERENCES `budgets`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX index_categories_budgetId ON categories(budgetId)")
                db.execSQL("INSERT INTO categories(id,name,kind,archived,createdAt,budgetId) SELECT c.id,c.name,c.kind,c.archived,c.createdAt,CASE WHEN p.currency='RUB' THEN 2 ELSE (SELECT id FROM budgets WHERE name='Общие (' || p.currency || ')' AND currency=p.currency LIMIT 1) END FROM categories_v1 c JOIN category_primary p ON p.oldCategoryId=c.id")
                db.execSQL("INSERT INTO categories(id,name,kind,archived,createdAt,budgetId) SELECT x.newCategoryId,c.name || ' (' || x.currency || ')',c.kind,c.archived,c.createdAt,(SELECT id FROM budgets WHERE name='Общие (' || x.currency || ')' AND currency=x.currency LIMIT 1) FROM category_extra x JOIN categories_v1 c ON c.id=x.oldCategoryId")
                db.execSQL("CREATE TABLE ledger_entries (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `accountId` INTEGER NOT NULL, `transferAccountId` INTEGER, `categoryId` INTEGER, `note` TEXT NOT NULL, `occurredAt` INTEGER NOT NULL, FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`transferAccountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("INSERT INTO ledger_entries SELECT e.id,e.kind,e.amountMinor,e.accountId,e.transferAccountId,CASE WHEN e.kind='EXPENSE' THEN COALESCE((SELECT x.newCategoryId FROM category_extra x JOIN accounts a ON a.id=e.accountId WHERE x.oldCategoryId=e.categoryId AND x.currency=a.currency),e.categoryId) ELSE e.categoryId END,e.note,e.occurredAt FROM ledger_entries_v1 e")
                db.execSQL("DROP TABLE ledger_entries_v1")
                db.execSQL("DROP TABLE categories_v1")
                db.execSQL("DROP TABLE category_extra")
                db.execSQL("DROP TABLE category_primary")
                db.execSQL("CREATE INDEX index_ledger_entries_accountId ON ledger_entries(accountId)")
                db.execSQL("CREATE INDEX index_ledger_entries_transferAccountId ON ledger_entries(transferAccountId)")
                db.execSQL("CREATE INDEX index_ledger_entries_categoryId ON ledger_entries(categoryId)")
                db.execSQL("CREATE INDEX index_ledger_entries_occurredAt ON ledger_entries(occurredAt)")
                installInvariantTrigger(db)
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE budgets ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE categories ADD COLUMN emoji TEXT NOT NULL DEFAULT '🏷️'")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TRIGGER IF EXISTS ledger_entries_validate_insert")
                db.execSQL("CREATE TABLE categories_v4 (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `archived` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `budgetId` INTEGER, `emoji` TEXT NOT NULL DEFAULT '🏷️', FOREIGN KEY(`budgetId`) REFERENCES `budgets`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("INSERT INTO categories_v4(id,name,kind,archived,createdAt,budgetId,emoji) SELECT id,name,kind,archived,createdAt,budgetId,emoji FROM categories")
                db.execSQL("DROP TABLE categories")
                db.execSQL("ALTER TABLE categories_v4 RENAME TO categories")
                db.execSQL("CREATE INDEX index_categories_budgetId ON categories(budgetId)")
                installInvariantTrigger(db)
            }
        }
        @Volatile private var instance: FinanceDatabase? = null
        private val invariantCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) = seedBudgets(db)
            override fun onOpen(db: SupportSQLiteDatabase) {
                installInvariantTrigger(db)
            }
        }
        private fun installInvariantTrigger(db: SupportSQLiteDatabase) {
            // Version 3 databases may contain the older trigger that required a
            // category. Reinstall it on open; no table migration is needed
            // because categoryId has always been nullable.
            db.execSQL("DROP TRIGGER IF EXISTS ledger_entries_validate_insert")
            db.execSQL(
                    """CREATE TRIGGER ledger_entries_validate_insert
                    BEFORE INSERT ON ledger_entries BEGIN
                      SELECT CASE WHEN NEW.kind NOT IN ('INCOME','EXPENSE','TRANSFER') THEN RAISE(ABORT, 'invalid entry kind') END;
                      SELECT CASE WHEN NEW.amountMinor <= 0 THEN RAISE(ABORT, 'amount must be positive') END;
                      SELECT CASE WHEN NEW.kind IN ('INCOME','EXPENSE') AND NEW.transferAccountId IS NOT NULL THEN RAISE(ABORT, 'invalid categorized entry') END;
                      SELECT CASE WHEN NEW.kind IN ('INCOME','EXPENSE') AND NEW.categoryId IS NOT NULL AND NOT EXISTS (SELECT 1 FROM categories WHERE id = NEW.categoryId AND kind = NEW.kind) THEN RAISE(ABORT, 'category kind mismatch') END;
                      SELECT CASE WHEN NEW.kind = 'EXPENSE' AND NEW.categoryId IS NOT NULL AND EXISTS (SELECT 1 FROM categories WHERE id = NEW.categoryId AND budgetId IS NOT NULL) AND NOT EXISTS (SELECT 1 FROM categories c JOIN budgets b ON b.id = c.budgetId JOIN accounts a ON a.id = NEW.accountId WHERE c.id = NEW.categoryId AND b.currency = a.currency) THEN RAISE(ABORT, 'budget currency mismatch') END;
                      SELECT CASE WHEN NEW.kind = 'TRANSFER' AND (NEW.categoryId IS NOT NULL OR NEW.transferAccountId IS NULL OR NEW.accountId = NEW.transferAccountId) THEN RAISE(ABORT, 'invalid transfer') END;
                    END""".trimIndent(),
            )
        }
        fun get(context: Context): FinanceDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, FinanceDatabase::class.java, "fff-finance.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).addCallback(invariantCallback).build().also { instance = it }
        }

        fun inMemory(context: Context): FinanceDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, FinanceDatabase::class.java)
                .addCallback(invariantCallback).build()

        private fun seedBudgets(db: SupportSQLiteDatabase) {
            db.execSQL("INSERT OR IGNORE INTO budgets(id,name,currency) VALUES (1,'Аринка','RUB'),(2,'Общие','RUB'),(3,'Ежедневные','RUB')")
        }

        private fun allocateCloneIds(db: SupportSQLiteDatabase) {
            var candidate = Long.MIN_VALUE
            db.query("SELECT seq FROM category_extra ORDER BY seq").use { extras ->
                while (extras.moveToNext()) {
                    while (db.query("SELECT 1 FROM categories_v1 WHERE id=? LIMIT 1", arrayOf(candidate.toString())).use { it.moveToFirst() }) {
                        if (candidate == Long.MAX_VALUE) throw android.database.sqlite.SQLiteException("No category IDs available for migration")
                        candidate++
                    }
                    db.execSQL("UPDATE category_extra SET newCategoryId=? WHERE seq=?", arrayOf(candidate, extras.getLong(0)))
                    if (candidate == Long.MAX_VALUE && !extras.isLast) throw android.database.sqlite.SQLiteException("No category IDs available for migration")
                    if (candidate != Long.MAX_VALUE) candidate++
                }
            }
        }
    }
}
