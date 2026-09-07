package ru.rockxi.fff.data.finance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.YearMonth
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class FinanceRepositoryTest {
    private lateinit var db: FinanceDatabase
    private lateinit var repo: FinanceRepository

    @Before fun setUp() {
        db = FinanceDatabase.inMemory(ApplicationProvider.getApplicationContext())
        repo = FinanceRepository(db)
    }

    @After fun tearDown() = db.close()

    @Test fun accountAndCategoryCrudIncludingArchive() = runBlocking {
        val account = repo.createAccount(" Cash ", "rub", 1_000)
        val category = repo.createCategory(" Food ", CategoryKind.EXPENSE)
        assertEquals("Cash", repo.accounts().single().name)
        assertEquals("RUB", repo.accounts().single().currency)
        assertEquals("Food", repo.categories(CategoryKind.EXPENSE).single().name)

        repo.archiveAccount(account)
        repo.archiveCategory(category)
        assertTrue(repo.accounts().isEmpty())
        assertTrue(repo.categories(CategoryKind.EXPENSE).isEmpty())
        assertEquals(1, repo.accounts(includeArchived = true).size)
        assertEquals(1, repo.categories(CategoryKind.EXPENSE, includeArchived = true).size)
    }

    @Test fun freshDatabaseSeedsNamedRubBudgets() = runBlocking {
        assertEquals(listOf("Аринка", "Общие", "Ежедневные"), repo.budgets().map { it.name })
        assertTrue(repo.budgets().all { it.currency == "RUB" })
    }

    @Test fun allocationSpentRemainingRespectMonthAndBudget() = runBlocking {
        val daily = repo.budgets().single { it.name == "Ежедневные" }
        val common = repo.budgets().single { it.name == "Общие" }
        val account = repo.createAccount("Cash", "RUB", 100_000)
        val food = repo.createCategory("Food", CategoryKind.EXPENSE, daily.id)
        val rent = repo.createCategory("Rent", CategoryKind.EXPENSE, common.id)
        val month = YearMonth.of(2026, 9)
        repo.allocate(daily.id, month, 30_000)
        repo.addExpense(account, food, 5_000, occurredAt = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        repo.addExpense(account, food, 2_000, occurredAt = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        repo.addExpense(account, rent, 9_000, occurredAt = month.atDay(2).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())

        val status = repo.budgetStatus(daily.id, month, ZoneOffset.UTC)
        assertEquals(30_000, status.allocatedMinor)
        assertEquals(5_000, status.spentMinor)
        assertEquals(25_000, status.remainingMinor)
        assertEquals(2_000, repo.budgetStatus(daily.id, month.plusMonths(1), ZoneOffset.UTC).spentMinor)
    }

    @Test fun expenseRequiresAccountAndBudgetCurrencyMatch() = runBlocking {
        val rubBudget = repo.budgets().first()
        val usd = repo.createAccount("USD", "USD", 100)
        val category = repo.createCategory("Food", CategoryKind.EXPENSE, rubBudget.id)
        assertFails { repo.addExpense(usd, category, 1) }
    }

    @Test fun sqliteForeignKeyRejectsDanglingCategoryBudget() = runBlocking {
        assertDatabaseRejects {
            db.financeDao().insertCategory(CategoryEntity(name = "Invalid", kind = CategoryKind.EXPENSE, budgetId = 999_999))
        }
    }

    @Test fun migrationFromV1PreservesDataAndAssignsFallbackBudget() = runBlocking {
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v1-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(sql: androidx.sqlite.db.SupportSQLiteDatabase) {
                    sql.execSQL("CREATE TABLE accounts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, currency TEXT NOT NULL, balanceMinor INTEGER NOT NULL, archived INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                    sql.execSQL("CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, archived INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                    sql.execSQL("CREATE TABLE ledger_entries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, kind TEXT NOT NULL, amountMinor INTEGER NOT NULL, accountId INTEGER NOT NULL, transferAccountId INTEGER, categoryId INTEGER, note TEXT NOT NULL, occurredAt INTEGER NOT NULL, FOREIGN KEY(accountId) REFERENCES accounts(id) ON DELETE RESTRICT, FOREIGN KEY(transferAccountId) REFERENCES accounts(id) ON DELETE RESTRICT, FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE RESTRICT)")
                    sql.execSQL("CREATE INDEX index_ledger_entries_accountId ON ledger_entries(accountId)")
                    sql.execSQL("CREATE INDEX index_ledger_entries_transferAccountId ON ledger_entries(transferAccountId)")
                    sql.execSQL("CREATE INDEX index_ledger_entries_categoryId ON ledger_entries(categoryId)")
                    sql.execSQL("CREATE INDEX index_ledger_entries_occurredAt ON ledger_entries(occurredAt)")
                    sql.execSQL("CREATE TRIGGER ledger_entries_validate_insert BEFORE INSERT ON ledger_entries BEGIN SELECT CASE WHEN NEW.amountMinor <= 0 THEN RAISE(ABORT, 'amount must be positive') END; END")
                    sql.execSQL("INSERT INTO accounts VALUES (7,'Cash','RUB',900,0,1)")
                    sql.execSQL("INSERT INTO accounts VALUES (10,'Dollar','USD',900,0,1)")
                    sql.execSQL("INSERT INTO categories VALUES (${Long.MIN_VALUE},'Occupied negative','EXPENSE',0,1)")
                    sql.execSQL("INSERT INTO categories VALUES (${Long.MAX_VALUE},'Food','EXPENSE',0,1)")
                    sql.execSQL("INSERT INTO ledger_entries VALUES (9,'EXPENSE',100,7,NULL,${Long.MAX_VALUE},'old',1)")
                    sql.execSQL("INSERT INTO ledger_entries VALUES (11,'EXPENSE',50,10,NULL,${Long.MAX_VALUE},'old-usd',1)")
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build(),
        )
        helper.writableDatabase
        helper.close()

        val migrated = Room.databaseBuilder(context, FinanceDatabase::class.java, name)
            .addMigrations(FinanceDatabase.MIGRATION_1_2, FinanceDatabase.MIGRATION_2_3).build()
        try {
            val dao = migrated.financeDao()
            assertEquals(900, dao.account(7)!!.balanceMinor)
            assertEquals("Food", dao.category(Long.MAX_VALUE)!!.name)
            assertEquals(2, dao.category(Long.MAX_VALUE)!!.budgetId)
            val migratedEntries = dao.entries()
            assertEquals(setOf(9L, 11L), migratedEntries.map { it.id }.toSet())
            assertEquals(setOf("old", "old-usd"), migratedEntries.map { it.note }.toSet())
            assertTrue(dao.budgets().map { it.name }.containsAll(listOf("Аринка", "Общие", "Ежедневные", "Общие (USD)")))
            val rubEntry = migratedEntries.single { it.id == 9L }
            val usdEntry = migratedEntries.single { it.id == 11L }
            assertTrue(rubEntry.categoryId != usdEntry.categoryId)
            val rubCategory = dao.category(rubEntry.categoryId!!)!!
            val usdCategory = dao.category(usdEntry.categoryId!!)!!
            assertEquals(Long.MIN_VALUE + 1, usdCategory.id)
            assertEquals("RUB", dao.budget(rubCategory.budgetId)!!.currency)
            assertEquals("USD", dao.budget(usdCategory.budgetId)!!.currency)
            val migratedRepo = FinanceRepository(migrated)
            val january1970 = YearMonth.of(1970, 1)
            assertEquals(100, migratedRepo.budgetStatus(rubCategory.budgetId, january1970, ZoneOffset.UTC).spentMinor)
            assertEquals(50, migratedRepo.budgetStatus(usdCategory.budgetId, january1970, ZoneOffset.UTC).spentMinor)
            assertDatabaseRejects {
                dao.insertEntry(LedgerEntryEntity(kind = EntryKind.EXPENSE, amountMinor = 1, accountId = 10, categoryId = Long.MAX_VALUE))
            }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
            db = FinanceDatabase.inMemory(context)
            repo = FinanceRepository(db)
        }
    }

    @Test fun incomeExpenseTransferBalancesFiltersAndTotals() = runBlocking {
        val cash = repo.createAccount("Cash", "RUB", 10_000)
        val bank = repo.createAccount("Bank", "RUB")
        val salary = repo.createCategory("Salary", CategoryKind.INCOME)
        val food = repo.createCategory("Food", CategoryKind.EXPENSE)
        repo.addIncome(cash, salary, 5_000, occurredAt = 100)
        repo.addExpense(cash, food, 2_000, occurredAt = 200)
        repo.transfer(cash, bank, 4_000, occurredAt = 300)

        val accounts = repo.accounts().associateBy { it.id }
        assertEquals(9_000, accounts.getValue(cash).balanceMinor)
        assertEquals(4_000, accounts.getValue(bank).balanceMinor)
        assertEquals(listOf(EntryKind.TRANSFER, EntryKind.EXPENSE), repo.entries(cash, from = 150).map { it.kind })
        assertEquals(1, repo.entries(bank).size)
        val totals = repo.totals(from = 0, to = 250)
        assertEquals(5_000, totals.incomeMinor)
        assertEquals(2_000, totals.expenseMinor)
    }

    @Test fun rejectsInvalidAmountCurrencyAndTransfer() = runBlocking {
        assertFails { repo.createAccount("Cash", "not-money") }
        val rub = repo.createAccount("RUB", "RUB")
        val usd = repo.createAccount("USD", "USD")
        val food = repo.createCategory("Food", CategoryKind.EXPENSE)
        assertFails { repo.addExpense(rub, food, 0) }
        assertFails { repo.transfer(rub, rub, 1) }
        assertFails { repo.transfer(rub, usd, 1) }
    }

    @Test fun rejectsWrongCategoryKindAndArchivedEntities() = runBlocking {
        val account = repo.createAccount("Cash", "RUB")
        val income = repo.createCategory("Salary", CategoryKind.INCOME)
        assertFails { repo.addExpense(account, income, 100) }
        repo.archiveAccount(account)
        assertFails { repo.addIncome(account, income, 100) }
    }

    @Test fun rejectsBalanceOverflowWithoutPartialMutation() = runBlocking {
        val full = repo.createAccount("Full", "RUB", Long.MAX_VALUE)
        val other = repo.createAccount("Other", "RUB", 1)
        val income = repo.createCategory("Income", CategoryKind.INCOME)
        assertFails { repo.addIncome(full, income, 1) }
        assertFails { repo.transfer(other, full, 1) }
        assertEquals(Long.MAX_VALUE, repo.accounts().first { it.id == full }.balanceMinor)
        assertEquals(1, repo.accounts().first { it.id == other }.balanceMinor)
        assertTrue(repo.entries().isEmpty())
    }

    @Test fun totalsSaturateInsteadOfOverflowing() = runBlocking {
        val account = repo.createAccount("Cash", "RUB")
        val income = repo.createCategory("Income", CategoryKind.INCOME)
        // Directly restore the balance between valid entries so their aggregate exceeds Long.MAX_VALUE.
        repo.addIncome(account, income, Long.MAX_VALUE)
        db.financeDao().setBalance(account, 0)
        repo.addIncome(account, income, 1)
        assertEquals(Long.MAX_VALUE, repo.totals().incomeMinor)
    }

    @Test fun sqliteBoundaryRejectsMalformedRawDaoEntries() = runBlocking {
        val account = repo.createAccount("Cash", "RUB")
        val category = repo.createCategory("Food", CategoryKind.EXPENSE)
        val dao = db.financeDao()
        assertDatabaseRejects { dao.insertEntry(LedgerEntryEntity(kind = EntryKind.EXPENSE, amountMinor = 0, accountId = account, categoryId = category)) }
        assertDatabaseRejects { dao.insertEntry(LedgerEntryEntity(kind = EntryKind.INCOME, amountMinor = 1, accountId = account)) }
        assertDatabaseRejects { dao.insertEntry(LedgerEntryEntity(kind = EntryKind.INCOME, amountMinor = 1, accountId = account, categoryId = category)) }
        assertDatabaseRejects { dao.insertEntry(LedgerEntryEntity(kind = EntryKind.TRANSFER, amountMinor = 1, accountId = account, transferAccountId = account)) }
        assertDatabaseRejects { dao.insertEntry(LedgerEntryEntity(kind = EntryKind.TRANSFER, amountMinor = 1, accountId = account, transferAccountId = account + 1, categoryId = category)) }
        assertTrue(dao.entries().isEmpty())
    }

    @Test fun sqliteBoundaryRejectsUnknownEntryKind() = runBlocking {
        val account = repo.createAccount("Cash", "RUB")
        assertDatabaseRejects {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO ledger_entries(kind, amountMinor, accountId, transferAccountId, categoryId, note, occurredAt) VALUES ('UNKNOWN', 1, ?, NULL, NULL, '', 1)",
                arrayOf(account),
            )
        }
        assertTrue(db.financeDao().entries().isEmpty())
    }

    @Test fun operationDeletionReversesIncomeExpenseAndTransferAtomically() = runBlocking {
        val cash = repo.createAccount("Cash", "RUB", 1_000)
        val card = repo.createAccount("Card", "RUB")
        val salary = repo.createCategory("Salary", CategoryKind.INCOME, emoji = "💰")
        val food = repo.createCategory("Food", CategoryKind.EXPENSE, emoji = "🍜")
        val income = repo.addIncome(cash, salary, 100)
        val expense = repo.addExpense(cash, food, 40)
        val transfer = repo.transfer(cash, card, 60)

        repo.deleteEntry(transfer)
        assertEquals(1_060, repo.accounts().single { it.id == cash }.balanceMinor)
        assertEquals(0, repo.accounts().single { it.id == card }.balanceMinor)
        repo.deleteEntry(expense)
        assertEquals(1_100, repo.accounts().single { it.id == cash }.balanceMinor)
        repo.deleteEntry(income)
        assertEquals(1_000, repo.accounts().single { it.id == cash }.balanceMinor)
        assertTrue(repo.entries().isEmpty())
    }

    @Test fun operationDeletionOverflowRollsBackBalanceAndHistory() = runBlocking {
        val account = repo.createAccount("Cash", "RUB")
        val income = repo.createCategory("Salary", CategoryKind.INCOME)
        val entry = repo.addIncome(account, income, 1)
        db.financeDao().setBalance(account, Long.MIN_VALUE)

        assertFails { repo.deleteEntry(entry) }

        assertEquals(Long.MIN_VALUE, repo.accounts().single { it.id == account }.balanceMinor)
        assertEquals(entry, repo.entries().single().id)
    }

    @Test fun permanentDeletionRequiresArchiveAndNoDependencies() = runBlocking {
        val budget = repo.createBudget("Trip", "RUB")
        val category = repo.createCategory("Tickets", CategoryKind.EXPENSE, budget, "✈️")
        val account = repo.createAccount("Cash", "RUB", 100)
        val entry = repo.addExpense(account, category, 10)
        assertFails { repo.deleteAccount(account) }
        repo.archiveAccount(account)
        assertFails { repo.deleteAccount(account) }
        repo.archiveCategory(category)
        assertFails { repo.deleteCategory(category) }
        repo.archiveBudget(budget)
        assertFails { repo.deleteBudget(budget) }

        repo.deleteEntry(entry)
        repo.deleteAccount(account)
        repo.deleteCategory(category)
        repo.allocate(budget, YearMonth.of(2026, 9), 100)
        assertFails { repo.deleteBudget(budget) }
        repo.removeAllocation(budget, YearMonth.of(2026, 9))
        repo.deleteBudget(budget)
        assertTrue(repo.accounts(true).none { it.id == account })
        assertTrue(repo.categories(CategoryKind.EXPENSE, true).none { it.id == category })
        assertTrue(repo.budgets(true).none { it.id == budget })
    }

    @Test fun backupRoundTripPreservesEveryTableArchiveIdAndEmoji() = runBlocking {
        val budget = repo.createBudget("Travel", "USD")
        val account = repo.createAccount("Wallet", "USD", 1_000)
        val category = repo.createCategory("Flights", CategoryKind.EXPENSE, budget, "🛫")
        repo.allocate(budget, YearMonth.of(2026, 10), 900)
        val entry = repo.addExpense(account, category, 125, "ticket", 123456)
        repo.archiveAccount(account)
        repo.archiveCategory(category)
        repo.archiveBudget(budget)
        val document = repo.exportBackup()
        val extra = repo.createAccount("Must disappear", "RUB")

        repo.restoreBackup(document.byteInputStream())

        assertTrue(repo.accounts(true).none { it.id == extra })
        assertEquals(AccountEntity(account, "Wallet", "USD", 875, true, repo.accounts(true).single { it.id == account }.createdAt), repo.accounts(true).single { it.id == account })
        assertEquals("🛫", repo.categories(CategoryKind.EXPENSE, true).single { it.id == category }.emoji)
        assertTrue(repo.budgets(true).single { it.id == budget }.archived)
        assertEquals(900L, db.financeDao().allocation(budget, "2026-10"))
        assertEquals(entry, repo.entries().single { it.note == "ticket" }.id)
    }

    @Test fun invalidBackupLeavesExistingDatabaseUntouched() = runBlocking {
        val account = repo.createAccount("Cash", "RUB", 500)
        val category = repo.createCategory("Food", CategoryKind.EXPENSE)
        repo.addExpense(account, category, 25)
        val valid = FinanceBackupCodec.decode(repo.exportBackup())
        val invalidEntry = valid.entries.single().copy(kind = EntryKind.INCOME.name)
        val invalid = FinanceBackupCodec.encode(valid.copy(entries = listOf(invalidEntry)))

        assertFails { repo.restoreBackup(invalid) }

        assertEquals(475, repo.accounts().single { it.id == account }.balanceMinor)
        assertEquals(EntryKind.EXPENSE, repo.entries().single().kind)
    }

    @Test fun oversizedStringBackupIsRejectedBeforeDecodeWithoutMutation() = runBlocking {
        val account = repo.createAccount("Keep", "RUB", 321)
        val oversized = "{" + "x".repeat(16 * 1024 * 1024) + "}"

        assertFails { repo.restoreBackup(oversized) }

        assertEquals(321, repo.accounts().single { it.id == account }.balanceMinor)
    }

    @Test fun backupCodecRejectsWrongPrimitiveTypesWithoutMutation() = runBlocking {
        val account = repo.createAccount("Keep", "RUB", 123)
        val valid = repo.exportBackup()
        val malformed = listOf(
            valid.replaceFirst("\"formatVersion\": 1", "\"formatVersion\": \"1\""),
            valid.replaceFirst("\"name\": \"Keep\"", "\"name\": 7"),
            valid.replaceFirst("\"archived\": false", "\"archived\": \"false\""),
        )

        malformed.forEach { document -> assertFails { repo.restoreBackup(document) } }

        assertEquals(123, repo.accounts().single { it.id == account }.balanceMinor)
    }

    @Test fun failedBudgetDeleteChecksAndDeleteAreAtomicAndPreserveDependencies() = runBlocking {
        val budget = repo.createBudget("Protected", "RUB")
        repo.allocate(budget, YearMonth.of(2027, 1), 500)
        repo.archiveBudget(budget)

        assertFails { repo.deleteBudget(budget) }

        assertTrue(repo.budgets(true).any { it.id == budget })
        assertEquals(500L, db.financeDao().allocation(budget, "2027-01"))
    }

    @Test fun migrationFromV2PreservesRowsAndAddsArchiveAndEmojiDefaults() = runBlocking {
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v2-v3-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(sql: androidx.sqlite.db.SupportSQLiteDatabase) {
                    sql.execSQL("CREATE TABLE accounts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, currency TEXT NOT NULL, balanceMinor INTEGER NOT NULL, archived INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                    sql.execSQL("CREATE TABLE budgets (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, currency TEXT NOT NULL)")
                    sql.execSQL("CREATE UNIQUE INDEX index_budgets_name ON budgets(name)")
                    sql.execSQL("CREATE TABLE budget_allocations (budgetId INTEGER NOT NULL, month TEXT NOT NULL, amountMinor INTEGER NOT NULL, PRIMARY KEY(budgetId,month), FOREIGN KEY(budgetId) REFERENCES budgets(id) ON DELETE CASCADE)")
                    sql.execSQL("CREATE INDEX index_budget_allocations_budgetId ON budget_allocations(budgetId)")
                    sql.execSQL("CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, archived INTEGER NOT NULL, createdAt INTEGER NOT NULL, budgetId INTEGER NOT NULL DEFAULT 2, FOREIGN KEY(budgetId) REFERENCES budgets(id) ON DELETE RESTRICT)")
                    sql.execSQL("CREATE INDEX index_categories_budgetId ON categories(budgetId)")
                    sql.execSQL("CREATE TABLE ledger_entries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, kind TEXT NOT NULL, amountMinor INTEGER NOT NULL, accountId INTEGER NOT NULL, transferAccountId INTEGER, categoryId INTEGER, note TEXT NOT NULL, occurredAt INTEGER NOT NULL, FOREIGN KEY(accountId) REFERENCES accounts(id) ON DELETE RESTRICT, FOREIGN KEY(transferAccountId) REFERENCES accounts(id) ON DELETE RESTRICT, FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE RESTRICT)")
                    sql.execSQL("CREATE INDEX index_ledger_entries_accountId ON ledger_entries(accountId)")
                    sql.execSQL("CREATE INDEX index_ledger_entries_transferAccountId ON ledger_entries(transferAccountId)")
                    sql.execSQL("CREATE INDEX index_ledger_entries_categoryId ON ledger_entries(categoryId)")
                    sql.execSQL("CREATE INDEX index_ledger_entries_occurredAt ON ledger_entries(occurredAt)")
                    sql.execSQL("INSERT INTO budgets VALUES(8,'Legacy','EUR')")
                    sql.execSQL("INSERT INTO accounts VALUES(9,'Cash','EUR',75,1,100)")
                    sql.execSQL("INSERT INTO categories VALUES(10,'Food','EXPENSE',1,101,8)")
                    sql.execSQL("INSERT INTO budget_allocations VALUES(8,'2026-09',50)")
                    sql.execSQL("INSERT INTO ledger_entries VALUES(11,'EXPENSE',25,9,NULL,10,'meal',102)")
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build(),
        )
        helper.writableDatabase
        helper.close()
        val migrated = Room.databaseBuilder(context, FinanceDatabase::class.java, name)
            .addMigrations(FinanceDatabase.MIGRATION_2_3).build()
        try {
            val migratedRepo = FinanceRepository(migrated)
            assertEquals("🏷️", migratedRepo.categories(CategoryKind.EXPENSE, true).single().emoji)
            assertTrue(migratedRepo.accounts(true).single().archived)
            assertFalse(migratedRepo.budgets(true).single().archived)
            assertEquals(11, migratedRepo.entries().single().id)
            assertEquals(50L, migrated.financeDao().allocation(8, "2026-09"))
        } finally {
            migrated.close()
            context.deleteDatabase(name)
            db = FinanceDatabase.inMemory(context)
            repo = FinanceRepository(db)
        }
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        var failed = false
        try { block() } catch (_: IllegalArgumentException) { failed = true }
        assertTrue("Expected IllegalArgumentException", failed)
    }

    private suspend fun assertDatabaseRejects(block: suspend () -> Unit) {
        var failed = false
        try { block() } catch (_: android.database.sqlite.SQLiteException) { failed = true }
        assertTrue("Expected SQLite constraint failure", failed)
    }
}
