package ru.rockxi.fff.ui.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.rockxi.fff.data.finance.AccountEntity
import ru.rockxi.fff.data.finance.CategoryEntity
import ru.rockxi.fff.data.finance.CategoryKind
import ru.rockxi.fff.data.finance.EntryKind
import ru.rockxi.fff.data.finance.FinanceRepository
import ru.rockxi.fff.data.finance.FinanceTotals
import ru.rockxi.fff.data.finance.LedgerEntryEntity
import ru.rockxi.fff.data.finance.BudgetEntity
import ru.rockxi.fff.data.finance.BudgetStatus
import java.time.YearMonth
import java.math.BigDecimal
import java.math.RoundingMode

internal interface FinanceStore {
    suspend fun accounts(includeArchived: Boolean = false): List<AccountEntity>
    suspend fun categories(kind: CategoryKind, includeArchived: Boolean = false): List<CategoryEntity>
    suspend fun entries(): List<LedgerEntryEntity>
    suspend fun totals(): FinanceTotals
    suspend fun budgets(): List<BudgetEntity>
    suspend fun budgetStatus(budgetId: Long, month: YearMonth): BudgetStatus
    suspend fun createBudget(name: String, currency: String): Long
    suspend fun allocate(budgetId: Long, month: YearMonth, amountMinor: Long)
    suspend fun createAccount(name: String, currency: String, initialBalanceMinor: Long): Long
    suspend fun createCategory(name: String, kind: CategoryKind, budgetId: Long): Long
    suspend fun archiveAccount(id: Long)
    suspend fun archiveCategory(id: Long)
    suspend fun addIncome(accountId: Long, categoryId: Long, amountMinor: Long, note: String): Long
    suspend fun addExpense(accountId: Long, categoryId: Long, amountMinor: Long, note: String): Long
    suspend fun transfer(fromAccountId: Long, toAccountId: Long, amountMinor: Long, note: String): Long
}

internal class RepositoryFinanceStore(private val repository: FinanceRepository) : FinanceStore {
    override suspend fun accounts(includeArchived: Boolean) = repository.accounts(includeArchived)
    override suspend fun categories(kind: CategoryKind, includeArchived: Boolean) = repository.categories(kind, includeArchived)
    override suspend fun entries() = repository.entries()
    override suspend fun totals() = repository.totals()
    override suspend fun budgets() = repository.budgets()
    override suspend fun budgetStatus(budgetId: Long, month: YearMonth) = repository.budgetStatus(budgetId, month)
    override suspend fun createBudget(name: String, currency: String) = repository.createBudget(name, currency)
    override suspend fun allocate(budgetId: Long, month: YearMonth, amountMinor: Long) = repository.allocate(budgetId, month, amountMinor)
    override suspend fun createAccount(name: String, currency: String, initialBalanceMinor: Long) = repository.createAccount(name, currency, initialBalanceMinor)
    override suspend fun createCategory(name: String, kind: CategoryKind, budgetId: Long) = repository.createCategory(name, kind, budgetId)
    override suspend fun archiveAccount(id: Long) = repository.archiveAccount(id)
    override suspend fun archiveCategory(id: Long) = repository.archiveCategory(id)
    override suspend fun addIncome(accountId: Long, categoryId: Long, amountMinor: Long, note: String) = repository.addIncome(accountId, categoryId, amountMinor, note)
    override suspend fun addExpense(accountId: Long, categoryId: Long, amountMinor: Long, note: String) = repository.addExpense(accountId, categoryId, amountMinor, note)
    override suspend fun transfer(fromAccountId: Long, toAccountId: Long, amountMinor: Long, note: String) = repository.transfer(fromAccountId, toAccountId, amountMinor, note)
}

internal data class FinanceUiState(
    val loading: Boolean = true,
    val accounts: List<AccountEntity> = emptyList(),
    val allAccounts: List<AccountEntity> = emptyList(),
    val incomeCategories: List<CategoryEntity> = emptyList(),
    val expenseCategories: List<CategoryEntity> = emptyList(),
    val allCategories: List<CategoryEntity> = emptyList(),
    val entries: List<LedgerEntryEntity> = emptyList(),
    val totals: FinanceTotals = FinanceTotals(0, 0),
    val currencySummaries: List<CurrencySummary> = emptyList(),
    val budgets: List<BudgetEntity> = emptyList(),
    val budgetStatuses: List<BudgetStatus> = emptyList(),
    val selectedMonth: YearMonth = YearMonth.now(),
    val error: String? = null,
)

