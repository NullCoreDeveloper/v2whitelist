package com.kiktor.v2whitelist.desktop.engine

import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

data class ConfiguredServerRoute(
    val ip: String,
    val gateway: String,
    val dev: String
)

/**
 * Управляет виртуальным сетевым адаптером TUN (v2w-tun / wintun) на Desktop через tun2socks
 * и фоновую службу Windows (v2w-service).
 *
 * Архитектурные принципы:
 * 1. Режим службы (Windows Service Mode, Вариант 1): на Windows работает через фоновую
 *    системную службу v2w-service (NT AUTHORITY\SYSTEM), поэтому GUI-клиент запускается
 *    без повышенных привилегий (asInvoker, без UAC при старте).
 * 2. Автономный режим: если служба не установлена, поддерживает прямой запуск tun2socks
 *    с настройкой IP-адреса Wintun (198.18.0.1/16), DNS (1.1.1.1, 8.8.8.8) и метрик маршрутов.
 * 3. Безопасность маршрутизации: перенаправляет трафик через два префикса 0.0.0.0/1 и 128.0.0.0/1
 *    с метрикой 1, не затирая системный default route, и добавляет прямой маршрут до удалённого сервера
 *    через физический шлюз во избежание петли маршрутизации.
 * 4. Защита от утечки DNS: на интерфейс назначаются DNS-серверы Cloudflare/Google.
 */
object DesktopTunManager {

    private const val SERVICE_IPC_URL = "http://127.0.0.1:53729"
    private const val DEFAULT_TUN_IP = "198.18.0.2"
    private const val DEFAULT_TUN_GW = "198.18.0.1"
    private const val DEFAULT_TUN_MASK = "255.255.0.0"

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

    var isServiceMode: Boolean = false
        private set

