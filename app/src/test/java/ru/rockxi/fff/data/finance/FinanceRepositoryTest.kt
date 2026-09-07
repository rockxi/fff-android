package ru.rockxi.fff.data.finance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
