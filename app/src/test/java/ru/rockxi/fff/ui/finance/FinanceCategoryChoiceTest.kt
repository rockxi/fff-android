package ru.rockxi.fff.ui.finance

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.rockxi.fff.data.finance.CategoryEntity
import ru.rockxi.fff.data.finance.CategoryKind

class FinanceCategoryChoiceTest {
    @Test fun `out of budget tile invokes its callback`() {
        var outOfBudget = 0
        var selected: Long? = null
        dispatchCategoryChoice(null, { outOfBudget++ }, { selected = it })
        assertEquals(1, outOfBudget)
        assertEquals(null, selected)
    }

    @Test fun `ordinary category tile invokes selection with id`() {
        var outOfBudget = 0
        var selected: Long? = null
        val category = CategoryEntity(id = 42, name = "Food", kind = CategoryKind.EXPENSE)
        dispatchCategoryChoice(category, { outOfBudget++ }, { selected = it })
        assertEquals(0, outOfBudget)
        assertEquals(42L, selected)
    }
}
