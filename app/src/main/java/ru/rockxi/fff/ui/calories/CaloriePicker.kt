package ru.rockxi.fff.ui.calories

import ru.rockxi.fff.data.calories.FoodEntity

internal fun findNewlyCreatedFood(previousIds: Set<Long>, foods: List<FoodEntity>): FoodEntity? =
    foods.filter { it.id !in previousIds }.maxByOrNull { it.id }
