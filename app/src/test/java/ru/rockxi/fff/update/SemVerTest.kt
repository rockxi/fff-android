package ru.rockxi.fff.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {
    @Test
    fun `detects newer stable version`() {
        assertTrue(SemVer.parse("v1.3.0")!! > SemVer.parse("1.2.9")!!)
        assertTrue(SemVer.parse("2.0.0")!! > SemVer.parse("1.99.99")!!)
    }

    @Test
    fun `build metadata does not change precedence`() {
        assertEquals(SemVer.parse("1.2.3+build.8"), SemVer.parse("1.2.3+build.9"))
    }

    @Test
    fun `stable version is newer than prerelease`() {
        assertTrue(SemVer.parse("1.0.0")!! > SemVer.parse("1.0.0-rc.1")!!)
        assertTrue(SemVer.parse("1.0.0-rc.2")!! > SemVer.parse("1.0.0-rc.1")!!)
    }

    @Test
    fun `rejects non semver tags`() {
        assertNull(SemVer.parse("release-1"))
        assertNull(SemVer.parse("1.02.3"))
        assertNull(SemVer.parse("1.0.0-01"))
        assertNull(SemVer.parse("1.0.0-rc.01"))
        assertNull(SemVer.parse(" 1.0.0"))
        assertNull(SemVer.parse("1.0.0 "))
        assertNull(SemVer.parse("\t1.0.0\n"))
    }

    @Test
    fun `compares arbitrary length numeric prerelease identifiers`() {
        val larger = SemVer.parse("1.0.0-99999999999999999999999999999999999999")!!
        val smaller = SemVer.parse("1.0.0-88888888888888888888888888888888888888")!!
        val fewerDigits = SemVer.parse("1.0.0-9999999999999999999999999999999999999")!!

        assertTrue(larger > smaller)
        assertTrue(smaller > fewerDigits)
        assertEquals(SemVer(1, 0, 0, listOf("0")), SemVer.parse("1.0.0-0"))
    }
}
