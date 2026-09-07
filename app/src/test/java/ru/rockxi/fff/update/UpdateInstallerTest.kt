package ru.rockxi.fff.update

import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

@RunWith(RobolectricTestRunner::class)
class UpdateInstallerTest {
    private val release = GitHubRelease(
        "v1.0.0", SemVer(1,0,0),
        "https://github.com/rockxi/fff-android/releases/tag/v1.0.0",
        "https://github.com/rockxi/fff-android/releases/download/v1.0.0/fff-v1.0.0.apk",
        "https://github.com/rockxi/fff-android/releases/download/v1.0.0/fff-v1.0.0.apk.sha256",
    )

    @Test fun `controller publishes progress ready and failure`() = runTest {
        val destination = File("build/test-update.apk")
        val states = mutableListOf<UpdateDownloadState>()
        val success = UpdateDownloadController(ApkDownloader { _, file, progress -> progress(5,10);file })
        assertEquals(destination, success.download(release,destination,states::add))
        assertTrue(states.first() is UpdateDownloadState.Downloading)
        assertEquals(UpdateDownloadState.Downloading(5,10),states[1])
        assertEquals(UpdateDownloadState.Ready(destination),states.last())
        states.clear()
        val failure = UpdateDownloadController(ApkDownloader { _,_,_->error("offline") })
        assertNull(failure.download(release,destination,states::add))
        assertEquals(UpdateDownloadState.Failed("offline"),states.last())
    }

    @Test fun `installer intent grants only content uri read access`() {
        val uri=Uri.parse("content://ru.rockxi.fff.fileprovider/updates/update.apk")
        val intent=apkInstallIntent(uri)
        assertEquals(Intent.ACTION_VIEW,intent.action)
        assertEquals("content",intent.data!!.scheme)
        assertEquals("application/vnd.android.package-archive",intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0)
    }

    @Test fun `downloader rejects non canonical apk before network`() = runTest {
        val hostile=release.copy(apkUrl="https://evil.example/fff-v1.0.0.apk")
        try { SecureApkDownloader().download(hostile,File("build/never.apk")){_,_->};fail("Expected rejection") }
        catch(error:IllegalArgumentException){assertTrue(error.message!!.contains("Недопустимый"))}
    }

    @Test fun `redirect policy allows only expected https asset hosts`() {
        assertTrue(isTrustedDownloadRedirect(URI("https://release-assets.githubusercontent.com/path?signature=ok")))
        assertTrue(isTrustedDownloadRedirect(URI("https://github.com/rockxi/fff-android/releases/download/v1/a.apk")))
        assertFalse(isTrustedDownloadRedirect(URI("http://release-assets.githubusercontent.com/path")))
        assertFalse(isTrustedDownloadRedirect(URI("https://release-assets.githubusercontent.com.evil.test/path")))
        assertFalse(isTrustedDownloadRedirect(URI("https://github.com:443/path")))
    }

    @Test fun `digest mismatch is rejected and cancellation propagates`() = runTest {
        assertFalse(digestsMatch(ByteArray(32),ByteArray(32){1}))
        val controller=UpdateDownloadController(ApkDownloader{_,_,_->throw CancellationException("cancel")})
        try{controller.download(release,File("build/cancel.apk")){};fail("Expected cancellation")}
        catch(_:CancellationException){}
    }

    @Test fun `cancelling actual downloader disconnects read and removes partial without final file`() = runTest {
        val directory = File("build/test-cancel-${System.nanoTime()}").apply { mkdirs() }
        val destination = File(directory, "fff-v1.0.0.apk")
        val started = CountDownLatch(1)
        val apkBytes = "partial-apk".toByteArray()
        val checksum = MessageDigest.getInstance("SHA-256").digest(apkBytes)
            .joinToString("") { "%02x".format(it) } + "  fff-v1.0.0.apk\n"
        var calls = 0
        val blockingInput = BlockingAfterFirstChunkInputStream(apkBytes, started)
        val downloader = SecureApkDownloader(connectionFactory = { url ->
            calls++
            if (calls == 1) FakeConnection(url, checksum.byteInputStream())
            else FakeConnection(url, blockingInput)
        })

        val job = async(Dispatchers.Default) { downloader.download(release, destination) { _, _ -> } }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        job.cancel(CancellationException("test cancellation"))
        try { job.await(); fail("Expected cancellation") } catch (_: CancellationException) {}

        repeat(50) {
            if (!File(directory, "${destination.name}.part").exists()) return@repeat
            Thread.sleep(10)
        }
        assertTrue(blockingInput.closed)
        assertFalse(destination.exists())
        assertFalse(File(directory, "${destination.name}.part").exists())
        directory.deleteRecursively()
    }

