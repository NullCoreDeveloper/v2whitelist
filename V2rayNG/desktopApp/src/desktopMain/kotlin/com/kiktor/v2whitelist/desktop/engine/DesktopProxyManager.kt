package com.kiktor.v2whitelist.desktop.engine

import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Управляет режимом прокси на десктопе:
 * 1. Интеграция с системным прокси ОС (Linux KDE Plasma 6/5, GNOME gsettings, Windows Registry, macOS networksetup).
 * 2. Генерация готовых конфигураций в 1 клик для Telegram, терминала, браузера и Git.
 * 3. Определение локального IP для раздачи прокси в локальной сети (Allow LAN).
 * 4. Безопасное авто-отключение системного прокси при выходе или остановке Xray.
 */
object DesktopProxyManager {

    private val osName = System.getProperty("os.name")?.lowercase().orEmpty()
    val isWindows = osName.contains("windows")
    val isMac = osName.contains("mac")
    val isLinux = osName.contains("linux")

    private val hasKWriteConfig6 by lazy {
        File("/usr/bin/kwriteconfig6").exists() || checkCommandExists("kwriteconfig6")
    }

    private val hasKWriteConfig5 by lazy {
        File("/usr/bin/kwriteconfig5").exists() || checkCommandExists("kwriteconfig5")
    }

    private val hasGSettings by lazy {
        File("/usr/bin/gsettings").exists() || checkCommandExists("gsettings")
    }

    private val hasQdbus6 by lazy {
        File("/usr/bin/qdbus6").exists() || checkCommandExists("qdbus6")
    }

