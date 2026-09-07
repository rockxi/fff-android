package ru.rockxi.fff.data.finance

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

data class FinanceTotals(val incomeMinor: Long, val expenseMinor: Long)

@Dao
internal interface FinanceDao {
    @Insert suspend fun insertAccount(account: AccountEntity): Long
    @Query("SELECT * FROM accounts WHERE id = :id") suspend fun account(id: Long): AccountEntity?
    @Query("SELECT * FROM accounts WHERE (:includeArchived OR archived = 0) ORDER BY createdAt, id") suspend fun accounts(includeArchived: Boolean = false): List<AccountEntity>
    @Query("UPDATE accounts SET archived = :archived WHERE id = :id") suspend fun setAccountArchived(id: Long, archived: Boolean): Int
    @Query("UPDATE accounts SET balanceMinor = :balance WHERE id = :id") suspend fun setBalance(id: Long, balance: Long): Int

    @Insert suspend fun insertCategory(category: CategoryEntity): Long
    @Query("SELECT * FROM categories WHERE id = :id") suspend fun category(id: Long): CategoryEntity?
    @Query("SELECT * FROM categories WHERE kind = :kind AND (:includeArchived OR archived = 0) ORDER BY name") suspend fun categories(kind: CategoryKind, includeArchived: Boolean = false): List<CategoryEntity>
    @Query("UPDATE categories SET archived = :archived WHERE id = :id") suspend fun setCategoryArchived(id: Long, archived: Boolean): Int

    @Insert suspend fun insertEntry(entry: LedgerEntryEntity): Long
    @Query("SELECT * FROM ledger_entries WHERE (:accountId IS NULL OR accountId = :accountId OR transferAccountId = :accountId) AND occurredAt BETWEEN :from AND :to ORDER BY occurredAt DESC, id DESC")
    suspend fun entries(accountId: Long? = null, from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE): List<LedgerEntryEntity>

    @Query("SELECT amountMinor FROM ledger_entries WHERE kind = :kind AND occurredAt BETWEEN :from AND :to AND (:accountId IS NULL OR accountId = :accountId OR transferAccountId = :accountId)")
    suspend fun amounts(kind: EntryKind, accountId: Long? = null, from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE): List<Long>
}
