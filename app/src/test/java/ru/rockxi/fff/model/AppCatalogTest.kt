package ru.rockxi.fff.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.rockxi.fff.navigation.Destination

class AppCatalogTest {
    @Test
    fun `catalog exposes all applications once`() {
        assertEquals(listOf("harness", "control", "finance", "gym"), AppCatalog.applications.map { it.id })
        assertEquals(4, AppCatalog.applications.map { it.destination }.distinct().size)
        assertTrue(AppCatalog.applications.all { it.name.isNotBlank() && it.description.isNotBlank() })
    }

    @Test
    fun `catalog cards point to expected destinations`() {
        assertEquals(Destination.RemoteControl, AppCatalog.applications.first { it.id == "control" }.destination)
        assertEquals(Destination.Finance, AppCatalog.applications.first { it.id == "finance" }.destination)
        assertEquals(Destination.Harness, AppCatalog.applications.first { it.id == "harness" }.destination)
        assertEquals(Destination.Gym, AppCatalog.applications.first { it.id == "gym" }.destination)
    }
}
