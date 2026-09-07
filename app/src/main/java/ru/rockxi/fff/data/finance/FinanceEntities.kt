package ru.rockxi.fff.data.finance

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class CategoryKind { INCOME, EXPENSE }
enum class EntryKind { INCOME, EXPENSE, TRANSFER }

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val currency: String,
    val balanceMinor: Long = 0,
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: CategoryKind,
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "ledger_entries",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["transferAccountId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("accountId"), Index("transferAccountId"), Index("categoryId"), Index("occurredAt")],
)
data class LedgerEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: EntryKind,
    val amountMinor: Long,
    val accountId: Long,
    val transferAccountId: Long? = null,
    val categoryId: Long? = null,
    val note: String = "",
    val occurredAt: Long = System.currentTimeMillis(),
)
