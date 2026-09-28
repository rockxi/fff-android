package ru.rockxi.fff.data.calories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalorieCatalogClientTest {
    @Test fun parsesBoundedSearchWithoutPersistableContent() {
        val items = CalorieCatalogJson.search("""{"source":"Perek","fetchedAt":1780000000,"items":[{"id":"p_bW9sb2tvLTE","name":"Овсянка","brand":null}]}""")
        assertEquals(listOf(ExternalFoodSummary("p_bW9sb2tvLTE", "Овсянка", null, 1780000000)), items)
    }

    @Test fun rejectsUnknownSource() {
        assertTrue(runCatching { CalorieCatalogJson.search("""{"source":"Other","items":[]}""") }.isFailure)
    }

    @Test fun skipsUnusableServingsButKeepsMetricGrams() {
        val food = CalorieCatalogJson.food("""{"source":"FatSecret","fetchedAt":1780000000,"id":"12","name":"Рис","brand":"Бренд","servings":[{"id":"1","description":"100 г","grams":100,"amount":100,"unit":"g","calories":130,"protein":2.5,"fat":0.3,"carbs":28},{"id":"2","description":"1 cup","grams":null,"amount":null,"unit":"ml","calories":130,"protein":2.5,"fat":0.3,"carbs":28}]}""")
        assertEquals("Рис", food.name)
        assertEquals(1, food.servings.size)
        assertEquals(100.0, food.servings.single().grams!!, 0.0)
        assertEquals("FatSecret", food.source)
    }

    @Test fun parsesPerekSourceLinkAndServing() {
        val food = CalorieCatalogJson.food("""{"source":"Perek","sourceUrl":"https://perek.us/food/magnit/sup-gribnoy","fetchedAt":1780000000,"id":"p_bWFnbml0L3N1cC1ncmlibm95","name":"Суп грибной","servings":[{"id":"0","description":"100 г","grams":100,"amount":100,"unit":"g","calories":310,"protein":10,"fat":3,"carbs":60}]}""")
        assertEquals("Perek", food.source)
        assertEquals("https://perek.us/food/magnit/sup-gribnoy", food.sourceUrl)
    }

    @Test fun externalPortionUsesServingMassAndHalfUpRounding() {
        val serving = ExternalServing("7", "25 г", 25.0, 50.0, 2.5, 1.0, 7.25)
        assertEquals(NutritionTotals(100, 5_000, 2_000, 14_500), externalPortionNutrition(serving, 50_000))
    }

    @Test fun milliliterServingKeepsVolumeDistinctFromGrams() {
        val serving = ExternalServing("9", "200 мл", null, 90.0, 3.0, 2.0, 12.0, 200.0, "ml")
        assertEquals("ml", serving.measureUnit)
        assertEquals(NutritionTotals(45, 1_500, 1_000, 6_000), externalPortionNutrition(serving, 100_000))
    }

    @Test fun transientContentExpiresWithinTwentyFourHours() {
        assertTrue(catalogContentFresh(100, 86_499))
        assertTrue(!catalogContentFresh(100, 86_500))
        assertTrue(!catalogContentFresh(100, 99))
    }
}
