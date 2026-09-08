package ru.rockxi.fff.data.gym

import androidx.room.TypeConverter

internal class GymConverters {
    @TypeConverter fun setMode(value: String): GymSetMode = GymSetMode.valueOf(value)
    @TypeConverter fun setMode(value: GymSetMode): String = value.name
}
