package com.kiktor.v2whitelist.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSanitizerTest {

    @Test
    fun `sanitizeLine masks proxy uri, auth headers, passwords and uuids`() {
        val line = "connect via vless://11111111-2222-3333-4444-555555555555@vpn.example.com:443?pbk=secretPubKey&sid=abcd"
        val cleaned = LogSanitizer.sanitizeLine(line)
        assertFalse(cleaned.contains("11111111-2222"))
        assertFalse(cleaned.contains("secretPubKey"))
        assertFalse(cleaned.contains("vpn.example.com"))
        assertTrue(cleaned.contains("<redacted-uri>"))

        val headerLine = "Authorization: Bearer my-super-secret-jwt X-V2W-Token: deadbeef1234 password=qwerty"
        val cleanedHeader = LogSanitizer.sanitizeLine(headerLine)
        assertFalse(cleanedHeader.contains("my-super-secret-jwt"))
        assertFalse(cleanedHeader.contains("deadbeef1234"))
        assertFalse(cleanedHeader.contains("qwerty"))
        assertTrue(cleanedHeader.contains("Authorization: <redacted>"))
        assertTrue(cleanedHeader.contains("X-V2W-Token: <redacted>"))
        assertTrue(cleanedHeader.contains("password=<redacted>"))

        val uuidLine = "user id 550e8400-e29b-41d4-a716-446655440000 connected"
        assertEquals("user id <redacted-uuid> connected", LogSanitizer.sanitizeLine(uuidLine))
    }

    @Test
    fun `sanitizeLine preserves ordinary diagnostic lines`() {
        val normal = "2026/11/26 16:00:00 [Info] app/proxyman/inbound: TCP connection accepted 127.0.0.1:10808"
        assertEquals(normal, LogSanitizer.sanitizeLine(normal))
    }
}
