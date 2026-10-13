package com.kiktor.v2whitelist.core.subscription

import com.kiktor.v2whitelist.core.config.util.Base64Util
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.subscription.parser.SubscriptionParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubscriptionParserTest {

    private val vlessNode = "vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443?type=tcp&security=reality&pbk=abcd&fp=chrome#RU-Moscow-01"
    private val trojanNode = "trojan://password123@5.6.7.8:443?security=tls&sni=example.com#DE-Frankfurt-01"
    private val ssNode = "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwYXNzd29yZA@9.10.11.12:8388#NL-Amsterdam-01"
    private val insecureNode = "vless://11111111-2222-3333-4444-555555555555@1.2.3.4:80?type=tcp&security=none#Insecure-Node"

    @Test
    fun testParsePlainTextSubscription() {
        val raw = """
            # This is a comment
            $vlessNode
            
            # Another comment
            $trojanNode
            $ssNode
        """.trimIndent()

        val parsed = SubscriptionParser.parse(raw, subscriptionId = "sub_test_1")
        assertEquals(3, parsed.size)
        assertEquals("sub_test_1", parsed[0].subscriptionId)
        assertEquals(EConfigType.VLESS, parsed[0].configType)
        assertEquals("RU-Moscow-01", parsed[0].remarks)

        assertEquals("sub_test_1", parsed[1].subscriptionId)
        assertEquals(EConfigType.TROJAN, parsed[1].configType)
        assertEquals("DE-Frankfurt-01", parsed[1].remarks)

        assertEquals("sub_test_1", parsed[2].subscriptionId)
        assertEquals(EConfigType.SHADOWSOCKS, parsed[2].configType)
        assertEquals("NL-Amsterdam-01", parsed[2].remarks)
    }

    @Test
    fun testParseBase64Subscription() {
        val raw = "$vlessNode\n$trojanNode"
        val b64 = Base64Util.encode(raw)

        val parsed = SubscriptionParser.parse(b64, subscriptionId = "sub_b64")
        assertEquals(2, parsed.size)
        assertEquals("RU-Moscow-01", parsed[0].remarks)
        assertEquals("DE-Frankfurt-01", parsed[1].remarks)
    }

    @Test
    fun testFilterByRegex() {
        val raw = """
            $vlessNode
            $trojanNode
            $ssNode
        """.trimIndent()

        val parsedRuOnly = SubscriptionParser.parse(raw, filter = "RU")
        assertEquals(1, parsedRuOnly.size)
        assertEquals("RU-Moscow-01", parsedRuOnly[0].remarks)

        val parsedEurope = SubscriptionParser.parse(raw, filter = "DE|NL")
        assertEquals(2, parsedEurope.size)
    }

    @Test
    fun testFilterInsecureProfiles() {
        val raw = """
            $vlessNode
            $insecureNode
        """.trimIndent()

        val parsedAll = SubscriptionParser.parse(raw, filterInsecure = false)
        assertEquals(2, parsedAll.size)

        val parsedSecureOnly = SubscriptionParser.parse(raw, filterInsecure = true)
        assertEquals(1, parsedSecureOnly.size)
        assertEquals("RU-Moscow-01", parsedSecureOnly[0].remarks)
    }
}
