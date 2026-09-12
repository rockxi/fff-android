package ru.rockxi.fff.data.calories

import androidx.room.TypeConverter

internal class CalorieConverters {
    @TypeConverter fun mealType(value: String): MealType = MealType.valueOf(value)
    @TypeConverter fun mealType(value: MealType): String = value.name
}
