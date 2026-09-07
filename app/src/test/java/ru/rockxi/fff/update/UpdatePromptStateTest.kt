package ru.rockxi.fff.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePromptStateTest {
    @Test fun `download starts only after confirmation and exposes progress`() {
        val initial = UpdatePromptState()
        assertTrue(initial.downloadState is UpdateDownloadState.Idle)
        val confirmed = initial.confirmDownload()
        assertEquals(UpdateDownloadState.Downloading(0, null), confirmed.downloadState)
        assertEquals(
            UpdateDownloadState.Downloading(512, 1024),
            confirmed.onDownloadState(confirmed.activeAttempt, UpdateDownloadState.Downloading(512, 1024)).downloadState,
        )
    }

    @Test fun `cancel is visible and ignores stale download callbacks`() {
        val cancelled = UpdatePromptState().confirmDownload().cancelDownload()
        assertEquals(UpdateDownloadState.Failed("Загрузка отменена"), cancelled.downloadState)
        assertSame(cancelled, cancelled.onDownloadState(1, UpdateDownloadState.Ready(File("stale.apk"))))
    }

    @Test fun `cancel retry rejects queued callback from old attempt`() {
        val attemptA = UpdatePromptState().confirmDownload(10)
        val cancelled = attemptA.cancelDownload(11)
        val attemptB = cancelled.confirmDownload(12)
            .onDownloadState(12, UpdateDownloadState.Downloading(20, 100))

        val afterLateProgressA = attemptB.onDownloadState(10, UpdateDownloadState.Downloading(90, 100))
        val afterLateReadyA = afterLateProgressA.onDownloadState(10, UpdateDownloadState.Ready(File("old.apk")))

        assertSame(attemptB, afterLateProgressA)
        assertSame(attemptB, afterLateReadyA)
        assertEquals(12, afterLateReadyA.activeAttempt)
        assertEquals(UpdateDownloadState.Downloading(20, 100), afterLateReadyA.downloadState)
    }

    @Test fun `queued progress from current attempt cannot regress completed download`() {
        val apk = File("current.apk")
        val completed = UpdatePromptState().confirmDownload(4).completeDownload(4, apk)

        val afterLateProgress = completed.onDownloadState(4, UpdateDownloadState.Downloading(99, 100))

        assertSame(completed, afterLateProgress)
        assertEquals(UpdateDownloadState.Ready(apk), afterLateProgress.downloadState)
    }

    @Test fun `retry uses isolated files so old attempt cleanup cannot delete new partial`() {
        val cache = File("build/update-path-${System.nanoTime()}")
        val attemptA = updateDestination(cache, "v1.2.3", 7, "session-a")
        val attemptB = updateDestination(cache, "v1.2.3", 9, "session-b")
        val partialA = File(attemptA.parentFile, "${attemptA.name}.part")
        val partialB = File(attemptB.parentFile, "${attemptB.name}.part")
        partialA.parentFile!!.mkdirs()
        partialA.writeText("old")
        partialB.writeText("new")

        partialA.delete() // Downloader A unwinds after retry B has already started.

        assertFalse(attemptA.path == attemptB.path)
        assertFalse(partialA.exists())
        assertTrue(partialB.exists())
        assertEquals("new", partialB.readText())
        cache.deleteRecursively()
    }

    @Test fun `retry clears cancellation and old error`() {
        val retried = UpdatePromptState(message = "old").confirmDownload().cancelDownload().confirmDownload()
        assertEquals(UpdateDownloadState.Downloading(0, null), retried.downloadState)
        assertNull(retried.message)
    }

    @Test fun `permission return preserves downloaded apk and reports denial`() {
        val apk = File("update.apk")
        val waiting = UpdatePromptState(UpdateDownloadState.Ready(apk)).onPermissionRequested()
        assertTrue(waiting.awaitingPermission)
        val denied = waiting.onPermissionReturned(false)
        assertFalse(denied.awaitingPermission)
        assertEquals(UpdateDownloadState.Ready(apk), denied.downloadState)
        assertTrue(denied.message!!.contains("не выдано"))
        val granted = waiting.onPermissionReturned(true)
        assertFalse(granted.awaitingPermission)
        assertNull(granted.message)
        assertEquals(UpdateDownloadState.Ready(apk), granted.downloadState)
    }
}
