package ru.rockxi.fff.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(val bytes: Long, val totalBytes: Long?) : UpdateDownloadState
    data class Ready(val apk: File) : UpdateDownloadState
    data class Failed(val message: String) : UpdateDownloadState
}

internal fun interface ApkDownloader {
    suspend fun download(release: GitHubRelease, destination: File, progress: (Long, Long?) -> Unit): File
}

private enum class ConnectionPhase { REGISTERED, IO_STARTED, CANCELLED }
private data class ConnectionRegistration(
    val connection: HttpURLConnection,
    val phase: AtomicReference<ConnectionPhase> = AtomicReference(ConnectionPhase.REGISTERED),
)

internal class SecureApkDownloader(
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val afterConnectionRegistered: () -> Unit = {},
) : ApkDownloader {
    override suspend fun download(release: GitHubRelease, destination: File, progress: (Long, Long?) -> Unit): File {
        val context = currentCoroutineContext()
        return suspendCancellableCoroutine { continuation ->
            val activeConnection = AtomicReference<ConnectionRegistration?>()
            val cancelled = AtomicBoolean(false)
            val completedDestination = AtomicBoolean(false)
            val completionLock = Any()
            val partial = File(destination.parentFile, "${destination.name}.part")
            continuation.invokeOnCancellation {
                cancelled.set(true)
                activeConnection.getAndSet(null)?.let { registration ->
                    registration.phase.compareAndSet(ConnectionPhase.REGISTERED, ConnectionPhase.CANCELLED)
                    registration.connection.disconnect()
                }
                synchronized(completionLock) {
                    partial.delete()
                    if (completedDestination.get()) destination.delete()
                }
            }
            Thread({
                try {
                    val result = performDownload(
                        release, destination, partial, context, cancelled, activeConnection,
                        completionLock, completedDestination, progress,
                    )
                    continuation.resume(result)
                } catch (error: Throwable) {
                    if (error is CancellationException || cancelled.get()) {
                        continuation.cancel(error as? CancellationException ?: CancellationException("Download cancelled"))
                    } else {
                        continuation.resumeWithException(error)
                    }
                } finally {
                    activeConnection.getAndSet(null)?.connection?.disconnect()
                }
            }, "fff-apk-download").apply { isDaemon = true }.start()
        }
    }

    private fun performDownload(
        release: GitHubRelease,
        destination: File,
        partial: File,
        context: CoroutineContext,
        cancelled: AtomicBoolean,
        activeConnection: AtomicReference<ConnectionRegistration?>,
        completionLock: Any,
        completedDestination: AtomicBoolean,
        progress: (Long, Long?) -> Unit,
    ): File {
        val url = release.apkUrl ?: throw IllegalArgumentException("В релизе нет APK")
        val checksumUrl = release.checksumUrl ?: throw IllegalArgumentException("В релизе нет SHA-256")
        val assetName = URI(url).rawPath.substringAfterLast('/')
        require(GitHubReleaseUrlPolicy.isApkDownload(url, release.tagName, assetName)) { "Недопустимый адрес APK" }
        require(GitHubReleaseUrlPolicy.isAssetDownload(checksumUrl, release.tagName, "$assetName.sha256")) { "Недопустимый адрес SHA-256" }
        val expectedDigest = downloadChecksum(checksumUrl, assetName, context, cancelled, activeConnection)
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, "${destination.name}.part")
        partial.delete()
        var connection: HttpURLConnection? = null
        try {
            context.ensureActive()
            connection = openDownloadConnection(url, context, activeConnection)
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("Загрузка APK завершилась с кодом $status")
            val total = connection.contentLengthLong.takeIf { it >= 0 }
            if (total != null && total > MAX_APK_BYTES) throw IllegalStateException("APK слишком большой")
            var received = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    context.ensureActive()
                    if (cancelled.get()) throw CancellationException("Download cancelled")
                    val count = input.read(buffer)
                    if (count < 0) break
                    context.ensureActive()
                    received += count
                    if (received > MAX_APK_BYTES) throw IllegalStateException("APK слишком большой")
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    progress(received, total)
                }
                output.fd.sync()
            } }
            if (received == 0L || (total != null && received != total)) throw IllegalStateException("APK загружен не полностью")
            if (!digestsMatch(expectedDigest, digest.digest())) throw IllegalStateException("SHA-256 APK не совпадает")
            synchronized(completionLock) {
                context.ensureActive()
                if (cancelled.get()) throw CancellationException("Download cancelled")
                Files.move(
                    partial.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
                completedDestination.set(true)
            }
            return destination
        } catch (error: Throwable) {
            partial.delete()
            throw error
        } finally {
            connection?.disconnect()
        }
    }
    private fun downloadChecksum(url:String,assetName:String,context:CoroutineContext,cancelled:AtomicBoolean,activeConnection:AtomicReference<ConnectionRegistration?>):ByteArray {
        val connection=openDownloadConnection(url,context,activeConnection)
        try { if(connection.responseCode !in 200..299)throw IllegalStateException("Не удалось скачать SHA-256");val bytes=connection.inputStream.use{input->val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(256);var total=0;while(true){context.ensureActive();if(cancelled.get())throw CancellationException("Download cancelled");val n=input.read(buffer);if(n<0)break;context.ensureActive();total+=n;if(total>1024)throw IllegalStateException("SHA-256 файл слишком большой");output.write(buffer,0,n)};output.toByteArray()};val line=String(bytes,Charsets.US_ASCII).trim();val match=Regex("^([0-9a-fA-F]{64})\\s+\\*?${Regex.escape(assetName)}$").matchEntire(line)?:throw IllegalStateException("Некорректный SHA-256 файл");return match.groupValues[1].chunked(2).map{it.toInt(16).toByte()}.toByteArray() } finally { activeConnection.get()?.takeIf { it.connection === connection }?.let { activeConnection.compareAndSet(it,null) };connection.disconnect() }
    }
    private fun openDownloadConnection(initialUrl: String,context:CoroutineContext,activeConnection:AtomicReference<ConnectionRegistration?>): HttpURLConnection {
        var current = initialUrl
        repeat(6) { hop ->
            context.ensureActive()
            val connection = connectionFactory(URL(current)).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream")
                setRequestProperty("User-Agent", "FFF-Android")
            }
            val registration = ConnectionRegistration(connection)
            activeConnection.set(registration)
            afterConnectionRegistered()
            if (!registration.phase.compareAndSet(ConnectionPhase.REGISTERED, ConnectionPhase.IO_STARTED)) {
                activeConnection.compareAndSet(registration, null)
                connection.disconnect()
                throw CancellationException("Download cancelled")
            }
            if (cancelledOrInactive(context)) {
                activeConnection.compareAndSet(registration, null)
                connection.disconnect()
                context.ensureActive()
                throw CancellationException("Download cancelled")
            }
            if (connection.responseCode !in 300..399) return connection
            val location = connection.getHeaderField("Location") ?: run { connection.disconnect(); throw IllegalStateException("Redirect без адреса") }
            connection.disconnect()
            activeConnection.compareAndSet(registration, null)
            val redirected = URI(current).resolve(location)
            if (!isTrustedDownloadRedirect(redirected)) throw IllegalStateException("Недопустимый redirect APK")
            current = redirected.toString()
            if (hop == 5) throw IllegalStateException("Слишком много redirect APK")
        }
        throw IllegalStateException("Слишком много redirect APK")
    }
    private fun cancelledOrInactive(context: CoroutineContext): Boolean =
        context[kotlinx.coroutines.Job]?.isActive == false
    companion object { const val MAX_APK_BYTES = 200L * 1024 * 1024 }
}

internal fun isTrustedDownloadRedirect(uri: URI): Boolean =
    uri.scheme == "https" && uri.port == -1 && uri.userInfo == null &&
        (uri.host.equals("github.com", true) || uri.host.equals("release-assets.githubusercontent.com", true))

internal fun digestsMatch(expected:ByteArray,actual:ByteArray)=MessageDigest.isEqual(expected,actual)

internal class UpdateDownloadController(private val downloader: ApkDownloader) {
    suspend fun download(release: GitHubRelease, destination: File, publish: (UpdateDownloadState) -> Unit): File? {
        publish(UpdateDownloadState.Downloading(0, null))
        return try { downloader.download(release, destination) { bytes, total -> publish(UpdateDownloadState.Downloading(bytes, total)) }.also { publish(UpdateDownloadState.Ready(it)) } }
        catch(cancelled:CancellationException){throw cancelled}
        catch(error:Throwable){publish(UpdateDownloadState.Failed(error.message ?: "Не удалось скачать обновление"));null}
    }
}

internal fun apkInstallIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(uri, "application/vnd.android.package-archive")
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

internal fun launchApkInstaller(context: Context, apk: File): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return false
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    context.startActivity(apkInstallIntent(uri))
    return true
}
