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
import java.io.InputStream
import java.io.OutputStream
import java.time.ZoneId
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Currency

internal interface FinanceStore {
    suspend fun accounts(includeArchived: Boolean = false): List<AccountEntity>
    suspend fun categories(kind: CategoryKind, includeArchived: Boolean = false): List<CategoryEntity>
    suspend fun entries(): List<LedgerEntryEntity>
    suspend fun totals(): FinanceTotals
    suspend fun budgets(includeArchived: Boolean = false): List<BudgetEntity>
    suspend fun budgetStatus(budgetId: Long, month: YearMonth): BudgetStatus
    suspend fun createBudget(name: String, currency: String): Long
    suspend fun allocate(budgetId: Long, month: YearMonth, amountMinor: Long)
    suspend fun createAccount(name: String, currency: String, initialBalanceMinor: Long): Long
    suspend fun createCategory(name: String, kind: CategoryKind, budgetId: Long, emoji: String): Long
    suspend fun updateCategory(id: Long, name: String, budgetId: Long, emoji: String)
    suspend fun archiveAccount(id: Long, archived: Boolean)
    suspend fun archiveCategory(id: Long, archived: Boolean)
    suspend fun archiveBudget(id: Long, archived: Boolean)
    suspend fun deleteAccount(id: Long)
    suspend fun deleteCategory(id: Long)
    suspend fun deleteBudget(id: Long)
    suspend fun deleteEntry(id: Long)
    suspend fun exportBackup(output: OutputStream)
    suspend fun restoreBackup(input: InputStream)
    suspend fun addIncome(accountId: Long, categoryId: Long, amountMinor: Long, note: String): Long
    suspend fun addExpense(accountId: Long, categoryId: Long, amountMinor: Long, note: String): Long
    suspend fun transfer(fromAccountId: Long, toAccountId: Long, amountMinor: Long, note: String): Long
}

internal class RepositoryFinanceStore(private val repository: FinanceRepository) : FinanceStore {
    override suspend fun accounts(includeArchived: Boolean) = repository.accounts(includeArchived)
    override suspend fun categories(kind: CategoryKind, includeArchived: Boolean) = repository.categories(kind, includeArchived)
    override suspend fun entries() = repository.entries()
    override suspend fun totals() = repository.totals()
    override suspend fun budgets(includeArchived: Boolean) = repository.budgets(includeArchived)
    override suspend fun budgetStatus(budgetId: Long, month: YearMonth) = repository.budgetStatus(budgetId, month)
    override suspend fun createBudget(name: String, currency: String) = repository.createBudget(name, currency)
    override suspend fun allocate(budgetId: Long, month: YearMonth, amountMinor: Long) = repository.allocate(budgetId, month, amountMinor)
    override suspend fun createAccount(name: String, currency: String, initialBalanceMinor: Long) = repository.createAccount(name, currency, initialBalanceMinor)
    override suspend fun createCategory(name: String, kind: CategoryKind, budgetId: Long, emoji: String) = repository.createCategory(name, kind, budgetId, emoji)
    override suspend fun updateCategory(id: Long, name: String, budgetId: Long, emoji: String) = repository.updateCategory(id, name, budgetId, emoji)
    override suspend fun archiveAccount(id: Long, archived: Boolean) = repository.archiveAccount(id, archived)
    override suspend fun archiveCategory(id: Long, archived: Boolean) = repository.archiveCategory(id, archived)
    override suspend fun archiveBudget(id: Long, archived: Boolean) = repository.archiveBudget(id, archived)
    override suspend fun deleteAccount(id: Long) = repository.deleteAccount(id)
    override suspend fun deleteCategory(id: Long) = repository.deleteCategory(id)
    override suspend fun deleteBudget(id: Long) = repository.deleteBudget(id)
    override suspend fun deleteEntry(id: Long) = repository.deleteEntry(id)
    override suspend fun exportBackup(output: OutputStream) = repository.exportBackup(output)
    override suspend fun restoreBackup(input: InputStream) = repository.restoreBackup(input)
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
    val allBudgets: List<BudgetEntity> = emptyList(),
    val budgetStatuses: List<BudgetStatus> = emptyList(),
    val selectedMonth: YearMonth = YearMonth.now(),
    val error: String? = null,
    val notice: String? = null,
    val backupBusy: Boolean = false,
)

