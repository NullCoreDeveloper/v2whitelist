package com.kiktor.v2whitelist.desktop

import com.kiktor.v2whitelist.core.model.AppScenario
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.repository.InMemoryServerRepository
import com.kiktor.v2whitelist.core.repository.InMemorySettingsRepository
import com.kiktor.v2whitelist.desktop.engine.DesktopCoreState
import com.kiktor.v2whitelist.desktop.engine.XrayDesktopRunner
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import com.kiktor.v2whitelist.desktop.state.DesktopTab
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopAppTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun testXrayDesktopRunnerStateTransitions() {
        val runner = XrayDesktopRunner()
        assertEquals(DesktopCoreState.STOPPED, runner.coreState.value)

        // Запуск с несуществующим путем должен перевести в ERROR
        val profile = ProfileItem(
            configType = EConfigType.VLESS,
            remarks = "Test-Node",
            server = "127.0.0.1",
            serverPort = "443",
            password = "test-uuid-value"
        )

        val result = runner.start(
            profile = profile,
            xrayBinaryPath = "/non/existent/path/to/xray_fake_binary_123"
        )
        assertFalse(result)
        assertEquals(DesktopCoreState.ERROR, runner.coreState.value)
        assertNotNull(runner.lastError.value)

        runner.stop()
        assertEquals(DesktopCoreState.STOPPED, runner.coreState.value)
    }

    @Test
    fun testDesktopAppStateInitialValuesAndTabs() = runTest {
        val serverRepo = InMemoryServerRepository()
        val settingsRepo = InMemorySettingsRepository()
        val runner = XrayDesktopRunner()

        val sampleProfile = ProfileItem(
            configType = EConfigType.VLESS,
            remarks = "RU-Fastest-Node",
            server = "ru.node.example.org",
            serverPort = "443"
        )
        serverRepo.saveServerConfig("node-1", sampleProfile)

        val state = DesktopAppState(
            serverRepo = serverRepo,
            settingsRepo = settingsRepo,
            xrayRunner = runner,
            autoCheckNetworkOnStart = false
        )

        assertEquals(DesktopTab.MAIN, state.activeTab.value)
        assertEquals(1, state.servers.value.size)
        assertEquals("node-1", state.selectedGuid.value)

        state.setTab(DesktopTab.GEEK_MODE)
        assertEquals(DesktopTab.GEEK_MODE, state.activeTab.value)

        state.setScenario(AppScenario.WHITELIST)
        assertEquals(AppScenario.WHITELIST, state.scenario.value)
        assertEquals(AppScenario.WHITELIST, settingsRepo.getAppScenario())

        state.setSearchQuery("Fastest")
        assertEquals("Fastest", state.searchQuery.value)
    }

    @Test
    fun testDesktopAppStateSettingsUpdate() = runTest {
        val serverRepo = InMemoryServerRepository()
        val settingsRepo = InMemorySettingsRepository()
        val runner = XrayDesktopRunner()

        val state = DesktopAppState(
            serverRepo = serverRepo,
            settingsRepo = settingsRepo,
            xrayRunner = runner,
            autoCheckNetworkOnStart = false
        )

        state.saveSettings(
            subUrl = "https://sub.example.com/v2ray",
            xrayPath = "/usr/local/bin/xray",
            v2wPath = "/usr/local/bin/v2w-scan",
            httpP = 8080,
            socksP = 1080
        )

        assertEquals("https://sub.example.com/v2ray", state.subscriptionUrl.value)
        assertEquals("/usr/local/bin/xray", state.xrayBinaryPath.value)
        assertEquals("/usr/local/bin/v2w-scan", state.v2wScanPath.value)
        assertEquals(8080, state.httpPort.value)
        assertEquals(1080, state.socksPort.value)

        assertEquals("https://sub.example.com/v2ray", settingsRepo.getString("subscription_url"))
        assertEquals("/usr/local/bin/xray", settingsRepo.getString("xray_path"))
        assertEquals("/usr/local/bin/v2w-scan", settingsRepo.getString("v2w_scan_path"))
        assertEquals(8080, settingsRepo.getHttpPort())
        assertEquals(1080, settingsRepo.getSocksPort())
    }

    @Test
    fun testV2WCoreScannerExecutableDiscovery() {
        val bin = com.kiktor.v2whitelist.desktop.engine.V2WCoreScanner.findV2WScanExecutable()
        assertNotNull(bin, "Бинарник v2w-scan должен быть обнаружен в репозитории")
        assertTrue(bin.exists())
        assertTrue(bin.canExecute())
    }

    @Test
    fun testDesktopBinaryResolverMountPathDetection() {
        assertTrue(com.kiktor.v2whitelist.desktop.engine.DesktopBinaryResolver.isTemporaryMountPath("/tmp/.mount_v2whit12345/bin/xray"))
        assertTrue(com.kiktor.v2whitelist.desktop.engine.DesktopBinaryResolver.isTemporaryMountPath("C:\\tmp\\.mount_v2whit\\xray.exe"))
        assertFalse(com.kiktor.v2whitelist.desktop.engine.DesktopBinaryResolver.isTemporaryMountPath("/usr/local/bin/xray"))
        assertFalse(com.kiktor.v2whitelist.desktop.engine.DesktopBinaryResolver.isTemporaryMountPath("C:\\Program Files\\v2whitelist\\bin\\xray.exe"))
    }

    @Test
    fun testXrayAndAssetDiscovery() {
        val runner = com.kiktor.v2whitelist.desktop.engine.XrayDesktopRunner()
        val xrayBin = runner.findXrayExecutable()
        assertNotNull(xrayBin, "Xray бинарник должен быть обнаружен")
        assertTrue(xrayBin.exists())

        val assetDir = runner.findAssetLocation(xrayBin)
        assertNotNull(assetDir, "Каталог геофайлов должен быть обнаружен")
        assertTrue(assetDir.exists())
    }

    @Test
    fun testDesktopSmartConnectManagerVipAndFlow() = runTest {
        val serverRepo = InMemoryServerRepository()
        val settingsRepo = InMemorySettingsRepository()
        val runner = XrayDesktopRunner()

        val sample1 = ProfileItem(
            configType = EConfigType.VLESS,
            remarks = "Node-1",
            server = "127.0.0.1",
            serverPort = "443"
        )
        val sample2 = ProfileItem(
            configType = EConfigType.VLESS,
            remarks = "Node-2",
            server = "127.0.0.1",
            serverPort = "443"
        )
        serverRepo.saveServerConfig("id-1", sample1)
        serverRepo.saveServerConfig("id-2", sample2)
        serverRepo.addVipServer("id-1")

        val manager = com.kiktor.v2whitelist.desktop.engine.DesktopSmartConnectManager(
            serverRepo = serverRepo,
            settingsRepo = settingsRepo,
            xrayRunner = runner,
            scope = this
        )

        var lastStage: com.kiktor.v2whitelist.desktop.engine.SmartConnectStage? = null
        manager.onStageChanged = { stage, _ ->
            lastStage = stage
        }

        manager.startSmartConnect(minSpeedMbps = 0.0, speedCheckEnabled = false)
        assertNotNull(lastStage)
    }

    @Test
    fun testSubscriptionSettingsAndScheduler() = runTest {
        val serverRepo = InMemoryServerRepository()
        val settingsRepo = InMemorySettingsRepository()
        val runner = XrayDesktopRunner()

        val state = DesktopAppState(
            serverRepo = serverRepo,
            settingsRepo = settingsRepo,
            xrayRunner = runner,
            autoCheckNetworkOnStart = false
        )

        state.saveSettings(
            subUrl = "https://sub.example.com",
            xrayPath = "",
            v2wPath = "",
            httpP = 10809,
            socksP = 10808,
            subAutoUpdate = true,
            subIntervalMins = 30,
            subUpdateOnStart = false,
            subOnlyViaProxy = true,
            subThreads = 5
        )

        assertEquals(true, state.subscriptionAutoUpdate.value)
        assertEquals(30, state.subscriptionUpdateIntervalMinutes.value)
        assertEquals(false, state.subscriptionUpdateOnStartup.value)
        assertEquals(true, state.subscriptionUpdateOnlyViaProxy.value)
        assertEquals(5, state.subscriptionThreads.value)

        assertEquals(true, settingsRepo.getBoolean("subscription_auto_update"))
        assertEquals(30, settingsRepo.getInt("subscription_update_interval_minutes"))
        assertEquals(false, settingsRepo.getBoolean("subscription_update_on_startup"))
        assertEquals(true, settingsRepo.getBoolean("subscription_update_only_via_proxy"))
        assertEquals(5, settingsRepo.getInt("subscription_threads"))
    }
}