internal data class CurrencySummary(
    val currency: String,
    val balanceMinor: Long,
    val incomeMinor: Long,
    val expenseMinor: Long,
) {
    val netMinor: Long get() = saturatedAdd(incomeMinor, -expenseMinor)
}

internal data class OperationFormState(
    val kind: EntryKind = EntryKind.EXPENSE,
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val targetAccountId: Long? = null,
) {
    fun selectSource(id: Long?, state: FinanceUiState? = null): OperationFormState {
        val selectedCategory = if (state == null || kind != EntryKind.EXPENSE || categoryId in expenseCategoriesFor(state, id).map { it.id }) categoryId else null
        return copy(accountId = id, categoryId = selectedCategory, targetAccountId = null)
    }
    fun selectKind(value: EntryKind): OperationFormState = copy(kind = value, categoryId = null, targetAccountId = null)
    fun transferTargets(accounts: List<AccountEntity>): List<AccountEntity> {
        val source = accounts.firstOrNull { it.id == accountId } ?: return emptyList()
        return accounts.filter { !it.archived && it.id != source.id && it.currency == source.currency }
    }
    fun categories(state: FinanceUiState): List<CategoryEntity> =
        if (kind == EntryKind.EXPENSE) expenseCategoriesFor(state, accountId) else state.incomeCategories
}

internal fun expenseCategoriesFor(state: FinanceUiState, accountId: Long?): List<CategoryEntity> {
    val currency = state.accounts.firstOrNull { it.id == accountId }?.currency ?: return emptyList()
    val budgetIds = state.budgets.filter { it.currency == currency }.map { it.id }.toSet()
    return state.expenseCategories.filter { it.budgetId in budgetIds }
}

internal fun availableEntryKinds(state: FinanceUiState): Set<EntryKind> = buildSet {
    if (state.accounts.isNotEmpty() && state.incomeCategories.isNotEmpty()) add(EntryKind.INCOME)
    if (state.accounts.any { account -> expenseCategoriesFor(state, account.id).isNotEmpty() }) add(EntryKind.EXPENSE)
    if (state.accounts.groupBy { it.currency }.any { it.value.size >= 2 }) add(EntryKind.TRANSFER)
}

internal fun currencySummaries(
    accounts: List<AccountEntity>,
    entries: List<LedgerEntryEntity>,
): List<CurrencySummary> {
    return accounts.asSequence().map { it.currency }.distinct().sorted().map { currency ->
        val currencyAccountIds = accounts.asSequence().filter { it.currency == currency }.map { it.id }.toSet()
        CurrencySummary(
            currency = currency,
            balanceMinor = accounts.asSequence().filter { !it.archived && it.currency == currency }.map { it.balanceMinor }.fold(0L, ::saturatedAdd),
            incomeMinor = entries.asSequence().filter { it.kind == EntryKind.INCOME && it.accountId in currencyAccountIds }.map { it.amountMinor }.fold(0L, ::saturatedAdd),
            expenseMinor = entries.asSequence().filter { it.kind == EntryKind.EXPENSE && it.accountId in currencyAccountIds }.map { it.amountMinor }.fold(0L, ::saturatedAdd),
        )
    }.toList()
}

private fun saturatedAdd(left: Long, right: Long): Long = when {
    right > 0 && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
    right < 0 && left < Long.MIN_VALUE - right -> Long.MIN_VALUE
    else -> left + right
}