    private fun checkCommandExists(cmd: String): Boolean {
        return try {
            val proc = ProcessBuilder("which", cmd).start()
            val finished = proc.waitFor(1, TimeUnit.SECONDS)
            finished && proc.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun runCommand(vararg args: String): Pair<Int, String> {
        return try {
            val proc = ProcessBuilder(*args).redirectErrorStream(true).start()
            val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            val finished = proc.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                -1 to "Таймаут выполнения команды"
            } else {
                proc.exitValue() to output.trim()
            }
        } catch (e: Exception) {
            -1 to (e.message ?: "Ошибка выполнения команды")
        }
    }

    /**
     * Включение системного прокси ОС.
     */
    fun enableSystemProxy(socksPort: Int = 10808, httpPort: Int = 10809): Boolean {
        GeekModeLogger.log("ProxyManager", "Включение системного прокси ОС (SOCKS: $socksPort, HTTP: $httpPort)...")
        var success = false

        try {
            when {
                isLinux -> {
                    // 1. KDE Plasma 6 / 5
                    if (hasKWriteConfig6 || hasKWriteConfig5) {
                        val kwrite = if (hasKWriteConfig6) "kwriteconfig6" else "kwriteconfig5"
                        runCommand(kwrite, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "ProxyType", "1")
                        runCommand(kwrite, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "httpProxy", "http://127.0.0.1 $httpPort")
                        runCommand(kwrite, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "httpsProxy", "http://127.0.0.1 $httpPort")
                        runCommand(kwrite, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "socksProxy", "socks://127.0.0.1 $socksPort")
                        if (hasQdbus6) {
                            runCommand("qdbus6", "org.kde.kded6", "/kded", "org.kde.kded6.reparseConfiguration", "kioslave")
                        }
                        GeekModeLogger.log("ProxyManager", "KDE Plasma: Системный прокси успешно сконфигурирован через $kwrite")
                        success = true
                    }

                    // 2. GNOME / Cinnamon / GTK apps via gsettings
                    if (hasGSettings) {
                        runCommand("gsettings", "set", "org.gnome.system.proxy", "mode", "manual")
                        runCommand("gsettings", "set", "org.gnome.system.proxy.http", "host", "127.0.0.1")
                        runCommand("gsettings", "set", "org.gnome.system.proxy.http", "port", httpPort.toString())
                        runCommand("gsettings", "set", "org.gnome.system.proxy.https", "host", "127.0.0.1")
                        runCommand("gsettings", "set", "org.gnome.system.proxy.https", "port", httpPort.toString())
                        runCommand("gsettings", "set", "org.gnome.system.proxy.socks", "host", "127.0.0.1")
                        runCommand("gsettings", "set", "org.gnome.system.proxy.socks", "port", socksPort.toString())
                        GeekModeLogger.log("ProxyManager", "GNOME / gsettings: Системный прокси успешно сконфигурирован")
                        success = true
                    }
                }

                isWindows -> {
                    // Windows Registry
                    runCommand("reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings", "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "1", "/f")
                    val proxyServer = "http=127.0.0.1:$httpPort;https=127.0.0.1:$httpPort;socks=127.0.0.1:$socksPort"
                    runCommand("reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings", "/v", "ProxyServer", "/t", "REG_SZ", "/d", proxyServer, "/f")
                    notifyWindowsSettingsChanged()
                    GeekModeLogger.log("ProxyManager", "Windows: Системный прокси включен в реестре Internet Settings")
                    success = true
                }

                isMac -> {
                    val services = getMacNetworkServices()
                    for (networkService in services) {
                        runCommand("networksetup", "-setwebproxy", networkService, "127.0.0.1", httpPort.toString())
                        runCommand("networksetup", "-setsecurewebproxy", networkService, "127.0.0.1", httpPort.toString())
                        runCommand("networksetup", "-setsocksfirewallproxy", networkService, "127.0.0.1", socksPort.toString())
                        runCommand("networksetup", "-setwebproxystate", networkService, "on")
                        runCommand("networksetup", "-setsecurewebproxystate", networkService, "on")
                        runCommand("networksetup", "-setsocksfirewallproxystate", networkService, "on")
                    }
                    GeekModeLogger.log("ProxyManager", "macOS: Системный прокси включен для служб: ${services.joinToString()}")
                    success = true
                }
            }
        } catch (e: Exception) {
            GeekModeLogger.log("ProxyManager", "Сбой включения системного прокси: ${e.message}")
        }

        return success
    }

    /**
     * Отключение системного прокси ОС.
     */
    fun disableSystemProxy(): Boolean {
        GeekModeLogger.log("ProxyManager", "Отключение системного прокси ОС...")
        var success = false

        try {
            when {
                isLinux -> {
                    if (hasKWriteConfig6 || hasKWriteConfig5) {
                        val kwrite = if (hasKWriteConfig6) "kwriteconfig6" else "kwriteconfig5"
                        runCommand(kwrite, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "ProxyType", "0")
                        if (hasQdbus6) {
                            runCommand("qdbus6", "org.kde.kded6", "/kded", "org.kde.kded6.reparseConfiguration", "kioslave")
                        }
                        GeekModeLogger.log("ProxyManager", "KDE Plasma: Системный прокси отключен (ProxyType 0)")
                        success = true
                    }

                    if (hasGSettings) {
                        runCommand("gsettings", "set", "org.gnome.system.proxy", "mode", "none")
                        GeekModeLogger.log("ProxyManager", "GNOME / gsettings: Системный прокси отключен (mode none)")
                        success = true
                    }
                }

                isWindows -> {
                    runCommand("reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings", "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "0", "/f")
                    notifyWindowsSettingsChanged()
                    GeekModeLogger.log("ProxyManager", "Windows: Системный прокси отключен в реестре (ProxyEnable 0)")
                    success = true
                }

                isMac -> {
                    val services = getMacNetworkServices()
                    for (networkService in services) {
                        runCommand("networksetup", "-setwebproxystate", networkService, "off")
                        runCommand("networksetup", "-setsecurewebproxystate", networkService, "off")
                        runCommand("networksetup", "-setsocksfirewallproxystate", networkService, "off")
                    }
                    GeekModeLogger.log("ProxyManager", "macOS: Системный прокси отключен для служб: ${services.joinToString()}")
                    success = true
                }
            }
        } catch (e: Exception) {
            GeekModeLogger.log("ProxyManager", "Сбой отключения системного прокси: ${e.message}")
        }

        return success
    }

    private fun notifyWindowsSettingsChanged() {
        try {
            val script = "\$w=Add-Type -MemberDefinition '[DllImport(\"wininet.dll\")] public static extern bool InternetSetOption(IntPtr h, int o, IntPtr b, int l);' -Name W -PassThru; \$w::InternetSetOption([IntPtr]::Zero, 39, [IntPtr]::Zero, 0); \$w::InternetSetOption([IntPtr]::Zero, 37, [IntPtr]::Zero, 0)"
            ProcessBuilder("powershell", "-WindowStyle", "Hidden", "-Command", script).start()
        } catch (_: Exception) {}
    }

    private fun getMacNetworkServices(): List<String> {
        val (code, out) = runCommand("networksetup", "-listallnetworkservices")
        if (code != 0 || out.isBlank()) return listOf("Wi-Fi")
        return out.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("An asterisk", ignoreCase = true) && !it.startsWith("*") }
            .ifEmpty { listOf("Wi-Fi") }
    }

    /**
     * Проверка, активен ли системный прокси в данный момент.
     */
    fun isSystemProxyEnabled(): Boolean {
        return try {
            when {
                isLinux -> {
                    if (hasKWriteConfig6) {
                        val (code, out) = runCommand("kreadconfig6", "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "ProxyType")
                        if (code == 0 && out == "1") return true
                    }
                    if (hasGSettings) {
                        val (code, out) = runCommand("gsettings", "get", "org.gnome.system.proxy", "mode")
                        if (code == 0 && out.contains("manual")) return true
                    }
                    false
                }
                isWindows -> {
                    val (code, out) = runCommand("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings", "/v", "ProxyEnable")
                    code == 0 && out.contains("0x1")
                }
                isMac -> {
                    val (code, out) = runCommand("networksetup", "-getwebproxy", "Wi-Fi")
                    code == 0 && out.contains("Enabled: Yes")
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Определение локального IP-адреса устройства в локальной сети (например 192.168.1.100).
     */
    fun getLocalIpAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces.toList()) {
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                for (addr in iface.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // --- Генераторы готовых строк экспорта ---

    fun getTelegramLink(socksPort: Int): String = "tg://socks?server=127.0.0.1&port=$socksPort"

    fun getSocks5Url(socksPort: Int): String = "socks5://127.0.0.1:$socksPort"

    fun getHttpUrl(httpPort: Int): String = "http://127.0.0.1:$httpPort"

    fun getTerminalExport(socksPort: Int, httpPort: Int): String {
        return "export http_proxy=http://127.0.0.1:$httpPort https_proxy=http://127.0.0.1:$httpPort all_proxy=socks5://127.0.0.1:$socksPort HTTP_PROXY=http://127.0.0.1:$httpPort HTTPS_PROXY=http://127.0.0.1:$httpPort ALL_PROXY=socks5://127.0.0.1:$socksPort"
    }

    fun getPowerShellExport(socksPort: Int, httpPort: Int): String {
        return "\$env:http_proxy=\"http://127.0.0.1:$httpPort\"; \$env:https_proxy=\"http://127.0.0.1:$httpPort\"; \$env:all_proxy=\"socks5://127.0.0.1:$socksPort\""
    }

    fun getBrowserFlag(socksPort: Int): String = "--proxy-server=\"socks5://127.0.0.1:$socksPort\""

    fun getGitConfig(socksPort: Int): String = "git config --global http.proxy socks5://127.0.0.1:$socksPort"

    fun getGitUnset(): String = "git config --global --unset http.proxy"

    /**
     * Копирование строки в буфер обмена.
     */
    fun copyToClipboard(text: String): Boolean {
        return try {
            val selection = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
            true
        } catch (e: Exception) {
            GeekModeLogger.log("ProxyManager", "Сбой копирования в буфер: ${e.message}")
            false
        }
    }

    /**
     * Открытие URI (например ссылки tg://) во внешнем приложении.
     */
    fun openUri(uriString: String): Boolean {
        return try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(uriString))
                true
            } else if (isWindows) {
                ProcessBuilder("cmd", "/c", "start", "", uriString).start()
                true
            } else if (isMac) {
                ProcessBuilder("open", uriString).start()
                true
            } else if (isLinux) {
                runCommand("xdg-open", uriString)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            GeekModeLogger.log("ProxyManager", "Сбой открытия URI $uriString: ${e.message}")
            false
        }
    }
}
