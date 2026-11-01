package com.kiktor.v2whitelist.core.model

import com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceCheckModelsTest {

    @Test
    fun testDefaultServices() {
        val defaults = ServiceCheckItem.DEFAULT_SERVICES
        assertEquals(7, defaults.size)
        val names = defaults.map { it.name }
        assertTrue(names.contains("Google"))
        assertTrue(names.contains("YouTube"))
        assertTrue(names.contains("Telegram"))
        assertTrue(names.contains("Gemini"))
        assertTrue(names.contains("Claude"))
        assertTrue(names.contains("ChatGPT"))
        assertTrue(names.contains("Discord"))
    }

    @Test
    fun testSerializationAndParsing() {
        val items = listOf(
            ServiceCheckItem(id = "google", name = "Google", url = "https://www.google.com/generate_204", enabled = false),
            ServiceCheckItem(id = "custom_1", name = "Custom Site", url = "https://example.com", enabled = true, isCustom = true)
        )

        val json = ServiceCheckItem.serializeList(items)
        val parsed = ServiceCheckItem.parseList(json)

        assertEquals(2, parsed.size)
        assertEquals("google", parsed[0].id)
        assertEquals(false, parsed[0].enabled)
        assertEquals("Custom Site", parsed[1].name)
        assertEquals(true, parsed[1].isCustom)
    }

    @Test
    fun testFallbackOnEmptyOrCorruptJson() {
        val defaults1 = ServiceCheckItem.parseList(null)
        assertEquals(7, defaults1.size)

        val defaults2 = ServiceCheckItem.parseList("")
        assertEquals(7, defaults2.size)

        val defaults3 = ServiceCheckItem.parseList("{ corrupt json }")
        assertEquals(7, defaults3.size)
    }
}

