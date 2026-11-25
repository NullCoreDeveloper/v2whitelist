package com.kiktor.v2whitelist.desktop.engine

import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class TunEnginePreference {
    AUTO,
    TUN2SOCKS,
    V2W_TUN;

    companion object {
        fun fromString(raw: String?): TunEnginePreference {
            return when (raw?.trim()?.uppercase()) {
                "TUN2SOCKS" -> TUN2SOCKS
                "V2W_TUN" -> V2W_TUN
                else -> AUTO
            }
        }
    }
}

enum class AppSplitMode {
    BYPASS,
    ONLY_PROXY;

    fun toWireValue(): String = when (this) {
        BYPASS -> "bypass"
        ONLY_PROXY -> "only_proxy"
    }

    companion object {
        fun fromString(raw: String?): AppSplitMode {
            return when (raw?.trim()?.uppercase()) {
                "ONLY_PROXY" -> ONLY_PROXY
                else -> BYPASS
            }
        }
    }
}

data class AppSplitTunnelingConfig(
    val enabled: Boolean = false,
    val mode: AppSplitMode = AppSplitMode.BYPASS,
    val directApps: List<String> = emptyList(),
    val proxyApps: List<String> = emptyList()
)

enum class TunStartFailureReason {
    NONE,
    NO_DEFAULT_GATEWAY,
    SERVICE_SELFTEST_FAILED,
    BINARY_NOT_FOUND,
    PRIVILEGE_REQUIRED,
    ROUTE_SETUP_FAILED,
    PROCESS_EXITED
}

data class DefaultGatewayInfo(
    val gateway: String,
    val physicalDev: String,
    val isOnLink: Boolean = false,
    val ifIndex: Int? = null
)

@Serializable
private data class ServiceStartTunRequestDto(
    @SerialName("socks_port") val socksPort: Int,
    @SerialName("server_ip") val serverIp: String,
    @SerialName("gateway") val gateway: String,
    @SerialName("physical_dev") val physicalDev: String,
    @SerialName("physical_if_idx") val physicalIfIdx: Int = 0,
    @SerialName("tun2socks_path") val tun2socksPath: String,
    @SerialName("tun_ip") val tunIp: String,
    @SerialName("tun_mask") val tunMask: String,
    @SerialName("tun_gateway") val tunGateway: String,
    @SerialName("dns_servers") val dnsServers: List<String>,
    @SerialName("tun_engine") val tunEngine: String,
    @SerialName("socks_user") val socksUser: String = "",
    @SerialName("socks_pass") val socksPass: String = "",
    @SerialName("direct_dns") val directDns: String = "",
    @SerialName("split_enabled") val splitEnabled: Boolean = false,
    @SerialName("split_mode") val splitMode: String = "bypass",
    @SerialName("direct_apps") val directApps: List<String> = emptyList(),
    @SerialName("proxy_apps") val proxyApps: List<String> = emptyList(),
    @SerialName("self_test_url") val selfTestUrl: String = "",
    @SerialName("bypass_lan") val bypassLan: Boolean = true
)

@Serializable
private data class V2wTunSplitConfigDto(
    @SerialName("enabled") val enabled: Boolean,
    @SerialName("mode") val mode: String,
    @SerialName("direct_apps") val directApps: List<String>,
    @SerialName("proxy_apps") val proxyApps: List<String>
)

@Serializable
private data class V2wTunRouterConfigDto(
    @SerialName("device_name") val deviceName: String,
    @SerialName("mtu") val mtu: Int,
    @SerialName("socks_addr") val socksAddr: String,
    @SerialName("socks_user") val socksUser: String = "",
    @SerialName("socks_pass") val socksPass: String = "",
    @SerialName("physical_iface") val physicalIface: String = "",
    @SerialName("direct_dns") val directDns: String = "",
    @SerialName("split") val split: V2wTunSplitConfigDto
)

