package com.kiktor.v2whitelist.core.model

import com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceCheckModelsTest {

    @Test
    fun testDefaultServices() {
        val defaults = ServiceCheckItem.DEFAULT_SERVICES
        assertEquals(9, defaults.size)
        val names = defaults.map { it.name }
        assertTrue(names.contains("Google"))
        assertTrue(names.contains("YouTube"))
        assertTrue(names.contains("Telegram"))
        assertTrue(names.contains("WhatsApp"))
        assertTrue(names.contains("Instagram"))
        assertTrue(names.contains("Gemini"))
        assertTrue(names.contains("Claude"))
        assertTrue(names.contains("ChatGPT"))
        assertTrue(names.contains("Discord"))
    }

    @Test
    fun testSerializationAndParsing() {
        val items = ServiceCheckItem.DEFAULT_SERVICES.map {
            if (it.id == "google") it.copy(enabled = false) else it
        }

        val json = ServiceCheckItem.serializeList(items)
        val parsed = ServiceCheckItem.parseList(json)

        assertEquals(9, parsed.size)
        assertEquals("google", parsed[0].id)
        assertEquals(false, parsed[0].enabled)
        assertTrue(parsed.any { it.id == "whatsapp" })
        assertTrue(parsed.any { it.id == "instagram" })
    }

    @Test
    fun testAutoMergeMissingDefaults() {
        // User had older saved list without WhatsApp and Instagram
        val oldSavedItems = listOf(
            ServiceCheckItem(id = "google", name = "Google", url = "https://www.google.com/generate_204", enabled = false),
            ServiceCheckItem(id = "custom_1", name = "Custom Site", url = "https://example.com", enabled = true, isCustom = true)
        )
        val json = ServiceCheckItem.serializeList(oldSavedItems)
        val parsed = ServiceCheckItem.parseList(json)

        // Custom items preserved + missing default services merged
        assertTrue(parsed.size > 2)
        assertEquals("google", parsed[0].id)
        assertEquals(false, parsed[0].enabled)
        assertEquals("Custom Site", parsed[1].name)
        assertTrue(parsed.any { it.id == "whatsapp" })
        assertTrue(parsed.any { it.id == "instagram" })
    }

    @Test
    fun testFallbackOnEmptyOrCorruptJson() {
        val defaults1 = ServiceCheckItem.parseList(null)
        assertEquals(9, defaults1.size)

        val defaults2 = ServiceCheckItem.parseList("")
        assertEquals(9, defaults2.size)

        val defaults3 = ServiceCheckItem.parseList("{ corrupt json }")
        assertEquals(9, defaults3.size)
    }
}

