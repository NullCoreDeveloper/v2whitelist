package com.kiktor.v2whitelist.desktop.engine

import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

data class ConfiguredServerRoute(
    val ip: String,
    val gateway: String,
    val dev: String
)

/**
 * Управляет виртуальным сетевым адаптером TUN (v2w-tun) на Desktop через tun2socks.
 *
 * Архитектурные принципы:
 * 1. Автономность: не требует внешних демонов. Ищет tun2socks в каталоге bin/ приложения,
 *    затем в системном PATH.
 * 2. Безопасность маршрутизации: перенаправляет трафик через два префикса 0.0.0.0/1 и 128.0.0.0/1,
 *    не затирая системный default route, и добавляет прямой маршрут до удалённого сервера
 *    через физический шлюз во избежание петли маршрутизации.
 * 3. Отказоустойчивость (Graceful fallback): если tun2socks не найден или не получены права root,
 *    возвращает false для мягкого переключения на системный прокси.
 */
object DesktopTunManager {

    private val osName = System.getProperty("os.name")?.lowercase().orEmpty()
    val isWindows = osName.contains("windows")
    val isMac = osName.contains("mac")
    val isLinux = !isWindows && !isMac

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tunProcess: Process? = null
    private var logJob: Job? = null
    private var currentSudoPassword: String? = null
    private var configuredRoute: ConfiguredServerRoute? = null

