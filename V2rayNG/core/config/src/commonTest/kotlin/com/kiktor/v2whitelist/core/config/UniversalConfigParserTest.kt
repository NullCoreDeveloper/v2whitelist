package com.kiktor.v2whitelist.core.config

import com.kiktor.v2whitelist.core.model.EConfigType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class UniversalConfigParserTest {

    @Test
    fun testParseVlessReality() {
        val uri = "vless://a1b2c3d4-e5f6-7890-abcd-ef1234567890@198.51.100.1:443?security=reality&sni=dl.google.com&fp=chrome&pbk=AbCdEfGhIjKlMnOpQrStUvWxYz0123456789&sid=1234abcd&spx=%2F&type=tcp&flow=xtls-rprx-vision#MyVlessRealityNode"
        val profile = UniversalConfigParser.parse(uri)

        assertNotNull(profile)
        assertEquals(EConfigType.VLESS, profile.configType)
        assertEquals("198.51.100.1", profile.server)
        assertEquals("443", profile.serverPort)
        assertEquals("a1b2c3d4-e5f6-7890-abcd-ef1234567890", profile.password)
        assertEquals("reality", profile.security)
        assertEquals("dl.google.com", profile.sni)
        assertEquals("chrome", profile.fingerPrint)
        assertEquals("AbCdEfGhIjKlMnOpQrStUvWxYz0123456789", profile.publicKey)
        assertEquals("1234abcd", profile.shortId)
        assertEquals("/", profile.spiderX)
        assertEquals("xtls-rprx-vision", profile.flow)
        assertEquals("MyVlessRealityNode", profile.remarks)
    }

    @Test
    fun testParseTrojan() {
        val uri = "trojan://secretPassword@proxy.example.com:443?sni=proxy.example.com#MyTrojanNode"
        val profile = UniversalConfigParser.parse(uri)

        assertNotNull(profile)
        assertEquals(EConfigType.TROJAN, profile.configType)
        assertEquals("proxy.example.com", profile.server)
        assertEquals("443", profile.serverPort)
        assertEquals("secretPassword", profile.password)
        assertEquals("proxy.example.com", profile.sni)
        assertEquals("tls", profile.security)
        assertEquals("MyTrojanNode", profile.remarks)
    }

    @Test
    fun testParseShadowsocksSip002() {
        // Base64("chacha20-ietf-poly1305:mysecret") = "Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpteXNlY3JldA=="
        val uri = "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpteXNlY3JldA==@192.0.2.1:8388#MyShadowsocks"
        val profile = UniversalConfigParser.parse(uri)

        assertNotNull(profile)
        assertEquals(EConfigType.SHADOWSOCKS, profile.configType)
        assertEquals("192.0.2.1", profile.server)
        assertEquals("8388", profile.serverPort)
        assertEquals("chacha20-ietf-poly1305", profile.method)
        assertEquals("mysecret", profile.password)
        assertEquals("MyShadowsocks", profile.remarks)
    }

    @Test
    fun testParseHysteria2() {
        val uri = "hysteria2://authPass@hy2.example.com:443?sni=hy2.example.com&obfs=salamander&obfs-password=obfsSecret&mport=20000-40000#MyHy2Node"
        val profile = UniversalConfigParser.parse(uri)

        assertNotNull(profile)
        assertEquals(EConfigType.HYSTERIA2, profile.configType)
        assertEquals("hy2.example.com", profile.server)
        assertEquals("443", profile.serverPort)
        assertEquals("authPass", profile.password)
        assertEquals("hy2.example.com", profile.sni)
        assertEquals("obfsSecret", profile.obfsPassword)
        assertEquals("20000-40000", profile.portHopping)
        assertEquals("MyHy2Node", profile.remarks)
    }

    @Test
    fun testParseVmessJson() {
        // {"v":"2","ps":"MyVmess","add":"198.51.100.2","port":"10086","id":"12345678-1234-1234-1234-123456789abc","aid":"0","scy":"auto","net":"ws","type":"none","host":"example.com","path":"/path","tls":"tls","sni":"example.com"}
        val base64Json = "eyJ2IjoiMiIsInBzIjoiTXlWbWVzcyIsImFkZCI6IjE5OC41MS4xMDAuMiIsInBvcnQiOiIxMDA4NiIsImlkIjoiMTIzNDU2NzgtMTIzNC0xMjM0LTEyMzQtMTIzNDU2Nzg5YWJjIiwiYWlkIjoiMCIsInNjeSI6ImF1dG8iLCJuZXQiOiJ3cyIsInR5cGUiOiJub25lIiwiaG9zdCI6ImV4YW1wbGUuY29tIiwicGF0aCI6Ii9wYXRoIiwidGxzIjoidGxzIiwic25pIjoiZXhhbXBsZS5jb20ifQ=="
        val uri = "vmess://$base64Json"
        val profile = UniversalConfigParser.parse(uri)

        assertNotNull(profile)
        assertEquals(EConfigType.VMESS, profile.configType)
        assertEquals("198.51.100.2", profile.server)
        assertEquals("10086", profile.serverPort)
        assertEquals("12345678-1234-1234-1234-123456789abc", profile.password)
        assertEquals("ws", profile.network)
        assertEquals("/path", profile.path)
        assertEquals("tls", profile.security)
        assertEquals("example.com", profile.sni)
        assertEquals("MyVmess", profile.remarks)
    }
}
