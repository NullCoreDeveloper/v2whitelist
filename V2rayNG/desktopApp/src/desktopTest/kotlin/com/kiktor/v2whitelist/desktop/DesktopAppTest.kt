package com.kiktor.v2whitelist.desktop

import com.kiktor.v2whitelist.core.domain.network.NetworkStatusChecker
import com.kiktor.v2whitelist.core.model.AppScenario
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.NetworkBindingRule
import com.kiktor.v2whitelist.core.model.PersonalServersConfig
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
    fun testStorageManagerBaseDir() {
        val baseDir = com.kiktor.v2whitelist.desktop.data.DesktopStorageManager.baseDir
        assertNotNull(baseDir)
        assertTrue(baseDir.exists())
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
        manager.stop()
    }

    @Test
    fun testDesktopSmartConnectManagerPersonalServersScenarioFlow() = runTest {
        val serverRepo = InMemoryServerRepository()
        val settingsRepo = InMemorySettingsRepository()
        val runner = XrayDesktopRunner()

        val personalNode = ProfileItem(
            configType = EConfigType.VLESS,
            remarks = "My-Personal-VPS",
            server = "10.0.0.1",
            serverPort = "443",
            subscriptionId = PersonalServersConfig.SUB_ID_PERSONAL_DIRECT
        )
        val publicNode = ProfileItem(
            configType = EConfigType.VLESS,
            remarks = "Public-Whitelist-Node",
            server = "10.0.0.2",
            serverPort = "443",
            subscriptionId = "public_sub_1"
        )
        serverRepo.saveServerConfig("pers-1", personalNode)
        serverRepo.saveServerConfig("pub-1", publicNode)

        settingsRepo.setBoolean("pref_personal_servers_enabled", true)
        settingsRepo.setString("pref_personal_blacklist_rule", NetworkBindingRule.USE_PERSONAL_AUTO.name)
        settingsRepo.setString("pref_personal_whitelist_rule", NetworkBindingRule.USE_PUBLIC_SUBS.name)
        settingsRepo.setBoolean("pref_personal_fallback_public", true)

        var simulatedNetStatus = NetworkStatusChecker.STATUS_FULL_INTERNET

        val manager = com.kiktor.v2whitelist.desktop.engine.DesktopSmartConnectManager(
            serverRepo = serverRepo,
            settingsRepo = settingsRepo,
            xrayRunner = runner,
            networkStatusProvider = { simulatedNetStatus },
            scope = this
        )

        // В сценарии VPN_BLACKLIST (ЧС) эффективный сценарий — VPN_BLACKLIST
        assertEquals(AppScenario.VPN_BLACKLIST, manager.resolveEffectiveScenario(AppScenario.VPN_BLACKLIST))

        // В сценарии WHITELIST (БС) эффективный сценарий — WHITELIST
        assertEquals(AppScenario.WHITELIST, manager.resolveEffectiveScenario(AppScenario.WHITELIST))

        // В сценарии AUTO при нормальном интернете -> VPN_BLACKLIST
        simulatedNetStatus = NetworkStatusChecker.STATUS_FULL_INTERNET
        assertEquals(AppScenario.VPN_BLACKLIST, manager.resolveEffectiveScenario(AppScenario.AUTO))
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

    @Test
    fun testDefaultSubscriptionsAllEnabledInAutoMode() = runTest {
        val serverRepo = InMemoryServerRepository()
        val settingsRepo = InMemorySettingsRepository()
        val runner = XrayDesktopRunner()

        val state = DesktopAppState(
            serverRepo = serverRepo,
            settingsRepo = settingsRepo,
            xrayRunner = runner,
            autoCheckNetworkOnStart = false
        )

        val subs = state.subscriptions.value
        assertTrue(subs.isNotEmpty(), "Subscriptions must not be empty")
        assertTrue(subs.size >= 18, "Should have all prepopulated subscriptions")
        assertTrue(subs.all { it.enabled }, "All default subscriptions must be enabled in Auto mode like on Android")
    }

    @Test
    fun testSaveSettingsPersistsAutoCheckUpdatesServiceCheckAndIncrementsRevision() = runTest {
        val serverRepo = InMemoryServerRepository()
        val settingsRepo = InMemorySettingsRepository()
        val runner = XrayDesktopRunner()

        val state = DesktopAppState(
            serverRepo = serverRepo,
            settingsRepo = settingsRepo,
            xrayRunner = runner,
            autoCheckNetworkOnStart = false
        )

        val initialRev = state.settingsRevision.value
        state.saveSettings(
            subUrl = "",
            xrayPath = "",
            v2wPath = "",
            httpP = 10809,
            socksP = 10808,
            serviceCheck = false,
            autoCheckUpdatesVal = false
        )

        assertEquals(false, state.serviceCheckEnabled.value)
        assertEquals(false, settingsRepo.getBoolean("pref_service_check_enabled", true))
        assertEquals(false, state.autoCheckUpdates.value)
        assertEquals(false, settingsRepo.getBoolean("pref_auto_check_updates", true))
        assertEquals(initialRev + 1, state.settingsRevision.value)

        state.saveSettings(
            subUrl = "",
            xrayPath = "",
            v2wPath = "",
            httpP = 10809,
            socksP = 10808,
            serviceCheck = true,
            autoCheckUpdatesVal = true
        )

        assertEquals(true, state.serviceCheckEnabled.value)
        assertEquals(true, settingsRepo.getBoolean("pref_service_check_enabled", false))
        assertEquals(true, state.autoCheckUpdates.value)
        assertEquals(true, settingsRepo.getBoolean("pref_auto_check_updates", false))
        assertEquals(initialRev + 2, state.settingsRevision.value)
    }

    @Test
    fun testVerifyConcurrencyRoutingModePersistenceAndSpeedMath() = runTest {
        val serverRepo = InMemoryServerRepository()
        val settingsRepo = InMemorySettingsRepository()
        settingsRepo.setString("pref_routing_mode", "TUN")
        val state = DesktopAppState(
            serverRepo = serverRepo,
            settingsRepo = settingsRepo,
            xrayRunner = XrayDesktopRunner(),
            autoCheckNetworkOnStart = false
        )

        assertEquals("TUN", state.routingMode.value)
        state.saveSettings(
            subUrl = "",
            xrayPath = "",
            v2wPath = "",
            httpP = 10809,
            socksP = 10808,
            verifyConcurrencyVal = 6
        )
        assertEquals(6, state.verifyConcurrency.value)
        assertEquals(6, settingsRepo.getInt("pref_verify_concurrency", 4))

        // Speed math: 1_000_000 bytes over 2.0 seconds = 4.0 Mbps; too short (< 80ms) returns null
        val speed = com.kiktor.v2whitelist.desktop.engine.DesktopProfileVerifier.computeSustainedMbps(1_000_000L, 2_000_000_000L)
        assertNotNull(speed)
        assertTrue(kotlin.math.abs(speed - 4.0) < 0.01)
        assertNull(com.kiktor.v2whitelist.desktop.engine.DesktopProfileVerifier.computeSustainedMbps(100_000L, 50_000_000L))
    }

    @Test
    fun testTcpPrecheckAndFullVerifyModeDefaultsAndSave() = runTest {
        val serverRepo = InMemoryServerRepository()
        val settingsRepo = InMemorySettingsRepository()
        val state = DesktopAppState(
            serverRepo = serverRepo,
            settingsRepo = settingsRepo,
            xrayRunner = XrayDesktopRunner(),
            autoCheckNetworkOnStart = false
        )

        assertEquals(50, state.tcpPrecheckConcurrency.value)
        assertEquals(35, state.v2wCoreSockets.value)
        assertEquals(false, state.v2wFullVerifyMode.value)

        state.saveSettings(
            subUrl = "",
            xrayPath = "",
            v2wPath = "",
            httpP = 10809,
            socksP = 10808,
            tcpPrecheck = 75,
            v2wSockets = 40,
            v2wFullVerify = true
        )

        assertEquals(75, state.tcpPrecheckConcurrency.value)
        assertEquals(40, state.v2wCoreSockets.value)
        assertEquals(true, state.v2wFullVerifyMode.value)
        assertEquals(75, settingsRepo.getInt("pref_tcp_precheck_concurrency", 50))
        assertEquals(40, settingsRepo.getInt("pref_v2w_core_sockets", 35))
        assertEquals(true, settingsRepo.getBoolean("pref_v2w_full_verify_mode", false))
    }

    @Test
    fun testV2WCoreOptionsHeaderAndSpeedOnlyVerifierSkip() = runTest {
        val header = com.kiktor.v2whitelist.desktop.engine.V2WCoreScanner.buildOptionsHeaderLine(
            tcpConcurrency = 50,
            verifyConcurrency = 35,
            timeoutSec = 6,
            endpointUrl = "vless://uuid@1.2.3.4:443",
            endpointE2e = true,
            serviceCheck = true,
            services = listOf(
                com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckItem(
                    id = "custom_1",
                    name = "Test \"Site\"",
                    url = "https://example.org/ok",
                    enabled = true,
                    isCustom = true
                )
            )
        )
        assertTrue(header.startsWith("#v2w-opts:"))
        assertTrue(header.contains("\"tcp_concurrency\":50"))
        assertTrue(header.contains("\"verify_concurrency\":35"))
        assertTrue(header.contains("\"endpoint_e2e\":true"))
        assertTrue(header.contains("Test \\\"Site\\\""))

        val profile = ProfileItem(
            configType = EConfigType.VLESS,
            server = "127.0.0.1",
            serverPort = "443",
            remarks = "Sample"
        )
        val res = com.kiktor.v2whitelist.desktop.engine.DesktopProfileVerifier.verifyProfile(
            profile = profile,
            xrayBinaryPath = "/nonexistent/path/to/xray",
            speedCheckEnabled = false,
            speedOnlyMode = true,
            preMeasuredLatencyMs = 42L
        )
        assertTrue(res.success)
        assertEquals(42L, res.latencyMs)
    }

    @Test
    fun testDesktopFileSettingsRepositoryInitializationDoesNotThrowNpe() {
        val repo = com.kiktor.v2whitelist.desktop.data.DesktopFileSettingsRepository()
        assertTrue(repo.getSocksPort() > 0)
        assertTrue(repo.getHttpPort() > 0)
    }

    @Test
    fun testResolveGenerate204DestUrlFiltersOutGeoIpEndpoints() {
        assertNull(com.kiktor.v2whitelist.desktop.engine.V2WCoreScanner.resolveGenerate204DestUrl(null))
        assertNull(com.kiktor.v2whitelist.desktop.engine.V2WCoreScanner.resolveGenerate204DestUrl(""))
        assertNull(com.kiktor.v2whitelist.desktop.engine.V2WCoreScanner.resolveGenerate204DestUrl("https://api.ip.sb/geoip"))
        assertNull(com.kiktor.v2whitelist.desktop.engine.V2WCoreScanner.resolveGenerate204DestUrl("ftp://cp.cloudflare.com/generate_204"))
        assertEquals(
            "https://cp.cloudflare.com/generate_204",
            com.kiktor.v2whitelist.desktop.engine.V2WCoreScanner.resolveGenerate204DestUrl("  https://cp.cloudflare.com/generate_204  ")
        )
        assertEquals(
            "http://www.gstatic.com/generate_204",
            com.kiktor.v2whitelist.desktop.engine.V2WCoreScanner.resolveGenerate204DestUrl("http://www.gstatic.com/generate_204")
        )
    }

    @Test
    fun testResetAllSettingsAndDataResetsAllStateFlowsToDefaults() = runTest {
        val serverRepo = InMemoryServerRepository()
        val settingsRepo = InMemorySettingsRepository()
        val state = DesktopAppState(
            serverRepo = serverRepo,
            settingsRepo = settingsRepo,
            xrayRunner = XrayDesktopRunner(),
            autoCheckNetworkOnStart = false
        )

        state.saveSettings(
            subUrl = "https://example.com/sub",
            xrayPath = "",
            v2wPath = "",
            httpP = 11809,
            socksP = 11808,
            customEndpoint = true,
            customEndpointConfig = "vless://uuid@1.2.3.4:443",
            customEndpointSub = "https://example.com/ep",
            checkUrl = "https://custom.example/geoip",
            v2wCore = false,
            v2wFullVerify = true
        )
        state.personalServersEnabled.value = true
        state.personalSelectedGuid.value = "guid-123"
        state.setGlobalSni("sni.example.com")
        state.setCloudflareIp("1.0.0.1")

        state.resetAllSettingsAndData()

        assertEquals("https://api.ip.sb/geoip", state.connectionCheckServiceUrl.value)
        assertEquals(false, state.useCustomEndpoint.value)
        assertEquals("", state.customEndpointUrl.value)
        assertEquals("", state.customEndpointSubUrl.value)
        assertEquals(true, state.useV2WCore.value)
        assertEquals(false, state.v2wFullVerifyMode.value)
        assertEquals(false, state.personalServersEnabled.value)
        assertEquals("", state.personalSelectedGuid.value)
        assertEquals("", state.globalSni.value)
        assertEquals("", state.cloudflareIp.value)
        assertEquals(com.kiktor.v2whitelist.desktop.engine.TunEnginePreference.AUTO, state.tunEnginePreference.value)
        assertEquals(false, state.appSplitTunnelingEnabled.value)
        assertTrue(state.appSplitDirectApps.value.isEmpty())
        assertTrue(state.appSplitProxyApps.value.isEmpty())
    }

    @Test
    fun testAppSplitTunnelingAndTunEnginePersistence() = runTest {
        val serverRepo = InMemoryServerRepository()
        val settingsRepo = InMemorySettingsRepository()
        val state = DesktopAppState(
            serverRepo = serverRepo,
            settingsRepo = settingsRepo,
            xrayRunner = XrayDesktopRunner(),
            autoCheckNetworkOnStart = false
        )

        assertEquals(com.kiktor.v2whitelist.desktop.engine.TunEnginePreference.AUTO, state.tunEnginePreference.value)
        assertEquals(false, state.appSplitTunnelingEnabled.value)

        state.setTunEnginePreference(com.kiktor.v2whitelist.desktop.engine.TunEnginePreference.V2W_TUN)
        state.setAppSplitEnabled(true)
        state.setAppSplitMode(com.kiktor.v2whitelist.desktop.engine.AppSplitMode.ONLY_PROXY)
        state.setAppSplitRoute("steam.exe", "DIRECT")
        state.setAppSplitRoute("firefox", "PROXY")
        // Перемещение приложения из PROXY в DIRECT должно удалять его из PROXY
        state.setAppSplitRoute("firefox", "DIRECT")

        assertEquals(com.kiktor.v2whitelist.desktop.engine.TunEnginePreference.V2W_TUN, state.tunEnginePreference.value)
        assertEquals(true, state.appSplitTunnelingEnabled.value)
        assertEquals(com.kiktor.v2whitelist.desktop.engine.AppSplitMode.ONLY_PROXY, state.appSplitTunnelingMode.value)
        assertEquals(listOf("steam.exe", "firefox"), state.appSplitDirectApps.value)
        assertTrue(state.appSplitProxyApps.value.isEmpty())
        assertEquals("V2W_TUN", settingsRepo.getString("pref_tun_engine"))
        assertEquals(true, settingsRepo.getBoolean("pref_app_split_enabled"))
    }

    @Test
    fun testRoutingModeDefaultIsTunAndPersistsAcrossRestarts() = runTest {
        val serverRepo = InMemoryServerRepository()
        val settingsRepo = InMemorySettingsRepository()

        // 1. По умолчанию на всех платформах режим TUN
        val initialState = DesktopAppState(
            serverRepo = serverRepo,
            settingsRepo = settingsRepo,
            xrayRunner = XrayDesktopRunner(),
            autoCheckNetworkOnStart = false
        )
        assertEquals("TUN", initialState.routingMode.value)

        // 2. Переключение на PROXY сохраняется и восстанавливается при перезапуске
        initialState.setRoutingMode("PROXY")
        assertEquals("PROXY", initialState.routingMode.value)
        assertEquals("PROXY", settingsRepo.getString("pref_routing_mode"))

        val restartedState = DesktopAppState(
            serverRepo = serverRepo,
            settingsRepo = settingsRepo,
            xrayRunner = XrayDesktopRunner(),
            autoCheckNetworkOnStart = false
        )
        assertEquals("PROXY", restartedState.routingMode.value)

        // 3. Сброс настроек возвращает режим по умолчанию (TUN)
        restartedState.resetAllSettingsAndData()
        assertEquals("TUN", restartedState.routingMode.value)
    }
}

