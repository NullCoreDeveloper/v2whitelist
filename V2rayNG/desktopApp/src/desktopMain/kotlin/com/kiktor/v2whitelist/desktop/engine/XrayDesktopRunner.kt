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

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var process: Process? = null
    private var tempConfigFile: File? = null
    private var logJob: Job? = null

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
            if (f.exists() && f.canExecute()) return f
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
     * Запуск ядра с выбранным профилем и опциями.
     */
    @Synchronized
    fun start(
        profile: ProfileItem,
        scenario: AppScenario = AppScenario.AUTO,
        xrayBinaryPath: String? = null,
        httpPort: Int = 10809,
        socksPort: Int = 10808
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
            // Генерация JSON конфига
            val options = XrayGeneratorOptions(
                socksPort = socksPort,
                httpPort = httpPort,
                enableHttpInbound = true
            )
            val jsonConfig = XrayConfigGenerator.generateJson(profile, options)

            // Запись во временный файл
            val tempFile = File.createTempFile("v2w_xray_config_", ".json").apply {
                deleteOnExit()
                writeText(jsonConfig)
            }
            tempConfigFile = tempFile

            GeekModeLogger.log("DesktopRunner", "Конфигурация создана: ${tempFile.absolutePath}")
            GeekModeLogger.log("DesktopRunner", "Запуск ядра: ${binary.absolutePath} run -c ${tempFile.absolutePath}")

            val pb = ProcessBuilder(
                binary.absolutePath,
                "run",
                "-c",
                tempFile.absolutePath
            ).redirectErrorStream(true)

            val proc = pb.start()
            process = proc

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

            // Проверка, что процесс не упал сразу
            Thread.sleep(300)
            if (!proc.isAlive) {
                val exitCode = proc.exitValue()
                val err = "Процесс Xray завершился сразу с кодом: $exitCode"
                GeekModeLogger.log("DesktopRunner", err)
                _lastError.value = err
                _coreState.value = DesktopCoreState.ERROR
                cleanup()
                return false
            }

            _coreState.value = DesktopCoreState.RUNNING
            GeekModeLogger.log("DesktopRunner", "Ядро успешно запущено. HTTP: $httpPort, SOCKS: $socksPort")
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
    }
}
