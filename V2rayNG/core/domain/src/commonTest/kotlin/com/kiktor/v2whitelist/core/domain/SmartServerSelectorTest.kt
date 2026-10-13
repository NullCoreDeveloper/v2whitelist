package com.kiktor.v2whitelist.core.domain

import com.kiktor.v2whitelist.core.domain.network.NetworkStatusChecker
import com.kiktor.v2whitelist.core.domain.selector.LocationFilterMode
import com.kiktor.v2whitelist.core.domain.selector.SmartServerFilterConfig
import com.kiktor.v2whitelist.core.domain.selector.SmartServerSelector
import com.kiktor.v2whitelist.core.model.AppScenario
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.subscription.model.CustomSubData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmartServerSelectorTest {

    private fun createProfile(
        remarks: String,
        subId: String,
        type: EConfigType = EConfigType.VLESS,
        security: String = "tls",
        insecure: Boolean = false
    ) = ProfileItem(
        remarks = remarks,
        subscriptionId = subId,
        configType = type,
        security = security,
        insecure = insecure
    )

    @Test
    fun testExcludeGuidAndDisabledSub() {
        val candidates = listOf(
            "guid1" to createProfile("🇩🇪 Server 1", "sub_active"),
            "guid2" to createProfile("🇩🇪 Server 2", "sub_disabled"),
            "guid3" to createProfile("🇫🇮 Server 3", "sub_active")
        )

        val config = SmartServerFilterConfig(
            excludeGuid = "guid1",
            disabledSubscriptionIds = setOf("sub_disabled"),
            locationFilterMode = LocationFilterMode.DISABLED,
            filterByScenario = false
        )

        val result = SmartServerSelector.filterServerPairs(candidates, config)
        assertEquals(1, result.size)
        assertEquals("guid3", result[0].first)
    }

    @Test
    fun testLocationExcludeFilter() {
        val candidates = listOf(
            "ru" to createProfile("🇷🇺 Russia Server", "sub1"),
            "ua" to createProfile("🇺🇦 Ukraine Server", "sub1"),
            "de" to createProfile("🇩🇪 Germany Server", "sub1")
        )

        val config = SmartServerFilterConfig(
            locationFilterMode = LocationFilterMode.EXCLUDE,
            locationFilterSet = setOf("🇷🇺", "🇺🇦"),
            filterByScenario = false
        )

        val result = SmartServerSelector.filterServerPairs(candidates, config)
        assertEquals(1, result.size)
        assertEquals("de", result[0].first)
    }

    @Test
    fun testLocationWhitelistFilter() {
        val candidates = listOf(
            "ru" to createProfile("🇷🇺 Russia Server", "sub1"),
            "nl" to createProfile("🇳🇱 Netherlands Server", "sub1"),
            "de" to createProfile("🇩🇪 Germany Server", "sub1")
        )

        val config = SmartServerFilterConfig(
            locationFilterMode = LocationFilterMode.WHITELIST,
            locationFilterSet = setOf("🇩🇪"),
            filterByScenario = false
        )

        val result = SmartServerSelector.filterServerPairs(candidates, config)
        assertEquals(1, result.size)
        assertEquals("de", result[0].first)
    }

    @Test
    fun testInsecureFiltering() {
        val candidates = listOf(
            "secure" to createProfile("Server 1", "sub1", security = "tls", insecure = false),
            "insecure_flag" to createProfile("Server 2", "sub1", security = "tls", insecure = true),
            "no_tls" to createProfile("Server 3", "sub1", security = "none", insecure = false)
        )

        val config = SmartServerFilterConfig(
            filterInsecure = true,
            locationFilterMode = LocationFilterMode.DISABLED,
            filterByScenario = false
        )

        val result = SmartServerSelector.filterServerPairs(candidates, config)
        assertEquals(1, result.size)
        assertEquals("secure", result[0].first)
    }

    @Test
    fun testScenarioFilteringAutoWhitelistJammed() {
        val candidates = listOf(
            "bs_node" to createProfile("BS Node", "sub_bs"),
            "vpn_node" to createProfile("VPN Node", "sub_vpn")
        )

        val scenariosMap = mapOf(
            "sub_bs" to setOf(CustomSubData.SCENARIO_WHITELIST),
            "sub_vpn" to setOf(CustomSubData.SCENARIO_BLACKLIST)
        )

        // Сеть глушат: internetStatus = STATUS_WHITELIST_ONLY (1)
        val config = SmartServerFilterConfig(
            locationFilterMode = LocationFilterMode.DISABLED,
            appScenario = AppScenario.AUTO,
            internetStatus = NetworkStatusChecker.STATUS_WHITELIST_ONLY,
            subscriptionScenariosMap = scenariosMap,
            filterByScenario = true
        )

        val result = SmartServerSelector.filterServerPairs(candidates, config)
        assertEquals(1, result.size)
        assertEquals("bs_node", result[0].first)
    }

    @Test
    fun testScenarioGracefulFallbackWhenZeroMatch() {
        val candidates = listOf(
            "vpn_node" to createProfile("VPN Node", "sub_vpn")
        )

        val scenariosMap = mapOf(
            "sub_vpn" to setOf(CustomSubData.SCENARIO_BLACKLIST)
        )

        // Запрошен режим WHITELIST, но есть только VPN_BLACKLIST
        val config = SmartServerFilterConfig(
            locationFilterMode = LocationFilterMode.DISABLED,
            appScenario = AppScenario.WHITELIST,
            subscriptionScenariosMap = scenariosMap,
            filterByScenario = true
        )

        val result = SmartServerSelector.filterServerPairs(candidates, config)
        // Сработал Graceful Fallback - возвращается доступный узел, связь не рвется
        assertEquals(1, result.size)
        assertEquals("vpn_node", result[0].first)
    }

    @Test
    fun testRankServersWithVipPreference() {
        val candidates = listOf(
            "reg1" to createProfile("Server 1", "sub1"),
            "reg2" to createProfile("Server 2", "sub1"),
            "vip1" to createProfile("VIP Server", "sub1"),
            "dead" to createProfile("Dead Server", "sub1")
        )

        val pingResults = mapOf(
            "reg1" to 100L,
            "vip1" to 110L, // VIP медленнее на 10мс (порог <= 50мс) -> должен встать первым
            "reg2" to 250L,
            "dead" to -1L   // Недоступный -> должен быть отфильтрован
        )

        val ranked = SmartServerSelector.rankServers(
            candidates = candidates,
            pingResults = pingResults,
            vipGuids = setOf("vip1")
        )

        assertEquals(3, ranked.size)
        assertEquals("vip1", ranked[0].first)
        assertEquals("reg1", ranked[1].first)
        assertEquals("reg2", ranked[2].first)
    }
}
