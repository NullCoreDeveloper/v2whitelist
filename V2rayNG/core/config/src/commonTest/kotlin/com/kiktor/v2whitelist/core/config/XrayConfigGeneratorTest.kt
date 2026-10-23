package com.kiktor.v2whitelist.core.config

import com.kiktor.v2whitelist.core.config.xray.XrayConfigGenerator
import com.kiktor.v2whitelist.core.config.xray.XrayGeneratorOptions
import com.kiktor.v2whitelist.core.model.EConfigType
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
