package ru.rockxi.fff.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckGateTest {
    @Test
    fun `does not access release source before user opt in`() {
        var requestCount = 0
        val gate = UpdateCheckGate(
            ReleaseSource {
                requestCount += 1
                release("1.1.0")
            },
        )

        assertNull(gate.findUpdate("1.0.0", userOptedIn = false))
        assertEquals(0, requestCount)
    }

    @Test
    fun `checks release source after user opt in`() {
        var requestCount = 0
        val gate = UpdateCheckGate(
            ReleaseSource {
                requestCount += 1
                release("1.1.0")
            },
        )

        assertEquals(SemVer(1, 1, 0), gate.findUpdate("1.0.0", userOptedIn = true)?.version)
        assertEquals(1, requestCount)
    }

    @Test
    fun `release source failure is non blocking`() {
        val gate = UpdateCheckGate(ReleaseSource { error("offline") })

        assertNull(gate.findUpdate("1.0.0", userOptedIn = true))
    }

    @Test fun `explicit check distinguishes current update and network failure`() {
        assertEquals(UpdateCheckResult.Current, UpdateCheckGate(ReleaseSource { release("1.0.0") }).check("1.0.0"))
        assertTrue(UpdateCheckGate(ReleaseSource { release("1.1.0") }).check("1.0.0") is UpdateCheckResult.Available)
        assertEquals(UpdateCheckResult.Failed, UpdateCheckGate(ReleaseSource { null }).check("1.0.0"))
    }

    private fun release(version: String) = GitHubRelease(
        tagName = version,
        version = SemVer.parse(version)!!,
        releaseUrl = "https://github.com/rockxi/fff-android/releases/tag/$version",
        apkUrl = null,
    )
}
