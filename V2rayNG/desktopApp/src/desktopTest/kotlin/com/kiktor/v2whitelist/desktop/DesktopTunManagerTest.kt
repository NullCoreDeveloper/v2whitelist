package com.kiktor.v2whitelist.desktop

import com.kiktor.v2whitelist.desktop.engine.DesktopTunManager
import com.kiktor.v2whitelist.desktop.engine.TunEnginePreference
import com.kiktor.v2whitelist.desktop.util.DesktopInstalledAppsProvider
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertFalse(DesktopTunManager.isServiceMode)
    }

    @Test
    fun testParseNetshDnsServersExtractsPhysicalPublicDns() {
        val sample = """
            Configuration for interface "Ethernet"
                DNS servers configured through DHCP:  46.61.250.138
                                                      46.61.250.139
            
            Настройка интерфейса "Wi-Fi"
                Статически настроенные DNS-серверы:   192.168.1.1
                                                      77.88.8.8
            
            Configuration for interface "wintun"
                Statically Configured DNS Servers:    1.1.1.1
                                                      8.8.8.8
            
            Configuration for interface "Loopback Pseudo-Interface 1"
                Statically Configured DNS Servers:    127.0.0.1
        """.trimIndent()

        val parsed = DesktopTunManager.parseNetshDnsServers(
            output = sample,
            tunDns = setOf("1.1.1.1", "8.8.8.8"),
            excludeIps = setOf("192.168.1.1")
        )
        assertEquals(listOf("46.61.250.138", "46.61.250.139", "77.88.8.8"), parsed)
    }

    @Test
    fun testResolveEffectiveTunEngineAutoAndManual() {
        assertEquals(
            TunEnginePreference.TUN2SOCKS,
            DesktopTunManager.resolveEffectiveTunEngine(TunEnginePreference.AUTO, appSplitEnabled = false)
        )
        assertEquals(
            TunEnginePreference.V2W_TUN,
            DesktopTunManager.resolveEffectiveTunEngine(TunEnginePreference.AUTO, appSplitEnabled = true)
        )
        assertEquals(
            TunEnginePreference.V2W_TUN,
            DesktopTunManager.resolveEffectiveTunEngine(TunEnginePreference.V2W_TUN, appSplitEnabled = false)
        )
        assertEquals(
            TunEnginePreference.TUN2SOCKS,
            DesktopTunManager.resolveEffectiveTunEngine(TunEnginePreference.TUN2SOCKS, appSplitEnabled = true)
        )
    }

    @Test
    fun testExtractExecutableFromDesktopExec() {
        assertEquals(
            "/usr/bin/firefox",
            DesktopInstalledAppsProvider.extractExecutableFromDesktopExec("\"/usr/bin/firefox\" %u")
        )
        assertEquals(
            "steam",
            DesktopInstalledAppsProvider.extractExecutableFromDesktopExec("env STEAM_FORCE_DESKTOPUI_SCALING=1 steam %U")
        )
    }

    @Test
    fun testParseWindowsDefaultGatewaySupportsOnLinkPppAndIgnoresTun() {
        val yotaPppOutput = """
            ===========================================================================
            Active Routes:
            Network Destination        Netmask          Gateway       Interface  Metric
                      0.0.0.0          0.0.0.0          On-link     10.97.142.101     26
                      0.0.0.0          0.0.0.0       10.10.14.2       10.10.14.1      6
            ===========================================================================
            Persistent Routes:
                      0.0.0.0          0.0.0.0       10.10.14.2  1
        """.trimIndent()

        val pppGw = DesktopTunManager.parseWindowsDefaultGateway(yotaPppOutput, activeTunGw = "10.10.14.2")
        assertNotNull(pppGw)
        assertEquals("0.0.0.0", pppGw.gateway)
        assertEquals("10.97.142.101", pppGw.physicalDev)
        assertTrue(pppGw.isOnLink)

        val standardEthOutput = """
            Active Routes:
                      0.0.0.0          0.0.0.0   192.168.51.100    192.168.51.50     25
                      0.0.0.0          0.0.0.0       198.18.0.1       198.18.0.2      1
            Persistent Routes:
                      0.0.0.0          0.0.0.0       10.10.14.2  1
        """.trimIndent()

        val ethGw = DesktopTunManager.parseWindowsDefaultGateway(standardEthOutput)
        assertNotNull(ethGw)
        assertEquals("192.168.51.100", ethGw.gateway)
        assertEquals("192.168.51.50", ethGw.physicalDev)
        assertFalse(ethGw.isOnLink)
    }
}
