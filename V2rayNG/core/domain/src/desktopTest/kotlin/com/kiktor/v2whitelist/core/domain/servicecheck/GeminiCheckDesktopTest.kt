package com.kiktor.v2whitelist.core.domain.servicecheck

import com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckItem
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeminiCheckDesktopTest {

    private val geminiItem = ServiceCheckItem(
        id = "gemini",
        name = "Gemini",
        url = "https://gemini.google.com",
        enabled = true
    )

    private fun withMockServer(
        handler: (path: String) -> Triple<Int, Map<String, String>, ByteArray>,
        block: (baseUrl: String) -> Unit
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val (code, headers, body) = handler(exchange.requestURI.path)
            headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            exchange.sendResponseHeaders(code, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun gzipBytes(text: String): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return baos.toByteArray()
    }

    @Test
    fun testGeminiGzipAllowedRegionSucceeds() {
        val largeHtml = "x".repeat(850_000) + """,2,1,200,"NLD",null"""
        val gz = gzipBytes(largeHtml)
        withMockServer({ _ ->
            Triple(200, mapOf("Content-Encoding" to "gzip"), gz)
        }) { base ->
            val res = checkGeminiUrl(geminiItem, Proxy.NO_PROXY, 3000L, "$base/app")
            assertTrue(res.success, "Expected success for gzip NLD payload, got: ${res.errorMessage}")
        }
    }

    @Test
    fun testGeminiBlockedRegionFails() {
        val gz = gzipBytes("x".repeat(850_000) + """,2,1,200,"RUS",null""")
        withMockServer({ _ ->
            Triple(200, mapOf("Content-Encoding" to "gzip"), gz)
        }) { base ->
            val res = checkGeminiUrl(geminiItem, Proxy.NO_PROXY, 3000L, "$base/app")
            assertFalse(res.success, "Expected RUS region to fail")
        }
    }

    @Test
    fun testGeminiExternalRedirectFails() {
        withMockServer({ _ ->
            Triple(302, mapOf("Location" to "https://support.google.com/gemini/answer/13575153"), ByteArray(0))
        }) { base ->
            val res = checkGeminiUrl(geminiItem, Proxy.NO_PROXY, 3000L, "$base/app")
            assertFalse(res.success, "Expected external redirect to fail")
        }
    }

    @Test
    fun testGeminiFaqRedirectFails() {
        withMockServer({ _ ->
            Triple(302, mapOf("Location" to "/faq"), ByteArray(0))
        }) { base ->
            val res = checkGeminiUrl(geminiItem, Proxy.NO_PROXY, 3000L, "$base/app")
            assertFalse(res.success, "Expected /faq redirect to fail")
        }
    }

    @Test
    fun testGeminiMissingMarkerFails() {
        withMockServer({ _ ->
            Triple(200, emptyMap(), "<html><body>Empty Gemini shell</body></html>".toByteArray())
        }) { base ->
            val res = checkGeminiUrl(geminiItem, Proxy.NO_PROXY, 3000L, "$base/app")
            assertFalse(res.success, "Expected response without region marker to fail")
        }
    }

    @Test
    fun testGeminiNon200InnerStatusFails() {
        val body = """{"data":true},2,1,403,"NLD",null""".toByteArray()
        withMockServer({ _ ->
            Triple(200, emptyMap(), body)
        }) { base ->
            val res = checkGeminiUrl(geminiItem, Proxy.NO_PROXY, 3000L, "$base/app")
            assertFalse(res.success, "Expected non-200 inner marker status to fail")
        }
    }
}
