package com.kiktor.v2whitelist.core.domain.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupSecurityPolicyTest {

    @Test
    fun checkJsonDepthRejectsExcessiveNestingAndMalformedJson() {
        assertTrue(BackupSecurityPolicy.checkJsonDepth("""{"a":{"b":[1,2,3]}}"""))
        assertTrue(BackupSecurityPolicy.checkJsonDepth("""{"text":"{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{"}"""))

        val deep = "[".repeat(BackupSecurityPolicy.MAX_JSON_DEPTH + 1) +
            "]".repeat(BackupSecurityPolicy.MAX_JSON_DEPTH + 1)
        assertFalse(BackupSecurityPolicy.checkJsonDepth(deep))
        assertFalse(BackupSecurityPolicy.checkJsonDepth("""{"unclosed": [1, 2"""))
        assertFalse(BackupSecurityPolicy.checkJsonDepth("""{"unclosed_str": "abc"""))
        assertFalse(BackupSecurityPolicy.checkJsonDepth("   "))
    }

    @Test
    fun sanitizeDesktopStringSettingsRemovesExecutablePaths() {
        val input = mapOf(
            "xray_path" to "/tmp/evil",
            "v2w_scan_path" to "C:\\evil.exe",
            "tun2socks_path" to "/bin/sh",
            "v2w_tun_path" to "/tmp/tun",
            "service_path" to "/tmp/svc",
            "custom_binary_path" to "/tmp/x",
            "custom_direct_domains" to "example.com",
            "pref_routing_mode" to "TUN"
        )
        val sanitized = BackupSecurityPolicy.sanitizeDesktopStringSettings(input)
        assertEquals(
            mapOf(
                "custom_direct_domains" to "example.com",
                "pref_routing_mode" to "TUN"
            ),
            sanitized
        )
    }

    @Test
    fun validatePortFallsBackOnInvalidValues() {
        assertEquals(10808, BackupSecurityPolicy.validatePort(0, 10808))
        assertEquals(10808, BackupSecurityPolicy.validatePort(-1, 10808))
        assertEquals(10808, BackupSecurityPolicy.validatePort(70000, 10808))
        assertEquals(20808, BackupSecurityPolicy.validatePort(20808, 10808))
    }

    @Test
    fun detectDesktopSecurityChangesReportsSensitiveDeltas() {
        val diff = BackupSecurityPolicy.detectDesktopSecurityChanges(
            currentStrings = mapOf("custom_direct_domains" to "ru"),
            currentBools = mapOf("allow_lan" to false, "allow_insecure" to false),
            incomingStrings = mapOf("custom_direct_domains" to "all"),
            incomingBools = mapOf("allow_lan" to true, "allow_insecure" to false)
        )
        assertTrue(diff.hasChanges)
        assertEquals(2, diff.warnings.size)
        assertTrue(diff.warnings.any { it.contains("allow_lan") })
        assertTrue(diff.warnings.any { it.contains("Список прямых доменов") })
    }
}
