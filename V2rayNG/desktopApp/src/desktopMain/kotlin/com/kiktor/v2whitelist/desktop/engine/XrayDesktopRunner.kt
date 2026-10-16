package com.kiktor.v2whitelist.desktop.engine

import com.kiktor.v2whitelist.core.config.xray.XrayConfigGenerator
import com.kiktor.v2whitelist.core.config.xray.XrayGeneratorOptions
import com.kiktor.v2whitelist.core.model.AppScenario
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

enum class DesktopCoreState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

/**
 * Управляет жизненным циклом процесса ядра Xray на десктопе.
 * Генерирует конфиг через XrayConfigGenerator, запускает процесс и перенаправляет логи в GeekModeLogger.
 */
class XrayDesktopRunner(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val _coreState = MutableStateFlow(DesktopCoreState.STOPPED)
    val coreState: StateFlow<DesktopCoreState> = _coreState.asStateFlow()

    fun markStarting() {
        _coreState.value = DesktopCoreState.STARTING
    }

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var process: Process? = null
    private var tempConfigFile: File? = null
    private var logJob: Job? = null
    private var currentSudoPassword: String? = null
    var isLaunchedWithSudo: Boolean = false
        private set

    /**
     * Поиск исполняемого файла Xray:
     * 1. В переданном кастомном пути
     * 2. В рабочей директории (./xray или ./xray.exe)
     * 3. В папке bin
     * 4. В системном PATH
     */
    fun findXrayExecutable(customPath: String? = null): File? {
        if (!customPath.isNullOrBlank()) {
            val f = File(customPath)
            return if (f.exists() && f.canExecute()) f else null
        }

        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val execName = if (isWindows) "xray.exe" else "xray"

        // 1. Текущая директория
        val local = File(execName)
        if (local.exists() && local.canExecute()) return local

        // 2. В папке bin
        val binDir = File("bin", execName)
        if (binDir.exists() && binDir.canExecute()) return binDir

        // 3. Системный PATH
        val pathEnv = System.getenv("PATH") ?: ""
        val paths = pathEnv.split(File.pathSeparator)
        for (dir in paths) {
            val candidate = File(dir, execName)
            if (candidate.exists() && candidate.canExecute()) {
                return candidate
            }
        }

        return null
    }

    /**
     * Поиск каталога гео-баз (geosite.dat / geoip.dat):
     * 1. В ~/.config/v2whitelist/assets
     * 2. В ./assets и ./bin
     * 3. В системных путях Linux: /usr/share/xray, /usr/share/v2ray, /usr/local/share/xray
     */
    fun findAssetLocation(): File? {
        val userHome = System.getProperty("user.home") ?: "."
        val candidates = listOf(
            File(userHome, ".config/v2whitelist/assets"),
            File("assets"),
            File("bin"),
            File("/usr/share/xray"),
            File("/usr/share/v2ray"),
            File("/usr/local/share/xray"),
            File("/usr/local/share/v2ray")
        )
        for (dir in candidates) {
            if (dir.exists() && (File(dir, "geosite.dat").exists() || File(dir, "geoip.dat").exists())) {
                return dir
            }
        }
        return null
    }

    /**
     * Запуск ядра с выбранным профилем и опциями.
     */
    @Synchronized
    fun start(
        profile: ProfileItem,
        scenario: AppScenario = AppScenario.AUTO,
        xrayBinaryPath: String? = null,
        httpPort: Int = 10809,
        socksPort: Int = 10808,
        listenAddress: String = "127.0.0.1",
        customDirectDomains: List<String> = emptyList(),
        customProxyDomains: List<String> = emptyList(),
        upstreamProxyHost: String? = null,
        upstreamProxyPort: Int? = null,
        upstreamProxyType: String? = null,
        sudoPassword: String? = null,
        socksUsername: String? = null,
        socksPassword: String? = null,
        remoteDns: String? = null,
        internalDns: String? = null,
        dnsHosts: String? = null,
        allowInsecure: Boolean = false,
        sniffingEnabled: Boolean = true,
        routeOnly: Boolean = false,
        muxEnabled: Boolean = false,
        muxConcurrency: Int = 8,
        muxXudpConcurrency: Int = 8,
        customEndpointUrl: String? = null
    ): Boolean {
        if (_coreState.value == DesktopCoreState.RUNNING || _coreState.value == DesktopCoreState.STARTING) {
            stop()
        }

        _coreState.value = DesktopCoreState.STARTING
        _lastError.value = null

        val binary = findXrayExecutable(xrayBinaryPath)
        if (binary == null) {
            val err = "Исполняемый файл Xray не найден. Установите xray или укажите путь в настройках."
            GeekModeLogger.log("DesktopRunner", err)
            _lastError.value = err
            _coreState.value = DesktopCoreState.ERROR
            return false
        }

        try {
            if (!customEndpointUrl.isNullOrBlank()) {
                GeekModeLogger.log("DesktopRunner", "Активирована цепочка прокси: конечный узел $customEndpointUrl")
            }

            // Генерация JSON конфига
            val options = XrayGeneratorOptions(
                socksPort = socksPort,
                httpPort = httpPort,
                listenAddress = listenAddress,
                enableHttpInbound = true,
                scenario = scenario,
                customDirectDomains = customDirectDomains,
                customProxyDomains = customProxyDomains,
                upstreamProxyHost = upstreamProxyHost,
                upstreamProxyPort = upstreamProxyPort,
                upstreamProxyType = upstreamProxyType,
                customEndpointUrl = customEndpointUrl,
                socksUsername = socksUsername,
                socksPassword = socksPassword,
                remoteDns = remoteDns,
                internalDns = internalDns,
                dnsHosts = dnsHosts,
                allowInsecureDefault = allowInsecure,
                sniffingEnabled = sniffingEnabled,
                routeOnly = routeOnly,
                muxEnabled = muxEnabled,
                muxConcurrency = muxConcurrency,
                muxXudpConcurrency = muxXudpConcurrency
            )
            val jsonConfig = XrayConfigGenerator.generateJson(profile, options)

            // Запись во временный файл
            val tempFile = File.createTempFile("v2w_xray_config_", ".json").apply {
                deleteOnExit()
                writeText(jsonConfig)
            }
            tempConfigFile = tempFile

            val useSudo = !sudoPassword.isNullOrBlank()
            currentSudoPassword = if (useSudo) sudoPassword else null
            isLaunchedWithSudo = useSudo

            GeekModeLogger.log("DesktopRunner", "Конфигурация создана: ${tempFile.absolutePath}")
            val pb = if (useSudo) {
                GeekModeLogger.log("DesktopRunner", "Запуск ядра с правами root (sudo) для режима TUN: ${binary.absolutePath}")
                ProcessBuilder("sudo", "-S", binary.absolutePath, "run", "-c", tempFile.absolutePath).redirectErrorStream(true)
            } else {
                GeekModeLogger.log("DesktopRunner", "Запуск ядра: ${binary.absolutePath} run -c ${tempFile.absolutePath}")
                ProcessBuilder(binary.absolutePath, "run", "-c", tempFile.absolutePath).redirectErrorStream(true)
            }

            // Настройка расположения геофайлов geosite.dat и geoip.dat
            val assetDir = findAssetLocation()
            if (assetDir != null) {
                pb.environment()["XRAY_LOCATION_ASSET"] = assetDir.absolutePath
                GeekModeLogger.log("DesktopRunner", "Каталог геофайлов XRAY_LOCATION_ASSET: ${assetDir.absolutePath}")
            }

            val proc = pb.start()
            process = proc

            if (useSudo) {
                try {
                    proc.outputStream.bufferedWriter().use { writer ->
                        writer.write("$sudoPassword\n")
                        writer.flush()
                    }
                } catch (_: Exception) {}
            }

            // Мониторинг логов
            logJob = scope.launch {
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        var line: String? = null
                        while (isActive && reader.readLine().also { line = it } != null) {
                            line?.let { GeekModeLogger.log("XrayCore", it) }
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        GeekModeLogger.log("DesktopRunner", "Поток чтения логов завершился: ${e.message}")
                    }
                }
            }

            // Быстрая проверка, что процесс не завершился с ошибкой мгновенно
            try {
                proc.waitFor(80, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Exception) {}
            if (!proc.isAlive) {
                if (useSudo) {
                    GeekModeLogger.log("DesktopRunner", "Запуск через sudo завершился с кодом ${proc.exitValue()}. Пробуем резервный прямой запуск ядра...")
                    logJob?.cancel()
                    logJob = null
                    val directPb = ProcessBuilder(binary.absolutePath, "run", "-c", tempFile.absolutePath).redirectErrorStream(true)
                    if (assetDir != null) directPb.environment()["XRAY_LOCATION_ASSET"] = assetDir.absolutePath
                    val directProc = directPb.start()
                    process = directProc
                    isLaunchedWithSudo = false
                    logJob = scope.launch {
                        try {
                            BufferedReader(InputStreamReader(directProc.inputStream)).use { reader ->
                                var line: String? = null
                                while (isActive && reader.readLine().also { line = it } != null) {
                                    line?.let { GeekModeLogger.log("XrayCore", it) }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    try { directProc.waitFor(80, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
                    if (directProc.isAlive) {
                        _coreState.value = DesktopCoreState.RUNNING
                        GeekModeLogger.log("DesktopRunner", "Ядро успешно запущено в прямом режиме. HTTP: $httpPort, SOCKS: $socksPort")
                        return true
                    }
                }
                val exitCode = proc.exitValue()
                val err = "Процесс Xray завершился сразу с кодом: $exitCode"
                GeekModeLogger.log("DesktopRunner", err)
                _lastError.value = err
                _coreState.value = DesktopCoreState.ERROR
                cleanup()
                return false
            }

            _coreState.value = DesktopCoreState.RUNNING
            GeekModeLogger.log("DesktopRunner", "Ядро успешно запущено. HTTP: $httpPort, SOCKS: $socksPort (Listen: $listenAddress)")
            return true
        } catch (e: Exception) {
            val err = "Ошибка запуска Xray: ${e.message}"
            GeekModeLogger.log("DesktopRunner", err)
            _lastError.value = err
            _coreState.value = DesktopCoreState.ERROR
            cleanup()
            return false
        }
    }

    /**
     * Остановка ядра Xray.
     */
    @Synchronized
    fun stop() {
        GeekModeLogger.log("DesktopRunner", "Остановка ядра Xray...")
        _coreState.value = DesktopCoreState.STOPPED
        logJob?.cancel()
        logJob = null

        if (isLaunchedWithSudo && currentSudoPassword != null) {
            try {
                val killPb = ProcessBuilder("sudo", "-S", "pkill", "-f", "xray.*run.*v2w_xray_config_")
                val killProc = killPb.start()
                killProc.outputStream.bufferedWriter().use { writer ->
                    writer.write("${currentSudoPassword}\n")
                    writer.flush()
                }
                killProc.waitFor(1, TimeUnit.SECONDS)
            } catch (_: Exception) {}
        }

        process?.let { proc ->
            if (proc.isAlive) {
                proc.destroy()
                val exited = proc.waitFor(1500, TimeUnit.MILLISECONDS)
                if (!exited && proc.isAlive) {
                    proc.destroyForcibly()
                }
            }
        }
        cleanup()
        GeekModeLogger.log("DesktopRunner", "Ядро Xray остановлено.")
    }

    private fun cleanup() {
        process = null
        try {
            tempConfigFile?.delete()
        } catch (_: Exception) {}
        tempConfigFile = null
        isLaunchedWithSudo = false
    }
}
