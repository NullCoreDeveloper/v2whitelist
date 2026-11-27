package com.kiktor.v2whitelist.desktop

import com.kiktor.v2whitelist.core.repository.InMemoryServerRepository
import com.kiktor.v2whitelist.core.repository.InMemorySettingsRepository
import com.kiktor.v2whitelist.desktop.engine.DesktopProxyHttpClient
import com.kiktor.v2whitelist.desktop.engine.XrayDesktopRunner
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopProxyAuthTest {

    @Test
    fun `activeProxyCredentials returns credentials only in PROXY mode with non-blank login and password`() {
        val settingsRepo = InMemorySettingsRepository().apply {
            setString("pref_routing_mode", "TUN")
            setString("pref_local_proxy_login", "user1")
            setString("pref_local_proxy_pass", "pass1")
        }
        val state = DesktopAppState(
            serverRepo = InMemoryServerRepository(),
            settingsRepo = settingsRepo,
            xrayRunner = XrayDesktopRunner(),
            autoCheckNetworkOnStart = false
        )

        assertNull(state.activeProxyCredentials())

        state.routingMode.value = "PROXY"
        assertEquals("user1" to "pass1", state.activeProxyCredentials())

        state.localProxyPassword.value = "   "
        assertNull(state.activeProxyCredentials())
    }

    @Test
    fun `DesktopProxyHttpClient authenticates against SOCKS5 username password proxy`() = runBlocking {
        val expectedUser = "v2w_test"
        val expectedPass = "secret_pw"
        var authSucceeded = false

        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val serverThread = thread(isDaemon = true) {
            serverSocket.use { ss ->
                val client = ss.accept()
                client.use { sock ->
                    val input = sock.getInputStream()
                    val output = sock.getOutputStream()

                    // 1. Greeting: VER(1)=5, NMETHODS(1), METHODS(N)
                    val ver = input.read()
                    val nMethods = input.read()
                    val methods = ByteArray(nMethods)
                    readFully(input, methods)
                    if (ver == 5 && methods.contains(0x02.toByte())) {
                        output.write(byteArrayOf(0x05, 0x02)) // choose USERNAME/PASSWORD
                    } else {
                        output.write(byteArrayOf(0x05, 0xFF.toByte()))
                        return@use
                    }

                    // 2. RFC 1929 Username/Password subnegotiation
                    val authVer = input.read()
                    val uLen = input.read()
                    val uBytes = ByteArray(uLen)
                    readFully(input, uBytes)
                    val pLen = input.read()
                    val pBytes = ByteArray(pLen)
                    readFully(input, pBytes)
                    val user = uBytes.decodeToString()
                    val pass = pBytes.decodeToString()
                    if (authVer == 1 && user == expectedUser && pass == expectedPass) {
                        authSucceeded = true
                        output.write(byteArrayOf(0x01, 0x00))
                    } else {
                        output.write(byteArrayOf(0x01, 0x01))
                        return@use
                    }

                    // 3. SOCKS5 CONNECT request: VER(1) CMD(1) RSV(1) ATYP(1) ...
                    input.read() // VER
                    input.read() // CMD
                    input.read() // RSV
                    val atyp = input.read()
                    when (atyp) {
                        1 -> readFully(input, ByteArray(4))
                        3 -> {
                            val dLen = input.read()
                            readFully(input, ByteArray(dLen))
                        }
                        4 -> readFully(input, ByteArray(16))
                    }
                    readFully(input, ByteArray(2)) // DST.PORT

                    // Reply success (0x00) bound 0.0.0.0:0
                    output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()

                    // 4. Consume HTTP request line/headers and respond
                    val reqBuf = ByteArrayOutputStream()
                    val tmp = ByteArray(1024)
                    while (true) {
                        val n = input.read(tmp)
                        if (n <= 0) break
                        reqBuf.write(tmp, 0, n)
                        if (reqBuf.toString().contains("\r\n\r\n")) break
                    }

                    val body = "vless://uuid@93.184.216.34:443#Test"
                    val response = (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "subscription-userinfo: upload=100; download=200; total=1000; expire=1900000000\r\n" +
                            "Content-Length: ${body.toByteArray().size}\r\n" +
                            "Connection: close\r\n\r\n" +
                            body
                        ).toByteArray()
                    output.write(response)
                    output.flush()
                }
            }
        }

        val res = DesktopProxyHttpClient.fetchSingleWithMeta(
            url = "http://93.184.216.34/sub",
            userAgent = "TestUA/1.0",
            socksPort = port,
            proxyUser = expectedUser,
            proxyPass = expectedPass,
            timeoutMs = 5000L
        )
        serverThread.join(3000L)

        assertTrue(authSucceeded, "SOCKS5 username/password auth must succeed")
        assertTrue(res.isSuccess, "Request via authenticated SOCKS5 must succeed: ${res.exceptionOrNull()}")
        val fetchResult = res.getOrThrow()
        assertEquals("vless://uuid@93.184.216.34:443#Test", fetchResult.content)
        assertNotNull(fetchResult.userInfo)
        assertEquals(100L, fetchResult.userInfo?.uploadBytes)
        assertEquals(200L, fetchResult.userInfo?.downloadBytes)
    }

    private fun readFully(input: InputStream, dst: ByteArray) {
        var offset = 0
        while (offset < dst.size) {
            val read = input.read(dst, offset, dst.size - offset)
            if (read < 0) error("Unexpected EOF")
            offset += read
        }
    }
}
