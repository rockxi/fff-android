package ru.rockxi.fff.ui.calories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.rockxi.fff.data.calories.FoodEntity

class CaloriePickerTest {
    private fun food(id: Long) = FoodEntity(id,"Продукт $id",100,0,0,0,0,0)

    @Test fun `created product can be selected after repository reload even if list order changed`() {
        assertEquals(7L,findNewlyCreatedFood(setOf(2,4),listOf(food(7),food(4),food(2)))?.id)
    }

    @Test fun `editing existing food does not create a new choice`() {
        assertNull(findNewlyCreatedFood(setOf(2,4),listOf(food(4),food(2))))
    }
}
