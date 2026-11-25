package com.kiktor.v2whitelist.core.model

import com.kiktor.v2whitelist.core.model.inspection.FullScanNodeResultDto
import com.kiktor.v2whitelist.core.model.inspection.ScanNetworkCondition
import com.kiktor.v2whitelist.core.model.inspection.ScanSessionMeta
import com.kiktor.v2whitelist.core.model.inspection.ServerInspectionInfo
import com.kiktor.v2whitelist.core.model.inspection.ServiceProbeStatus
import com.kiktor.v2whitelist.core.model.inspection.countryCodeToFlagEmoji
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerInspectionModelsTest {

    @Test
    fun parsesFullScanNodeResultWithServices() {
        val raw = """
            {
              "link": "vless://1111@1.2.3.4:443#DE",
              "alive": true,
              "delay_ms": 142,
              "exit_ip": "185.10.20.30",
              "country_code": "de",
              "frag_mode": "TCP",
              "services": [
                {"id": "youtube", "name": "YouTube", "success": true, "latency_ms": 110},
                {"id": "gemini", "name": "Gemini", "success": false, "latency_ms": -1, "error": "region RUS blocked"}
              ]
            }
        """.trimIndent()

        val dto = FullScanNodeResultDto.fromJson(raw)
        assertNotNull(dto)
        val info = dto.toInspectionInfo(testedAtMs = 123456L)

        assertTrue(info.alive)
        assertEquals(142L, info.delayMs)
        assertEquals("185.10.20.30", info.exitIp)
        assertEquals("DE", info.countryCode)
        assertEquals("tcp", info.fragMode)
        assertNull(info.endpointPassed)
        assertEquals(1, info.okServicesCount)
        assertEquals(2, info.totalServicesCount)
        assertEquals(0.5, info.serviceSuccessRatio)
        assertEquals(123456L, info.testedAtMs)
    }

    @Test
    fun parsesFullScanNodeResultWithEndpointAndDeadNode() {
        val rawEndpoint = """
            {
              "link": "vless://2222@5.6.7.8:443#NL",
              "alive": true,
              "delay_ms": 95,
              "exit_ip": "203.0.113.9",
              "country_code": "NL",
              "endpoint_passed": true
            }
        """.trimIndent()
        val dtoEp = FullScanNodeResultDto.fromJson(rawEndpoint)
        assertNotNull(dtoEp)
        assertEquals(true, dtoEp.endpointPassed)

        val rawDead = """
            {
              "link": "vless://3333@9.9.9.9:443#Dead",
              "alive": false,
              "delay_ms": -1,
              "error": "dial tcp timeout"
            }
        """.trimIndent()
        val dtoDead = FullScanNodeResultDto.fromJson(rawDead)
        assertNotNull(dtoDead)
        assertFalse(dtoDead.alive)
        assertEquals("dial tcp timeout", dtoDead.error)

        assertNull(FullScanNodeResultDto.fromJson("not json"))
        assertNull(FullScanNodeResultDto.fromJson(""))
    }

    @Test
    fun roundTripServerInspectionAndSessionMeta() {
        val original = ServerInspectionInfo(
            alive = true,
            delayMs = 88L,
            exitIp = "198.51.100.42",
            countryCode = "FI",
            fragMode = "record",
            endpointPassed = false,
            services = listOf(
                ServiceProbeStatus(id = "tg", name = "Telegram", success = true, latencyMs = 70L)
            ),
            error = "",
            testedAtMs = 999999L
        )
        val encoded = ServerInspectionInfo.toJson(original)
        val decoded = ServerInspectionInfo.fromJson(encoded)
        assertEquals(original, decoded)

        val meta = ScanSessionMeta(
            startedAtMs = 1000L,
            finishedAtMs = 5000L,
            networkCondition = ScanNetworkCondition.WHITELIST,
            networkCheckEnabled = true,
            endpointEnabled = false,
            totalScanned = 120,
            aliveCount = 34,
            failedCount = 86
        )
        val decodedMeta = ScanSessionMeta.fromJson(ScanSessionMeta.toJson(meta))
        assertEquals(meta, decodedMeta)
    }

    @Test
    fun countryCodeToFlagEmojiHandlesValidAndInvalidCodes() {
        assertEquals("🇩🇪", countryCodeToFlagEmoji("DE"))
        assertEquals("🇳🇱", countryCodeToFlagEmoji(" nl "))
        assertEquals("", countryCodeToFlagEmoji(""))
        assertEquals("", countryCodeToFlagEmoji("USA"))
        assertEquals("", countryCodeToFlagEmoji("12"))
        assertEquals("", countryCodeToFlagEmoji(null))
    }

    @Test
    fun scanNetworkConditionMapping() {
        assertEquals(ScanNetworkCondition.UNKNOWN, ScanNetworkCondition.fromInternetStatus(0, networkCheckEnabled = false))
        assertEquals(ScanNetworkCondition.BLACKLIST, ScanNetworkCondition.fromInternetStatus(0, networkCheckEnabled = true))
        assertEquals(ScanNetworkCondition.WHITELIST, ScanNetworkCondition.fromInternetStatus(1, networkCheckEnabled = true))
        assertEquals(ScanNetworkCondition.NO_INTERNET, ScanNetworkCondition.fromInternetStatus(2, networkCheckEnabled = true))
        assertEquals(ScanNetworkCondition.UNKNOWN, ScanNetworkCondition.fromInternetStatus(99, networkCheckEnabled = true))
    }
}
