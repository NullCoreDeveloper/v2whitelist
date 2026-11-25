package com.kiktor.v2whitelist.core.config

import com.kiktor.v2whitelist.core.config.xray.DomainRouteTester
import com.kiktor.v2whitelist.core.config.xray.RouteVerdict
import com.kiktor.v2whitelist.core.config.xray.XrayGeneratorOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DomainRouteTesterTest {

    @Test
    fun testNormalizesUrlPortAndPath() {
        assertEquals("sub.gosuslugi.ru", DomainRouteTester.normalizeInput("https://sub.gosuslugi.ru:8443/lk/profile?a=1"))
        assertEquals("192.168.1.1", DomainRouteTester.normalizeInput("http://192.168.1.1:8080/"))
        assertEquals("youtube.com", DomainRouteTester.normalizeInput("YOUTUBE.COM."))
    }

    @Test
    fun testBypassRuMatchesExplicitAndRuTld() {
        val options = XrayGeneratorOptions(bypassRussianServices = true)
        val sber = DomainRouteTester.test("online.sberbank.ru", options)
        assertEquals(RouteVerdict.DIRECT, sber.verdict)
        assertTrue(sber.matchedRule.contains("Bypass RU"))

        val ruDomain = DomainRouteTester.test("myip.ru", options)
        assertEquals(RouteVerdict.DIRECT, ruDomain.verdict)
        assertTrue(ruDomain.matchedRule.contains(".ru"))
    }

    @Test
    fun testPrivateIpMatchesDirectWhenBypassPrivateEnabled() {
        val enabled = DomainRouteTester.test("192.168.1.1", XrayGeneratorOptions(bypassPrivateIps = true))
        assertEquals(RouteVerdict.DIRECT, enabled.verdict)
        assertTrue(enabled.matchedRule.contains("Private IP"))

        val resolvedLan = DomainRouteTester.test(
            "router.local",
            XrayGeneratorOptions(bypassPrivateIps = true, bypassRussianServices = false),
            resolvedIps = listOf("10.0.0.1")
        )
        assertEquals(RouteVerdict.DIRECT, resolvedLan.verdict)

        val disabled = DomainRouteTester.test(
            "192.168.1.1",
            XrayGeneratorOptions(bypassPrivateIps = false, bypassRussianServices = false)
        )
        assertEquals(RouteVerdict.PROXY, disabled.verdict)
    }

    @Test
    fun testCustomProxyOverridesWhitelistAndCustomDirect() {
        val options = XrayGeneratorOptions(
            bypassRussianServices = false,
            domainWhitelistMode = true,
            customProxyDomains = listOf("youtube.com", "203.0.113.0/24"),
            customDirectDomains = listOf("youtube.com")
        )
        val yt = DomainRouteTester.test("www.youtube.com", options)
        assertEquals(RouteVerdict.PROXY, yt.verdict)
        assertTrue(yt.matchedRule.contains("Custom Proxy"))

        val cidr = DomainRouteTester.test("api.example.org", options, resolvedIps = listOf("203.0.113.42"))
        assertEquals(RouteVerdict.PROXY, cidr.verdict)
        assertTrue(cidr.matchedRule.contains("203.0.113.0/24"))

        val otherInWhitelist = DomainRouteTester.test("example.com", options)
        assertEquals(RouteVerdict.DIRECT, otherInWhitelist.verdict)
        assertTrue(otherInWhitelist.matchedRule.contains("Whitelist"))
    }

    @Test
    fun testCustomDirectAndDefaultProxyWithGeoNote() {
        val options = XrayGeneratorOptions(
            bypassRussianServices = true,
            domainWhitelistMode = false,
            customDirectDomains = listOf("keyword:internal", "full:exact.example.net")
        )
        assertEquals(RouteVerdict.DIRECT, DomainRouteTester.test("portal.internal.corp", options).verdict)
        assertEquals(RouteVerdict.DIRECT, DomainRouteTester.test("exact.example.net", options).verdict)
        assertEquals(RouteVerdict.PROXY, DomainRouteTester.test("sub.exact.example.net", options).verdict)

        val defaultRes = DomainRouteTester.test("ipinfo.io", options)
        assertEquals(RouteVerdict.PROXY, defaultRes.verdict)
        assertNotNull(defaultRes.note)
    }
}
