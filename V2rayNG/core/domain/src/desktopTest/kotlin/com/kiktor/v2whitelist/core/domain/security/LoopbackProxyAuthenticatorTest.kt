package com.kiktor.v2whitelist.core.domain.security

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoopbackProxyAuthenticatorTest {

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
