package com.kiktor.v2whitelist.desktop

import com.kiktor.v2whitelist.desktop.engine.DesktopConflictDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopConflictDetectorTest {

    @Test
    fun testParseWindowsTasklistConflicts() {
        val csv = """
            "explorer.exe","1234","Console","1","45,000 K"
            "winws.exe","4512","Console","1","8,120 K"
            "wireguard.exe","9012","Services","0","12,300 K"
            "xray.exe","7777","Console","1","22,000 K"
        """.trimIndent()

        val found = DesktopConflictDetector.parseWindowsTasklistConflicts(csv)
        assertEquals(listOf("Zapret (winws.exe)", "WireGuard"), found)
    }

    @Test
    fun testParseLinuxProcessConflicts() {
        val ps = """
            systemd
            nfqws
            xray
            openvpn
        """.trimIndent()

        val found = DesktopConflictDetector.parseLinuxProcessConflicts(ps)
        assertEquals(listOf("Zapret (nfqws)", "OpenVPN"), found)
    }

    @Test
    fun testDetectForeignVpnInterfacesIgnoresOwnWintun() {
        val ifaces = listOf(
            "wintun Wintun Userspace Tunnel",
            "wg0 WireGuard Tunnel",
            "Ethernet Realtek PCIe",
            "AmneziaVPN AmneziaWG Tunnel"
        )
        val found = DesktopConflictDetector.detectForeignVpnInterfaces(ifaces)
        assertEquals(2, found.size)
        assertTrue(found.any { it.contains("wg0") })
        assertTrue(found.any { it.contains("AmneziaVPN") })
    }
}
