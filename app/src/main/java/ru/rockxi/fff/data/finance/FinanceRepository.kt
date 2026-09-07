package ru.rockxi.fff.data.finance

import androidx.room.withTransaction
import java.util.Currency
import java.time.YearMonth
import java.time.ZoneId
import java.io.InputStream
import java.io.OutputStream

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

    suspend fun deleteAccount(id: Long) = database.withTransaction {
        val account = requireNotNull(dao.account(id)) { "Account not found" }
        require(account.archived) { "Сначала переместите счёт в архив" }
        require(dao.accountUsage(id) == 0) { "Сначала удалите связанные операции" }
        require(dao.deleteAccount(id) == 1) { "Account not found" }
    }

    suspend fun createCategory(name: String, kind: CategoryKind, budgetId: Long? = null, emoji: String = "🏷️"): Long {
        require(name.isNotBlank()) { "Category name is required" }
        val selected = budgetId?.let { dao.budget(it) } ?: dao.budgetByName("Общие")
        requireNotNull(selected) { "Budget not found" }
        val normalizedEmoji = emoji.trim()
        require(normalizedEmoji.isNotBlank() && normalizedEmoji.length <= 8) { "Выберите один смайлик" }
        return dao.insertCategory(CategoryEntity(name = name.trim(), kind = kind, budgetId = selected.id, emoji = normalizedEmoji))
    }

    suspend fun createBudget(name: String, currency: String): Long {
        require(name.isNotBlank()) { "Budget name is required" }
        val normalized = currency.trim().uppercase()
        require(runCatching { Currency.getInstance(normalized) }.isSuccess) { "Invalid currency" }
        return dao.insertBudget(BudgetEntity(name = name.trim(), currency = normalized))
    }

    suspend fun budgets(includeArchived: Boolean = false) = dao.budgets(includeArchived)

    suspend fun archiveBudget(id: Long, archived: Boolean = true) {
        require(dao.setBudgetArchived(id, archived) == 1) { "Budget not found" }
    }

    suspend fun deleteBudget(id: Long) = database.withTransaction {
        val budget = requireNotNull(dao.budget(id)) { "Budget not found" }
        require(budget.archived) { "Сначала переместите бюджет в архив" }
        require(dao.budgetUsage(id) == 0) { "Сначала удалите или перенесите категории бюджета" }
        require(dao.budgetAllocationUsage(id) == 0) { "Сначала удалите распределения бюджета" }
        require(dao.deleteBudget(id) == 1) { "Budget not found" }
    }

    suspend fun allocate(budgetId: Long, month: YearMonth, amountMinor: Long) {
        require(amountMinor >= 0) { "Allocation cannot be negative" }
        requireNotNull(dao.budget(budgetId)) { "Budget not found" }
        dao.setAllocation(BudgetAllocationEntity(budgetId, month.toString(), amountMinor))
    }

    suspend fun removeAllocation(budgetId: Long, month: YearMonth) {
        requireNotNull(dao.budget(budgetId)) { "Budget not found" }
        require(dao.deleteAllocation(budgetId, month.toString()) == 1) { "Распределение бюджета не найдено" }
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

    suspend fun deleteCategory(id: Long) = database.withTransaction {
        val category = requireNotNull(dao.category(id)) { "Category not found" }
        require(category.archived) { "Сначала переместите категорию в архив" }
        require(dao.categoryUsage(id) == 0) { "Сначала удалите связанные операции" }
        require(dao.deleteCategory(id) == 1) { "Category not found" }
    }

    suspend fun deleteEntry(id: Long) = database.withTransaction {
        val entry = requireNotNull(dao.entry(id)) { "Операция не найдена" }
        val source = requireNotNull(dao.account(entry.accountId)) { "Счёт операции не найден" }
        when (entry.kind) {
            EntryKind.INCOME -> requireBalanceUpdated(source.id, exactBalance(source.balanceMinor, -entry.amountMinor))
            EntryKind.EXPENSE -> requireBalanceUpdated(source.id, exactBalance(source.balanceMinor, entry.amountMinor))
            EntryKind.TRANSFER -> {
                val target = requireNotNull(entry.transferAccountId?.let { dao.account(it) }) { "Счёт назначения не найден" }
                requireBalanceUpdated(source.id, exactBalance(source.balanceMinor, entry.amountMinor))
                requireBalanceUpdated(target.id, exactBalance(target.balanceMinor, -entry.amountMinor))
            }
        }
        require(dao.deleteEntry(id) == 1) { "Операция не найдена" }
    }

    suspend fun exportBackup(): String = database.withTransaction {
        FinanceBackupCodec.encode(FinanceBackup(
            exportedAt = System.currentTimeMillis(),
            accounts = dao.accounts(true).map { BackupAccount(it.id, it.name, it.currency, it.balanceMinor, it.archived, it.createdAt) },
            budgets = dao.budgets(true).map { BackupBudget(it.id, it.name, it.currency, it.archived) },
            allocations = dao.allocations().map { BackupAllocation(it.budgetId, it.month, it.amountMinor) },
            categories = (dao.categories(CategoryKind.INCOME, true) + dao.categories(CategoryKind.EXPENSE, true)).map { BackupCategory(it.id, it.name, it.kind.name, it.archived, it.createdAt, it.budgetId, it.emoji) },
            entries = dao.entries().map { BackupEntry(it.id, it.kind.name, it.amountMinor, it.accountId, it.transferAccountId, it.categoryId, it.note, it.occurredAt) },
        ))
    }

    suspend fun exportBackup(output: OutputStream) {
        output.writer(Charsets.UTF_8).use { it.write(exportBackup()) }
    }

    suspend fun restoreBackup(input: InputStream) = restoreBackup(
        input.bufferedReader(Charsets.UTF_8).use { reader ->
            val result = StringBuilder()
            val chunk = CharArray(8 * 1024)
            while (true) {
                val count = reader.read(chunk)
                if (count < 0) break
                require(result.length + count <= MAX_BACKUP_CHARS) { "Резервная копия слишком большая" }
                result.append(chunk, 0, count)
            }
            result.toString()
        },
    )

    suspend fun restoreBackup(document: String) {
        require(document.length <= MAX_BACKUP_CHARS) { "Резервная копия слишком большая" }
        val backup = FinanceBackupCodec.decode(document)
        require(backup.formatVersion == 1) { "Неподдерживаемая версия резервной копии" }
        val accounts = backup.accounts.map {
            require(it.id != 0L && it.name.isNotBlank()) { "Некорректный счёт в копии" }
            AccountEntity(it.id, it.name.trim(), normalizeCurrency(it.currency), it.balanceMinor, it.archived, it.createdAt)
        }
        val budgets = backup.budgets.map {
            require(it.id != 0L && it.name.isNotBlank()) { "Некорректный бюджет в копии" }
            BudgetEntity(it.id, it.name.trim(), normalizeCurrency(it.currency), it.archived)
        }
        val budgetIds = budgets.map { it.id }.toSet()
        val accountIds = accounts.map { it.id }.toSet()
        require(accountIds.size == accounts.size && budgetIds.size == budgets.size) { "Повторяющиеся идентификаторы в копии" }
        require(budgets.map { it.name }.toSet().size == budgets.size) { "Повторяющиеся названия бюджетов в копии" }
        val budgetsById = budgets.associateBy { it.id }
        val accountsById = accounts.associateBy { it.id }
        val categories = backup.categories.map {
            require(it.budgetId in budgetIds) { "Категория ссылается на отсутствующий бюджет" }
            require(it.id != 0L && it.name.isNotBlank() && validEmoji(it.emoji)) { "Некорректная категория в копии" }
            CategoryEntity(it.id, it.name.trim(), parseCategoryKind(it.kind), it.archived, it.createdAt, it.budgetId, it.emoji.trim())
        }
        val categoryIds = categories.map { it.id }.toSet()
        require(categoryIds.size == categories.size) { "Повторяющиеся категории в копии" }
        val allocations = backup.allocations.map {
            require(it.budgetId in budgetIds && it.amountMinor >= 0) { "Некорректный бюджет в копии" }
            YearMonth.parse(it.month)
            BudgetAllocationEntity(it.budgetId, it.month, it.amountMinor)
        }
        require(allocations.map { it.budgetId to it.month }.toSet().size == allocations.size) { "Повторяющиеся распределения в копии" }
        val categoriesById = categories.associateBy { it.id }
        val entries = backup.entries.map {
            require(it.id != 0L && it.amountMinor > 0 && it.accountId in accountIds) { "Некорректная операция в копии" }
            val kind = parseEntryKind(it.kind)
            if (kind == EntryKind.TRANSFER) {
                require(it.transferAccountId in accountIds && it.transferAccountId != it.accountId && it.categoryId == null) { "Некорректный перевод в копии" }
                require(accountsById.getValue(it.accountId).currency == accountsById.getValue(it.transferAccountId!!).currency) { "Валюты перевода не совпадают" }
            } else {
                require(it.categoryId in categoryIds && it.transferAccountId == null) { "Некорректная категория операции" }
                val category = categoriesById.getValue(it.categoryId!!)
                require(category.kind.name == kind.name) { "Тип категории операции не совпадает" }
                if (kind == EntryKind.EXPENSE) require(budgetsById.getValue(category.budgetId).currency == accountsById.getValue(it.accountId).currency) { "Валюта бюджета операции не совпадает" }
            }
            LedgerEntryEntity(it.id, kind, it.amountMinor, it.accountId, it.transferAccountId, it.categoryId, it.note, it.occurredAt)
        }
        require(entries.map { it.id }.toSet().size == entries.size) { "Повторяющиеся операции в копии" }
        database.withTransaction {
            dao.clearEntries(); dao.clearCategories(); dao.clearAllocations(); dao.clearBudgets(); dao.clearAccounts()
            if (accounts.isNotEmpty()) dao.insertAccounts(accounts)
            if (budgets.isNotEmpty()) dao.insertBudgets(budgets)
            if (allocations.isNotEmpty()) dao.insertAllocations(allocations)
            if (categories.isNotEmpty()) dao.insertCategories(categories)
            if (entries.isNotEmpty()) dao.insertEntries(entries)
        }
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

    private fun normalizeCurrency(value: String): String {
        val normalized = value.trim().uppercase()
        require(runCatching { Currency.getInstance(normalized) }.isSuccess) { "Некорректная валюта в копии" }
        return normalized
    }

    private fun validEmoji(value: String): Boolean = value.trim().isNotBlank() && value.trim().length <= 8
    private fun parseCategoryKind(value: String) = runCatching { CategoryKind.valueOf(value) }
        .getOrElse { throw IllegalArgumentException("Некорректный тип категории в копии") }
    private fun parseEntryKind(value: String) = runCatching { EntryKind.valueOf(value) }
        .getOrElse { throw IllegalArgumentException("Некорректный тип операции в копии") }
    private suspend fun requireBalanceUpdated(id: Long, balance: Long) {
        require(dao.setBalance(id, balance) == 1) { "Счёт операции не найден" }
    }
    private fun exactBalance(balance: Long, delta: Long): Long = try {
        Math.addExact(balance, delta)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("Удаление операции приведёт к переполнению баланса")
    }

    private companion object { const val MAX_BACKUP_CHARS = 16 * 1024 * 1024 }
}
