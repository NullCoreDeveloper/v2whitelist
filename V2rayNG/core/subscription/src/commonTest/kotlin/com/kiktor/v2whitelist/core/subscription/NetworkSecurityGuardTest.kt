package com.kiktor.v2whitelist.core.subscription

import com.kiktor.v2whitelist.core.subscription.network.NetworkSecurityGuard
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkSecurityGuardTest {

    @Test
    fun blocksLocalLinkLocalAndMulticastHosts() {
        val blocked = listOf(
            "localhost",
            "LOCALHOST.",
            "api.localhost",
            "127.0.0.1",
            "127.42.0.1",
            "0.0.0.0",
            "::1",
            "[::1]",
            "::",
            "169.254.169.254",
            "fe80::1",
            "224.0.0.1",
            "::ffff:127.0.0.1",
            "::ffff:169.254.169.254"
        )
        blocked.forEach { host ->
            assertTrue(NetworkSecurityGuard.isBlockedHost(host), "Expected blocked host: $host")
        }

        val allowed = listOf("example.com", "1.1.1.1", "192.168.1.1", "2606:4700:4700::1111")
        allowed.forEach { host ->
            assertFalse(NetworkSecurityGuard.isBlockedHost(host), "Expected allowed host: $host")
        }
    }

    @Test
    fun validateExternalUrlRejectsUnsafeUrls() {
        assertNull(NetworkSecurityGuard.validateExternalUrl("http://127.0.0.1:8080/sub"))
        assertNull(NetworkSecurityGuard.validateExternalUrl("http://[::1]/sub"))
        assertNull(NetworkSecurityGuard.validateExternalUrl("https://169.254.169.254/latest/meta-data"))
        assertNull(NetworkSecurityGuard.validateExternalUrl("file:///etc/passwd"))
        assertNull(NetworkSecurityGuard.validateExternalUrl("javascript:alert(1)"))
        assertNull(NetworkSecurityGuard.validateExternalUrl("https://example.com/\nX-Evil: 1"))
        assertNull(NetworkSecurityGuard.validateExternalUrl("https://example.com/" + "a".repeat(5000)))
        assertNull(NetworkSecurityGuard.validateExternalUrl("http://example.com/sub", allowHttp = false))

        assertNotNull(NetworkSecurityGuard.validateExternalUrl("https://example.com/sub"))
        assertNotNull(NetworkSecurityGuard.validateExternalUrl("http://example.com/sub", allowHttp = true))
    }

    @Test
    fun validateRedirectBlocksDowngradeAndLocalTargets() {
        val from = Url("https://example.com/sub")

        assertFailsWith<IllegalArgumentException> {
            NetworkSecurityGuard.validateRedirect(from, "http://example.com/sub")
        }
        assertFailsWith<IllegalArgumentException> {
            NetworkSecurityGuard.validateRedirect(from, "https://127.0.0.1/secret")
        }
        assertFailsWith<IllegalArgumentException> {
            NetworkSecurityGuard.validateRedirect(from, "https://localhost/secret")
        }
        assertFailsWith<IllegalArgumentException> {
            NetworkSecurityGuard.validateRedirect(from, null)
        }

        val okAbs = NetworkSecurityGuard.validateRedirect(from, "https://cdn.example.org/list.txt")
        assertEquals("cdn.example.org", okAbs.host)

        val okRel = NetworkSecurityGuard.validateRedirect(from, "/v2/list.txt")
        assertEquals("example.com", okRel.host)
        assertEquals("/v2/list.txt", okRel.encodedPath)
    }

    @Test
    fun isSameOriginChecksSchemeHostAndPort() {
        assertTrue(NetworkSecurityGuard.isSameOrigin(Url("https://example.com/a"), Url("https://EXAMPLE.COM:443/b")))
        assertFalse(NetworkSecurityGuard.isSameOrigin(Url("https://example.com/a"), Url("https://cdn.example.com/a")))
        assertFalse(NetworkSecurityGuard.isSameOrigin(Url("https://example.com/a"), Url("http://example.com/a")))
    }
}
