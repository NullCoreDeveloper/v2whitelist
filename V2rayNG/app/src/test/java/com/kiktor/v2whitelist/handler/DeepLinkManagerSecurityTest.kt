package com.kiktor.v2whitelist.handler

import android.util.Base64
import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import java.io.ByteArrayOutputStream
import java.util.Base64 as JavaBase64
import java.util.zip.GZIPOutputStream

class DeepLinkManagerSecurityTest {

    @Test
    fun decodeRejectsOversizedGzipBombPayload() {
        val largeJson = "{\"name\":\"" + "A".repeat(DeepLinkManager.MAX_DECOMPRESSED_BYTES + 1024) + "\"}"
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).bufferedWriter(Charsets.UTF_8).use { it.write(largeJson) }
        val encoded = JavaBase64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray())

        Mockito.mockStatic(Base64::class.java).use { base64Mock ->
            base64Mock.`when`<ByteArray> { Base64.decode(anyString(), anyInt()) }.thenAnswer { inv ->
                JavaBase64.getUrlDecoder().decode(inv.getArgument<String>(0))
            }
            Mockito.mockStatic(Log::class.java).use {
                val decoded = DeepLinkManager.decodeFromDeepLinkData(
                    encoded,
                    DeepLinkManager.SharedSubscription::class.java
                )
                assertNull(decoded)
            }
        }
    }

    @Test
    fun decodeAndValidateValidSharedSubscription() {
        val original = DeepLinkManager.SharedSubscription(
            name = "Test Sub",
            url = "https://example.com/sub.txt"
        )
        val json = """{"name":"Test Sub","url":"https://example.com/sub.txt"}"""
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).bufferedWriter(Charsets.UTF_8).use { it.write(json) }
        val encoded = JavaBase64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray())

        Mockito.mockStatic(Base64::class.java).use { base64Mock ->
            base64Mock.`when`<ByteArray> { Base64.decode(anyString(), anyInt()) }.thenAnswer { inv ->
                JavaBase64.getUrlDecoder().decode(inv.getArgument<String>(0))
            }
            val decoded = DeepLinkManager.decodeFromDeepLinkData(
                encoded,
                DeepLinkManager.SharedSubscription::class.java
            )
            assertNotNull(decoded)
            assertEquals(original.name, decoded?.name)
            assertTrue(DeepLinkManager.validateSharedSubscription(decoded))
        }

        assertFalse(
            DeepLinkManager.validateSharedSubscription(
                DeepLinkManager.SharedSubscription(name = "SSRF", url = "http://127.0.0.1:8080/sub")
            )
        )
    }

    @Test
    fun validateSharedSplitTunnelingRejectsInvalidPackages() {
        assertTrue(
            DeepLinkManager.validateSharedSplitTunneling(
                DeepLinkManager.SharedSplitTunneling(
                    name = "RU Apps",
                    bypassMode = true,
                    packages = listOf("ru.sberbankmobile", "com.vkontakte.android")
                )
            )
        )
        assertFalse(
            DeepLinkManager.validateSharedSplitTunneling(
                DeepLinkManager.SharedSplitTunneling(
                    name = "Bad",
                    bypassMode = true,
                    packages = listOf("../etc/passwd")
                )
            )
        )
    }
}
