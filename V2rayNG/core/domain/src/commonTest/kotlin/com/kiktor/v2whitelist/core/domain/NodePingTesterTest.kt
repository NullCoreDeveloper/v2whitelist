package com.kiktor.v2whitelist.core.domain

import com.kiktor.v2whitelist.core.domain.ping.NodePingTester
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodePingTesterTest {

    @Test
    fun testEmptyBatch() = runTest {
        val results = NodePingTester.testBatchTcp(emptyList())
        assertTrue(results.isEmpty())
    }

    @Test
    fun testInvalidHostReturnsNegative() = runTest {
        val profile = ProfileItem(
            configType = EConfigType.VLESS,
            server = "invalid.domain.that.does.not.exist.example.test",
            serverPort = "443"
        )
        val delay = NodePingTester.testSingleTcp(profile, timeoutMs = 200L)
        assertEquals(-1L, delay)
    }

    @Test
    fun testUdpProtocolHandlingInBatch() = runTest {
        val servers = listOf(
            "guid_hy2" to ProfileItem(
                configType = EConfigType.HYSTERIA2,
                server = "1.2.3.4",
                serverPort = "443"
            )
        )
        val results = NodePingTester.testBatchTcp(servers)
        assertEquals(1, results.size)
        assertEquals("guid_hy2", results[0].first)
    }
}
