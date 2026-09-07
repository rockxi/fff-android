package ru.rockxi.fff.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DestinationTest {
    @Test
    fun `every destination has unique round trippable route`() {
        assertEquals(Destination.all.size, Destination.all.map { it.route }.distinct().size)
        Destination.all.forEach { assertEquals(it, Destination.fromRoute(it.route)) }
    }

    @Test
    fun `unknown route is rejected`() {
        assertNull(Destination.fromRoute("settings"))
        assertNull(Destination.fromRoute(null))
    }
}