    var isTunActive: Boolean = false
        private set

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            try {
                if (isTunActive) {
                    stopSync(currentSudoPassword)
                }
            } catch (_: Exception) {}
        })
    }

    /**
     * Поиск бинарного файла tun2socks:
     * 1. Кастомный путь из настроек
     * 2. bin/tun2socks в рабочей директории и относительно дистрибутива
     * 3. Системные каталоги (/usr/local/bin, /usr/bin)
     * 4. Переменная окружения PATH
     */
    fun findTun2SocksExecutable(customPath: String? = null): File? {
        if (!customPath.isNullOrBlank()) {
            val f = File(customPath)
            return if (f.exists() && f.canExecute()) f else null
        }

        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        val execName = if (isWindows) "tun2socks.exe" else "tun2socks"

        val appResDir = System.getProperty("compose.application.resources.dir")?.let { File(it) }
        val appDataBin = when {
            isWindows -> System.getenv("LOCALAPPDATA")?.let { File(it, "v2whitelist/bin") }
            isMac -> File(System.getProperty("user.home"), "Library/Application Support/v2whitelist/bin")
            else -> File(System.getProperty("user.home"), ".config/v2whitelist/bin")
        }

        val candidates = listOfNotNull(
            appResDir?.let { File(it, execName) },
            appResDir?.let { File(it, "bin/$execName") },
            appDataBin?.let { File(it, execName) },
            File("bin", execName),
            File("../bin", execName),
            File("../../bin", execName),
            File("V2rayNG/desktopApp/bin", execName),
            File(execName),
            File("/usr/local/bin", execName),
            File("/usr/bin", execName)
        )

        for (cand in candidates) {
            if (cand.exists() && cand.canExecute()) {
                return cand.canonicalFile
            }
        }

        val pathEnv = System.getenv("PATH") ?: ""
        for (dir in pathEnv.split(File.pathSeparator)) {
            val cand = File(dir, execName)
            if (cand.exists() && cand.canExecute()) {
                return cand.canonicalFile
            }
        }

        return null
    }

    /**
     * Запуск L3 TUN интерфейса:
     * - Запуск tun2socks
     * - Назначение IP-адреса и перевод интерфейса v2w-tun в состояние UP
     * - Добавление прямого маршрута до сервера через шлюз по умолчанию (защита от петли)
     * - Маршрутизация 0.0.0.0/1 и 128.0.0.0/1 в интерфейс v2w-tun
     */
    @Synchronized
    fun start(
        socksPort: Int,
        serverAddress: String?,
        sudoPassword: String?,
        customPath: String? = null
    ): Boolean {
        if (isTunActive) {
            stop(sudoPassword)
        }

        currentSudoPassword = sudoPassword

        val binary = findTun2SocksExecutable(customPath)
        if (binary == null) {
            GeekModeLogger.log("TUN", "Исполняемый файл tun2socks не найден в bin/ и системе. Переход в резервный режим.")
            return false
        }

        GeekModeLogger.log("TUN", "Используется tun2socks: ${binary.absolutePath}")

        // 1. Определение шлюза и сетевого интерфейса по умолчанию
        val (gateway, physicalDev) = detectDefaultGateway()
        if (gateway == null || physicalDev == null) {
            GeekModeLogger.log("TUN", "Не удалось определить физический шлюз по умолчанию. TUN не может быть запущен безопасно.")
            return false
        }
        GeekModeLogger.log("TUN", "Физический маршрут: шлюз $gateway через интерфейс $physicalDev")

        // 2. Определение IP адреса целевого сервера (защита от петли трафика)
        val serverIp = if (!serverAddress.isNullOrBlank()) resolveServerIp(serverAddress) else null
        if (serverIp != null) {
            GeekModeLogger.log("TUN", "Целевой сервер для исключения из туннеля: $serverIp ($serverAddress)")
        }

        // 0. Подготовка виртуального интерфейса
        val deviceName = when {
            isWindows -> "wintun"
            isMac -> "utun"
            else -> "v2w-tun"
        }

        if (isLinux) {
            if (!sudoPassword.isNullOrBlank()) {
                runSudoCommand(listOf("modprobe", "tun"), sudoPassword)
            }
            runSudoCommand(listOf("ip", "link", "del", "dev", "v2w-tun"), sudoPassword)
        }

        // 3. Запуск tun2socks
        val useSudo = !sudoPassword.isNullOrBlank() && isLinux
        val tunArgs = mutableListOf<String>()
        if (useSudo) {
            tunArgs.addAll(listOf("sudo", "-S"))
        }
        tunArgs.addAll(listOf(
            binary.absolutePath,
            "-device", deviceName,
            "-proxy", "socks5://127.0.0.1:$socksPort",
            "-loglevel", "warn"
        ))

        try {
            val pb = ProcessBuilder(tunArgs).redirectErrorStream(true)
            val proc = pb.start()
            tunProcess = proc

            if (useSudo) {
                try {
                    proc.outputStream.bufferedWriter().use { writer ->
                        writer.write("$sudoPassword\n")
                        writer.flush()
                    }
                } catch (_: Exception) {}
            }

            // Мониторинг логов tun2socks
            logJob = scope.launch(Dispatchers.IO) {
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        var line: String? = null
                        while (isActive && reader.readLine().also { line = it } != null) {
                            line?.let {
                                if (!it.contains("[sudo]")) {
                                    GeekModeLogger.log("tun2socks", it)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // Ждём инициализации виртуального интерфейса
            Thread.sleep(350)

            if (!proc.isAlive) {
                val code = proc.exitValue()
                GeekModeLogger.log("TUN", "Процесс tun2socks завершился сразу с кодом: $code")
                cleanupProcess(sudoPassword)
                return false
            }

            // 4. Конфигурация интерфейса и маршрутов
            when {
                isLinux -> {
                    val addrRes = runSudoCommand(listOf("ip", "addr", "add", "198.18.0.1/15", "dev", "v2w-tun"), sudoPassword)
                    if (addrRes.first != 0 && !addrRes.second.contains("File exists", ignoreCase = true)) {
                        GeekModeLogger.log("TUN", "Ошибка назначения IP интерфейсу v2w-tun: ${addrRes.second}")
                        cleanupProcess(sudoPassword)
                        return false
                    }

                    val linkRes = runSudoCommand(listOf("ip", "link", "set", "dev", "v2w-tun", "up"), sudoPassword)
                    if (linkRes.first != 0) {
                        GeekModeLogger.log("TUN", "Ошибка поднятия интерфейса v2w-tun: ${linkRes.second}")
                        cleanupProcess(sudoPassword)
                        return false
                    }

                    if (serverIp != null && serverIp != "127.0.0.1" && !serverIp.startsWith("192.168.") && !serverIp.startsWith("10.")) {
                        val routeServerRes = runSudoCommand(
                            listOf("ip", "route", "add", "$serverIp/32", "via", gateway, "dev", physicalDev),
                            sudoPassword
                        )
                        if (routeServerRes.first == 0 || routeServerRes.second.contains("File exists", ignoreCase = true)) {
                            configuredRoute = ConfiguredServerRoute(serverIp, gateway, physicalDev)
                            GeekModeLogger.log("TUN", "Добавлен прямой маршрут до сервера: $serverIp/32 via $gateway dev $physicalDev")
                        }
                    }

                    val r1 = runSudoCommand(listOf("ip", "route", "add", "0.0.0.0/1", "dev", "v2w-tun"), sudoPassword)
                    val r2 = runSudoCommand(listOf("ip", "route", "add", "128.0.0.0/1", "dev", "v2w-tun"), sudoPassword)
                    if (r1.first != 0 || r2.first != 0) {
                        GeekModeLogger.log("TUN", "Сбой установки маршрутов TUN: ${r1.second} / ${r2.second}")
                        stop(sudoPassword)
                        return false
                    }
                }

                isWindows -> {
                    if (serverIp != null && serverIp != "127.0.0.1" && !serverIp.startsWith("192.168.") && !serverIp.startsWith("10.")) {
                        val routeRes = runCommand("cmd", "/c", "route add $serverIp mask 255.255.255.255 $gateway")
                        if (routeRes.first == 0) {
                            configuredRoute = ConfiguredServerRoute(serverIp, gateway, physicalDev)
                            GeekModeLogger.log("TUN", "Windows: Добавлен прямой маршрут к серверу: $serverIp -> $gateway")
                        }
                    }
                    runCommand("cmd", "/c", "route add 0.0.0.0 mask 128.0.0.0 198.18.0.1")
                    runCommand("cmd", "/c", "route add 128.0.0.0 mask 128.0.0.0 198.18.0.1")
                }

                isMac -> {
                    runSudoCommand(listOf("ifconfig", deviceName, "198.18.0.1", "198.18.0.2", "up"), sudoPassword)
                    if (serverIp != null && serverIp != "127.0.0.1" && !serverIp.startsWith("192.168.") && !serverIp.startsWith("10.")) {
                        runSudoCommand(listOf("route", "add", "-host", serverIp, gateway), sudoPassword)
                        configuredRoute = ConfiguredServerRoute(serverIp, gateway, physicalDev)
                    }
                    runSudoCommand(listOf("route", "add", "-net", "0.0.0.0/1", "-interface", deviceName), sudoPassword)
                    runSudoCommand(listOf("route", "add", "-net", "128.0.0.0/1", "-interface", deviceName), sudoPassword)
                }
            }

            isTunActive = true
            GeekModeLogger.log("TUN", "Адаптер $deviceName успешно активен. Все соединения ОС направлены через Xray TUN.")
            return true
        } catch (e: Exception) {
            GeekModeLogger.log("TUN", "Исключение при запуске TUN: ${e.message}")
            cleanupProcess(sudoPassword)
            return false
        }
    }

    /**
     * Остановка TUN и восстановление системных маршрутов.
     */
    @Synchronized
    fun stop(sudoPassword: String? = null): Boolean {
        return stopSync(sudoPassword ?: currentSudoPassword)
    }

    private fun stopSync(sudoPassword: String?): Boolean {
        GeekModeLogger.log("TUN", "Остановка TUN адаптера и снятие маршрутов...")
        isTunActive = false

        when {
            isLinux -> {
                runSudoCommand(listOf("ip", "route", "del", "0.0.0.0/1", "dev", "v2w-tun"), sudoPassword)
                runSudoCommand(listOf("ip", "route", "del", "128.0.0.0/1", "dev", "v2w-tun"), sudoPassword)
                configuredRoute?.let { route ->
                    runSudoCommand(listOf("ip", "route", "del", "${route.ip}/32", "via", route.gateway, "dev", route.dev), sudoPassword)
                    configuredRoute = null
                }
            }

            isWindows -> {
                runCommand("cmd", "/c", "route delete 0.0.0.0 mask 128.0.0.0")
                runCommand("cmd", "/c", "route delete 128.0.0.0 mask 128.0.0.0")
                configuredRoute?.let { route ->
                    runCommand("cmd", "/c", "route delete ${route.ip}")
                    configuredRoute = null
                }
            }

            isMac -> {
                runSudoCommand(listOf("route", "delete", "-net", "0.0.0.0/1"), sudoPassword)
                runSudoCommand(listOf("route", "delete", "-net", "128.0.0.0/1"), sudoPassword)
                configuredRoute?.let { route ->
                    runSudoCommand(listOf("route", "delete", "-host", route.ip), sudoPassword)
                    configuredRoute = null
                }
            }
        }

        cleanupProcess(sudoPassword)
        GeekModeLogger.log("TUN", "Режим TUN отключен. Сетевые маршруты восстановлены.")
        return true
    }

    private fun cleanupProcess(sudoPassword: String?) {
        logJob?.cancel()
        logJob = null

        tunProcess?.let { proc ->
            if (proc.isAlive) {
                proc.destroy()
                val exited = proc.waitFor(1, TimeUnit.SECONDS)
                if (!exited && proc.isAlive) {
                    proc.destroyForcibly()
                }
            }
        }
        tunProcess = null

        if (isLinux) {
            runSudoCommand(listOf("ip", "link", "del", "dev", "v2w-tun"), sudoPassword)
        }
    }

    /**
     * Определение активного шлюза и физического интерфейса.
     */
    private fun detectDefaultGateway(): Pair<String?, String?> {
        if (isLinux) {
            val (code, output) = runCommand("ip", "route", "show", "default")
            if (code == 0 && output.isNotBlank()) {
                val lines = output.lines()
                for (line in lines) {
                    val viaMatch = Regex("""via\s+([^\s]+)""").find(line)
                    val devMatch = Regex("""dev\s+([^\s]+)""").find(line)
                    if (viaMatch != null && devMatch != null) {
                        val gw = viaMatch.groupValues[1]
                        val dev = devMatch.groupValues[1]
                        if (dev != "v2w-tun" && !dev.startsWith("tun")) {
                            return gw to dev
                        }
                    }
                }
            }
        } else if (isWindows) {
            val (code, output) = runCommand("cmd", "/c", "route print 0.0.0.0")
            if (code == 0 && output.isNotBlank()) {
                for (line in output.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("0.0.0.0")) {
                        val tokens = trimmed.split(Regex("""\s+"""))
                        if (tokens.size >= 4) {
                            val gw = tokens[2]
                            val iface = tokens[3]
                            if (gw != "0.0.0.0" && gw != "On-link" && !gw.startsWith("198.18.")) {
                                return gw to iface
                            }
                        }
                    }
                }
            }
        } else if (isMac) {
            val (code, output) = runCommand("route", "-n", "get", "default")
            if (code == 0 && output.isNotBlank()) {
                var gw: String? = null
                var iface: String? = null
                for (line in output.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("gateway:")) {
                        gw = trimmed.removePrefix("gateway:").trim()
                    } else if (trimmed.startsWith("interface:")) {
                        iface = trimmed.removePrefix("interface:").trim()
                    }
                }
                if (gw != null && iface != null) {
                    return gw to iface
                }
            }
        }
        return null to null
    }

    private fun resolveServerIp(server: String): String? {
        return try {
            if (server.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) {
                return server
            }
            val addresses = InetAddress.getAllByName(server)
            addresses.firstOrNull { it is Inet4Address }?.hostAddress ?: addresses.firstOrNull()?.hostAddress
        } catch (e: Exception) {
            GeekModeLogger.log("TUN", "Не удалось отрезолвить адрес сервера $server: ${e.message}")
            null
        }
    }

    private fun runCommand(vararg cmd: String): Pair<Int, String> {
        return try {
            val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            val finished = proc.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                -1 to "Timeout"
            } else {
                proc.exitValue() to output.trim()
            }
        } catch (e: Exception) {
            -1 to (e.message ?: "Execution error")
        }
    }

    private fun runSudoCommand(cmd: List<String>, sudoPassword: String?): Pair<Int, String> {
        val fullCmd = if (!sudoPassword.isNullOrBlank()) {
            listOf("sudo", "-S") + cmd
        } else {
            cmd
        }
        return try {
            val proc = ProcessBuilder(fullCmd).redirectErrorStream(true).start()
            if (!sudoPassword.isNullOrBlank()) {
                try {
                    proc.outputStream.bufferedWriter().use { writer ->
                        writer.write("$sudoPassword\n")
                        writer.flush()
                    }
                } catch (_: Exception) {}
            }
            val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            val finished = proc.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                -1 to "Timeout"
            } else {
                proc.exitValue() to output.trim()
            }
        } catch (e: Exception) {
            -1 to (e.message ?: "Execution error")
        }
    }
}
