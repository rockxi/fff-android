package ru.rockxi.fff.ui.finance

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.rockxi.fff.data.finance.*

@OptIn(ExperimentalCoroutinesApi::class)
class FinanceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `money input accepts comma and rejects excess precision`() {
        assertEquals(125050L, FinanceViewModel.parseMoney("1 250,50"))
        assertEquals(0L, FinanceViewModel.parseMoney("0"))
        assertNull(FinanceViewModel.parseMoney("12.345"))
        assertNull(FinanceViewModel.parseMoney("abc"))
    }

    @Test fun `create account refreshes state and reports success`() = runTest(dispatcher) {
        val store = FakeStore()
        val model = FinanceViewModel(store, dispatcher)
        advanceUntilIdle()
        var success: Boolean? = null
        model.createAccount("Карта", "rub", "100,25") { success = it }
        advanceUntilIdle()
        assertEquals(true, success)
        assertEquals("Карта", model.state.value.accounts.single().name)
        assertEquals(10025L, model.state.value.accounts.single().balanceMinor)
        assertNull(model.state.value.error)
    }

    @Test fun `invalid operation stays local and exposes useful error`() = runTest(dispatcher) {
        val store = FakeStore().apply { accounts += AccountEntity(1, "Карта", "RUB") }
        val model = FinanceViewModel(store, dispatcher)
        advanceUntilIdle()
        var success: Boolean? = null
        model.addEntry(EntryKind.EXPENSE, "oops", 1, 2, null, "") { success = it }
        assertEquals(false, success)
        assertTrue(model.state.value.error!!.contains("сумму"))
        assertTrue(store.entries.isEmpty())
    }

    @Test fun `expense is saved and refreshed`() = runTest(dispatcher) {
        val store = FakeStore().apply {
            accounts += AccountEntity(1, "Карта", "RUB", 50000)
            categories += CategoryEntity(2, "Еда", CategoryKind.EXPENSE)
        }
        val model = FinanceViewModel(store, dispatcher)
        advanceUntilIdle()
        model.addEntry(EntryKind.EXPENSE, "125.50", 1, 2, null, "обед")
        advanceUntilIdle()
        assertEquals(1, model.state.value.entries.size)
        assertEquals(12550L, model.state.value.totals.expenseMinor)
        assertEquals("обед", model.state.value.entries.single().note)
    }

    @Test fun `mixed currencies keep balance and analytics totals separate`() = runTest(dispatcher) {
        val store = FakeStore().apply {
            accounts += AccountEntity(1, "Рубли", "RUB", 150_00)
            accounts += AccountEntity(2, "Dollars", "USD", 70_00)
            categories += CategoryEntity(10, "Зарплата", CategoryKind.INCOME)
            categories += CategoryEntity(11, "Food", CategoryKind.EXPENSE)
            entries += LedgerEntryEntity(1, EntryKind.INCOME, 25_000_00, 1, categoryId = 10)
            entries += LedgerEntryEntity(2, EntryKind.EXPENSE, 1_500_00, 1, categoryId = 11)
            entries += LedgerEntryEntity(3, EntryKind.INCOME, 2_000_00, 2, categoryId = 10)
            entries += LedgerEntryEntity(4, EntryKind.EXPENSE, 35_00, 2, categoryId = 11)
        }
        val model = FinanceViewModel(store, dispatcher)
        advanceUntilIdle()
        val rub = model.state.value.currencySummaries.single { it.currency == "RUB" }
        val usd = model.state.value.currencySummaries.single { it.currency == "USD" }
        assertEquals(CurrencySummary("RUB", 150_00, 25_000_00, 1_500_00), rub)
        assertEquals(CurrencySummary("USD", 70_00, 2_000_00, 35_00), usd)
        assertEquals(23_500_00, rub.netMinor)
        assertEquals(1_965_00, usd.netMinor)
    }

    @Test fun `transfer targets share source currency and source change clears stale target`() {
        val accounts = listOf(
            AccountEntity(1, "RUB 1", "RUB"), AccountEntity(2, "RUB 2", "RUB"),
            AccountEntity(3, "USD 1", "USD"), AccountEntity(4, "USD 2", "USD"),
        )
        val rubForm = OperationFormState(EntryKind.TRANSFER, 1, targetAccountId = 2)
        assertEquals(listOf(2L), rubForm.transferTargets(accounts).map { it.id })
        val usdForm = rubForm.selectSource(3)
        assertNull(usdForm.targetAccountId)
        assertEquals(listOf(4L), usdForm.transferTargets(accounts).map { it.id })
    }

    @Test fun `transfer is unavailable without same currency pair`() {
        val state = FinanceUiState(
            loading = false,
            accounts = listOf(AccountEntity(1, "RUB", "RUB"), AccountEntity(2, "USD", "USD")),
            expenseCategories = listOf(CategoryEntity(1, "Еда", CategoryKind.EXPENSE)),
        )
        assertEquals(setOf(EntryKind.EXPENSE), availableEntryKinds(state))
        assertTrue(EntryKind.TRANSFER in availableEntryKinds(state.copy(accounts = state.accounts + AccountEntity(3, "RUB 2", "RUB"))))
    }

    @Test fun `missing category returns form error without calling store`() = runTest(dispatcher) {
        val store = FakeStore().apply { accounts += AccountEntity(1, "Карта", "RUB") }
        val model = FinanceViewModel(store, dispatcher)
        advanceUntilIdle()
        var success: Boolean? = null
        model.addEntry(EntryKind.EXPENSE, "100", 1, null, null, "") { success = it }
        assertEquals(false, success)
        assertEquals("Выберите категорию", model.state.value.error)
        assertTrue(store.entries.isEmpty())
    }
}

