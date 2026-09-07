package ru.rockxi.fff.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseParserTest {
    @Test
    fun `parses published release and prefers apk asset`() {
        val release = GitHubReleaseParser.parse(
            """
            {
              "tag_name": "v0.2.0",
              "html_url": "https://github.com/rockxi/fff-android/releases/tag/v0.2.0",
              "draft": false,
              "prerelease": false,
              "assets": [
                {"name": "checksums.txt", "browser_download_url": "https://github.com/rockxi/fff-android/releases/download/v0.2.0/checksums.txt"},
                {"name": "fff-0.2.0.apk", "browser_download_url": "https://github.com/rockxi/fff-android/releases/download/v0.2.0/fff-0.2.0.apk"}
              ]
            }
            """.trimIndent(),
        )!!

        assertEquals(SemVer(0, 2, 0), release.version)
        assertEquals(
            "https://github.com/rockxi/fff-android/releases/download/v0.2.0/fff-0.2.0.apk",
            release.apkUrl,
        )
    }

    @Test
    fun `uses release page when no apk is present`() {
        val release = GitHubReleaseParser.parse(
            """{"tag_name":"1.0.1","html_url":"https://github.com/rockxi/fff-android/releases/tag/1.0.1","assets":[]}""",
        )!!

        assertNull(release.apkUrl)
        assertEquals("https://github.com/rockxi/fff-android/releases/tag/1.0.1", release.releaseUrl)
    }

    @Test
    fun `rejects malformed draft and prerelease responses`() {
        assertNull(GitHubReleaseParser.parse("not json"))
        assertNull(
            GitHubReleaseParser.parse(
                """{"tag_name":"1.0.1","html_url":"https://github.com/rockxi/fff-android/releases/tag/1.0.1","draft":true}""",
            ),
        )
        assertNull(
            GitHubReleaseParser.parse(
                """{"tag_name":"1.0.1-rc.1","html_url":"https://github.com/rockxi/fff-android/releases/tag/1.0.1-rc.1","prerelease":true}""",
            ),
        )
    }

    @Test
    fun `rejects lookalike host and unrelated repository release pages`() {
        val template =
            """{"tag_name":"1.0.1","html_url":"%s","assets":[]}"""

        assertNull(
            GitHubReleaseParser.parse(
                template.format("https://github.com.example/rockxi/fff-android/releases/tag/1.0.1"),
            ),
        )
        assertNull(
            GitHubReleaseParser.parse(
                template.format("https://github.com/rockxi/unrelated/releases/tag/1.0.1"),
            ),
        )
        assertNull(
            GitHubReleaseParser.parse(
                template.format("https://evil.example/?next=https://github.com/rockxi/fff-android/releases/tag/1.0.1"),
            ),
        )
    }

    @Test
    fun `ignores apk assets outside exact repository download path`() {
        val release = GitHubReleaseParser.parse(
            """
            {
              "tag_name":"1.0.1",
              "html_url":"https://github.com/rockxi/fff-android/releases/tag/1.0.1",
              "assets":[
                {"name":"lookalike.apk","browser_download_url":"https://github.com.example/rockxi/fff-android/releases/download/1.0.1/lookalike.apk"},
                {"name":"unrelated.apk","browser_download_url":"https://github.com/other/fff-android/releases/download/1.0.1/unrelated.apk"}
              ]
            }
            """.trimIndent(),
        )!!

        assertNull(release.apkUrl)
    }

    @Test
    fun `url policy rejects non canonical release urls`() {
        assertNull(
            GitHubReleaseParser.parse(
                """{"tag_name":"1.0.1","html_url":"http://github.com/rockxi/fff-android/releases/tag/1.0.1"}""",
            ),
        )
        assertNull(
            GitHubReleaseParser.parse(
                """{"tag_name":"1.0.1","html_url":"https://github.com:443/rockxi/fff-android/releases/tag/1.0.1"}""",
            ),
        )
        assertNull(
            GitHubReleaseParser.parse(
                """{"tag_name":"1.0.1","html_url":"https://github.com/rockxi/fff-android/releases/tag/2.0.0"}""",
            ),
        )
    }

    @Test
    fun `url policy requires exact raw release path`() {
        val canonical = "https://github.com/rockxi/fff-android/releases/tag/1.0.1"
        assertTrue(GitHubReleaseUrlPolicy.isReleasePage(canonical, "1.0.1"))
        assertFalse(GitHubReleaseUrlPolicy.isReleasePage("$canonical/", "1.0.1"))
        assertFalse(
            GitHubReleaseUrlPolicy.isReleasePage(
                "https://github.com/rockxi//fff-android/releases/tag/1.0.1",
                "1.0.1",
            ),
        )
        assertFalse(
            GitHubReleaseUrlPolicy.isReleasePage(
                "https://github.com/%72ockxi/fff-android/releases/tag/1.0.1",
                "1.0.1",
            ),
        )
    }

    @Test
    fun `url policy requires exact raw apk path`() {
        val canonical =
            "https://github.com/rockxi/fff-android/releases/download/1.0.1/fff-1.0.1.apk"
        assertTrue(GitHubReleaseUrlPolicy.isApkDownload(canonical, "1.0.1", "fff-1.0.1.apk"))
        assertFalse(GitHubReleaseUrlPolicy.isApkDownload("$canonical/", "1.0.1", "fff-1.0.1.apk"))
        assertFalse(
            GitHubReleaseUrlPolicy.isApkDownload(
                "https://github.com/rockxi/fff-android/releases//download/1.0.1/fff-1.0.1.apk",
                "1.0.1",
                "fff-1.0.1.apk",
            ),
        )
        assertFalse(
            GitHubReleaseUrlPolicy.isApkDownload(
                "https://github.com/rockxi/fff-android/releases/download/1.0.1/fff%2D1.0.1.apk",
                "1.0.1",
                "fff-1.0.1.apk",
            ),
        )
    }
}
