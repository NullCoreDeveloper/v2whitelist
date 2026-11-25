package com.kiktor.v2whitelist.core.domain

import com.kiktor.v2whitelist.core.domain.inspection.EndpointFilterMode
import com.kiktor.v2whitelist.core.domain.inspection.ServerListEngine
import com.kiktor.v2whitelist.core.domain.inspection.ServerListEntry
import com.kiktor.v2whitelist.core.domain.inspection.ServerListFilter
import com.kiktor.v2whitelist.core.domain.inspection.ServerSortOrder
import com.kiktor.v2whitelist.core.domain.inspection.ServerStatusFilter
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.inspection.ServerInspectionInfo
import com.kiktor.v2whitelist.core.model.inspection.ServiceProbeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerListEngineTest {

    private fun makeEntry(
        guid: String,
        remarks: String,
        protocol: EConfigType = EConfigType.VLESS,
        subId: String = "sub_1",
        subName: String = "MainSub",
        isVip: Boolean = false,
        fallbackPingMs: Long = 0L,
        inspection: ServerInspectionInfo? = null
    ): ServerListEntry {
        return ServerListEntry(
            guid = guid,
            profile = ProfileItem(configType = protocol, remarks = remarks, server = "example.com"),
            subscriptionId = subId,
            subscriptionName = subName,
            isVip = isVip,
            isPersonal = false,
            fallbackPingMs = fallbackPingMs,
            inspection = inspection
        )
    }

    private fun svc(id: String, ok: Boolean): ServiceProbeStatus {
        return ServiceProbeStatus(id = id, name = id.uppercase(), success = ok, latencyMs = if (ok) 80L else -1L)
    }

    @Test
    fun separatesVipAndAppliesSmartSortingWithDeadAlwaysAtBottom() {
        val vipAlive = makeEntry(
            guid = "vip1",
            remarks = "VIP Germany",
            isVip = true,
            inspection = ServerInspectionInfo(
                alive = true,
                delayMs = 120L,
                countryCode = "DE",
                services = listOf(svc("youtube", true), svc("chatgpt", true))
            )
        )
        val vipDead = makeEntry(
            guid = "vip2",
            remarks = "VIP Dead",
            isVip = true,
            inspection = ServerInspectionInfo(alive = false, delayMs = -1L, error = "timeout")
        )
        val regTopServices = makeEntry(
            guid = "r1",
            remarks = "All Services",
            inspection = ServerInspectionInfo(
                alive = true,
                delayMs = 200L,
                countryCode = "NL",
                services = listOf(svc("youtube", true), svc("chatgpt", true), svc("gemini", true))
            )
        )
        val regTwoServicesFast = makeEntry(
            guid = "r2",
            remarks = "Two Services Fast",
            inspection = ServerInspectionInfo(
                alive = true,
                delayMs = 60L,
                countryCode = "FI",
                services = listOf(svc("youtube", true), svc("chatgpt", true), svc("gemini", false))
            )
        )
        val regTwoServicesSlow = makeEntry(
            guid = "r3",
            remarks = "Two Services Slow",
            inspection = ServerInspectionInfo(
                alive = true,
                delayMs = 150L,
                countryCode = "FI",
                services = listOf(svc("youtube", true), svc("chatgpt", true), svc("gemini", false))
            )
        )
        val regUntested = makeEntry(guid = "r4", remarks = "Untested Node", fallbackPingMs = 40L, inspection = null)
        val regDead = makeEntry(
            guid = "r5",
            remarks = "AAA Dead Node",
            inspection = ServerInspectionInfo(alive = false, delayMs = -1L, error = "refused")
        )

        val entries = listOf(regDead, regUntested, regTwoServicesSlow, vipDead, regTwoServicesFast, vipAlive, regTopServices)
        val res = ServerListEngine.buildView(
            entries = entries,
            filter = ServerListFilter(sortOrder = ServerSortOrder.SMART),
            endpointEnabled = false,
            removedDuplicatesCount = 7
        )

        assertEquals(listOf("vip1", "vip2"), res.vipServers.map { it.guid })
        assertEquals(listOf("r1", "r2", "r3", "r4", "r5"), res.regularServers.map { it.guid })
        assertEquals(7, res.stats.totalServers)
        assertEquals(2, res.stats.vipCount)
        assertEquals(4, res.stats.aliveCount)
        assertEquals(2, res.stats.deadCount)
        assertEquals(1, res.stats.untestedCount)
        assertEquals(7, res.stats.removedDuplicatesCount)

        // Even with NAME_ASC or PING_ASC, dead nodes remain at the bottom below alive & untested
        val resNameAsc = ServerListEngine.buildView(
            entries = listOf(regDead, regUntested, regTwoServicesFast),
            filter = ServerListFilter(sortOrder = ServerSortOrder.NAME_ASC),
            endpointEnabled = false
        )
        assertEquals(listOf("r2", "r4", "r5"), resNameAsc.regularServers.map { it.guid })
    }

    @Test
    fun enforcesMutualExclusivityBetweenEndpointAndServiceFilters() {
        val epPassed = makeEntry(
            guid = "ep1",
            remarks = "EP Passed",
            inspection = ServerInspectionInfo(
                alive = true,
                delayMs = 180L,
                countryCode = "DE",
                endpointPassed = true,
                services = emptyList()
            )
        )
        val epFailed = makeEntry(
            guid = "ep2",
            remarks = "EP Failed",
            inspection = ServerInspectionInfo(
                alive = true,
                delayMs = 50L,
                countryCode = "DE",
                endpointPassed = false,
                services = emptyList()
            )
        )

        // When endpointEnabled = true, endpointPassed=true ranks above endpointPassed=false in SMART sort,
        // PASSED_ONLY filters to ep1, and requiredServiceIds is ignored.
        val viewEndpoint = ServerListEngine.buildView(
            entries = listOf(epFailed, epPassed),
            filter = ServerListFilter(
                endpointFilter = EndpointFilterMode.PASSED_ONLY,
                requiredServiceIds = setOf("youtube")
            ),
            endpointEnabled = true
        )
        assertEquals(listOf("ep1"), viewEndpoint.regularServers.map { it.guid })

        val viewEndpointSmart = ServerListEngine.buildView(
            entries = listOf(epFailed, epPassed),
            filter = ServerListFilter(sortOrder = ServerSortOrder.SMART),
            endpointEnabled = true
        )
        assertEquals(listOf("ep1", "ep2"), viewEndpointSmart.regularServers.map { it.guid })

        // When endpointEnabled = false, requiredServiceIds filters entries and endpointFilter is ignored.
        val svcOk = makeEntry(
            guid = "s1",
            remarks = "Has YT & GPT",
            inspection = ServerInspectionInfo(
                alive = true,
                delayMs = 100L,
                services = listOf(svc("youtube", true), svc("chatgpt", true))
            )
        )
        val svcPartial = makeEntry(
            guid = "s2",
            remarks = "Only YT",
            inspection = ServerInspectionInfo(
                alive = true,
                delayMs = 80L,
                services = listOf(svc("youtube", true), svc("chatgpt", false))
            )
        )
        val viewServices = ServerListEngine.buildView(
            entries = listOf(svcOk, svcPartial),
            filter = ServerListFilter(
                requiredServiceIds = setOf("youtube", "chatgpt"),
                endpointFilter = EndpointFilterMode.PASSED_ONLY
            ),
            endpointEnabled = false
        )
        assertEquals(listOf("s1"), viewServices.regularServers.map { it.guid })
    }

    @Test
    fun filtersByCountriesSubscriptionsProtocolsSearchAndStatus() {
        val n1 = makeEntry(
            guid = "n1",
            remarks = "Frankfurt Fast",
            protocol = EConfigType.VLESS,
            subId = "sub_a",
            subName = "Alpha",
            inspection = ServerInspectionInfo(alive = true, delayMs = 70L, exitIp = "185.10.1.2", countryCode = "DE")
        )
        val n2 = makeEntry(
            guid = "n2",
            remarks = "Amsterdam Trojan",
            protocol = EConfigType.TROJAN,
            subId = "sub_b",
            subName = "Beta",
            inspection = ServerInspectionInfo(alive = true, delayMs = 90L, exitIp = "198.51.100.5", countryCode = "NL")
        )
        val n3 = makeEntry(
            guid = "n3",
            remarks = "Helsinki Dead",
            protocol = EConfigType.HYSTERIA2,
            subId = "sub_a",
            subName = "Alpha",
            inspection = ServerInspectionInfo(alive = false, delayMs = -1L, countryCode = "FI")
        )

        val entries = listOf(n1, n2, n3)

        // Multi-country + multi-sub + protocol + hide dead
        val filtered = ServerListEngine.buildView(
            entries = entries,
            filter = ServerListFilter(
                statusFilter = ServerStatusFilter.HIDE_DEAD,
                selectedCountryCodes = setOf("de", "fi"),
                selectedSubscriptionIds = setOf("sub_a"),
                selectedProtocols = setOf(EConfigType.VLESS, EConfigType.HYSTERIA2)
            ),
            endpointEnabled = false
        )
        assertEquals(listOf("n1"), filtered.regularServers.map { it.guid })
        assertEquals(mapOf("DE" to 1, "FI" to 1, "NL" to 1), filtered.availableCountryCounts)

        // Search by Exit IP
        val byIp = ServerListEngine.buildView(
            entries = entries,
            filter = ServerListFilter(searchQuery = "198.51.100"),
            endpointEnabled = false
        )
        assertEquals(listOf("n2"), byIp.regularServers.map { it.guid })
        assertTrue(filtered.stats.filteredServers == 1)
    }
}
