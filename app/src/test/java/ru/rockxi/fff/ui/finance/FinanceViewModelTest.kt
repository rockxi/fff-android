package ru.rockxi.fff.ui.finance

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.rockxi.fff.data.finance.*
import java.time.YearMonth
import java.time.ZoneId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

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
            expenseCategories = listOf(CategoryEntity(1, "Еда", CategoryKind.EXPENSE, budgetId = 1)),
            budgets = listOf(BudgetEntity(1, "Общие", "RUB")),
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

    @Test fun `category requires explicit budget`() = runTest(dispatcher) {
        val model = FinanceViewModel(FakeStore(), dispatcher); advanceUntilIdle()
        var success:Boolean?=null
        model.createCategory("Food",CategoryKind.EXPENSE,null,"🍜"){success=it}
        assertEquals(false,success); assertEquals("Выберите бюджет",model.state.value.error)
    }

    @Test fun `month switching reloads budget status and overspend`() = runTest(dispatcher) {
        val store=FakeStore(); val model=FinanceViewModel(store,dispatcher); advanceUntilIdle()
        val initial=model.state.value.selectedMonth
        store.allocations[1L to initial.plusMonths(1)]=100L; store.spending[1L to initial.plusMonths(1)]=150L
        model.changeMonth(1); advanceUntilIdle()
        assertEquals(initial.plusMonths(1),model.state.value.selectedMonth)
        assertEquals(-50L,model.state.value.budgetStatuses.single().remainingMinor)
    }

    @Test fun `out of order month loads cannot publish stale cards`() = runTest(dispatcher) {
        val store=FakeStore(); val model=FinanceViewModel(store,dispatcher); advanceUntilIdle()
        val initial=model.state.value.selectedMonth
        store.allocations[1L to initial.plusMonths(1)]=111L
        store.allocations[1L to initial.plusMonths(2)]=222L
        store.statusDelays[initial.plusMonths(1)]=100
        store.statusDelays[initial.plusMonths(2)]=1
        model.changeMonth(1); model.changeMonth(1); advanceUntilIdle()
        assertEquals(initial.plusMonths(2),model.state.value.selectedMonth)
        assertEquals(222L,model.state.value.budgetStatuses.single().allocatedMinor)
    }

    @Test fun `ABA month selection rejects noncooperative old same-month result`() = runTest(dispatcher) {
        val september = YearMonth.of(2026, 9)
        val store = FakeStore().apply {
            statusResponses[september] = ArrayDeque(listOf(100L to 111L, 1L to 333L))
            statusResponses[september.plusMonths(1)] = ArrayDeque(listOf(1L to 222L))
        }
        val model = FinanceViewModel(store, dispatcher, september)
        runCurrent() // First September request has captured 111 and is suspended non-cooperatively.
        model.changeMonth(1)
        runCurrent()
        model.changeMonth(-1)
        advanceUntilIdle()
        assertEquals(september, model.state.value.selectedMonth)
        assertEquals(333L, model.state.value.budgetStatuses.single().allocatedMinor)
    }

    @Test fun `expense categories follow account budget currency and incompatible selection resets`() {
        val rubBudget=BudgetEntity(1,"RUB budget","RUB"); val usdBudget=BudgetEntity(2,"USD budget","USD")
        val rubCategory=CategoryEntity(10,"Еда",CategoryKind.EXPENSE,budgetId=1)
        val usdCategory=CategoryEntity(11,"Food",CategoryKind.EXPENSE,budgetId=2)
        val state=FinanceUiState(loading=false,accounts=listOf(AccountEntity(1,"RUB","RUB"),AccountEntity(2,"USD","USD")),expenseCategories=listOf(rubCategory,usdCategory),budgets=listOf(rubBudget,usdBudget))
        val rubForm=OperationFormState(EntryKind.EXPENSE,1,10)
        assertEquals(listOf(10L),rubForm.categories(state).map{it.id})
        val usdForm=rubForm.selectSource(2,state)
        assertNull(usdForm.categoryId)
        assertEquals(listOf(11L),usdForm.categories(state).map{it.id})
        assertTrue(EntryKind.EXPENSE in availableEntryKinds(state))
        assertTrue(EntryKind.EXPENSE !in availableEntryKinds(state.copy(budgets=listOf(rubBudget),expenseCategories=listOf(usdCategory))))
    }

    @Test fun `category grid always has four columns and keeps every category`() {
        val categories = (1L..11L).map { CategoryEntity(it, "Категория $it", CategoryKind.EXPENSE, budgetId = 1, emoji = "🍜") }
        val rows = categoryGridRows(categories)
        assertEquals(listOf(4, 4, 3), rows.map { it.size })
        assertEquals(categories.map { it.id }, rows.flatten().map { it.id })
    }

    @Test fun `emoji picker rows use the same four column contract`() {
        val emoji = listOf("🛒", "🍔", "☕", "🏠", "🚕", "🚗", "✈️", "🎁", "❤️")
        val rows = fourColumnRows(emoji)
        assertEquals(listOf(4, 4, 1), rows.map { it.size })
        assertEquals(emoji, rows.flatten())
    }

    @Test fun `lazy item keys stay unique when database tables share ids`() {
        val categoryId = 7L
        val entryId = 7L
        val keys = listOf(
            financeItemKey("budget-category", categoryId),
            financeItemKey("budget-entry", entryId),
            financeItemKey("overview-account", entryId),
            financeItemKey("overview-entry", entryId),
        )
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("budget-category:7", financeItemKey("budget-category", categoryId))
        assertEquals("budget-entry:7", financeItemKey("budget-entry", entryId))
    }

    @Test fun `back is consumed only by an open budget detail`() {
        assertTrue(shouldCloseBudgetDetailsOnBack(isBudgetsTab = true, selectedBudgetId = 42))
        assertFalse(shouldCloseBudgetDetailsOnBack(isBudgetsTab = true, selectedBudgetId = null))
        assertFalse(shouldCloseBudgetDetailsOnBack(isBudgetsTab = false, selectedBudgetId = 42))
    }

    @Test fun `budget breakdown uses exact local month bounds and includes archived categories`() {
        val zone = ZoneId.of("Europe/Moscow")
        val month = YearMonth.of(2026, 9)
        val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val until = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val category = CategoryEntity(7, "Архивная", CategoryKind.EXPENSE, archived = true, budgetId = 3, emoji = "📦")
        val entries = listOf(
            LedgerEntryEntity(1, EntryKind.EXPENSE, 10, 1, categoryId = 7, occurredAt = from - 1),
            LedgerEntryEntity(2, EntryKind.EXPENSE, 20, 1, categoryId = 7, occurredAt = from),
            LedgerEntryEntity(3, EntryKind.EXPENSE, 30, 1, categoryId = 7, occurredAt = until - 1),
            LedgerEntryEntity(4, EntryKind.EXPENSE, 40, 1, categoryId = 7, occurredAt = until),
            LedgerEntryEntity(5, EntryKind.INCOME, 50, 1, categoryId = 7, occurredAt = from),
        )
        val result = budgetBreakdown(3, month, listOf(category), entries, zone)
        assertEquals(listOf(3L, 2L), result.entries.map { it.id })
        assertEquals(50L, result.categories.single().amountMinor)
        assertEquals(category, result.categories.single().category)
    }

    @Test fun `backup streams run through store and expose success`() = runTest(dispatcher) {
        val store = FakeStore()
        val model = FinanceViewModel(store, dispatcher)
        advanceUntilIdle()
        val output = ByteArrayOutputStream()
        var exported: Boolean? = null
        model.createBackup(openOutput = { output }) { exported = it }
        advanceUntilIdle()
        assertEquals(true, exported)
        assertEquals("backup", output.toString(Charsets.UTF_8.name()))
        assertEquals("Резервная копия сохранена", model.state.value.notice)

        var restored: Boolean? = null
        model.restoreBackup(openInput = { ByteArrayInputStream("restored".toByteArray()) }) { restored = it }
        advanceUntilIdle()
        assertEquals(true, restored)
        assertEquals("restored", store.restoredDocument)
        assertEquals("Данные восстановлены", model.state.value.notice)
    }

    @Test fun `backup export and restore are one atomic flight`() = runTest(dispatcher) {
        val store = FakeStore().apply { exportGate = CompletableDeferred() }
        val model = FinanceViewModel(store, dispatcher)
        advanceUntilIdle()
        var firstResult: Boolean? = null
        var rejectedResult: Boolean? = null

        assertTrue(model.createBackup(openOutput = { ByteArrayOutputStream() }) { firstResult = it })
        runCurrent()
        assertTrue(model.state.value.backupBusy)
        assertTrue(store.exportStarted.isCompleted)

        assertFalse(model.restoreBackup(openInput = { ByteArrayInputStream("late".toByteArray()) }) { rejectedResult = it })
        assertEquals(false, rejectedResult)
        assertTrue(model.state.value.backupBusy)
        assertNull(store.restoredDocument)

        store.exportGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(true, firstResult)
        assertFalse(model.state.value.backupBusy)
        assertEquals("Резервная копия сохранена", model.state.value.notice)
    }
}