internal data class BudgetCategoryBreakdown(
    val category: CategoryEntity,
    val amountMinor: Long,
    val entries: List<LedgerEntryEntity>,
)

internal data class BudgetBreakdown(
    val entries: List<LedgerEntryEntity>,
    val categories: List<BudgetCategoryBreakdown>,
)

internal data class OperationDayGroup(
    val date: LocalDate,
    val entries: List<LedgerEntryEntity>,
    val expenseMinorByCurrency: Map<String, Long>,
)

internal data class OperationsTimeline(
    val days: List<OperationDayGroup>,
    val todayExpenseMinorByCurrency: Map<String, Long>,
)

internal fun operationsTimeline(
    entries: List<LedgerEntryEntity>,
    accounts: List<AccountEntity>,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): OperationsTimeline {
    val accountsById = accounts.associateBy { it.id }
    val sorted = entries.sortedWith(compareByDescending<LedgerEntryEntity> { it.occurredAt }.thenByDescending { it.id })
    val days = sorted.groupBy { java.time.Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }
        .map { (date, values) ->
            val expenseTotals = values.asSequence()
                .filter { it.kind == EntryKind.EXPENSE }
                .mapNotNull { entry -> accountsById[entry.accountId]?.currency?.let { it to entry.amountMinor } }
                .groupingBy { it.first }
                .fold(0L) { total, (_, amount) -> saturatedAdd(total, amount) }
                .toSortedMap()
            OperationDayGroup(date, values, expenseTotals)
        }
        .sortedByDescending { it.date }
    val currencies = accounts.map { it.currency }.distinct().sorted()
    val totals = currencies.associateWith { currency ->
        sorted.asSequence().filter {
            it.kind == EntryKind.EXPENSE &&
                accountsById[it.accountId]?.currency == currency &&
                java.time.Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() == today
        }.map { it.amountMinor }.fold(0L, ::saturatedAdd)
    }
    return OperationsTimeline(days, totals)
}

private val operationDayDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

internal fun formatOperationDayDate(date: LocalDate): String = date.format(operationDayDateFormatter)

internal fun <T> fourColumnRows(items: List<T>): List<List<T>> = items.chunked(4)

internal fun financeItemKey(domain: String, id: Long): String = "$domain:$id"

internal fun requiredNameError(value: String): String? =
    if (value.isBlank()) "Введите название" else null

internal fun currencyFieldError(value: String): String? {
    val normalized = value.trim().uppercase()
    return if (normalized.length == 3 && runCatching { Currency.getInstance(normalized) }.isSuccess) null
    else "Укажите валюту: RUB, USD…"
}

internal fun moneyFieldError(value: String, allowBlank: Boolean = false, allowZero: Boolean): String? {
    if (allowBlank && value.isBlank()) return null
    val minor = FinanceViewModel.parseMoney(value) ?: return "Введите корректную сумму"
    return when {
        allowZero && minor < 0 -> "Сумма не может быть отрицательной"
        !allowZero && minor <= 0 -> "Сумма должна быть больше нуля"
        else -> null
    }
}

internal fun categoryGridRows(categories: List<CategoryEntity>): List<List<CategoryEntity>> =
    fourColumnRows(categories)

