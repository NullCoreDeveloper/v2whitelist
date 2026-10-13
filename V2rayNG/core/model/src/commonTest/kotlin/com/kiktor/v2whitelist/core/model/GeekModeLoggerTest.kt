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
        for (i in 1..550) {
            GeekModeLogger.log("Tag", "Message $i")
        }
        assertEquals(500, GeekModeLogger.logs.value.size)
        assertTrue(GeekModeLogger.logs.value.last().contains("Message 550"))
    }
}