internal class FinanceViewModel(
    private val store: FinanceStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    initialMonth: YearMonth = YearMonth.now(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(FinanceUiState(selectedMonth = initialMonth))
    val state: StateFlow<FinanceUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null
    private val loadLock = Any()
    private var loadGeneration = 0L

    init { refresh() }

    fun refresh() {
        loadJob?.cancel()
        val (month, generation) = synchronized(loadLock) {
            mutableState.value.selectedMonth to ++loadGeneration
        }
        loadJob = launchAction(clearError = false) { loadState(month, generation) }
    }
    fun clearError() { mutableState.value = mutableState.value.copy(error = null) }

    fun createAccount(name: String, currency: String, opening: String, done: (Boolean) -> Unit = {}) {
        val amount = parseMoney(opening.ifBlank { "0" }) ?: return invalid("Введите корректный начальный баланс", done)
        launchAction(done = done) { store.createAccount(name, currency, amount); reloadCurrentState() }
    }

    fun createCategory(name: String, kind: CategoryKind, budgetId: Long?, done: (Boolean) -> Unit = {}) {
        if (budgetId == null) return invalid("Выберите бюджет", done)
        launchAction(done = done) { store.createCategory(name, kind, budgetId); reloadCurrentState() }
    }

    fun createBudget(name: String, currency: String, done: (Boolean) -> Unit = {}) = launchAction(done = done) {
        store.createBudget(name, currency); reloadCurrentState()
    }

    fun setAllocation(budgetId: Long?, amount: String, done: (Boolean) -> Unit = {}) {
        val minor = parseMoney(amount) ?: return invalid("Введите корректную сумму бюджета", done)
        if (budgetId == null || minor < 0) return invalid("Выберите бюджет и укажите неотрицательную сумму", done)
        launchAction(done = done) { store.allocate(budgetId, mutableState.value.selectedMonth, minor); reloadCurrentState() }
    }

    fun changeMonth(delta: Long) {
        loadJob?.cancel()
        val (month, generation) = synchronized(loadLock) {
            val month = mutableState.value.selectedMonth.plusMonths(delta)
            mutableState.value = mutableState.value.copy(selectedMonth = month)
            month to ++loadGeneration
        }
        loadJob = launchAction(clearError = false) { loadState(month, generation) }
    }

    fun archiveAccount(id: Long) = launchAction { store.archiveAccount(id); reloadCurrentState() }
    fun archiveCategory(id: Long) = launchAction { store.archiveCategory(id); reloadCurrentState() }

    fun addEntry(kind: EntryKind, amount: String, accountId: Long?, categoryId: Long?, targetId: Long?, note: String, done: (Boolean) -> Unit = {}) {
        val minor = parseMoney(amount) ?: return invalid("Введите положительную сумму, например 1250,50", done)
        if (minor <= 0 || accountId == null) return invalid("Выберите счёт и укажите положительную сумму", done)
        if (kind != EntryKind.TRANSFER && categoryId == null) return invalid("Выберите категорию", done)
        if (kind == EntryKind.TRANSFER && targetId == null) return invalid("Выберите счёт назначения", done)
        launchAction(done = done) {
            when (kind) {
                EntryKind.INCOME -> store.addIncome(accountId, categoryId!!, minor, note)
                EntryKind.EXPENSE -> store.addExpense(accountId, categoryId!!, minor, note)
                EntryKind.TRANSFER -> store.transfer(accountId, targetId!!, minor, note)
            }
            reloadCurrentState()
        }
    }

    private fun invalid(message: String, done: (Boolean) -> Unit) { mutableState.value = mutableState.value.copy(error = message); done(false) }
    private fun launchAction(clearError: Boolean = true, done: (Boolean) -> Unit = {}, block: suspend () -> Unit): Job {
        if (clearError) mutableState.value = mutableState.value.copy(error = null)
        return viewModelScope.launch {
            try {
                withContext(io) { block() }
                done(true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.value = mutableState.value.copy(loading = false, error = error.message ?: "Не удалось сохранить данные")
                done(false)
            }
        }
    }
    private suspend fun reloadCurrentState() {
        val (month, generation) = synchronized(loadLock) { mutableState.value.selectedMonth to ++loadGeneration }
        loadState(month, generation)
    }

    private suspend fun loadState(month: YearMonth, generation: Long) {
        val accounts = store.accounts()
        val allAccounts = store.accounts(true)
        val entries = store.entries()
        val budgets = store.budgets()
        val statuses = budgets.map { store.budgetStatus(it.id, month) }
        val incomeCategories = store.categories(CategoryKind.INCOME)
        val expenseCategories = store.categories(CategoryKind.EXPENSE)
        val allCategories = store.categories(CategoryKind.INCOME, true) + store.categories(CategoryKind.EXPENSE, true)
        val totals = store.totals()
        val summaries = currencySummaries(allAccounts, entries)
        val loaded = FinanceUiState(
            loading = false,
            accounts = accounts, allAccounts = allAccounts,
            incomeCategories = incomeCategories, expenseCategories = expenseCategories,
            allCategories = allCategories,
            entries = entries, totals = totals, currencySummaries = summaries,
            budgets = budgets, budgetStatuses = statuses, selectedMonth = month, error = null,
        )
        currentCoroutineContext().ensureActive()
        synchronized(loadLock) {
            if (generation == loadGeneration && mutableState.value.selectedMonth == month) mutableState.value = loaded
        }
    }

    companion object {
        internal fun parseMoney(value: String): Long? = runCatching {
            val normalized = value.trim().replace(" ", "").replace(',', '.')
            BigDecimal(normalized).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
        }.getOrNull()
        fun factory(store: FinanceStore) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = FinanceViewModel(store) as T
        }
    }
}
