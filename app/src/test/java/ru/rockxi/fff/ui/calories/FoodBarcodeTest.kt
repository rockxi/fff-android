package ru.rockxi.fff.ui.calories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoodBarcodeTest {
    @Test fun `ean 13 remains unchanged`() {
        assertEquals("4006381333931", normalizeFoodBarcode("4006381333931"))
    }

    @Test fun `upc a receives a leading zero`() {
        assertEquals("0036000291452", normalizeFoodBarcode("036000291452"))
    }

    @Test fun `ean 8 receives five leading zeros`() {
        assertEquals("0000096385074", normalizeFoodBarcode("96385074"))
    }

    @Test fun `rejects unsupported, non numeric and invalid checksums`() {
        assertNull(normalizeFoodBarcode(null))
        assertNull(normalizeFoodBarcode(""))
        assertNull(normalizeFoodBarcode("0123456"))
        assertNull(normalizeFoodBarcode("4006381333932"))
        assertNull(normalizeFoodBarcode("400638133393A"))
        assertNull(normalizeFoodBarcode("036000291453"))
        assertNull(normalizeFoodBarcode("9780201379625"))
    }
}
