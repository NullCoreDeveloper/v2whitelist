package com.kiktor.v2whitelist.desktop.engine

import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

data class NetworkConflictWarning(
    val title: String,
    val details: String,
    val items: List<String>
)

/**
 * Обнаружение конфликтующего ПО (Zapret / GoodbyeDPI) и сторонних TUN/VPN-клиентов
 * на Desktop, способных вызывать SSL-ошибки или перехват маршрутов.
 */
object DesktopConflictDetector {

    private val WINDOWS_CONFLICT_PROCESSES = listOf(
        "winws.exe" to "Zapret (winws.exe)",
        "goodbyedpi.exe" to "GoodbyeDPI (goodbyedpi.exe)",
        "zapret.exe" to "Zapret (zapret.exe)",
        "wireguard.exe" to "WireGuard",
        "amneziawg.exe" to "AmneziaWG",
        "openvpn.exe" to "OpenVPN",
        "sing-box.exe" to "sing-box",
        "nekobox_core.exe" to "NekoBox"
    )

    private val LINUX_CONFLICT_PROCESSES = listOf(
        "nfqws" to "Zapret (nfqws)",
        "tpws" to "Zapret (tpws)",
        "openvpn" to "OpenVPN",
        "sing-box" to "sing-box"
    )

    private val FOREIGN_VPN_IFACE_HINTS = listOf(
        "wireguard",
        "wg",
        "amnezia",
        "openvpn",
        "tap",
        "sing-box",
        "nekobox",
        "tun"
    )

    private val OWN_VPN_IFACES = setOf("wintun", "v2w-tun", "tun0-v2w")

    fun detectConflicts(): NetworkConflictWarning? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val found = LinkedHashSet<String>()

        if (os.contains("win")) {
            val csv = runQuietCommand(listOf("tasklist", "/FO", "CSV", "/NH"))
            if (csv != null) {
                found.addAll(parseWindowsTasklistConflicts(csv))
            }
        } else if (os.contains("linux") || os.contains("nix") || os.contains("nux")) {
            val ps = runQuietCommand(listOf("ps", "-eo", "comm"))
            if (ps != null) {
                found.addAll(parseLinuxProcessConflicts(ps))
            }
        }

        val upIfaces = runCatching {
            val names = mutableListOf<String>()
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val nif = en.nextElement()
                if (nif.isUp && !nif.isLoopback) {
                    names.add("${nif.name} ${nif.displayName}".trim())
                }
            }
            names
        }.getOrDefault(emptyList())

        found.addAll(detectForeignVpnInterfaces(upIfaces))

        if (found.isEmpty()) return null
        val list = found.toList()
        return NetworkConflictWarning(
            title = "Обнаружены возможные конфликты: ${list.joinToString(", ")}",
            details = "Одновременная работа с Zapret / GoodbyeDPI или другим активным TUN/VPN может вызывать SSL-ошибки (SSL_ERROR_DECODE_ERROR_ALERT) и сбои маршрутизации.",
            items = list
        )
    }

    fun parseWindowsTasklistConflicts(csvOutput: String): List<String> {
        val hits = LinkedHashSet<String>()
        for (rawLine in csvOutput.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val exe = line.removePrefix("\"").substringBefore("\"").trim().lowercase()
            val match = WINDOWS_CONFLICT_PROCESSES.firstOrNull { exe == it.first }
            if (match != null) {
                hits.add(match.second)
            }
        }
        return hits.toList()
    }

    fun parseLinuxProcessConflicts(psOutput: String): List<String> {
        val hits = LinkedHashSet<String>()
        for (rawLine in psOutput.lineSequence()) {
            val proc = rawLine.trim().lowercase()
            if (proc.isEmpty()) continue
            val match = LINUX_CONFLICT_PROCESSES.firstOrNull { proc == it.first || proc.endsWith("/${it.first}") }
            if (match != null) {
                hits.add(match.second)
            }
        }
        return hits.toList()
    }

    fun detectForeignVpnInterfaces(interfaceNames: List<String>): List<String> {
        val hits = LinkedHashSet<String>()
        for (rawName in interfaceNames) {
            val lower = rawName.trim().lowercase()
            if (lower.isEmpty()) continue
            if (OWN_VPN_IFACES.any { lower == it || lower.startsWith("$it ") || lower.contains("wintun") }) {
                continue
            }
            val matched = FOREIGN_VPN_IFACE_HINTS.any { hint ->
                if (hint == "wg" || hint == "tap" || hint == "tun") {
                    Regex("(^|[^a-z0-9])$hint[0-9]*($|[^a-z0-9])").containsMatchIn(lower)
                } else {
                    lower.contains(hint)
                }
            }
            if (matched) {
                hits.add("Интерфейс ${rawName.trim()}")
            }
        }
        return hits.toList()
    }

    private fun runQuietCommand(cmd: List<String>): String? {
        return runCatching {
            val proc = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(2, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return null
            }
            proc.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
    }
}
