package com.kiktor.v2whitelist.core.domain

import com.kiktor.v2whitelist.core.domain.failover.FailoverServerSelector
import com.kiktor.v2whitelist.core.domain.failover.PhysicalNetworkState
import com.kiktor.v2whitelist.core.domain.failover.TrafficStallDetector
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoFailoverTest {

    private fun createProfile(remarks: String) = ProfileItem(
        remarks = remarks,
        configType = EConfigType.VLESS,
        server = "1.2.3.4",
        serverPort = "443"
    )

    @Test
    fun testTrafficStallDetection() {
        val detector = TrafficStallDetector(
            minUplinkBytes = 1500.0,
            thresholdDownBytes = 3072.0,
            requiredStallSeconds = 3
        )

        // 1. Нормальный трафик: Up есть, Down есть
        assertFalse(detector.onTrafficSample(proxyUpRate = 2048.0, proxyDownRate = 10240.0))
        assertEquals(0, detector.consecutiveStallSeconds)

        // 2. Нет исходящего трафика (тишина): Up < minUplinkBytes
        assertFalse(detector.onTrafficSample(proxyUpRate = 500.0, proxyDownRate = 0.0))
        assertEquals(0, detector.consecutiveStallSeconds)

        // 3. Аномалия (исходящие запросы идут, но ответов нет): Up >= 1500, Down < 3072
        assertFalse(detector.onTrafficSample(proxyUpRate = 2000.0, proxyDownRate = 500.0))
        assertEquals(1, detector.consecutiveStallSeconds)

        assertFalse(detector.onTrafficSample(proxyUpRate = 2500.0, proxyDownRate = 300.0))
        assertEquals(2, detector.consecutiveStallSeconds)

        // 3-й сэмпл подряд -> порог 3 сек достигнут! Возвращает true и сбрасывает счётчик
        assertTrue(detector.onTrafficSample(proxyUpRate = 2200.0, proxyDownRate = 100.0))
        assertEquals(0, detector.consecutiveStallSeconds)

        // 4. Прерывание аномалии: 2 секунды затыка, затем 1 секунда восстановления
        assertFalse(detector.onTrafficSample(proxyUpRate = 2000.0, proxyDownRate = 500.0))
        assertEquals(1, detector.consecutiveStallSeconds)
        assertFalse(detector.onTrafficSample(proxyUpRate = 2000.0, proxyDownRate = 500.0))
        assertEquals(2, detector.consecutiveStallSeconds)
        // Трафик пошел!
        assertFalse(detector.onTrafficSample(proxyUpRate = 3000.0, proxyDownRate = 8000.0))
        assertEquals(0, detector.consecutiveStallSeconds)
    }

    @Test
    fun testFailoverServerSelectorVipPriority() {
        val candidates = listOf(
            "node1" to createProfile("Node 1"),
            "node2" to createProfile("Node 2 (VIP)"),
            "node3" to createProfile("Node 3")
        )
        val vipGuids = listOf("node2")

        // Сбой произошел на node1. В VIP есть node2 -> должен выбрать node2
        val next = FailoverServerSelector.selectNextServer(
            currentGuid = "node1",
            candidates = candidates,
            vipGuids = vipGuids
        )
        assertNotNull(next)
        assertEquals("node2", next.first)
    }

    @Test
    fun testFailoverServerSelectorRoundRobinWhenNoVip() {
        val candidates = listOf(
            "node1" to createProfile("Node 1"),
            "node2" to createProfile("Node 2"),
            "node3" to createProfile("Node 3")
        )

        // Текущий сервер node2, VIP пуст -> должен переключить на следующий по кругу (node3)
        val next = FailoverServerSelector.selectNextServer(
            currentGuid = "node2",
            candidates = candidates,
            vipGuids = emptyList()
        )
        assertNotNull(next)
        assertEquals("node3", next.first)

        // Текущий сервер node3 -> должен закольцеваться на node1
        val wrapAround = FailoverServerSelector.selectNextServer(
            currentGuid = "node3",
            candidates = candidates,
            vipGuids = emptyList()
        )
        assertNotNull(wrapAround)
        assertEquals("node1", wrapAround.first)
    }

    @Test
    fun testFailoverServerSelectorSingleServerReturnsNull() {
        val candidates = listOf("node1" to createProfile("Single Node"))
        val next = FailoverServerSelector.selectNextServer(
            currentGuid = "node1",
            candidates = candidates,
            vipGuids = emptyList()
        )
        assertNull(next)
    }

    @Test
    fun testPhysicalNetworkArbitrationRule() {
        assertFalse(FailoverServerSelector.shouldProceedWithFailover(PhysicalNetworkState.NO_INTERNET))
        assertTrue(FailoverServerSelector.shouldProceedWithFailover(PhysicalNetworkState.WHITELIST_ONLY))
        assertTrue(FailoverServerSelector.shouldProceedWithFailover(PhysicalNetworkState.FULL_INTERNET))
    }
}
