package com.kiktor.v2whitelist.desktop

import com.kiktor.v2whitelist.core.domain.inspection.ServerFingerprint
import com.kiktor.v2whitelist.core.domain.inspection.ServerInspectionCachePolicy
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.inspection.ScanNetworkCondition
import com.kiktor.v2whitelist.core.model.inspection.ScanSessionMeta
import com.kiktor.v2whitelist.core.model.inspection.ServerInspectionInfo
import com.kiktor.v2whitelist.core.model.inspection.ServiceProbeStatus
import com.kiktor.v2whitelist.desktop.data.DesktopInspectionStore
import com.kiktor.v2whitelist.desktop.engine.V2WCoreScanner
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopFullScanBackendTest {

    @Test
    fun parseFullScanProtocolLines() {
        val resultLine = """RESULT	{"link":"vless://id@1.2.3.4:443#DE","alive":true,"delay_ms":123,"exit_ip":"185.10.20.30","country_code":"DE","frag_mode":"tcp","services":[{"id":"youtube","name":"YouTube","success":true,"latency_ms":90}]}"""
        val dto = V2WCoreScanner.parseFullScanResultLine(resultLine)
        assertNotNull(dto)
        assertEquals("vless://id@1.2.3.4:443#DE", dto.link)
        assertTrue(dto.alive)
        assertEquals(123L, dto.delayMs)
        assertEquals("185.10.20.30", dto.exitIp)
        assertEquals("DE", dto.countryCode)
        assertEquals("tcp", dto.fragMode)
        assertEquals(1, dto.services.size)
        assertEquals("youtube", dto.services.first().id)

        assertNull(V2WCoreScanner.parseFullScanResultLine("COMPLETE\t1\t2"))
        assertNull(V2WCoreScanner.parseFullScanResultLine("RESULT\t{not_json"))

        val complete = V2WCoreScanner.parseFullScanCompleteLine("COMPLETE\t5\t12")
        assertEquals(5 to 12, complete)
        assertNull(V2WCoreScanner.parseFullScanCompleteLine("COMPLETE\t5"))
    }

    @Test
    fun inspectionStorePersistsByFingerprintPrunesExpiredAndSavesMeta() {
        val tempDir = Files.createTempDirectory("v2w_insp_test_").toFile()
        try {
            val file = tempDir.resolve("inspections.json")
            val now = 1_700_000_000_000L

            val profile = ProfileItem(
                configType = EConfigType.VLESS,
                subscriptionId = "sub_old",
                remarks = "Germany Old",
                server = "de.example.com",
                serverPort = "443",
                password = "uuid-1",
                network = "tcp",
                sni = "cdn.example.com",
                publicKey = "pk1"
            )
            val staleProfile = profile.copy(server = "stale.example.com", password = "uuid-stale")

            val store = DesktopInspectionStore(storageFile = file, loadImmediately = false)
            store.put(
                ServerFingerprint.compute(profile),
                ServerInspectionInfo(
                    alive = true,
                    delayMs = 140L,
                    exitIp = "1.1.1.1",
                    countryCode = "DE",
                    services = listOf(ServiceProbeStatus("youtube", "YouTube", true, 80L)),
                    testedAtMs = now - 60_000L
                )
            )
            store.put(
                ServerFingerprint.compute(staleProfile),
                ServerInspectionInfo(
                    alive = true,
                    delayMs = 200L,
                    testedAtMs = now - ServerInspectionCachePolicy.EXPIRE_AFTER_MS - 10_000L
                )
            )
            store.setLastMeta(
                ScanSessionMeta(
                    startedAtMs = now - 5000L,
                    finishedAtMs = now,
                    networkCondition = ScanNetworkCondition.WHITELIST,
                    networkCheckEnabled = true,
                    endpointEnabled = false,
                    totalScanned = 10,
                    aliveCount = 4,
                    failedCount = 6
                )
            )
            store.setLastRemovedDuplicates(7)
            store.flushToDisk(nowMs = now)

            val reloaded = DesktopInspectionStore(storageFile = file, loadImmediately = false)
            reloaded.loadFromDisk(nowMs = now)

            // Обновлённый профиль из новой подписки (другой GUID/подписка/remarks) находит свой кэш
            val refreshedProfile = profile.copy(subscriptionId = "sub_new", remarks = "🇩🇪 Germany Renamed")
            val hit = reloaded.get(refreshedProfile, nowMs = now)
            assertNotNull(hit)
            assertEquals(140L, hit.delayMs)
            assertEquals("DE", hit.countryCode)

            // Протухшая (>24ч) запись удаляется автоматически
            assertNull(reloaded.get(staleProfile, nowMs = now))
            assertEquals(1, reloaded.size())
            assertEquals(7, reloaded.getLastRemovedDuplicates())
            assertEquals(ScanNetworkCondition.WHITELIST, reloaded.getLastMeta()?.networkCondition)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
