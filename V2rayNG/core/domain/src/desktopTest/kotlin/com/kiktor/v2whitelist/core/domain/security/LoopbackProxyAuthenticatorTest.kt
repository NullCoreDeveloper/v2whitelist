package com.kiktor.v2whitelist.core.domain.security

import java.net.Authenticator
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoopbackProxyAuthenticatorTest {

    @Test
    fun allowPortAndDisallowPortWorkForEphemeralPorts() {
        LoopbackProxyAuthenticator.install(
            userProvider = { "u" },
            passProvider = { "p" },
            allowedPortsProvider = { setOf(10808) }
        )
        val loopback = InetAddress.getByName("127.0.0.1")
        val ephemeralPort = 54321

        assertNull(
            Authenticator.requestPasswordAuthentication(
                "127.0.0.1",
                loopback,
                ephemeralPort,
                "SOCKS5",
                "",
                null,
                null,
                Authenticator.RequestorType.PROXY
            )
        )

        LoopbackProxyAuthenticator.allowPort(ephemeralPort)
        try {
            val auth = Authenticator.requestPasswordAuthentication(
                "127.0.0.1",
                loopback,
                ephemeralPort,
                "SOCKS5",
                "",
                null,
                null,
                Authenticator.RequestorType.PROXY
            )
            assertNotNull(auth)
            assertEquals("u", auth.userName)
        } finally {
            LoopbackProxyAuthenticator.disallowPort(ephemeralPort)
        }

        assertNull(
            Authenticator.requestPasswordAuthentication(
                "127.0.0.1",
                loopback,
                ephemeralPort,
                "SOCKS5",
                "",
                null,
                null,
                Authenticator.RequestorType.PROXY
            )
        )
    }

    @Test
    fun allowsOnlyLoopbackSocksAndHttpOnAllowedPorts() {
        assertTrue(
            LoopbackProxyAuthPolicy.shouldAuthenticate(
                protocol = "SOCKS5",
                host = "127.0.0.1",
                siteIsLoopback = false,
                port = 10808,
                allowedPorts = setOf(10808, 10809)
            )
        )
        assertTrue(
            LoopbackProxyAuthPolicy.shouldAuthenticate(
                protocol = "HTTP",
                host = "localhost",
                siteIsLoopback = InetAddress.getByName("127.0.0.1").isLoopbackAddress,
                port = 10809,
                allowedPorts = setOf(10808, 10809)
            )
        )

        assertFalse(
            LoopbackProxyAuthPolicy.shouldAuthenticate(
                protocol = "HTTPS",
                host = "evil.example.com",
                siteIsLoopback = false,
                port = 443,
                allowedPorts = setOf(10808)
            )
        )
        assertFalse(
            LoopbackProxyAuthPolicy.shouldAuthenticate(
                protocol = "SOCKS5",
                host = "192.168.1.50",
                siteIsLoopback = false,
                port = 10808,
                allowedPorts = setOf(10808)
            )
        )
        assertFalse(
            LoopbackProxyAuthPolicy.shouldAuthenticate(
                protocol = "SOCKS5",
                host = "127.0.0.1",
                siteIsLoopback = true,
                port = 9999,
                allowedPorts = setOf(10808, 10809)
            )
        )
    }
}
