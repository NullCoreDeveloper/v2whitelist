package com.kiktor.v2whitelist.core.subscription

import com.kiktor.v2whitelist.core.subscription.tag.ServerTagResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerTagResolverTest {

    @Test
    fun testFlagEmojiExtraction() {
        assertEquals("🇷🇺", ServerTagResolver.extractFirstFlagEmoji("🇷🇺 Moscow Highspeed"))
        assertEquals("🇩🇪", ServerTagResolver.extractFirstFlagEmoji("Premium 🇩🇪 Frankfurt"))
        assertEquals("🇺🇸", ServerTagResolver.extractFirstFlagEmoji("Fast US Node 🇺🇸"))
        assertNull(ServerTagResolver.extractFirstFlagEmoji("No flag here"))
    }

    @Test
    fun testFlagToCountryCode() {
        assertEquals("RU", ServerTagResolver.flagEmojiToCountryCode("🇷🇺"))
        assertEquals("DE", ServerTagResolver.flagEmojiToCountryCode("🇩🇪"))
        assertEquals("US", ServerTagResolver.flagEmojiToCountryCode("🇺🇸"))
        assertEquals("NL", ServerTagResolver.flagEmojiToCountryCode("🇳🇱"))
        assertEquals("FI", ServerTagResolver.flagEmojiToCountryCode("🇫🇮"))
        assertNull(ServerTagResolver.flagEmojiToCountryCode("NotAFlag"))
    }

    @Test
    fun testTextCountryTagDetection() {
        assertEquals("🇷🇺", ServerTagResolver.extractTextCountryTag("VLESS Russia Moscow"))
        assertEquals("🇩🇪", ServerTagResolver.extractTextCountryTag("Trojan Germany Berlin"))
        assertEquals("🇳🇱", ServerTagResolver.extractTextCountryTag("Amsterdam Netherlands 01"))
        assertEquals("🇺🇸", ServerTagResolver.extractTextCountryTag("USA California Fast"))
    }

    @Test
    fun testResolveServerTagPriorities() {
        // 1. Flag takes priority
        assertEquals("🇷🇺", ServerTagResolver.resolveServerTag("🇷🇺 Germany Node (flag wins)"))

        // 2. Text country detection
        assertEquals("🇩🇪", ServerTagResolver.resolveServerTag("Frankfurt Fast 01"))

        // 3. GroupRegex
        val groupRegex = "\\[(BL)\\]"
        assertEquals("BL", ServerTagResolver.resolveServerTag("Node [BL] custom", regexStr = groupRegex))

        val cidrRegex = "(CIDR-[A-Za-z0-9_-]+)"
        assertEquals("CIDR-ALPHA", ServerTagResolver.resolveServerTag("Node CIDR-ALPHA test", regexStr = cidrRegex))

        // 4. Protocol fallback
        assertEquals("VLESS", ServerTagResolver.resolveServerTag("Ultra Node VLESS 01"))
        assertEquals("HYSTERIA2", ServerTagResolver.resolveServerTag("Fast Hysteria2 server"))

        // 5. Unknown fallback
        assertEquals(ServerTagResolver.TAG_UNKNOWN, ServerTagResolver.resolveServerTag("UnknownCustomServer123"))
    }
}