data class ConfiguredServerRoute(
    val ip: String,
    val gateway: String,
    val dev: String,
    val isOnLink: Boolean = false
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
    private val jsonCodec = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var tunProcess: Process? = null
    private var v2wTunConfigFile: File? = null
    private var logJob: Job? = null
    private var currentSudoPassword: String? = null
    private var configuredRoute: ConfiguredServerRoute? = null
    private val configuredDnsRoutes = mutableListOf<Pair<String, String>>()
    private var activeTunGw: String = DEFAULT_TUN_GW
    private var activeMacDevice: String = "utun"
    private var macPrivilegedActive: Boolean = false

    var isTunActive: Boolean = false
        private set

    var isServiceMode: Boolean = false
        private set

    var lastStartFailureReason: TunStartFailureReason = TunStartFailureReason.NONE
        private set

    /** Служба отработала, но самопроверка интернета через TUN провалилась — резервный режим не нужен. */
    private var serviceSelfTestFailed = false
    val lastStartSelfTestFailed: Boolean
        get() = serviceSelfTestFailed

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
     * Поиск бинарного файла встроенного кастомного TUN-движка v2w-tun.
     */
    fun findV2wTunExecutable(customPath: String? = null): File? {
        return DesktopBinaryResolver.findBinary(DesktopBinaryResolver.v2wTunBinaryNames, customPath)
    }

    /**
     * Выбирает фактический TUN-движок:
     * - По умолчанию (AUTO) используется tun2socks;
     * - При включении раздельного туннелирования по приложениям (AUTO + split) автоматически выбирается v2w-tun;
     * - Явный выбор пользователя (TUN2SOCKS или V2W_TUN) всегда соблюдается.
     */
    fun resolveEffectiveTunEngine(
        preference: TunEnginePreference,
        appSplitEnabled: Boolean
    ): TunEnginePreference {
        return when (preference) {
            TunEnginePreference.V2W_TUN -> TunEnginePreference.V2W_TUN
            TunEnginePreference.TUN2SOCKS -> TunEnginePreference.TUN2SOCKS
            TunEnginePreference.AUTO -> if (appSplitEnabled) TunEnginePreference.V2W_TUN else TunEnginePreference.TUN2SOCKS
        }
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

    private fun resolveTunNetworkConfig(rawTunIp: String?): Triple<String, String, String> {
        val cleaned = rawTunIp?.substringBefore('/')?.trim().orEmpty()
        val octets = cleaned.split('.').mapNotNull { it.toIntOrNull() }
        val isValidIpv4 = octets.size == 4 && octets.all { it in 0..255 }
        val ip = if (isValidIpv4) cleaned else DEFAULT_TUN_IP
        val isFakeDnsRange = ip.startsWith("198.18.")
        val mask = if (isFakeDnsRange) DEFAULT_TUN_MASK else "255.255.255.0"
        val gw = if (isFakeDnsRange) {
            if (ip == DEFAULT_TUN_GW) DEFAULT_TUN_IP else DEFAULT_TUN_GW
        } else {
            val parts = ip.split('.')
            val last = parts[3].toInt()
            val gwLast = if (last == 1) 2 else 1
            "${parts[0]}.${parts[1]}.${parts[2]}.$gwLast"
        }
        return Triple(ip, mask, gw)
    }

    /**
     * Запуск TUN через системную службу Windows (Service Mode).
     */
    private fun startViaService(
        socksPort: Int,
        serverIp: String?,
        gateway: String,
        physicalDev: String,
        physicalIfIdx: Int,
        tun2socksPath: String?,
        tunIp: String,
        tunMask: String,
        tunGateway: String,
        effectiveEngine: TunEnginePreference,
        socksUser: String?,
        socksPass: String?,
        directDns: String?,
        appSplitConfig: AppSplitTunnelingConfig,
        selfTestUrl: String? = null,
        bypassLan: Boolean = true
    ): Boolean {
        return try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(1500))
                .build()
            serviceSelfTestFailed = false

            val engineWire = if (effectiveEngine == TunEnginePreference.V2W_TUN) "v2w-tun" else "tun2socks"
            val jsonBody = jsonCodec.encodeToString(
                ServiceStartTunRequestDto(
                    socksPort = socksPort,
                    serverIp = serverIp.orEmpty(),
                    gateway = gateway,
                    physicalDev = physicalDev,
                    physicalIfIdx = physicalIfIdx,
                    tun2socksPath = tun2socksPath.orEmpty(),
                    tunIp = tunIp,
                    tunMask = tunMask,
                    tunGateway = tunGateway,
                    dnsServers = listOf("1.1.1.1", "8.8.8.8"),
                    tunEngine = engineWire,
                    socksUser = socksUser.orEmpty(),
                    socksPass = socksPass.orEmpty(),
                    directDns = directDns.orEmpty(),
                    splitEnabled = appSplitConfig.enabled,
                    splitMode = appSplitConfig.mode.toWireValue(),
                    directApps = appSplitConfig.directApps,
                    proxyApps = appSplitConfig.proxyApps,
                    selfTestUrl = selfTestUrl.orEmpty().trim(),
                    bypassLan = bypassLan
                )
            )

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$SERVICE_IPC_URL/tun/start"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(25))
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
     * Остановка TUN и самой службы v2w-service через HTTP IPC (без UAC).
     */
    private fun shutdownViaService(): Boolean {
        return try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(1000))
                .build()
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$SERVICE_IPC_URL/service/shutdown"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(4))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() == 200
        } catch (e: Exception) {
            GeekModeLogger.log("TUN", "Ошибка отправки команды завершения службе v2w-service: ${e.message}")
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
     * Остановка системной службы v2w-service при выходе из приложения (без UAC).
     */
    fun stopWindowsService(): Boolean {
        if (!isWindows) return false
        return try {
            if (isServiceAvailable()) {
                shutdownViaService()
                // Даём SCM до 1.5 сек, чтобы завершить службу и перевести её в STOPPED
                repeat(15) {
                    val current = queryServiceState()
                    if (current == null || current == "STOPPED") {
                        GeekModeLogger.log("TUN", "Служба v2w-service остановлена через IPC при выходе.")
                        return true
                    }
                    Thread.sleep(100)
                }
            }
            val state = queryServiceState()
            if (state == null || state == "STOPPED") return true
            val (code, out) = runCommand("sc", "stop", "v2w-service")
            if (code == 0) {
                GeekModeLogger.log("TUN", "Служба v2w-service остановлена при выходе.")
                true
            } else {
                GeekModeLogger.log("TUN", "Не удалось остановить службу v2w-service: $out")
                false
            }
        } catch (e: Exception) {
            GeekModeLogger.log("TUN", "Ошибка остановки службы v2w-service: ${e.message}")
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
        val (code, out) = runCommand(
            "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
            "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; (Get-NetIPAddress -AddressFamily IPv4 -IPAddress '$physicalIp' -ErrorAction SilentlyContinue | Select-Object -First 1).InterfaceAlias"
        )
        val name = out.lines().firstOrNull { it.isNotBlank() }?.trim()
        return if (code == 0 && !name.isNullOrBlank() && !name.contains('\uFFFD')) name else null
    }

    /** Определяет (IP-адрес физического интерфейса) для привязки Xray; вызывать ДО start() TUN. */
    fun detectPhysicalInterfaceNameForBind(): String? {
        if (!isWindows) return null
        val gwInfo = detectDefaultGatewayInfo() ?: return null
        return resolvePhysicalInterfaceName(gwInfo.physicalDev)
    }

    /** Проверяет наличие физического шлюза по умолчанию (без TUN-интерфейса). */
    fun hasPhysicalDefaultGateway(): Boolean {
        return detectDefaultGatewayInfo() != null
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
        customPath: String? = null,
        tunIp: String? = null,
        tunEnginePreference: TunEnginePreference = TunEnginePreference.AUTO,
        appSplitConfig: AppSplitTunnelingConfig = AppSplitTunnelingConfig(),
        directDns: String? = null,
        socksUser: String? = null,
        socksPass: String? = null,
        allowServiceMode: Boolean = true,
        selfTestUrl: String? = null,
        bypassLan: Boolean = true
    ): Boolean {
        if (isTunActive) {
            stop(sudoPassword)
        }

        currentSudoPassword = sudoPassword
        serviceSelfTestFailed = false
        lastStartFailureReason = TunStartFailureReason.NONE
        val (resolvedTunIp, resolvedTunMask, resolvedTunGw) = resolveTunNetworkConfig(tunIp)
        activeTunGw = resolvedTunGw
        val effectiveEngine = resolveEffectiveTunEngine(tunEnginePreference, appSplitConfig.enabled)
        GeekModeLogger.log(
            "TUN",
            "Выбранный TUN-движок: $effectiveEngine (настройка=$tunEnginePreference, appSplit=${appSplitConfig.enabled}, mode=${appSplitConfig.mode})"
        )

        // 1. Определение шлюза и сетевого интерфейса по умолчанию
        val gwInfo = detectDefaultGatewayInfo()
        if (gwInfo == null) {
            lastStartFailureReason = TunStartFailureReason.NO_DEFAULT_GATEWAY
            GeekModeLogger.log("TUN", "Не удалось определить физический шлюз по умолчанию. TUN не может быть запущен безопасно.")
            return false
        }
        val gateway = gwInfo.gateway
        val physicalDev = gwInfo.physicalDev
        val gwDesc = if (gwInfo.isOnLink) "On-link (if=${gwInfo.ifIndex ?: "?"})" else gateway
        GeekModeLogger.log("TUN", "Физический маршрут: шлюз $gwDesc через интерфейс $physicalDev")

        // 2. Определение IP адреса целевого сервера (защита от петли трафика)
        val serverIp = if (!serverAddress.isNullOrBlank()) resolveServerIp(serverAddress) else null
        if (serverIp != null) {
            GeekModeLogger.log("TUN", "Целевой сервер для исключения из туннеля: $serverIp ($serverAddress)")
        }

        val resolvedDirectDns = directDns?.trim()?.takeIf { it.isNotEmpty() }
            ?: detectUnixPhysicalDns(excludeIps = setOfNotNull(serverIp, "1.1.1.1", "8.8.8.8"))

        // 3. Windows Service Mode (Вариант 1)
        if (isWindows && allowServiceMode) {
            if (!isServiceAvailable()) {
                installOrStartService()
            }
            if (isServiceAvailable()) {
                GeekModeLogger.log("TUN", "Используется системная служба v2w-service ($effectiveEngine, Service Mode)")
                val started = startViaService(
                    socksPort = socksPort,
                    serverIp = serverIp,
                    gateway = gateway,
                    physicalDev = physicalDev,
                    physicalIfIdx = gwInfo.ifIndex ?: 0,
                    tun2socksPath = findTun2SocksExecutable(customPath)?.absolutePath,
                    tunIp = resolvedTunIp,
                    tunMask = resolvedTunMask,
                    tunGateway = resolvedTunGw,
                    effectiveEngine = effectiveEngine,
                    socksUser = socksUser,
                    socksPass = socksPass,
                    directDns = resolvedDirectDns,
                    appSplitConfig = appSplitConfig,
                    selfTestUrl = selfTestUrl,
                    bypassLan = bypassLan
                )
                if (started) {
                    isTunActive = true
                    isServiceMode = true
                    return true
                }
                if (serviceSelfTestFailed) {
                    lastStartFailureReason = TunStartFailureReason.SERVICE_SELFTEST_FAILED
                    GeekModeLogger.log("TUN", "TUN поднят, но через него нет интернета — откат. Логи: C:\\ProgramData\\v2whitelist\\logs")
                    return false
                }
                // Гарантируем остановку TUN в службе перед резервным запуском, чтобы не было гонки двух tun2socks
                stopViaService()
                GeekModeLogger.log("TUN", "Служба v2w-service не смогла запустить TUN. Переход в резервный прямой режим...")
            }
        }

        // 4. Прямой режим (Direct process mode)
        var useV2wTun = effectiveEngine == TunEnginePreference.V2W_TUN
        var binary = if (useV2wTun) findV2wTunExecutable() else findTun2SocksExecutable(customPath)
        if (useV2wTun && binary == null) {
            if (appSplitConfig.enabled || tunEnginePreference == TunEnginePreference.V2W_TUN) {
                lastStartFailureReason = TunStartFailureReason.BINARY_NOT_FOUND
                GeekModeLogger.log("TUN", "Исполняемый файл v2w-tun не найден в bin/ и системе.")
                return false
            }
            GeekModeLogger.log("TUN", "Бинарник v2w-tun не найден, откат на tun2socks.")
            useV2wTun = false
            binary = findTun2SocksExecutable(customPath)
        }
        if (binary == null) {
            lastStartFailureReason = TunStartFailureReason.BINARY_NOT_FOUND
            GeekModeLogger.log("TUN", "Исполняемый файл tun2socks не найден в bin/ и системе. Переход в резервный режим.")
            return false
        }

        GeekModeLogger.log("TUN", "Используется ${if (useV2wTun) "v2w-tun" else "tun2socks"}: ${binary.absolutePath}")

        if (isWindows && !isWindowsAdmin()) {
            lastStartFailureReason = TunStartFailureReason.PRIVILEGE_REQUIRED
            GeekModeLogger.log(
                "TUN",
                "Предупреждение: для создания интерфейса Wintun и настройки сетевых маршрутов на Windows требуются права Администратора либо работающая служба v2w-service."
            )
        }

        var deviceName = when {
            isWindows -> "wintun"
            isMac -> "utun"
            else -> "v2w-tun"
        }

        if (isMac) {
            return startMacTunWithOsascript(
                binary = binary,
                useV2wTun = useV2wTun,
                socksPort = socksPort,
                socksUser = socksUser,
                socksPass = socksPass,
                physicalDev = physicalDev,
                resolvedDirectDns = resolvedDirectDns,
                appSplitConfig = appSplitConfig,
                resolvedTunIp = resolvedTunIp,
                resolvedTunGw = resolvedTunGw,
                serverIp = serverIp,
                gateway = gateway
            )
        }

        if (isLinux) {
            if (!sudoPassword.isNullOrBlank()) {
                runSudoCommand(listOf("modprobe", "tun"), sudoPassword)
            }
            runSudoCommand(listOf("ip", "link", "del", "dev", "v2w-tun"), sudoPassword)
        }

        val useSudo = !sudoPassword.isNullOrBlank() && isLinux
        val effectiveBinary = if (isLinux && (DesktopBinaryResolver.isTemporaryMountPath(binary.absolutePath) || useSudo)) {
            val tmpName = if (useV2wTun) "v2w-tun" else "v2w-tun2socks"
            val extracted = File("/tmp/$tmpName")
            try {
                if (!extracted.exists() || extracted.length() != binary.length()) {
                    binary.copyTo(extracted, overwrite = true)
                    extracted.setReadable(true, false)
                    extracted.setExecutable(true, false)
                }
                extracted
            } catch (e: Exception) {
                GeekModeLogger.log("TUN", "Копирование $tmpName в /tmp не удалось: ${e.message}")
                binary
            }
        } else {
            binary
        }

        val tunArgs = mutableListOf<String>()
        if (useSudo) {
            tunArgs.addAll(listOf("sudo", "-S"))
        }
        if (useV2wTun) {
            val cfgDto = V2wTunRouterConfigDto(
                deviceName = deviceName,
                mtu = 1500,
                socksAddr = "127.0.0.1:$socksPort",
                socksUser = socksUser.orEmpty(),
                socksPass = socksPass.orEmpty(),
                physicalIface = physicalDev,
                directDns = resolvedDirectDns.orEmpty(),
                split = V2wTunSplitConfigDto(
                    enabled = appSplitConfig.enabled,
                    mode = appSplitConfig.mode.toWireValue(),
                    directApps = appSplitConfig.directApps,
                    proxyApps = appSplitConfig.proxyApps
                )
            )
            val cfgFile = File.createTempFile("v2w_tun_", ".json").apply {
                deleteOnExit()
                writeText(jsonCodec.encodeToString(cfgDto))
            }
            v2wTunConfigFile = cfgFile
            tunArgs.addAll(listOf(effectiveBinary.absolutePath, "-config", cfgFile.absolutePath))
        } else {
            tunArgs.addAll(listOf(
                effectiveBinary.absolutePath,
                "-device", deviceName,
                "-proxy", "socks5://127.0.0.1:$socksPort",
                "-loglevel", "warning"
            ))
        }

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

            val readyLatch = if (useV2wTun) CountDownLatch(1) else null
            val reportedDevice = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val logTag = if (useV2wTun) "v2w-tun" else "tun2socks"

            logJob = scope.launch(Dispatchers.IO) {
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        var line: String? = null
                        while (isActive && reader.readLine().also { line = it } != null) {
                            line?.let { raw ->
                                if (raw.startsWith("V2W_TUN_READY")) {
                                    val devMatch = Regex("""device=([^\s]+)""").find(raw)
                                    if (devMatch != null) {
                                        reportedDevice.set(devMatch.groupValues[1])
                                    }
                                    readyLatch?.countDown()
                                }
                                if (!raw.contains("[sudo]")) {
                                    GeekModeLogger.log(logTag, raw)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    readyLatch?.countDown()
                }
            }

            if (readyLatch != null) {
                readyLatch.await(2000, TimeUnit.MILLISECONDS)
                Thread.sleep(150)
            } else {
                Thread.sleep(500)
            }

            if (!proc.isAlive) {
                val code = proc.exitValue()
                lastStartFailureReason = TunStartFailureReason.PROCESS_EXITED
                GeekModeLogger.log("TUN", "Процесс $logTag завершился сразу с кодом: $code")
                cleanupProcess(sudoPassword)
                return false
            }

            val effectiveDeviceName = reportedDevice.get()?.takeIf { it.isNotBlank() } ?: deviceName
            if (isMac) {
                activeMacDevice = effectiveDeviceName
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

                    // 1. Присваиваем статический IP и маску интерфейсу wintun (gateway=none, чтобы не плодить persistent route)
                    val netshAddrRes = runCommand(
                        "netsh", "interface", "ipv4", "set", "address",
                        "name=wintun", "source=static",
                        "addr=$resolvedTunIp", "mask=$resolvedTunMask",
                        "gateway=none"
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
                        val routeCmd = if (gwInfo.isOnLink && gwInfo.ifIndex != null) {
                            "route add $serverIp mask 255.255.255.255 0.0.0.0 metric 1 if ${gwInfo.ifIndex}"
                        } else if (!gwInfo.isOnLink) {
                            "route add $serverIp mask 255.255.255.255 $gateway metric 1"
                        } else null
                        if (routeCmd != null) {
                            val routeRes = runCommand("cmd", "/c", routeCmd)
                            if (routeRes.first == 0) {
                                configuredRoute = ConfiguredServerRoute(serverIp, gateway, physicalDev, isOnLink = gwInfo.isOnLink)
                                GeekModeLogger.log("TUN", "Windows: Добавлен прямой маршрут к серверу: $serverIp -> $gwDesc")
                            } else {
                                GeekModeLogger.log("TUN", "Windows: Предупреждение при добавлении маршрута к серверу: ${routeRes.second}")
                            }
                        }
                    }

                    // 4b. Прямые маршруты к DNS физического адаптера (защита от DNS-ловушки при route 0/1)
                    configuredDnsRoutes.clear()
                    if (!gwInfo.isOnLink) {
                        val physDnsList = detectWindowsPhysicalDnsServers(
                            tunDns = setOf("1.1.1.1", "8.8.8.8"),
                            excludeIps = setOfNotNull(serverIp, gateway)
                        )
                        for (dnsIp in physDnsList) {
                            val dnsRouteRes = runCommand("cmd", "/c", "route add $dnsIp mask 255.255.255.255 $gateway metric 1")
                            if (dnsRouteRes.first == 0) {
                                configuredDnsRoutes.add(dnsIp to gateway)
                                GeekModeLogger.log("TUN", "Windows: Добавлен прямой маршрут к DNS провайдера: $dnsIp -> $gateway")
                            }
                        }
                    }

                    // 5. Очищаем возможные старые маршруты во избежание коллизий
                    runCommand("cmd", "/c", "route delete 0.0.0.0 mask 128.0.0.0")
                    runCommand("cmd", "/c", "route delete 128.0.0.0 mask 128.0.0.0")

                    // 6. Направляем весь IPv4 интернет в wintun с обязательным указанием if <wintunIdx>
                    val ifPart = if (wintunIdx != null) "if $wintunIdx" else ""
                    val r1 = runCommand("cmd", "/c", "route add 0.0.0.0 mask 128.0.0.0 $resolvedTunGw metric 1 $ifPart".trim())
                    val r2 = runCommand("cmd", "/c", "route add 128.0.0.0 mask 128.0.0.0 $resolvedTunGw metric 1 $ifPart".trim())
                    if (r1.first != 0 || r2.first != 0) {
                        lastStartFailureReason = if (isWindowsAdmin()) TunStartFailureReason.ROUTE_SETUP_FAILED else TunStartFailureReason.PRIVILEGE_REQUIRED
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
                    runSudoCommand(listOf("ifconfig", effectiveDeviceName, "198.18.0.1", "198.18.0.2", "up"), sudoPassword)
                    if (serverIp != null && serverIp != "127.0.0.1" && !serverIp.startsWith("192.168.") && !serverIp.startsWith("10.")) {
                        runSudoCommand(listOf("route", "add", "-host", serverIp, gateway), sudoPassword)
                        configuredRoute = ConfiguredServerRoute(serverIp, gateway, physicalDev)
                    }
                    runSudoCommand(listOf("route", "add", "-net", "0.0.0.0/1", "-interface", effectiveDeviceName), sudoPassword)
                    runSudoCommand(listOf("route", "add", "-net", "128.0.0.0/1", "-interface", effectiveDeviceName), sudoPassword)
                }
            }

            isTunActive = true
            isServiceMode = false
            GeekModeLogger.log("TUN", "Адаптер $effectiveDeviceName ($logTag) успешно активен. Соединения ОС маршрутизируются через TUN.")
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

    /**
     * Гарантирует, что TUN остановлен и в системе (в т.ч. в службе v2w-service) не осталось активных TUN-маршрутов.
     */
    @Synchronized
    fun ensureStopped(sudoPassword: String? = null) {
        if (isTunActive) {
            stop(sudoPassword)
            return
        }
        if (isWindows) {
            if (isServiceAvailable()) {
                stopViaService()
            }
            if (isWindowsAdmin()) {
                runCommand("cmd", "/c", "route delete 0.0.0.0 mask 128.0.0.0")
                runCommand("cmd", "/c", "route delete 128.0.0.0 mask 128.0.0.0")
            }
        }
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
                runCommand("cmd", "/c", "route delete 0.0.0.0 mask 128.0.0.0 $activeTunGw")
                runCommand("cmd", "/c", "route delete 128.0.0.0 mask 128.0.0.0 $activeTunGw")
                if (activeTunGw != DEFAULT_TUN_GW) {
                    runCommand("cmd", "/c", "route delete 0.0.0.0 mask 128.0.0.0 $DEFAULT_TUN_GW")
                    runCommand("cmd", "/c", "route delete 128.0.0.0 mask 128.0.0.0 $DEFAULT_TUN_GW")
                }
                runCommand("netsh", "interface", "ipv6", "delete", "route", "::/1", "interface=wintun")
                runCommand("netsh", "interface", "ipv6", "delete", "route", "8000::/1", "interface=wintun")
                configuredRoute?.let { route ->
                    val delCmd = if (route.isOnLink) {
                        "route delete ${route.ip} mask 255.255.255.255"
                    } else {
                        "route delete ${route.ip} mask 255.255.255.255 ${route.gateway}"
                    }
                    runCommand("cmd", "/c", delCmd)
                    configuredRoute = null
                }
                for ((dnsIp, gw) in configuredDnsRoutes) {
                    runCommand("cmd", "/c", "route delete $dnsIp mask 255.255.255.255 $gw")
                }
                configuredDnsRoutes.clear()
                runCommand("ipconfig", "/flushdns")
            }

            isMac -> {
                if (macPrivilegedActive) {
                    stopMacTunWithOsascript()
                } else {
                    runSudoCommand(listOf("route", "delete", "-net", "0.0.0.0/1"), sudoPassword)
                    runSudoCommand(listOf("route", "delete", "-net", "128.0.0.0/1"), sudoPassword)
                    configuredRoute?.let { route ->
                        runSudoCommand(listOf("route", "delete", "-host", route.ip), sudoPassword)
                        configuredRoute = null
                    }
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
        runCatching { v2wTunConfigFile?.delete() }
        v2wTunConfigFile = null

        if (isLinux) {
            runSudoCommand(listOf("ip", "link", "del", "dev", "v2w-tun"), sudoPassword)
        }
    }

    private fun detectUnixPhysicalDns(excludeIps: Set<String> = emptySet()): String? {
        if (!isLinux && !isMac) return null
        return runCatching {
            val resolv = File("/etc/resolv.conf")
            if (!resolv.exists()) return@runCatching null
            resolv.readLines()
                .asSequence()
                .map { it.trim() }
                .filter { it.startsWith("nameserver") }
                .mapNotNull { it.split(Regex("""\s+""")).getOrNull(1)?.trim() }
                .firstOrNull { ip ->
                    ip.matches(Regex("""\d+\.\d+\.\d+\.\d+""")) &&
                        !ip.startsWith("127.") &&
                        !ip.startsWith("198.18.") &&
                        ip !in excludeIps
                }
        }.getOrNull()
    }

    private fun shQuote(arg: String): String {
        return "'" + arg.replace("'", "'\\''") + "'"
    }

    private fun runMacOsascript(shellCommand: String, timeoutSec: Long = 60): Pair<Int, String> {
        val escaped = shellCommand
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val appleScript = "do shell script \"$escaped\" with administrator privileges"
        return try {
            val proc = ProcessBuilder("osascript", "-e", appleScript)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { it.readText() }
            val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                try { proc.waitFor(1, TimeUnit.SECONDS) } catch (_: Exception) {}
                -1 to "Timeout"
            } else {
                proc.exitValue() to output.trim()
            }
        } catch (e: Exception) {
            -1 to (e.message ?: "osascript error")
        }
    }

    private fun startMacTunWithOsascript(
        binary: File,
        useV2wTun: Boolean,
        socksPort: Int,
        socksUser: String?,
        socksPass: String?,
        physicalDev: String,
        resolvedDirectDns: String?,
        appSplitConfig: AppSplitTunnelingConfig,
        resolvedTunIp: String,
        resolvedTunGw: String,
        serverIp: String?,
        gateway: String
    ): Boolean {
        val pidFile = "/tmp/v2w-tun-mac.pid"
        val devFile = "/tmp/v2w-tun-mac.dev"
        val logFile = "/tmp/v2w-tun-mac.log"
        val scriptFile = File("/tmp/v2w-tun-mac-start.sh")
        val logTag = if (useV2wTun) "v2w-tun" else "tun2socks"

        val launchLine = if (useV2wTun) {
            val cfgDto = V2wTunRouterConfigDto(
                deviceName = "utun",
                mtu = 1500,
                socksAddr = "127.0.0.1:$socksPort",
                socksUser = socksUser.orEmpty(),
                socksPass = socksPass.orEmpty(),
                physicalIface = physicalDev,
                directDns = resolvedDirectDns.orEmpty(),
                split = V2wTunSplitConfigDto(
                    enabled = appSplitConfig.enabled,
                    mode = appSplitConfig.mode.toWireValue(),
                    directApps = appSplitConfig.directApps,
                    proxyApps = appSplitConfig.proxyApps
                )
            )
            val cfgFile = File.createTempFile("v2w_tun_", ".json").apply {
                deleteOnExit()
                writeText(jsonCodec.encodeToString(cfgDto))
            }
            v2wTunConfigFile = cfgFile
            "${shQuote(binary.absolutePath)} -config ${shQuote(cfgFile.absolutePath)}"
        } else {
            "${shQuote(binary.absolutePath)} -device utun -proxy ${shQuote("socks5://127.0.0.1:$socksPort")} -loglevel warning"
        }

        val addHostRoute = if (serverIp != null && serverIp != "127.0.0.1" && !serverIp.startsWith("192.168.") && !serverIp.startsWith("10.")) {
            "route -n add -host ${shQuote(serverIp)} ${shQuote(gateway)} >/dev/null 2>&1 || true"
        } else {
            ""
        }

        val scriptContent = """
            #!/bin/sh
            set -e
            if [ -f $pidFile ]; then
              OLD_PID=${'$'}(cat $pidFile 2>/dev/null || true)
              if [ -n "${'$'}OLD_PID" ]; then kill "${'$'}OLD_PID" >/dev/null 2>&1 || true; fi
            fi
            rm -f $pidFile $devFile $logFile
            BEFORE=${'$'}(ifconfig -l | tr ' ' '\n' | grep '^utun' || true)
            nohup $launchLine > $logFile 2>&1 < /dev/null &
            TUN_PID=${'$'}!
            echo "${'$'}TUN_PID" > $pidFile
            DEV=""
            for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
              if ! kill -0 "${'$'}TUN_PID" >/dev/null 2>&1; then
                exit 2
              fi
              if [ -f $logFile ]; then
                READY_DEV=${'$'}(grep -o 'device=utun[0-9]*' $logFile | head -n1 | cut -d= -f2 || true)
                if [ -n "${'$'}READY_DEV" ]; then
                  DEV="${'$'}READY_DEV"
                  break
                fi
              fi
              AFTER=${'$'}(ifconfig -l | tr ' ' '\n' | grep '^utun' || true)
              for d in ${'$'}AFTER; do
                case "${'$'}BEFORE" in
                  *"${'$'}d"*) ;;
                  *) DEV="${'$'}d"; break ;;
                esac
              done
              if [ -n "${'$'}DEV" ]; then break; fi
              sleep 0.2
            done
            if [ -z "${'$'}DEV" ]; then
              kill "${'$'}TUN_PID" >/dev/null 2>&1 || true
              exit 3
            fi
            echo "${'$'}DEV" > $devFile
            ifconfig "${'$'}DEV" ${shQuote(resolvedTunIp)} ${shQuote(resolvedTunGw)} up
            $addHostRoute
            route -n delete -net 0.0.0.0/1 >/dev/null 2>&1 || true
            route -n delete -net 128.0.0.0/1 >/dev/null 2>&1 || true
            route -n add -net 0.0.0.0/1 -interface "${'$'}DEV"
            route -n add -net 128.0.0.0/1 -interface "${'$'}DEV"
        """.trimIndent() + "\n"

        return try {
            scriptFile.writeText(scriptContent)
            scriptFile.setExecutable(true, false)
            GeekModeLogger.log("TUN", "macOS: Запрос системных прав для запуска $logTag...")
            val (code, out) = runMacOsascript("/bin/sh ${shQuote(scriptFile.absolutePath)}")
            runCatching { scriptFile.delete() }
            if (code != 0) {
                val tail = runCatching { File(logFile).takeIf { it.exists() }?.readLines()?.takeLast(5)?.joinToString(" | ") }.getOrNull().orEmpty()
                lastStartFailureReason = if (out.contains("User canceled", ignoreCase = true) || out.contains("-128")) {
                    TunStartFailureReason.PRIVILEGE_REQUIRED
                } else {
                    TunStartFailureReason.PROCESS_EXITED
                }
                GeekModeLogger.log("TUN", "macOS: Сбой запуска TUN (code=$code): $out ${if (tail.isNotBlank()) "[$tail]" else ""}")
                return false
            }

            val devName = runCatching { File(devFile).takeIf { it.exists() }?.readText()?.trim() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "utun"
            activeMacDevice = devName
            macPrivilegedActive = true
            if (serverIp != null && serverIp != "127.0.0.1" && !serverIp.startsWith("192.168.") && !serverIp.startsWith("10.")) {
                configuredRoute = ConfiguredServerRoute(serverIp, gateway, physicalDev)
            }

            logJob?.cancel()
            logJob = scope.launch(Dispatchers.IO) {
                try {
                    val lf = File(logFile)
                    var offset = 0L
                    while (isActive && macPrivilegedActive) {
                        if (lf.exists() && lf.length() > offset) {
                            java.io.RandomAccessFile(lf, "r").use { raf ->
                                raf.seek(offset)
                                var line: String?
                                while (raf.readLine().also { line = it } != null) {
                                    val text = line ?: continue
                                    GeekModeLogger.log(logTag, text)
                                }
                                offset = raf.filePointer
                            }
                        }
                        delay(400L)
                    }
                } catch (_: Exception) {}
            }

            isTunActive = true
            isServiceMode = false
            GeekModeLogger.log("TUN", "macOS: Адаптер $devName ($logTag) успешно поднят с системными правами.")
            true
        } catch (e: Exception) {
            runCatching { scriptFile.delete() }
            lastStartFailureReason = TunStartFailureReason.PROCESS_EXITED
            GeekModeLogger.log("TUN", "macOS: Исключение при запуске TUN: ${e.message}")
            false
        }
    }

    private fun stopMacTunWithOsascript() {
        val pidFile = "/tmp/v2w-tun-mac.pid"
        val devFile = "/tmp/v2w-tun-mac.dev"
        val logFile = "/tmp/v2w-tun-mac.log"
        val hostDel = configuredRoute?.let { "route -n delete -host ${shQuote(it.ip)} >/dev/null 2>&1 || true" } ?: ""
        configuredRoute = null
        val cmd = """
            route -n delete -net 0.0.0.0/1 >/dev/null 2>&1 || true
            route -n delete -net 128.0.0.0/1 >/dev/null 2>&1 || true
            $hostDel
            if [ -f $pidFile ]; then P=${'$'}(cat $pidFile 2>/dev/null || true); if [ -n "${'$'}P" ]; then kill "${'$'}P" >/dev/null 2>&1 || true; fi; fi
            rm -f $pidFile $devFile $logFile
        """.trimIndent().replace("\n", "; ")
        macPrivilegedActive = false
        val (code, out) = runMacOsascript(cmd, timeoutSec = 25)
        if (code != 0) {
            GeekModeLogger.log("TUN", "macOS: Предупреждение при остановке TUN (code=$code): $out")
        }
    }

    /**
     * Определение активного шлюза и физического интерфейса.
     */
    private fun detectDefaultGatewayInfo(): DefaultGatewayInfo? {
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
                            return DefaultGatewayInfo(gateway = gw, physicalDev = dev)
                        }
                    }
                }
            }
        } else if (isWindows) {
            val (code, output) = runCommand("cmd", "/c", "route print -4 0.0.0.0")
            if (code == 0 && output.isNotBlank()) {
                val parsed = parseWindowsDefaultGateway(output, activeTunGw) ?: return null
                return if (parsed.isOnLink && parsed.ifIndex == null) {
                    parsed.copy(ifIndex = resolveWindowsInterfaceIndexByIp(parsed.physicalDev))
                } else {
                    parsed
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
                    return DefaultGatewayInfo(gateway = gw, physicalDev = iface)
                }
            }
        }
        return null
    }

    internal fun parseWindowsDefaultGateway(output: String, activeTunGw: String = DEFAULT_TUN_GW): DefaultGatewayInfo? {
        var best: Pair<DefaultGatewayInfo, Int>? = null
        val ipv4Regex = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")
        for (rawLine in output.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("Persistent Routes", ignoreCase = true) ||
                trimmed.startsWith("Постоянные маршруты", ignoreCase = true) ||
                trimmed.startsWith("ﭭ", ignoreCase = true)
            ) {
                break
            }
            val tokens = trimmed.split(Regex("""\s+"""))
            if (tokens.size < 5 || tokens[0] != "0.0.0.0" || tokens[1] != "0.0.0.0") continue

            var gwToken: String? = null
            var ifaceToken: String? = null
            var metric: Int? = null
            var isOnLink = false

            if (ipv4Regex.matches(tokens[2])) {
                gwToken = tokens[2]
                ifaceToken = tokens[3]
                metric = tokens[4].toIntOrNull()
            } else if (ipv4Regex.matches(tokens[3])) {
                // On-link / Привязан (одно слово)
                gwToken = "0.0.0.0"
                ifaceToken = tokens[3]
                metric = tokens[4].toIntOrNull()
                isOnLink = true
            } else if (tokens.size >= 6 && ipv4Regex.matches(tokens[4])) {
                // Локализованное On-link из двух слов (например, "На связи")
                gwToken = "0.0.0.0"
                ifaceToken = tokens[4]
                metric = tokens[5].toIntOrNull()
                isOnLink = true
            }

            if (gwToken == null || ifaceToken == null || metric == null) continue
            if (!ipv4Regex.matches(ifaceToken)) continue
            if (ifaceToken.startsWith("127.") || ifaceToken.startsWith("198.18.") || ifaceToken.startsWith("10.10.14.")) continue
            if (!isOnLink && (gwToken == "0.0.0.0" || gwToken.startsWith("198.18.") || gwToken.startsWith("10.10.14.") || gwToken == activeTunGw)) continue

            val candidate = DefaultGatewayInfo(
                gateway = gwToken,
                physicalDev = ifaceToken,
                isOnLink = isOnLink
            )
            if (best == null || metric < best.second) {
                best = candidate to metric
            }
        }
        return best?.first
    }

    private fun resolveWindowsInterfaceIndexByIp(ip: String): Int? {
        if (!isWindows || ip.isBlank()) return null
        val (code, out) = runCommand(
            "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
            "(Get-NetIPAddress -AddressFamily IPv4 -IPAddress '$ip' -ErrorAction SilentlyContinue | Select-Object -First 1).InterfaceIndex"
        )
        val idx = out.lines().firstOrNull { it.isNotBlank() }?.trim()?.toIntOrNull()
        return if (code == 0 && idx != null && idx > 0) idx else null
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
            val output = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { it.readText() }
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
            val output = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { it.readText() }
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
                if (idx != null && ifName.equals(name, ignoreCase = true)) {
                    return idx
                }
            }
        }
        return null
    }

    /**
     * Извлекает публичные IPv4 DNS-серверы физических адаптеров Windows (исключая wintun/loopback),
     * чтобы при добавлении маршрутов 0.0.0.0/1 системный DNS-клиент не зациклился.
     */
    internal fun detectWindowsPhysicalDnsServers(
        tunDns: Set<String> = setOf("1.1.1.1", "8.8.8.8"),
        excludeIps: Set<String> = emptySet()
    ): List<String> {
        val (code, output) = runCommand("netsh", "interface", "ipv4", "show", "dnsservers")
        if (code != 0) return emptyList()
        return parseNetshDnsServers(output, tunDns, excludeIps)
    }

    internal fun parseNetshDnsServers(
        output: String,
        tunDns: Set<String> = setOf("1.1.1.1", "8.8.8.8"),
        excludeIps: Set<String> = emptySet()
    ): List<String> {
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val excluded = tunDns + excludeIps
        val ipv4Regex = Regex("""\b(\d{1,3}(?:\.\d{1,3}){3})\b""")
        var skipSection = false

        for (rawLine in output.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                skipSection = false
                continue
            }
            if (line.contains("\"")) {
                val lower = line.lowercase()
                skipSection = lower.contains("\"wintun\"") || lower.contains("loopback")
            }
            if (skipSection) continue

            for (match in ipv4Regex.findAll(line)) {
                val ip = match.groupValues[1]
                val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
                if (parts.size != 4 || parts.any { it !in 0..255 }) continue
                val a = parts[0]
                val b = parts[1]
                val isLocal = a == 0 || a == 127 || a == 10 ||
                    (a == 169 && b == 254) ||
                    (a == 172 && b in 16..31) ||
                    (a == 192 && b == 168) ||
                    a >= 224
                if (isLocal || ip in excluded || !seen.add(ip)) continue
                result.add(ip)
            }
        }
        return result
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