    @Test fun `cancellation between connection creation and registration disconnects before network io`() = runTest {
        val directory = File("build/test-register-cancel-${System.nanoTime()}").apply { mkdirs() }
        val destination = File(directory, "fff-v1.0.0.apk")
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        lateinit var connection: TrackingConnection
        val downloader = SecureApkDownloader(connectionFactory = { url ->
            connection = TrackingConnection(url)
            factoryEntered.countDown()
            releaseFactory.await(2, TimeUnit.SECONDS)
            connection
        })

        val job = async(Dispatchers.Default) { downloader.download(release, destination) { _, _ -> } }
        assertTrue(factoryEntered.await(2, TimeUnit.SECONDS))
        job.cancel(CancellationException("cancel before registration"))
        releaseFactory.countDown()
        try { job.await(); fail("Expected cancellation") } catch (_: CancellationException) {}
        repeat(50) {
            if (connection.disconnected) return@repeat
            Thread.sleep(10)
        }

        assertTrue(connection.disconnected)
        assertFalse(connection.responseCodeAccessed)
        assertFalse(connection.inputAccessed)
        assertFalse(destination.exists())
        assertFalse(File(directory, "${destination.name}.part").exists())
        directory.deleteRecursively()
    }

    @Test fun `cancellation after registration wins CAS before response code`() = runTest {
        val directory = File("build/test-phase-cancel-${System.nanoTime()}").apply { mkdirs() }
        val destination = File(directory, "fff-v1.0.0.apk")
        val registered = CountDownLatch(1)
        val continueToIo = CountDownLatch(1)
        lateinit var connection: TrackingConnection
        val downloader = SecureApkDownloader(
            connectionFactory = { url -> TrackingConnection(url).also { connection = it } },
            afterConnectionRegistered = {
                registered.countDown()
                continueToIo.await(2, TimeUnit.SECONDS)
            },
        )

        val job = async(Dispatchers.Default) { downloader.download(release, destination) { _, _ -> } }
        assertTrue(registered.await(2, TimeUnit.SECONDS))
        job.cancel(CancellationException("cancel registered connection"))
        continueToIo.countDown()
        try { job.await(); fail("Expected cancellation") } catch (_: CancellationException) {}
        repeat(50) {
            if (connection.disconnected) return@repeat
            Thread.sleep(10)
        }

        assertTrue(connection.disconnected)
        assertFalse(connection.responseCodeAccessed)
        assertFalse(connection.inputAccessed)
        assertFalse(destination.exists())
        assertFalse(File(directory, "${destination.name}.part").exists())
        directory.deleteRecursively()
    }

    private class FakeConnection(url: URL, private val stream: InputStream) : HttpURLConnection(url) {
        override fun getResponseCode() = 200
        override fun getContentLengthLong() = -1L
        override fun getInputStream() = stream
        override fun disconnect() { stream.close() }
        override fun usingProxy() = false
        override fun connect() = Unit
    }

    private class BlockingAfterFirstChunkInputStream(
        private val firstChunk: ByteArray,
        private val started: CountDownLatch,
    ) : InputStream() {
        @Volatile var closed = false
        private var emitted = false
        override fun read(): Int = error("bulk reads only")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!emitted) {
                emitted = true
                firstChunk.copyInto(buffer, offset, 0, minOf(length, firstChunk.size))
                return minOf(length, firstChunk.size)
            }
            started.countDown()
            while (!closed) Thread.sleep(5)
            throw IOException("closed")
        }
        override fun close() { closed = true }
    }

    private class TrackingConnection(url: URL) : HttpURLConnection(url) {
        @Volatile var disconnected = false
        @Volatile var responseCodeAccessed = false
        @Volatile var inputAccessed = false
        override fun getResponseCode(): Int { responseCodeAccessed = true; return 200 }
        override fun getInputStream(): InputStream { inputAccessed = true; return ByteArray(0).inputStream() }
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() = Unit
    }
}
