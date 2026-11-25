package com.kiktor.v2whitelist.core.config

import com.kiktor.v2whitelist.core.config.xray.XrayConfigGenerator
import com.kiktor.v2whitelist.core.config.xray.XrayGeneratorOptions
import com.kiktor.v2whitelist.core.model.EConfigType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class XrayConfigGeneratorTest {

    @Test
    fun testGenerateVlessRealityJson() {
        val uri = "vless://a1b2c3d4-e5f6-7890-abcd-ef1234567890@198.51.100.1:443?security=reality&sni=dl.google.com&fp=chrome&pbk=AbCdEfGhIjKlMnOpQrStUvWxYz0123456789&sid=1234abcd&spx=%2F&type=tcp&flow=xtls-rprx-vision#MyVlessRealityNode"
        val profile = UniversalConfigParser.parse(uri)!!

        val jsonStr = XrayConfigGenerator.generateJson(profile, XrayGeneratorOptions(socksPort = 10808))
        println("Generated VLESS Reality JSON:\n$jsonStr")

        assertTrue(jsonStr.contains("\"protocol\": \"vless\""))
        assertTrue(jsonStr.contains("\"security\": \"reality\""))
        assertTrue(jsonStr.contains("\"serverName\": \"dl.google.com\""))
        assertTrue(jsonStr.contains("\"publicKey\": \"AbCdEfGhIjKlMnOpQrStUvWxYz0123456789\""))
        assertTrue(jsonStr.contains("\"flow\": \"xtls-rprx-vision\""))
        assertTrue(jsonStr.contains("\"port\": 10808"))
    }

    @Test
    fun testGenerateHysteria2Json() {
        val uri = "hysteria2://authPass@hy2.example.com:443?sni=hy2.example.com&obfs=salamander&obfs-password=obfsSecret&mport=20000-40000#MyHy2Node"
        val profile = UniversalConfigParser.parse(uri)!!

        val jsonStr = XrayConfigGenerator.generateJson(profile)
        println("Generated Hysteria2 JSON:\n$jsonStr")

        assertTrue(jsonStr.contains("\"protocol\": \"hysteria\""))
        assertTrue(jsonStr.contains("\"port\": \"20000-40000\""))
        assertTrue(jsonStr.contains("\"auth\": \"authPass\""))
        assertTrue(jsonStr.contains("\"finalmask\""))
        assertTrue(jsonStr.contains("\"type\": \"salamander\""), "Hysteria2 finalmask entry must explicitly serialize type=salamander")
        assertTrue(jsonStr.contains("\"localhost\""), "DNS fallback must include localhost")
    }

    @Test
    fun testGenerateShadowsocksJson() {
        val uri = "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpteXNlY3JldA==@192.0.2.1:8388#MyShadowsocks"
        val profile = UniversalConfigParser.parse(uri)!!

        val jsonStr = XrayConfigGenerator.generateJson(profile)
        println("Generated Shadowsocks JSON:\n$jsonStr")

        assertTrue(jsonStr.contains("\"protocol\": \"shadowsocks\""))
        assertTrue(jsonStr.contains("\"method\": \"chacha20-ietf-poly1305\""))
        assertTrue(jsonStr.contains("\"password\": \"mysecret\""))
        assertTrue(jsonStr.contains("\"address\": \"192.0.2.1\""))
    }

    @Test
    fun testGenerateAllowLanListenAddress() {
        val uri = "vless://a1b2c3d4-e5f6-7890-abcd-ef1234567890@198.51.100.1:443?security=reality&sni=dl.google.com&fp=chrome&pbk=AbCdEfGhIjKlMnOpQrStUvWxYz0123456789&sid=1234abcd&spx=%2F&type=tcp#MyNode"
        val profile = UniversalConfigParser.parse(uri)!!

        val jsonStr = XrayConfigGenerator.generateJson(profile, XrayGeneratorOptions(listenAddress = "0.0.0.0"))
        assertTrue(jsonStr.contains("\"listen\": \"0.0.0.0\""))
    }

    @Test
    fun testGenerateProxyChainWithCustomEndpoint() {
        val baseNodeUri = "vless://a1b2c3d4-e5f6-7890-abcd-ef1234567890@198.51.100.1:443?security=reality&sni=dl.google.com&fp=chrome&pbk=AbCdEfGhIjKlMnOpQrStUvWxYz0123456789&sid=1234abcd&spx=%2F&type=tcp#BaseNode"
        val customEndpointUri = "trojan://mypassword@mycustomendpoint.com:443?security=tls&sni=mycustomendpoint.com#CustomEndpoint"

        val profile = UniversalConfigParser.parse(baseNodeUri)!!
        val options = XrayGeneratorOptions(
            socksPort = 10808,
            customEndpointUrl = customEndpointUri
        )

        val jsonStr = XrayConfigGenerator.generateJson(profile, options)
        println("Generated Proxy Chain JSON:\n$jsonStr")

        assertTrue(jsonStr.contains("\"tag\": \"proxy\""))
        assertTrue(jsonStr.contains("\"tag\": \"proxy1\""))
        assertTrue(jsonStr.contains("\"dialerProxy\": \"proxy1\""))
        assertTrue(jsonStr.contains("mycustomendpoint.com"))
    }

    @Test
    fun testGenerateCustomProxyAndDirectDomains() {
        val uri = "vless://a1b2c3d4-e5f6-7890-abcd-ef1234567890@198.51.100.1:443?security=reality&sni=dl.google.com&fp=chrome&pbk=AbCdEfGhIjKlMnOpQrStUvWxYz0123456789&sid=1234abcd&spx=%2F&type=tcp#Node"
        val profile = UniversalConfigParser.parse(uri)!!
        val options = XrayGeneratorOptions(
            customProxyDomains = listOf("blocked-site.com", "geosite:category-media-ru-blocked"),
            customDirectDomains = listOf("internal-company.ru")
        )

        val jsonStr = XrayConfigGenerator.generateJson(profile, options)
        println("Generated Split Tunneling JSON:\n$jsonStr")

        assertTrue(jsonStr.contains("\"domain:blocked-site.com\""))
        assertTrue(jsonStr.contains("\"geosite:category-media-ru-blocked\""))
        assertTrue(jsonStr.contains("\"domain:internal-company.ru\""))
        // Proxy rule should appear before direct rule
        val proxyIdx = jsonStr.indexOf("\"domain:blocked-site.com\"")
        val directIdx = jsonStr.indexOf("\"domain:internal-company.ru\"")
        assertTrue(proxyIdx != -1 && directIdx != -1 && proxyIdx < directIdx)
    }

    @Test
    fun testGenerateProxyChainWithXhttpCustomEndpoint() {
        val baseNodeUri = "trojan://mitivpn@104.25.47.216:443?security=tls&sni=de.mitivpn.in.net&type=ws#BaseTrojan"
        val xhttpEndpointUri = "vless://9f07bfd9-715e-4372-8aa6-ec1a39b4157b@media.kali9124123s.space:8443?type=xhttp&host=media.kali9124123s.space&path=%2Fmedia%2Fvideos&encryption=none&security=tls&sni=media.kali9124123s.space&fp=qq&alpn=h2,http/1.1,h3#EndpointXHTTP"

        val profile = UniversalConfigParser.parse(baseNodeUri)!!
        val options = XrayGeneratorOptions(
            socksPort = 10808,
            customEndpointUrl = xhttpEndpointUri
        )

        val jsonStr = XrayConfigGenerator.generateJson(profile, options)
        println("Generated XHTTP Proxy Chain JSON:\n$jsonStr")

        assertTrue(jsonStr.contains("\"tag\": \"proxy\""))
        assertTrue(jsonStr.contains("\"tag\": \"proxy1\""))
        assertTrue(jsonStr.contains("\"dialerProxy\": \"proxy1\""))
        assertTrue(jsonStr.contains("\"network\": \"xhttp\""))
        assertTrue(jsonStr.contains("\"xhttpSettings\""))
        assertTrue(jsonStr.contains("\"path\": \"/media/videos\""))
        assertTrue(jsonStr.contains("\"host\": \"media.kali9124123s.space\""))
    }

    @Test
    fun testGeneratePerProfileFragmentationTcp() {
        val uri = "vless://a1b2c3d4-e5f6-7890-abcd-ef1234567890@198.51.100.1:443?security=reality&sni=dl.google.com&fp=chrome&pbk=AbCdEfGhIjKlMnOpQrStUvWxYz0123456789&sid=1234abcd&type=tcp&v2w_fragment=tcp#Node [⚡FRAG:TCP]"
        val profile = UniversalConfigParser.parse(uri)!!

        assertTrue(profile.fragmentEnabled == true, "Profile must have fragmentEnabled = true")
        assertEquals("1-3", profile.fragmentPackets)

        // Глобальная фрагментация выключена в options
        val options = XrayGeneratorOptions(fragmentEnabled = false)
        val jsonStr = XrayConfigGenerator.generateJson(profile, options)

        assertTrue(jsonStr.contains("\"tag\": \"fragment\""), "Config must contain fragment outbound")
        assertTrue(jsonStr.contains("\"dialerProxy\": \"fragment\""), "Proxy streamSettings must have dialerProxy = fragment")
        assertTrue(jsonStr.contains("\"packets\": \"1-3\""), "Fragment must have packets 1-3 for TCP Split")
    }

    @Test
    fun testGeneratePerProfileFragmentationRecord() {
        val uri = "vless://a1b2c3d4-e5f6-7890-abcd-ef1234567890@198.51.100.1:443?security=tls&sni=dl.google.com&type=tcp&v2w_fragment=record#Node [⚡FRAG:RECORD]"
        val profile = UniversalConfigParser.parse(uri)!!

        assertTrue(profile.fragmentEnabled == true, "Profile must have fragmentEnabled = true")
        assertEquals("tlshello", profile.fragmentPackets)

        val options = XrayGeneratorOptions(fragmentEnabled = false)
        val jsonStr = XrayConfigGenerator.generateJson(profile, options)

        assertTrue(jsonStr.contains("\"tag\": \"fragment\""))
        assertTrue(jsonStr.contains("\"dialerProxy\": \"fragment\""))
        assertTrue(jsonStr.contains("\"packets\": \"tlshello\""))
    }

    @Test
    fun testSplitTunnelingSeparatesDomainAndIpRules() {
        val uri = "vless://a1b2c3d4-e5f6-7890-abcd-ef1234567890@198.51.100.1:443?security=tls&type=tcp#Node"
        val profile = UniversalConfigParser.parse(uri)!!
        val options = XrayGeneratorOptions(
            customDirectDomains = listOf("vk.com", "192.168.10.0/24", "geoip:ru"),
            customProxyDomains = listOf("youtube.com", "8.8.8.8")
        )
        val jsonStr = XrayConfigGenerator.generateJson(profile, options)

        assertTrue(jsonStr.contains("\"domain:vk.com\""))
        assertTrue(jsonStr.contains("\"domain:youtube.com\""))
        assertTrue(jsonStr.contains("\"192.168.10.0/24\""))
        assertTrue(jsonStr.contains("\"geoip:ru\""))
        assertTrue(jsonStr.contains("\"8.8.8.8\""))
        assertFalse(jsonStr.contains("\"domain:192.168.10.0/24\""))
        assertFalse(jsonStr.contains("\"domain:geoip:ru\""))
        assertFalse(jsonStr.contains("\"domain:8.8.8.8\""))
    }

    @Test
    fun testTunSniffingRoutingAndBypassRuRulesSerializedExplicitly() {
        val uri = "vless://a1b2c3d4-e5f6-7890-abcd-ef1234567890@198.51.100.1:443?security=tls&type=tcp#Node"
        val profile = UniversalConfigParser.parse(uri)!!
        val options = XrayGeneratorOptions(
            sniffingEnabled = true,
            bypassRussianServices = true
        )
        val jsonStr = XrayConfigGenerator.generateJson(profile, options)

        // With encodeDefaults = false, default values must still be emitted in JSON
        assertTrue(jsonStr.contains("\"enabled\": true"), "sniffing.enabled must be serialized")
        assertTrue(jsonStr.contains("\"quic\""), "sniffing.destOverride must include quic")
        assertTrue(jsonStr.contains("\"domainStrategy\": \"IPIfNonMatch\""), "routing.domainStrategy must be serialized")
        assertTrue(jsonStr.contains("\"type\": \"field\""), "rule.type must be serialized")

        // bypassRussianServices must split domain rules and geoip:ru into separate OR rules
        assertTrue(jsonStr.contains("\"geosite:category-ru\""))
        assertTrue(jsonStr.contains("\"geoip:ru\""))
        val compact = jsonStr.replace(Regex("\\s+"), "")
        assertFalse(
            compact.contains("\"domain\":[") && compact.contains("\"domain\":[\"geosite:category-ru\"") &&
                compact.substringAfter("\"geosite:category-ru\"").substringBefore("}").contains("\"ip\":[\"geoip:ru\"]"),
            "bypassRussianServices must not combine domain and geoip:ru into a single AND rule"
        )
    }
}
