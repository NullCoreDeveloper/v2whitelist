package com.kiktor.v2whitelist.core.domain.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckerSecurityTest {

    @Test
    fun isAllowedUpdateUrlAcceptsOnlyGithubHttps() {
        assertTrue(UpdateChecker.isAllowedUpdateUrl("https://github.com/NullCoreDeveloper/v2whitelist/releases/download/v1.0.0/app.apk"))
        assertTrue(UpdateChecker.isAllowedUpdateUrl("https://objects.githubusercontent.com/github-production-release-asset/123"))
        assertTrue(UpdateChecker.isAllowedUpdateUrl("https://release-assets.githubusercontent.com/file.apk"))

        assertFalse(UpdateChecker.isAllowedUpdateUrl("http://github.com/NullCoreDeveloper/v2whitelist/releases/download/v1.0.0/app.apk"))
        assertFalse(UpdateChecker.isAllowedUpdateUrl("https://evil.example.com/app.apk"))
        assertFalse(UpdateChecker.isAllowedUpdateUrl("https://github.com@evil.example.com/app.apk"))
        assertFalse(UpdateChecker.isAllowedUpdateUrl("file:///sdcard/app.apk"))
        assertFalse(UpdateChecker.isAllowedUpdateUrl(null))
    }

    @Test
    fun extractExpectedChecksumMatchesExactFileNameAndValidatesHex() {
        val hashA = "a".repeat(64)
        val hashB = "b".repeat(64)
        val text = """
            $hashA  v2whitelist_1.0.0_universal.apk
            $hashB *dist/v2whitelist_1.0.0_arm64-v8a.apk
            not_a_hash  v2whitelist_1.0.0_x86.apk
        """.trimIndent()

        assertEquals(hashA, UpdateChecker.extractExpectedChecksum(text, "v2whitelist_1.0.0_universal.apk"))
        assertEquals(hashB, UpdateChecker.extractExpectedChecksum(text, "v2whitelist_1.0.0_arm64-v8a.apk"))
        assertNull(UpdateChecker.extractExpectedChecksum(text, "universal.apk"))
        assertNull(UpdateChecker.extractExpectedChecksum(text, "v2whitelist_1.0.0_x86.apk"))
    }
}
