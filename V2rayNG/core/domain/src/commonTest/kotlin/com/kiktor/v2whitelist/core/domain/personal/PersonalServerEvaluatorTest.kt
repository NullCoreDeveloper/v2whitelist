package com.kiktor.v2whitelist.core.domain.personal

import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.NetworkBindingRule
import com.kiktor.v2whitelist.core.model.PersonalServersConfig
import com.kiktor.v2whitelist.core.model.ProfileItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonalServerEvaluatorTest {

    private val server1 = "guid-1" to ProfileItem(
        configType = EConfigType.VLESS,
        subscriptionId = PersonalServersConfig.SUB_ID_PERSONAL_DIRECT,
        remarks = "Personal VPS Netherlands",
        server = "1.2.3.4",
        serverPort = "443"
    )

    private val server2 = "guid-2" to ProfileItem(
        configType = EConfigType.TROJAN,
        subscriptionId = "${PersonalServersConfig.SUB_ID_PERSONAL_PREFIX}sub1",
        remarks = "Personal Backup Germany",
        server = "5.6.7.8",
        serverPort = "8443"
    )

    private val candidates = listOf(server1, server2)

    @Test
    fun testDisabledConfigFallbackToPublic() {
        val config = PersonalServersConfig(enabled = false)
        val decision = PersonalServerEvaluator.evaluate(
            config = config,
            isWifi = true,
            isCellular = false,
            isBsJamming = false,
            allPersonalCandidates = candidates
        )
        assertTrue(decision is PersonalConnectDecision.UsePublicSubscriptions)
    }

    @Test
    fun testDirectConnectWhenSpecificServerSelected() {
        val config = PersonalServersConfig(
            enabled = true,
            selectedServerGuid = "guid-1",
            wifiRule = NetworkBindingRule.DEFAULT,
            cellularRule = NetworkBindingRule.DEFAULT
        )
        val decision = PersonalServerEvaluator.evaluate(
            config = config,
            isWifi = true,
            isCellular = false,
            isBsJamming = false,
            allPersonalCandidates = candidates
        )
        assertTrue(decision is PersonalConnectDecision.ConnectPersonalDirect)
        assertEquals("guid-1", decision.guid)
    }

    @Test
    fun testScanPersonalPoolWhenNoServerSelected() {
        val config = PersonalServersConfig(
            enabled = true,
            selectedServerGuid = null,
            wifiRule = NetworkBindingRule.DEFAULT,
            cellularRule = NetworkBindingRule.DEFAULT
        )
        val decision = PersonalServerEvaluator.evaluate(
            config = config,
            isWifi = true,
            isCellular = false,
            isBsJamming = false,
            allPersonalCandidates = candidates
        )
        assertTrue(decision is PersonalConnectDecision.ScanPersonalPool)
        assertEquals(2, decision.candidates.size)
    }

    @Test
    fun testCellularRuleSwitchesToPublicSubscriptions() {
        val config = PersonalServersConfig(
            enabled = true,
            selectedServerGuid = "guid-1",
            wifiRule = NetworkBindingRule.USE_PERSONAL_SELECTED,
            cellularRule = NetworkBindingRule.USE_PUBLIC_SUBS
        )
        // On cellular network
        val decision = PersonalServerEvaluator.evaluate(
            config = config,
            isWifi = false,
            isCellular = true,
            isBsJamming = false,
            allPersonalCandidates = candidates
        )
        assertTrue(decision is PersonalConnectDecision.UsePublicSubscriptions)
    }

    @Test
    fun testBsJammingRuleSwitchesToPublicSubscriptions() {
        val config = PersonalServersConfig(
            enabled = true,
            selectedServerGuid = "guid-1",
            wifiRule = NetworkBindingRule.USE_PERSONAL_SELECTED,
            cellularRule = NetworkBindingRule.USE_PUBLIC_SUBS
        )
        // Even on Wi-Fi, if Jamming is detected, follow cellular/jamming rule
        val decision = PersonalServerEvaluator.evaluate(
            config = config,
            isWifi = true,
            isCellular = false,
            isBsJamming = true,
            allPersonalCandidates = candidates
        )
        assertTrue(decision is PersonalConnectDecision.UsePublicSubscriptions)
    }

    @Test
    fun testEmptyPoolFallbackToPublic() {
        val config = PersonalServersConfig(
            enabled = true,
            fallbackToPublicOnFailure = true
        )
        val decision = PersonalServerEvaluator.evaluate(
            config = config,
            isWifi = true,
            isCellular = false,
            isBsJamming = false,
            allPersonalCandidates = emptyList()
        )
        assertTrue(decision is PersonalConnectDecision.UsePublicSubscriptions)
    }

    @Test
    fun testIsPersonalServerHelper() {
        assertTrue(PersonalServersConfig.isPersonalServer(PersonalServersConfig.SUB_ID_PERSONAL_DIRECT))
        assertTrue(PersonalServersConfig.isPersonalServer("personal_sub_12345"))
        assertTrue(!PersonalServersConfig.isPersonalServer("v2whitelist_hardcoded_sub"))
        assertTrue(!PersonalServersConfig.isPersonalServer("custom_sub_99"))
        assertTrue(!PersonalServersConfig.isPersonalServer(null))
        assertTrue(!PersonalServersConfig.isPersonalServer(""))
    }
}