private class FakeStore : FinanceStore {
    val accounts = mutableListOf<AccountEntity>()
    val categories = mutableListOf<CategoryEntity>()
    val entries = mutableListOf<LedgerEntryEntity>()
    override suspend fun accounts(includeArchived: Boolean) = accounts.filter { includeArchived || !it.archived }
    override suspend fun categories(kind: CategoryKind, includeArchived: Boolean) = categories.filter { it.kind == kind && (includeArchived || !it.archived) }
    override suspend fun entries() = entries.sortedByDescending { it.occurredAt }
    override suspend fun totals() = FinanceTotals(entries.filter { it.kind == EntryKind.INCOME }.sumOf { it.amountMinor }, entries.filter { it.kind == EntryKind.EXPENSE }.sumOf { it.amountMinor })
    override suspend fun createAccount(name: String, currency: String, initialBalanceMinor: Long): Long { val id=(accounts.size+1).toLong(); accounts += AccountEntity(id,name,currency.uppercase(),initialBalanceMinor); return id }
    override suspend fun createCategory(name: String, kind: CategoryKind): Long { val id=(categories.size+1).toLong(); categories += CategoryEntity(id,name,kind); return id }
    override suspend fun archiveAccount(id: Long) { val i=accounts.indexOfFirst{it.id==id}; accounts[i]=accounts[i].copy(archived=true) }
    override suspend fun archiveCategory(id: Long) { val i=categories.indexOfFirst{it.id==id}; categories[i]=categories[i].copy(archived=true) }
    override suspend fun addIncome(accountId: Long, categoryId: Long, amountMinor: Long, note: String): Long = add(EntryKind.INCOME,accountId,categoryId,null,amountMinor,note)
    override suspend fun addExpense(accountId: Long, categoryId: Long, amountMinor: Long, note: String): Long = add(EntryKind.EXPENSE,accountId,categoryId,null,amountMinor,note)
    override suspend fun transfer(fromAccountId: Long, toAccountId: Long, amountMinor: Long, note: String): Long = add(EntryKind.TRANSFER,fromAccountId,null,toAccountId,amountMinor,note)
    private fun add(kind:EntryKind,account:Long,category:Long?,target:Long?,amount:Long,note:String):Long { val id=(entries.size+1).toLong(); entries += LedgerEntryEntity(id,kind,amount,account,target,category,note); return id }
}
