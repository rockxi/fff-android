package ru.rockxi.fff.update

import java.net.HttpURLConnection
import java.net.URL

fun interface ReleaseSource {
    fun getLatestRelease(): GitHubRelease?
}

class GitHubReleaseClient(
    private val endpoint: String = LATEST_RELEASE_ENDPOINT,
) : ReleaseSource {
    override fun getLatestRelease(): GitHubRelease? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "FFF-Android")
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { GitHubReleaseParser.parse(it.readText()) }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        const val LATEST_RELEASE_ENDPOINT =
            "https://api.github.com/repos/rockxi/fff-android/releases/latest"
    }
}

class UpdateCheckGate(private val source: ReleaseSource) {
    fun findUpdate(currentVersion: String, userOptedIn: Boolean): GitHubRelease? {
        if (!userOptedIn) return null
        val installed = SemVer.parse(currentVersion) ?: return null
        return runCatching { source.getLatestRelease() }
            .getOrNull()
            ?.takeIf { it.version > installed }
    }

    fun check(currentVersion: String): UpdateCheckResult {
        val installed = SemVer.parse(currentVersion) ?: return UpdateCheckResult.Failed
        val latest = runCatching { source.getLatestRelease() }.getOrNull() ?: return UpdateCheckResult.Failed
        return if (latest.version > installed) UpdateCheckResult.Available(latest) else UpdateCheckResult.Current
    }
}

sealed interface UpdateCheckResult {
    data class Available(val release: GitHubRelease) : UpdateCheckResult
    data object Current : UpdateCheckResult
    data object Failed : UpdateCheckResult
}
