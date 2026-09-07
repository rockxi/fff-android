package ru.rockxi.fff.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalBrowserLaunchTest {
    @Test
    fun `reports successful external launch`() {
        var launched = false

        assertTrue(safelyLaunchExternalBrowser { launched = true })
        assertTrue(launched)
    }

    @Test
    fun `security failure cannot escape click handler`() {
        assertFalse(safelyLaunchExternalBrowser { throw SecurityException("blocked") })
    }

    @Test
    fun `other runtime launch failure cannot escape click handler`() {
        assertFalse(safelyLaunchExternalBrowser { throw IllegalArgumentException("bad uri") })
    }
}
