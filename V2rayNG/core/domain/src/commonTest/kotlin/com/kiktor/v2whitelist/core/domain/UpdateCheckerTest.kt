package com.kiktor.v2whitelist.core.domain

import com.kiktor.v2whitelist.core.domain.update.UpdateChecker
import com.kiktor.v2whitelist.core.model.update.GitHubRelease
import com.kiktor.v2whitelist.core.model.update.TargetPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UpdateCheckerTest {

    @Test
    fun testVersionComparison() {
        assertTrue(UpdateChecker.compareVersions("1.2.3", "1.2.0") > 0)
        assertTrue(UpdateChecker.compareVersions("v1.2.0", "1.2.0") == 0)
        assertTrue(UpdateChecker.compareVersions("1.10.0", "1.9.0") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0.0", "1.0.5") < 0)
        assertTrue(UpdateChecker.compareVersions("v2.1.0-beta", "2.1.0") == 0)
    }

    @Test
    fun testParseGitHubReleaseJson() {
        val mockJson = """
            {
              "tag_name": "v2.5.0",
              "body": "Bugfixes and performance improvements",
              "prerelease": false,
              "published_at": "2026-10-12T10:00:00Z",
              "assets": [
                {
                  "name": "v2whitelist-arm64-v8a.apk",
                  "browser_download_url": "https://github.com/test/download/arm64.apk",
                  "size": 45000000
                },
                {
                  "name": "v2whitelist-fdroid-universal.apk",
                  "browser_download_url": "https://github.com/test/download/fdroid.apk",
                  "size": 48000000
                },
                {
                  "name": "v2whitelist-desktop-linux-x64.tar.gz",
                  "browser_download_url": "https://github.com/test/download/desktop-linux.tar.gz",
                  "size": 60000000
                },
                {
                  "name": "sha256sum.txt",
                  "browser_download_url": "https://github.com/test/download/sha256sum.txt",
                  "size": 1024
                }
              ]
            }
        """.trimIndent()

        val release = UpdateChecker.parseLatestRelease(mockJson)
        assertNotNull(release)
        assertEquals("v2.5.0", release.tagName)
        assertEquals(4, release.assets.size)

        // Тест подбора ассета для Android ARM64
        val androidAsset = UpdateChecker.findCompatibleAsset(
            release,
            TargetPlatform.Android(abi = "arm64-v8a", isFdroid = false)
        )
        assertNotNull(androidAsset)
        assertEquals("v2whitelist-arm64-v8a.apk", androidAsset.name)

        // Тест подбора ассета для F-Droid
        val fdroidAsset = UpdateChecker.findCompatibleAsset(
            release,
            TargetPlatform.Android(abi = "arm64-v8a", isFdroid = true)
        )
        assertNotNull(fdroidAsset)
        assertEquals("v2whitelist-fdroid-universal.apk", fdroidAsset.name)

        // Тест подбора ассета для Desktop Linux
        val desktopAsset = UpdateChecker.findCompatibleAsset(
            release,
            TargetPlatform.Desktop(os = "linux", arch = "x64")
        )
        assertNotNull(desktopAsset)
        assertEquals("v2whitelist-desktop-linux-x64.tar.gz", desktopAsset.name)
    }

    @Test
    fun testExtractExpectedChecksum() {
        val sha256Content = """
            e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  v2whitelist-arm64-v8a.apk
            a1b2c3d4e5f67890123456789abcdef0123456789abcdef0123456789abcdef0  v2whitelist-fdroid-universal.apk
        """.trimIndent()

        val hash1 = UpdateChecker.extractExpectedChecksum(sha256Content, "v2whitelist-arm64-v8a.apk")
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash1)

        val hash2 = UpdateChecker.extractExpectedChecksum(sha256Content, "v2whitelist-fdroid-universal.apk")
        assertEquals("a1b2c3d4e5f67890123456789abcdef0123456789abcdef0123456789abcdef0", hash2)

        val hashMissing = UpdateChecker.extractExpectedChecksum(sha256Content, "nonexistent.apk")
        assertEquals(null, hashMissing)
    }
}
