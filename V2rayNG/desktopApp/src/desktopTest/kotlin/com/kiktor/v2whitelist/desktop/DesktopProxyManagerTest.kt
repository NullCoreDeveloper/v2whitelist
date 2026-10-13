package com.kiktor.v2whitelist.desktop

import com.kiktor.v2whitelist.core.repository.InMemoryServerRepository
import com.kiktor.v2whitelist.core.repository.InMemorySettingsRepository
import com.kiktor.v2whitelist.desktop.engine.DesktopProxyManager
import com.kiktor.v2whitelist.desktop.engine.XrayDesktopRunner
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopProxyManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun testTelegramLinkGeneration() {
        val link = DesktopProxyManager.getTelegramLink(10808)
        assertEquals("tg://socks?server=127.0.0.1&port=10808", link)
    }

    @Test
    fun testSocks5AndHttpUrls() {
        val socks = DesktopProxyManager.getSocks5Url(10808)
        val http = DesktopProxyManager.getHttpUrl(10809)
        assertEquals("socks5://127.0.0.1:10808", socks)
        assertEquals("http://127.0.0.1:10809", http)
    }

    @Test
    fun testTerminalExportStrings() {
        val bashCmd = DesktopProxyManager.getTerminalExport(socksPort = 10808, httpPort = 10809)
        assertTrue(bashCmd.contains("http_proxy=http://127.0.0.1:10809"))
        assertTrue(bashCmd.contains("all_proxy=socks5://127.0.0.1:10808"))

        val psCmd = DesktopProxyManager.getPowerShellExport(socksPort = 10808, httpPort = 10809)
        assertTrue(psCmd.contains("\$env:http_proxy=\"http://127.0.0.1:10809\""))
        assertTrue(psCmd.contains("\$env:all_proxy=\"socks5://127.0.0.1:10808\""))
    }

    @Test
    fun testBrowserAndGitCommands() {
        val browserFlag = DesktopProxyManager.getBrowserFlag(10808)
        assertEquals("--proxy-server=\"socks5://127.0.0.1:10808\"", browserFlag)

        val gitCmd = DesktopProxyManager.getGitConfig(10808)
        assertEquals("git config --global http.proxy socks5://127.0.0.1:10808", gitCmd)

        val gitUnset = DesktopProxyManager.getGitUnset()
        assertEquals("git config --global --unset http.proxy", gitUnset)
    }

    @Test
    fun testAppStateProxyModeSettings() = runTest {
        val serverRepo = InMemoryServerRepository()
        val settingsRepo = InMemorySettingsRepository()
        val runner = XrayDesktopRunner()

        val state = DesktopAppState(
            serverRepo = serverRepo,
            settingsRepo = settingsRepo,
            xrayRunner = runner,
            scope = testScope
        )

        assertFalse(state.autoEnableSystemProxy.value)
        assertFalse(state.allowLan.value)

        state.setAutoEnableSystemProxy(true)
        assertTrue(state.autoEnableSystemProxy.value)
        assertTrue(settingsRepo.getBoolean("auto_enable_system_proxy", false))

        state.setAllowLan(true)
        assertTrue(state.allowLan.value)
        assertTrue(settingsRepo.getBoolean("allow_lan", false))

        state.saveSettings(
            subUrl = "https://sub.example.com",
            xrayPath = "/usr/bin/xray",
            v2wPath = "/usr/bin/v2w-scan",
            httpP = 8080,
            socksP = 1080,
            autoSystemProxy = false,
            lan = false
        )

        assertEquals(8080, state.httpPort.value)
        assertEquals(1080, state.socksPort.value)
        assertFalse(state.autoEnableSystemProxy.value)
        assertFalse(state.allowLan.value)
    }
}
