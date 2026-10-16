package com.kiktor.v2whitelist.desktop

import com.kiktor.v2whitelist.desktop.engine.DesktopTunManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopTunManagerTest {

    @Test
    fun testTun2SocksExecutableDiscovery() {
        // tun2socks был скопирован в bin/ и также присутствует в системе
        val exec = DesktopTunManager.findTun2SocksExecutable()
        assertNotNull(exec, "tun2socks executable should be found in bin/ or system PATH")
        assertTrue(exec.exists())
        assertTrue(exec.canExecute())
    }

    @Test
    fun testCustomPathResolution() {
        val nonExistent = DesktopTunManager.findTun2SocksExecutable("/non/existent/path/to/tun2socks")
        // Должен вернуть null для несуществующего пути
        assertTrue(nonExistent == null)
    }

    @Test
    fun testInitialTunState() {
        assertFalse(DesktopTunManager.isTunActive)
    }
}