    /** Служба отработала, но самопроверка интернета через TUN провалилась — резервный режим не нужен. */
    private var serviceSelfTestFailed = false

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
        return DesktopBinaryResolver.findBinary(DesktopBinaryResolver.tun2socksBinaryNames, customPath)
    }

    /**
     * Поиск бинарного файла службы v2w-service.
     */
    fun findServiceExecutable(): File? {
        return DesktopBinaryResolver.findBinary(DesktopBinaryResolver.serviceBinaryNames)
    }

    /**
     * Проверяет, отвечает ли локальная фоновая служба v2w-service.
     */
    fun isServiceAvailable(): Boolean {
        if (!isWindows) return false
        return try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .build()
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$SERVICE_IPC_URL/ping"))
                .timeout(Duration.ofMillis(600))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() == 200 && response.body().contains("\"status\":\"ok\"")
        } catch (_: Exception) {
            false
        }
    }

    /** Ждёт ответа службы (она может стартовать несколько секунд после sc start / install). */
    fun waitForService(timeoutMs: Long = 6000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isServiceAvailable()) return true
            Thread.sleep(300)
        }
        return isServiceAvailable()
    }

    /** Состояние службы в SCM: null — не зарегистрирована, иначе RUNNING / STOPPED / START_PENDING ... */
    fun queryServiceState(): String? {
        if (!isWindows) return null
        val (code, out) = runCommand("sc", "query", "v2w-service")
        if (code != 0 && !out.contains("STATE")) return null
        val m = Regex("""STATE\s*:\s*\d+\s+(\w+)""").find(out) ?: return null
        return m.groupValues[1]
    }

    /** Служба установлена в portable-режиме или из MSI: в Program Files считаем «установленной» версией. */
    fun isInstalledDistribution(): Boolean {
        if (!isWindows) return false
        val path = System.getProperty("user.dir").orEmpty() + ";" + (System.getProperty("compose.application.resources.dir") ?: "") + ";" + (System.getProperty("java.home") ?: "")
        return path.contains("Program Files", ignoreCase = true)
    }

    /**
     * Запуск TUN через системную службу Windows (Service Mode).
     */
    private fun startViaService(
        socksPort: Int,
        serverIp: String?,
        gateway: String,
        physicalDev: String,
        tun2socksPath: String?
    ): Boolean {
        return try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(1500))
                .build()
            serviceSelfTestFailed = false

            val jsonBody = StringBuilder()
                .append("{")
                .append("\"socks_port\":").append(socksPort).append(",")
                .append("\"server_ip\":\"").append(serverIp.orEmpty()).append("\",")
                .append("\"gateway\":\"").append(gateway).append("\",")
                .append("\"physical_dev\":\"").append(physicalDev).append("\",")
                .append("\"tun2socks_path\":\"").append((tun2socksPath.orEmpty()).replace("\\", "\\\\")).append("\",")
                .append("\"tun_ip\":\"").append(DEFAULT_TUN_IP).append("\",")
                .append("\"tun_mask\":\"").append(DEFAULT_TUN_MASK).append("\",")
                .append("\"dns_servers\":[\"1.1.1.1\",\"8.8.8.8\"]")
                .append("}")
                .toString()

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$SERVICE_IPC_URL/tun/start"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(40))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200 && response.body().contains("\"success\":true")) {
                GeekModeLogger.log("TUN", "Служба v2w-service успешно запустила и настроила TUN интерфейс Wintun.")
                true
            } else {
                GeekModeLogger.log("TUN", "Ошибка ответа службы v2w-service (${response.statusCode()}): ${response.body()}")
                if (response.body().contains("SELFTEST_FAILED")) serviceSelfTestFailed = true
                false
            }
        } catch (e: Exception) {
            GeekModeLogger.log("TUN", "Не удалось связаться со службой v2w-service: ${e.message}")
            false
        }
    }

    /**
     * Остановка TUN через службу Windows.
     */
    private fun stopViaService(): Boolean {
        return try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(1000))
                .build()
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$SERVICE_IPC_URL/tun/stop"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(4))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() == 200
        } catch (e: Exception) {
            GeekModeLogger.log("TUN", "Ошибка отправки команды остановки службе v2w-service: ${e.message}")
            false
        }
    }

    /**
     * Тихая установка службы (если процесс уже имеет права Администратора).
     */
    fun runSilentServiceInstall(srvBin: File? = null): Boolean {
        val bin = srvBin ?: findServiceExecutable() ?: return false
        return try {
            val pb = ProcessBuilder(bin.absolutePath, "-install").redirectErrorStream(true)
            val proc = pb.start()
            val finished = proc.waitFor(8, TimeUnit.SECONDS)
            if (finished && proc.exitValue() == 0) {
                waitForService(6000)
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Запуск инсталляции службы через системное UAC окно (PowerShell Start-Process -Verb RunAs).
     */
    fun installServiceViaUac(): Boolean {
        val srvBin = findServiceExecutable() ?: return false
        return try {
            GeekModeLogger.log("TUN", "Запрос прав администратора для установки службы: ${srvBin.absolutePath}")
            val psCmd = "Start-Process '${srvBin.absolutePath}' -ArgumentList '-install' -Verb RunAs -Wait"
            runCommand("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", psCmd)
            val available = waitForService(8000)
            if (available) {
                GeekModeLogger.log("TUN", "Служба v2w-service успешно установлена и запущена!")
            }
            available
        } catch (e: Exception) {
            GeekModeLogger.log("TUN", "Ошибка при установке службы: ${e.message}")
            false
        }
    }

    /**
     * Удаление службы через UAC (PowerShell Start-Process -Verb RunAs).
     */
    fun uninstallServiceViaUac(): Boolean {
        val srvBin = findServiceExecutable() ?: return false
        return try {
            GeekModeLogger.log("TUN", "Запрос прав администратора для удаления службы: ${srvBin.absolutePath}")
            val psCmd = "Start-Process '${srvBin.absolutePath}' -ArgumentList '-uninstall' -Verb RunAs -Wait"
            runCommand("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", psCmd)
            Thread.sleep(800)
            !isServiceAvailable()
        } catch (e: Exception) {
            GeekModeLogger.log("TUN", "Ошибка при удалении службы: ${e.message}")
            false
        }
    }

    /**
     * Попытка инсталляции или запуска службы v2w-service (если не запущена).
     */
    fun installOrStartService() {
        val srvBin = findServiceExecutable() ?: return
        try {
            val state = queryServiceState()
            // 1. Служба уже зарегистрирована (например, ставили заранее) — НЕ ставим повторно
            if (state != null) {
                GeekModeLogger.log("TUN", "Служба v2w-service уже установлена (состояние: $state).")
                if (state == "RUNNING" || state == "START_PENDING") {
                    if (waitForService(6000)) return
                } else {
                    runCommand("sc", "start", "v2w-service")
                    if (waitForService(6000)) return
                    // sc start без прав — одна попытка через UAC (только запуск, без переустановки)
                    runCommand("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                        "Start-Process sc.exe -ArgumentList 'start','v2w-service' -Verb RunAs -Wait -WindowStyle Hidden")
                    if (waitForService(6000)) return
                }
                GeekModeLogger.log("TUN", "Служба зарегистрирована, но не отвечает. Переустановка не выполняется автоматически.")
                return
            }

            // 2. Не установлена: тихо, если мы админ, иначе один UAC
            if (isWindowsAdmin()) {
                if (runSilentServiceInstall(srvBin)) return
            }
            installServiceViaUac()
        } catch (e: Exception) {
            GeekModeLogger.log("TUN", "Не удалось автоматически установить/запустить службу: ${e.message}")
        }
    }

    /**
     * Имя (FriendlyName) физического адаптера по его IPv4 — для привязки сокетов Xray (sockopt.interface).
     */
    fun resolvePhysicalInterfaceName(physicalIp: String?): String? {
        if (!isWindows || physicalIp.isNullOrBlank()) return null
        val (code, out) = runCommand("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
            "(Get-NetIPAddress -AddressFamily IPv4 -IPAddress '$physicalIp' -ErrorAction SilentlyContinue | Select-Object -First 1).InterfaceAlias")
        val name = out.lines().firstOrNull { it.isNotBlank() }?.trim()
        return if (code == 0 && !name.isNullOrBlank()) name else null
    }

    /** Определяет (IP-адрес физического интерфейса) для привязки Xray; вызывать ДО start() TUN. */
    fun detectPhysicalInterfaceNameForBind(): String? {
        if (!isWindows) return null
        val (_, iface) = detectDefaultGateway()
        return resolvePhysicalInterfaceName(iface)
    }

    /**
     * Запуск L3 TUN интерфейса:
     * - Запуск tun2socks (через службу v2w-service или напрямую)
     * - Назначение IP-адреса и DNS на адаптер (wintun / v2w-tun)
     * - Добавление прямого маршрута до сервера через шлюз по умолчанию (защита от петли)
     * - Маршрутизация 0.0.0.0/1 и 128.0.0.0/1 в интерфейс TUN
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

        // 3. Windows Service Mode (Вариант 1)
        if (isWindows) {
            if (!isServiceAvailable()) {
                installOrStartService()
            }
            if (isServiceAvailable()) {
                GeekModeLogger.log("TUN", "Используется системная служба v2w-service (Service Mode, запуск без UAC)")
                val started = startViaService(
                    socksPort = socksPort,
                    serverIp = serverIp,
                    gateway = gateway,
                    physicalDev = physicalDev,
                    tun2socksPath = findTun2SocksExecutable(customPath)?.absolutePath
                )
                if (started) {
                    isTunActive = true
                    isServiceMode = true
                    return true
                }
                if (serviceSelfTestFailed) {
                    GeekModeLogger.log("TUN", "TUN поднят, но через него нет интернета — откат. Логи: C:\\ProgramData\\v2whitelist\\logs")
                    return false
                }
                GeekModeLogger.log("TUN", "Служба v2w-service не смогла запустить TUN. Переход в резервный прямой режим...")
            }
        }

        // 4. Прямой режим (Direct process mode)
        val binary = findTun2SocksExecutable(customPath)
        if (binary == null) {
            GeekModeLogger.log("TUN", "Исполняемый файл tun2socks не найден в bin/ и системе. Переход в резервный режим.")
            return false
        }

        GeekModeLogger.log("TUN", "Используется tun2socks: ${binary.absolutePath}")

        if (isWindows && !isWindowsAdmin()) {
            GeekModeLogger.log(
                "TUN",
                "Предупреждение: для создания интерфейса Wintun и настройки сетевых маршрутов на Windows требуются права Администратора либо работающая служба v2w-service."
            )
        }

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

        val useSudo = !sudoPassword.isNullOrBlank() && isLinux
        val effectiveBinary = if (isLinux && (DesktopBinaryResolver.isTemporaryMountPath(binary.absolutePath) || useSudo)) {
            val extracted = File("/tmp/v2w-tun2socks")
            try {
                if (!extracted.exists() || extracted.length() != binary.length()) {
                    binary.copyTo(extracted, overwrite = true)
                    extracted.setReadable(true, false)
                    extracted.setExecutable(true, false)
                }
                extracted
            } catch (e: Exception) {
                GeekModeLogger.log("TUN", "Копирование tun2socks в /tmp не удалось: ${e.message}")
                binary
            }
        } else {
            binary
        }

        val tunArgs = mutableListOf<String>()
        if (useSudo) {
            tunArgs.addAll(listOf("sudo", "-S"))
        }
        tunArgs.addAll(listOf(
            effectiveBinary.absolutePath,
            "-device", deviceName,
            "-proxy", "socks5://127.0.0.1:$socksPort",
            "-loglevel", "warning"
        ))

        try {
            val pb = ProcessBuilder(tunArgs).redirectErrorStream(true)
            effectiveBinary.parentFile?.let { if (it.exists()) pb.directory(it) }
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

            Thread.sleep(500)

            if (!proc.isAlive) {
                val code = proc.exitValue()
                GeekModeLogger.log("TUN", "Процесс tun2socks завершился сразу с кодом: $code")
                cleanupProcess(sudoPassword)
                return false
            }

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
                    // Ожидаем появления интерфейса wintun в системе (до 3 сек)
                    var wintunIdx: Int? = null
                    for (i in 1..20) {
                        wintunIdx = getWindowsInterfaceIndex("wintun")
                        if (wintunIdx != null) break
                        Thread.sleep(150)
                    }

                    if (wintunIdx != null) {
                        GeekModeLogger.log("TUN", "Windows: Обнаружен интерфейс wintun с индексом $wintunIdx")
                    } else {
                        GeekModeLogger.log("TUN", "Windows: Предупреждение: не удалось определить индекс интерфейса wintun")
                    }

                    // 1. Присваиваем статический IP 198.18.0.2 и шлюз 198.18.0.1 интерфейсу wintun (для NCSI/NLA)
                    val netshAddrRes = runCommand(
                        "netsh", "interface", "ipv4", "set", "address",
                        "name=wintun", "source=static",
                        "addr=$DEFAULT_TUN_IP", "mask=$DEFAULT_TUN_MASK",
                        "gateway=$DEFAULT_TUN_GW", "gwmetric=1"
                    )
                    if (netshAddrRes.first != 0) {
                        GeekModeLogger.log("TUN", "Windows: netsh set address: ${netshAddrRes.second}")
                    }

                    // 2. Устанавливаем наивысший приоритет метрики интерфейса Wintun (Metric = 1)
                    runCommand("netsh", "interface", "ipv4", "set", "interface", "name=wintun", "metric=1")
                    runCommand("netsh", "interface", "ipv4", "set", "subinterface", "name=wintun", "mtu=1500", "metric=1")

                    // 3. Назначаем DNS Cloudflare и Google на wintun адаптер для предотвращения DNS Leak
                    runCommand("netsh", "interface", "ipv4", "set", "dnsservers", "name=wintun", "source=static", "address=1.1.1.1", "validate=no")
                    runCommand("netsh", "interface", "ipv4", "add", "dnsservers", "name=wintun", "address=8.8.8.8", "validate=no")

                    // 4. Прямой маршрут до сервера через физический шлюз (защита от зацикливания трафика)
                    if (serverIp != null && serverIp != "127.0.0.1" && !serverIp.startsWith("192.168.") && !serverIp.startsWith("10.")) {
                        val routeRes = runCommand("cmd", "/c", "route add $serverIp mask 255.255.255.255 $gateway metric 1")
                        if (routeRes.first == 0) {
                            configuredRoute = ConfiguredServerRoute(serverIp, gateway, physicalDev)
                            GeekModeLogger.log("TUN", "Windows: Добавлен прямой маршрут к серверу: $serverIp -> $gateway")
                        } else {
                            GeekModeLogger.log("TUN", "Windows: Предупреждение при добавлении маршрута к серверу: ${routeRes.second}")
                        }
                    }

                    // 5. Очищаем возможные старые маршруты во избежание коллизий
                    runCommand("cmd", "/c", "route delete 0.0.0.0 mask 128.0.0.0 $DEFAULT_TUN_GW")
                    runCommand("cmd", "/c", "route delete 128.0.0.0 mask 128.0.0.0 $DEFAULT_TUN_GW")

                    // 6. Направляем весь IPv4 интернет в wintun с обязательным указанием if <wintunIdx>
                    val ifPart = if (wintunIdx != null) "if $wintunIdx" else ""
                    val r1 = runCommand("cmd", "/c", "route add 0.0.0.0 mask 128.0.0.0 $DEFAULT_TUN_GW metric 1 $ifPart".trim())
                    val r2 = runCommand("cmd", "/c", "route add 128.0.0.0 mask 128.0.0.0 $DEFAULT_TUN_GW metric 1 $ifPart".trim())
                    if (r1.first != 0 || r2.first != 0) {
                        GeekModeLogger.log("TUN", "Windows: Сбой установки маршрутов TUN (требуются права Администратора): ${r1.second} / ${r2.second}")
                        stopSync(null)
                        return false
                    }

                    // 7. Защита от утечки IPv6: перенаправляем весь IPv6 трафик в Wintun
                    // (IPv6 не перехватываем: tun2socks принимает TCP локально и ломает Happy Eyeballs)

                    // 8. Сброс DNS кэша Windows
                    runCommand("ipconfig", "/flushdns")
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
            isServiceMode = false
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

        if (isServiceMode) {
            stopViaService()
            isServiceMode = false
            GeekModeLogger.log("TUN", "Режим TUN через службу отключен.")
            return true
        }

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
                runCommand("cmd", "/c", "route delete 0.0.0.0 mask 128.0.0.0 $DEFAULT_TUN_GW")
                runCommand("cmd", "/c", "route delete 128.0.0.0 mask 128.0.0.0 $DEFAULT_TUN_GW")
                runCommand("netsh", "interface", "ipv6", "delete", "route", "::/1", "interface=wintun")
                runCommand("netsh", "interface", "ipv6", "delete", "route", "8000::/1", "interface=wintun")
                configuredRoute?.let { route ->
                    runCommand("cmd", "/c", "route delete ${route.ip} mask 255.255.255.255 ${route.gateway}")
                    configuredRoute = null
                }
                runCommand("ipconfig", "/flushdns")
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
                    try { proc.waitFor(1, TimeUnit.SECONDS) } catch (_: Exception) {}
                }
            } else {
                try { proc.waitFor() } catch (_: Exception) {}
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
            val (code, output) = runCommand("cmd", "/c", "route print -4 0.0.0.0")
            if (code == 0 && output.isNotBlank()) {
                // Только активные маршруты (5 колонок: сеть, маска, шлюз, интерфейс, метрика);
                // выбираем с минимальной метрикой, игнорируя persistent-секцию и наш TUN.
                var best: Triple<String, String, Int>? = null
                for (line in output.lines()) {
                    val tokens = line.trim().split(Regex("""\s+"""))
                    if (tokens.size < 5 || tokens[0] != "0.0.0.0" || tokens[1] != "0.0.0.0") continue
                    val gw = tokens[2]
                    val iface = tokens[3]
                    val metric = tokens[4].toIntOrNull() ?: continue
                    if (!gw.matches(Regex("""\d+\.\d+\.\d+\.\d+""")) || gw == "0.0.0.0" || gw.startsWith("198.18.")) continue
                    if (best == null || metric < best.third) best = Triple(gw, iface, metric)
                }
                if (best != null) return best.first to best.second
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
                try { proc.waitFor(1, TimeUnit.SECONDS) } catch (_: Exception) {}
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
                try { proc.waitFor(1, TimeUnit.SECONDS) } catch (_: Exception) {}
                -1 to "Timeout"
            } else {
                proc.exitValue() to output.trim()
            }
        } catch (e: Exception) {
            -1 to (e.message ?: "Execution error")
        }
    }

    /**
     * Возвращает индекс сетевого интерфейса Windows (Idx) по его имени (например, "wintun").
     */
    fun getWindowsInterfaceIndex(name: String = "wintun"): Int? {
        val (code, output) = runCommand("netsh", "interface", "ipv4", "show", "interfaces")
        if (code != 0 || output.isBlank()) return null
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("---") || trimmed.startsWith("Idx")) continue
            val parts = trimmed.split(Regex("""\s+"""), limit = 5)
            if (parts.size >= 5) {
                val idx = parts[0].toIntOrNull()
                val ifName = parts[4].trim().trim('"')
                if (idx != null && (ifName.equals(name, ignoreCase = true) || ifName.contains(name, ignoreCase = true))) {
                    return idx
                }
            }
        }
        return null
    }

    /**
     * Проверяет, запущено ли приложение с правами Администратора на Windows.
     * Быстрая и надежная проверка через команду net session (код возврата 0 при повышенных правах).
     */
    fun isWindowsAdmin(): Boolean {
        if (!isWindows) return false
        return try {
            val (code, _) = runCommand("cmd", "/c", "net session")
            code == 0
        } catch (_: Exception) {
            true
        }
    }
}
