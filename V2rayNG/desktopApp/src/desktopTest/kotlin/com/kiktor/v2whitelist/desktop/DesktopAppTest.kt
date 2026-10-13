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
            scope = this
        )

        assertEquals(DesktopTab.SERVERS, state.activeTab.value)
        assertEquals(1, state.servers.value.size)
        assertEquals("node-1", state.selectedGuid.value)

        state.setTab(DesktopTab.LOGS)
        assertEquals(DesktopTab.LOGS, state.activeTab.value)

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
            scope = this
        )

        state.saveSettings(
            subUrl = "https://sub.example.com/v2ray",
            xrayPath = "/usr/local/bin/xray",
            httpP = 8080,
            socksP = 1080
        )

        assertEquals("https://sub.example.com/v2ray", state.subscriptionUrl.value)
        assertEquals("/usr/local/bin/xray", state.xrayBinaryPath.value)
        assertEquals(8080, state.httpPort.value)
        assertEquals(1080, state.socksPort.value)

        assertEquals("https://sub.example.com/v2ray", settingsRepo.getString("subscription_url"))
        assertEquals("/usr/local/bin/xray", settingsRepo.getString("xray_path"))
        assertEquals(8080, settingsRepo.getHttpPort())
        assertEquals(1080, settingsRepo.getSocksPort())
    }
}