private class FakeStore : FinanceStore {
    val accounts = mutableListOf<AccountEntity>()
    val categories = mutableListOf<CategoryEntity>()
    val entries = mutableListOf<LedgerEntryEntity>()
    val budgetList = mutableListOf(BudgetEntity(1,"Общие","RUB"))
    val allocations = mutableMapOf<Pair<Long,YearMonth>,Long>()
    val spending = mutableMapOf<Pair<Long,YearMonth>,Long>()
    val statusDelays = mutableMapOf<YearMonth,Long>()
    val statusResponses = mutableMapOf<YearMonth,ArrayDeque<Pair<Long,Long>>>()
    override suspend fun accounts(includeArchived: Boolean) = accounts.filter { includeArchived || !it.archived }
    override suspend fun categories(kind: CategoryKind, includeArchived: Boolean) = categories.filter { it.kind == kind && (includeArchived || !it.archived) }
    override suspend fun entries() = entries.sortedByDescending { it.occurredAt }
    override suspend fun totals() = FinanceTotals(entries.filter { it.kind == EntryKind.INCOME }.sumOf { it.amountMinor }, entries.filter { it.kind == EntryKind.EXPENSE }.sumOf { it.amountMinor })
    var restoredDocument: String? = null
    var exportGate: CompletableDeferred<Unit>? = null
    val exportStarted = CompletableDeferred<Unit>()
    override suspend fun budgets(includeArchived: Boolean)=budgetList.filter { includeArchived || !it.archived }
    override suspend fun budgetStatus(budgetId:Long,month:YearMonth):BudgetStatus { val response=statusResponses[month]?.removeFirstOrNull(); val wait=response?.first?:statusDelays[month]; wait?.let { withContext(NonCancellable) { delay(it) } }; val b=budgetList.single{it.id==budgetId};val a=response?.second?:allocations[budgetId to month]?:0;val s=spending[budgetId to month]?:0;return BudgetStatus(b,a,s,a-s) }
    override suspend fun createBudget(name:String,currency:String):Long { val id=(budgetList.size+1).toLong();budgetList+=BudgetEntity(id,name,currency);return id }
    override suspend fun allocate(budgetId:Long,month:YearMonth,amountMinor:Long){allocations[budgetId to month]=amountMinor}
    override suspend fun createAccount(name: String, currency: String, initialBalanceMinor: Long): Long { val id=(accounts.size+1).toLong(); accounts += AccountEntity(id,name,currency.uppercase(),initialBalanceMinor); return id }
    override suspend fun createCategory(name: String, kind: CategoryKind, budgetId:Long, emoji:String): Long { val id=(categories.size+1).toLong(); categories += CategoryEntity(id,name,kind,budgetId=budgetId,emoji=emoji); return id }
    override suspend fun archiveAccount(id: Long, archived:Boolean) { val i=accounts.indexOfFirst{it.id==id}; accounts[i]=accounts[i].copy(archived=archived) }
    override suspend fun archiveCategory(id: Long, archived:Boolean) { val i=categories.indexOfFirst{it.id==id}; categories[i]=categories[i].copy(archived=archived) }
    override suspend fun archiveBudget(id: Long, archived:Boolean) { val i=budgetList.indexOfFirst{it.id==id}; budgetList[i]=budgetList[i].copy(archived=archived) }
    override suspend fun deleteAccount(id: Long) { accounts.removeAll { it.id == id } }
    override suspend fun deleteCategory(id: Long) { categories.removeAll { it.id == id } }
    override suspend fun deleteBudget(id: Long) { budgetList.removeAll { it.id == id } }
    override suspend fun deleteEntry(id: Long) { entries.removeAll { it.id == id } }
    override suspend fun exportBackup(output: java.io.OutputStream) {
        exportStarted.complete(Unit)
        exportGate?.await()
        output.writer().use { it.write("backup") }
    }
    override suspend fun restoreBackup(input: java.io.InputStream) { restoredDocument = input.reader().use { it.readText() } }
    override suspend fun addIncome(accountId: Long, categoryId: Long, amountMinor: Long, note: String): Long = add(EntryKind.INCOME,accountId,categoryId,null,amountMinor,note)
    override suspend fun addExpense(accountId: Long, categoryId: Long, amountMinor: Long, note: String): Long = add(EntryKind.EXPENSE,accountId,categoryId,null,amountMinor,note)
    override suspend fun transfer(fromAccountId: Long, toAccountId: Long, amountMinor: Long, note: String): Long = add(EntryKind.TRANSFER,fromAccountId,null,toAccountId,amountMinor,note)
    private fun add(kind:EntryKind,account:Long,category:Long?,target:Long?,amount:Long,note:String):Long { val id=(entries.size+1).toLong(); entries += LedgerEntryEntity(id,kind,amount,account,target,category,note); return id }
}
