package com.kiktor.v2whitelist.core.model

import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeekModeLoggerTest {

    @BeforeTest
    fun setup() {
        GeekModeLogger.clear()
        GeekModeLogger.platformLogHandler = null
    }

    @Test
    fun testLogAndClear() {
        assertEquals(0, GeekModeLogger.logs.value.size)

        var lastTag = ""
        var lastMsg = ""
        GeekModeLogger.platformLogHandler = { tag, msg ->
            lastTag = tag
            lastMsg = msg
        }

        GeekModeLogger.log("TestTag", "Hello World")
        assertEquals("TestTag", lastTag)
        assertEquals("Hello World", lastMsg)
        assertEquals(1, GeekModeLogger.logs.value.size)
        assertTrue(GeekModeLogger.logs.value[0].contains("[TestTag] Hello World"))

        GeekModeLogger.clear()
        assertEquals(0, GeekModeLogger.logs.value.size)
    }

    @Test
    fun testMaxLogsLimit() {
        for (i in 1..2550) {
            GeekModeLogger.log("Tag", "Message $i")
        }
        assertEquals(2500, GeekModeLogger.logs.value.size)
        assertTrue(GeekModeLogger.logs.value.last().contains("Message 2550"))
    }

    @Test
    fun testSanitizeLogLine() {
        val raw = "Importing vless://secret-uuid@example.com:443?type=ws&token=my_secret_123"
        val sanitized = GeekModeLogger.sanitizeLogLine(raw)
        assertTrue(!sanitized.contains("secret-uuid"))
        assertTrue(!sanitized.contains("my_secret_123"))

        val urlWithToken = "GET https://sub.example.com/api?token=abc123&flag=1"
        val sanitizedUrl = GeekModeLogger.sanitizeLogLine(urlWithToken)
        assertTrue(sanitizedUrl.contains("token=***"))
        assertTrue(!sanitizedUrl.contains("abc123"))
    }
}
