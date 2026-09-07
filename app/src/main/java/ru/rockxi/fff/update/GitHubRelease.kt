package ru.rockxi.fff.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

data class GitHubRelease(
    val tagName: String,
    val version: SemVer,
    val releaseUrl: String,
    val apkUrl: String?,
)

object GitHubReleaseParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(response: String): GitHubRelease? {
        val root = runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull() ?: return null
        if (root["draft"]?.jsonPrimitive?.booleanOrNull == true) return null
        if (root["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return null

        val tagName = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val version = SemVer.parse(tagName) ?: return null
        val releaseUrl = root["html_url"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { GitHubReleaseUrlPolicy.isReleasePage(it, tagName) }
            ?: return null
        val apkUrl = root["assets"]
            ?.runCatching { jsonArray }
            ?.getOrNull()
            ?.asSequence()
            ?.mapNotNull { asset ->
                runCatching {
                    val item = asset.jsonObject
                    val name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val url = item["browser_download_url"]?.jsonPrimitive?.contentOrNull
                    if (
                        name.endsWith(".apk", ignoreCase = true) &&
                        url != null &&
                        GitHubReleaseUrlPolicy.isApkDownload(url, tagName, name)
                    ) {
                        url
                    } else {
                        null
                    }
                }.getOrNull()
            }
            ?.firstOrNull()

        return GitHubRelease(tagName, version, releaseUrl, apkUrl)
    }
}

object GitHubReleaseUrlPolicy {
    private const val OWNER = "rockxi"
    private const val REPOSITORY = "fff-android"

    fun isReleasePage(url: String, tagName: String): Boolean {
        val uri = canonicalGitHubUri(url) ?: return false
        return uri.rawPath == "/$OWNER/$REPOSITORY/releases/tag/$tagName"
    }

    fun isApkDownload(url: String, tagName: String, assetName: String): Boolean {
        if (!SAFE_APK_NAME.matches(assetName)) return false
        val uri = canonicalGitHubUri(url) ?: return false
        return uri.rawPath == "/$OWNER/$REPOSITORY/releases/download/$tagName/$assetName"
    }

    private fun canonicalGitHubUri(url: String): URI? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || !uri.host.equals("github.com", ignoreCase = true)) return null
        if (uri.port != -1 || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        return uri
    }

    private val SAFE_APK_NAME = Regex("[A-Za-z0-9._-]+\\.[aA][pP][kK]")
}
