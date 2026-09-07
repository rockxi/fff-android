package ru.rockxi.fff.data.finance

import androidx.room.TypeConverter

class FinanceConverters {
    @TypeConverter fun categoryKind(value: String): CategoryKind = CategoryKind.valueOf(value)
    @TypeConverter fun categoryKind(value: CategoryKind): String = value.name
    @TypeConverter fun entryKind(value: String): EntryKind = EntryKind.valueOf(value)
    @TypeConverter fun entryKind(value: EntryKind): String = value.name
}
