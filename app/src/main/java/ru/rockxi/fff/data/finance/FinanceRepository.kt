package ru.rockxi.fff.data.finance

import androidx.room.withTransaction
import java.util.Currency
import java.time.YearMonth
import java.time.ZoneId

data class BudgetStatus(val budget: BudgetEntity, val allocatedMinor: Long, val spentMinor: Long, val remainingMinor: Long)

internal class FinanceRepository(private val database: FinanceDatabase) {
    private val dao = database.financeDao()

    suspend fun createAccount(name: String, currency: String, initialBalanceMinor: Long = 0): Long {
        require(name.isNotBlank()) { "Account name is required" }
        require(initialBalanceMinor >= 0) { "Initial balance cannot be negative" }
        val normalized = currency.trim().uppercase()
        require(runCatching { Currency.getInstance(normalized) }.isSuccess) { "Invalid currency" }
        return dao.insertAccount(AccountEntity(name = name.trim(), currency = normalized, balanceMinor = initialBalanceMinor))
    }

    suspend fun archiveAccount(id: Long, archived: Boolean = true) {
        require(dao.setAccountArchived(id, archived) == 1) { "Account not found" }
    }

    suspend fun createCategory(name: String, kind: CategoryKind, budgetId: Long? = null): Long {
        require(name.isNotBlank()) { "Category name is required" }
        val selected = budgetId?.let { dao.budget(it) } ?: dao.budgetByName("Общие")
        requireNotNull(selected) { "Budget not found" }
        return dao.insertCategory(CategoryEntity(name = name.trim(), kind = kind, budgetId = selected.id))
    }

    suspend fun createBudget(name: String, currency: String): Long {
        require(name.isNotBlank()) { "Budget name is required" }
        val normalized = currency.trim().uppercase()
        require(runCatching { Currency.getInstance(normalized) }.isSuccess) { "Invalid currency" }
        return dao.insertBudget(BudgetEntity(name = name.trim(), currency = normalized))
    }

    suspend fun budgets() = dao.budgets()

    suspend fun allocate(budgetId: Long, month: YearMonth, amountMinor: Long) {
        require(amountMinor >= 0) { "Allocation cannot be negative" }
        requireNotNull(dao.budget(budgetId)) { "Budget not found" }
        dao.setAllocation(BudgetAllocationEntity(budgetId, month.toString(), amountMinor))
    }

    suspend fun budgetStatus(budgetId: Long, month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): BudgetStatus {
        val budget = requireNotNull(dao.budget(budgetId)) { "Budget not found" }
        val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val until = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val allocated = dao.allocation(budgetId, month.toString()) ?: 0
        val spent = saturated(dao.spentAmounts(budgetId, from, until))
        return BudgetStatus(budget, allocated, spent, allocated - spent)
    }

    suspend fun archiveCategory(id: Long, archived: Boolean = true) {
        require(dao.setCategoryArchived(id, archived) == 1) { "Category not found" }
    }

    suspend fun addIncome(accountId: Long, categoryId: Long, amountMinor: Long, note: String = "", occurredAt: Long = System.currentTimeMillis()): Long =
        addCategorized(EntryKind.INCOME, accountId, categoryId, amountMinor, note, occurredAt)

    suspend fun addExpense(accountId: Long, categoryId: Long, amountMinor: Long, note: String = "", occurredAt: Long = System.currentTimeMillis()): Long =
        addCategorized(EntryKind.EXPENSE, accountId, categoryId, amountMinor, note, occurredAt)

    private suspend fun addCategorized(kind: EntryKind, accountId: Long, categoryId: Long, amountMinor: Long, note: String, occurredAt: Long): Long = database.withTransaction {
        require(amountMinor > 0) { "Amount must be positive" }
        val account = requireNotNull(dao.account(accountId)) { "Account not found" }
        require(!account.archived) { "Account is archived" }
        val category = requireNotNull(dao.category(categoryId)) { "Category not found" }
        require(!category.archived && category.kind.name == kind.name) { "Category kind does not match entry" }
        if (kind == EntryKind.EXPENSE) {
            val budget = requireNotNull(dao.budget(category.budgetId)) { "Budget not found" }
            require(budget.currency == account.currency) { "Budget and account currencies must match" }
        }
        val delta = if (kind == EntryKind.INCOME) amountMinor else -amountMinor
        val balance = try { Math.addExact(account.balanceMinor, delta) } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Balance overflow")
        }
        dao.setBalance(accountId, balance)
        dao.insertEntry(LedgerEntryEntity(kind = kind, amountMinor = amountMinor, accountId = accountId, categoryId = categoryId, note = note.trim(), occurredAt = occurredAt))
    }

    suspend fun transfer(fromAccountId: Long, toAccountId: Long, amountMinor: Long, note: String = "", occurredAt: Long = System.currentTimeMillis()): Long = database.withTransaction {
        require(amountMinor > 0) { "Amount must be positive" }
        require(fromAccountId != toAccountId) { "Transfer accounts must differ" }
        val from = requireNotNull(dao.account(fromAccountId)) { "Source account not found" }
        val to = requireNotNull(dao.account(toAccountId)) { "Destination account not found" }
        require(!from.archived && !to.archived) { "Account is archived" }
        require(from.currency == to.currency) { "Transfer currencies must match" }
        val fromBalance = try { Math.subtractExact(from.balanceMinor, amountMinor) } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Source balance overflow")
        }
        val toBalance = try { Math.addExact(to.balanceMinor, amountMinor) } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Destination balance overflow")
        }
        dao.setBalance(fromAccountId, fromBalance)
        dao.setBalance(toAccountId, toBalance)
        dao.insertEntry(LedgerEntryEntity(kind = EntryKind.TRANSFER, amountMinor = amountMinor, accountId = fromAccountId, transferAccountId = toAccountId, note = note.trim(), occurredAt = occurredAt))
    }

    suspend fun accounts(includeArchived: Boolean = false) = dao.accounts(includeArchived)
    suspend fun categories(kind: CategoryKind, includeArchived: Boolean = false) = dao.categories(kind, includeArchived)
    suspend fun entries(accountId: Long? = null, from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE) = dao.entries(accountId, from, to)
    suspend fun totals(accountId: Long? = null, from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE): FinanceTotals {
        return FinanceTotals(
            incomeMinor = saturated(dao.amounts(EntryKind.INCOME, accountId, from, to)),
            expenseMinor = saturated(dao.amounts(EntryKind.EXPENSE, accountId, from, to)),
        )
    }

    private fun saturated(values: List<Long>): Long = values.fold(0L) { total, value ->
        if (Long.MAX_VALUE - total < value) Long.MAX_VALUE else total + value
    }
}