internal fun budgetBreakdown(
    budgetId: Long,
    month: YearMonth,
    categories: List<CategoryEntity>,
    entries: List<LedgerEntryEntity>,
    zone: ZoneId = ZoneId.systemDefault(),
): BudgetBreakdown {
    val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val until = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val byId = categories.filter { it.budgetId == budgetId }.associateBy { it.id }
    val matching = entries.filter {
        it.kind == EntryKind.EXPENSE && it.categoryId in byId && it.occurredAt >= from && it.occurredAt < until
    }.sortedWith(compareByDescending<LedgerEntryEntity> { it.occurredAt }.thenByDescending { it.id })
    val grouped = matching.groupBy { it.categoryId }.mapNotNull { (id, values) ->
        byId[id]?.let { category ->
            BudgetCategoryBreakdown(category, values.fold(0L) { total, entry -> saturatedAdd(total, entry.amountMinor) }, values)
        }
    }.sortedWith(compareByDescending<BudgetCategoryBreakdown> { it.amountMinor }.thenBy { it.category.name })
    return BudgetBreakdown(matching, grouped)
}

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
    private val backupLock = Any()
    private var backupGeneration = 0L
    private var backupInFlight = false

    init { refresh() }

    fun refresh() {
        loadJob?.cancel()
        val (month, generation) = synchronized(loadLock) {
            mutableState.value.selectedMonth to ++loadGeneration
        }
        loadJob = launchAction(clearError = false) { loadState(month, generation) }
    }
    fun clearError() { mutableState.value = mutableState.value.copy(error = null, notice = null) }

    fun createAccount(name: String, currency: String, opening: String, done: (Boolean) -> Unit = {}) {
        val amount = parseMoney(opening.ifBlank { "0" }) ?: return invalid("Введите корректный начальный баланс", done)
        launchAction(done = done) { store.createAccount(name, currency, amount); reloadCurrentState() }
    }

    fun createCategory(name: String, kind: CategoryKind, budgetId: Long?, emoji: String, done: (Boolean) -> Unit = {}) {
        if (budgetId == null) return invalid("Выберите бюджет", done)
        launchAction(done = done) { store.createCategory(name, kind, budgetId, emoji); reloadCurrentState() }
    }

    fun updateCategory(id: Long, name: String, budgetId: Long?, emoji: String, done: (Boolean) -> Unit = {}) {
        if (budgetId == null) return invalid("Выберите бюджет", done)
        launchAction(done = done) { store.updateCategory(id, name, budgetId, emoji); reloadCurrentState() }
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

    fun archiveAccount(id: Long, archived: Boolean) = launchAction { store.archiveAccount(id, archived); reloadCurrentState() }
    fun archiveCategory(id: Long, archived: Boolean) = launchAction { store.archiveCategory(id, archived); reloadCurrentState() }
    fun archiveBudget(id: Long, archived: Boolean) = launchAction { store.archiveBudget(id, archived); reloadCurrentState() }
    fun deleteAccount(id: Long) = launchAction { store.deleteAccount(id); reloadCurrentState() }
    fun deleteCategory(id: Long) = launchAction { store.deleteCategory(id); reloadCurrentState() }
    fun deleteBudget(id: Long) = launchAction { store.deleteBudget(id); reloadCurrentState() }
    fun deleteEntry(id: Long) = launchAction { store.deleteEntry(id); reloadCurrentState() }

    fun createBackup(openOutput: () -> OutputStream?, done: (Boolean) -> Unit = {}): Boolean {
        val generation = beginBackup() ?: run { done(false); return false }
        runBackup(generation, "Резервная копия сохранена", done) {
            store.exportBackup(requireNotNull(openOutput()) { "Не удалось открыть файл резервной копии" })
        }
        return true
    }

    fun restoreBackup(openInput: () -> InputStream?, done: (Boolean) -> Unit = {}): Boolean {
        val generation = beginBackup() ?: run { done(false); return false }
        runBackup(generation, "Данные восстановлены", done) {
            store.restoreBackup(requireNotNull(openInput()) { "Не удалось открыть резервную копию" })
            reloadCurrentState()
        }
        return true
    }

    private fun beginBackup(): Long? = synchronized(backupLock) {
        if (backupInFlight) return@synchronized null
        backupInFlight = true
        val generation = ++backupGeneration
        mutableState.value = mutableState.value.copy(backupBusy = true, error = null, notice = null)
        generation
    }

    private fun runBackup(
        generation: Long,
        successNotice: String,
        done: (Boolean) -> Unit,
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            var success = false
            var failure: String? = null
            try {
                withContext(io) { block() }
                success = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failure = error.message ?: "Не удалось обработать резервную копию"
            } finally {
                val active = synchronized(backupLock) {
                    if (!backupInFlight || backupGeneration != generation) false else {
                        backupInFlight = false
                        mutableState.value = mutableState.value.copy(
                            backupBusy = false,
                            error = failure,
                            notice = if (success) successNotice else null,
                        )
                        true
                    }
                }
                if (active) done(success)
            }
        }
    }

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
        val allBudgets = store.budgets(true)
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
            budgets = budgets, allBudgets = allBudgets, budgetStatuses = statuses, selectedMonth = month, error = null,
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
