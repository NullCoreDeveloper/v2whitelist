package com.kiktor.v2whitelist.desktop.util

import com.kiktor.v2whitelist.core.model.PersonalServersConfig
import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import com.kiktor.v2whitelist.desktop.data.DesktopStorageManager
import com.kiktor.v2whitelist.desktop.engine.DesktopBinaryResolver
import com.kiktor.v2whitelist.desktop.engine.DesktopTunManager
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Сборщик диагностических логов и системной информации для Desktop-версии.
 * Формирует ZIP-архив с санитизацией чувствительных данных (без ключей, UUID, паролей и URL подписок).
 */
object DesktopLogExporter {

    private const val MAX_LOG_FILE_BYTES = 2L * 1024 * 1024 // 2 МБ
    private val fileLock = Any()
    private var activeLogFile: File? = null
    private var prevLogFile: File? = null

    fun initFileLogging(logsDir: File) {
        try {
            if (!logsDir.exists()) logsDir.mkdirs()
            val mainFile = File(logsDir, "probel-app.log")
            val oldFile = File(logsDir, "probel-app.prev.log")
            synchronized(fileLock) {
                activeLogFile = mainFile
                prevLogFile = oldFile
            }
            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            GeekModeLogger.fileLogHandler = { formattedLine ->
                appendLineToFile(dateFmt.format(Date()) + " " + formattedLine)
            }
        } catch (_: Exception) {
            // Игнорируем, если директория недоступна на запись
        }
    }

    private fun appendLineToFile(line: String) {
        synchronized(fileLock) {
            val file = activeLogFile ?: return
            try {
                if (file.exists() && file.length() >= MAX_LOG_FILE_BYTES) {
                    val prev = prevLogFile
                    if (prev != null) {
                        if (prev.exists()) prev.delete()
                        file.renameTo(prev)
                    } else {
                        file.delete()
                    }
                }
                file.appendText(line + "\n", Charsets.UTF_8)
            } catch (_: Exception) {
            }
        }
    }

    fun defaultZipFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "probel-logs-$ts.zip"
    }

    suspend fun exportDiagnosticsZip(state: DesktopAppState, targetZipFile: File): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                targetZipFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

                ZipOutputStream(BufferedOutputStream(FileOutputStream(targetZipFile))).use { zos ->
                    writeTextEntry(zos, "summary.txt", buildSummary(state))

                    val memoryLogs = GeekModeLogger.snapshot()
                        .joinToString("\n") { GeekModeLogger.sanitizeLogLine(it) }
                    writeTextEntry(zos, "geekmode-memory.log", memoryLogs.ifBlank { "(empty)" })

                    synchronized(fileLock) {
                        activeLogFile?.takeIf { it.exists() && it.isFile }?.let { f ->
                            writeSanitizedFileEntry(zos, "probel-app.log", f)
                        }
                        prevLogFile?.takeIf { it.exists() && it.isFile }?.let { f ->
                            writeSanitizedFileEntry(zos, "probel-app.prev.log", f)
                        }
                    }

                    // На Windows собираем логи системной службы v2w-service (если есть)
                    if (DesktopBinaryResolver.isWindows) {
                        val serviceLogDir = File("C:\\ProgramData\\v2whitelist\\logs")
                        if (serviceLogDir.exists() && serviceLogDir.isDirectory) {
                            serviceLogDir.listFiles()
                                ?.filter { it.isFile && it.length() > 0 && it.length() <= 5L * 1024 * 1024 }
                                ?.sortedByDescending { it.lastModified() }
                                ?.take(5)
                                ?.forEach { logFile ->
                                    writeSanitizedFileEntry(zos, "v2w-service/${logFile.name}", logFile)
                                }
                        }
                    }

                    writeTextEntry(zos, "network-interfaces.txt", buildNetworkInterfacesInfo())
                }
                targetZipFile
            }
        }

    private fun writeTextEntry(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun writeSanitizedFileEntry(zos: ZipOutputStream, entryName: String, file: File) {
        try {
            val content = file.readLines(Charsets.UTF_8)
                .takeLast(5000)
                .joinToString("\n") { GeekModeLogger.sanitizeLogLine(it) }
            writeTextEntry(zos, entryName, content)
        } catch (e: Exception) {
            writeTextEntry(zos, entryName, "Failed to read ${file.name}: ${e.message}")
        }
    }

    private fun buildSummary(state: DesktopAppState): String {
        val sb = StringBuilder()
        val isoFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
        val subTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        sb.appendLine("=== ProBel Desktop Diagnostics ===")
        sb.appendLine("Generated: ${isoFmt.format(Date())} (TZ: ${TimeZone.getDefault().id})")
        sb.appendLine("App Version: ${DesktopAppState.APP_VERSION}")
        sb.appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
        sb.appendLine("Java: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
        sb.appendLine("Portable Mode: ${DesktopStorageManager.isPortableMode}")
        sb.appendLine("Config Dir: ${DesktopStorageManager.baseDir.absolutePath}")
        sb.appendLine()

        sb.appendLine("--- Runtime State ---")
        sb.appendLine("Core State: ${state.coreState.value}")
        sb.appendLine("Routing Mode: ${state.routingMode.value}")
        sb.appendLine("TUN Active: ${DesktopTunManager.isTunActive}")
        sb.appendLine("System Proxy Active: ${state.isSystemProxyActive.value}")
        sb.appendLine("Ports: socks=${state.socksPort.value}, http=${state.httpPort.value}")
        sb.appendLine("App Scenario: ${state.scenario.value}")
        sb.appendLine("Network Status: ${state.networkStatus.value}")
        sb.appendLine("Location Filter: ${state.locationFilterMode.value}, countries=${state.locationFilterSet.value.joinToString(",")}")
        sb.appendLine("Exit IP: ${state.exitIp.value ?: "(not fetched)"}")
        sb.appendLine("SmartConnect Status: ${state.smartConnectStatus.value ?: "(idle)"}")
        sb.appendLine()

        val allServers = state.servers.value
        val personalCount = allServers.count { PersonalServersConfig.isPersonalServer(it.second.subscriptionId) }
        val regularCount = allServers.size - personalCount
        val selectedGuid = state.selectedGuid.value
        val selectedServer = allServers.firstOrNull { it.first == selectedGuid }?.second
        val selectedPing = selectedGuid?.let { state.pingMap.value[it] }

        sb.appendLine("--- Servers ---")
        sb.appendLine("Total: ${allServers.size} (regular=$regularCount, personal=$personalCount), VIP cache=${state.vipGuids.value.size}")
        if (selectedServer != null) {
            sb.appendLine(
                "Selected: remarks='${selectedServer.remarks}', type=${selectedServer.configType}, " +
                    "port=${selectedServer.serverPort}, personal=${PersonalServersConfig.isPersonalServer(selectedServer.subscriptionId)}, " +
                    "ping=${selectedPing ?: "unknown"}"
            )
        } else {
            sb.appendLine("Selected: (none)")
        }
        sb.appendLine()

        sb.appendLine("--- Subscriptions (URLs redacted) ---")
        val subs = state.subscriptions.value
        if (subs.isEmpty()) {
            sb.appendLine("(none)")
        } else {
            for (sub in subs) {
                val isPersonal = PersonalServersConfig.isPersonalServer(sub.id)
                val serverCount = allServers.count { it.second.subscriptionId == sub.id || it.second.subscriptionId == sub.name }
                val updatedStr = if (sub.lastUpdated > 0L) subTimeFmt.format(Date(sub.lastUpdated)) else "never"
                sb.appendLine(
                    "- name='${sub.name}', enabled=${sub.enabled}, personal=$isPersonal, " +
                        "servers=$serverCount, lastUpdated=$updatedStr, failed=${sub.lastUpdateFailed}"
                )
            }
        }
        sb.appendLine()

        sb.appendLine("--- Settings ---")
        sb.appendLine("smartFailover=${state.smartFailover.value}, autoFailover=${state.autoFailover.value}")
        sb.appendLine("v2wCoreEnabled=${state.useV2WCore.value}, fallback=${state.useFallbackScanner.value}, batchSize=${state.smartConnectBatchSize.value}, sockets=${state.v2wCoreSockets.value}")
        sb.appendLine("subAutoUpdate=${state.subscriptionAutoUpdate.value}, intervalMin=${state.subscriptionUpdateIntervalMinutes.value}, onStartup=${state.subscriptionUpdateOnStartup.value}, onlyViaProxy=${state.subscriptionUpdateOnlyViaProxy.value}, yandexBs=${state.yandexTranslateUpdateEnabled.value}")
        sb.appendLine("allowInsecure=${state.allowInsecure.value}, fragmentEnabled=${state.fragmentEnabled.value}, domainSplit=${state.domainSplitTunnelingEnabled.value}, domainWhitelist=${state.domainWhitelistMode.value}")
        sb.appendLine("globalSniSet=${state.globalSni.value.isNotBlank()}, cloudflareIpSet=${state.cloudflareIp.value.isNotBlank()}")
        sb.appendLine()

        sb.appendLine("--- Binaries & Assets ---")
        val xray = DesktopBinaryResolver.findBinary(DesktopBinaryResolver.xrayBinaryNames)
        val v2w = DesktopBinaryResolver.findBinary(DesktopBinaryResolver.v2wBinaryNames)
        val tun2socks = DesktopBinaryResolver.findBinary(DesktopBinaryResolver.tun2socksBinaryNames)
        val serviceBin = DesktopBinaryResolver.findBinary(DesktopBinaryResolver.serviceBinaryNames)
        val assets = DesktopBinaryResolver.findAssetDirectory(xrayBinary = xray)
        sb.appendLine("xray: ${xray?.absolutePath ?: "NOT FOUND"}")
        sb.appendLine("v2w-core: ${v2w?.absolutePath ?: "NOT FOUND"}")
        sb.appendLine("tun2socks: ${tun2socks?.absolutePath ?: "NOT FOUND"}")
        sb.appendLine("v2w-service: ${serviceBin?.absolutePath ?: "NOT FOUND"}")
        sb.appendLine("assetsDir: ${assets?.absolutePath ?: "NOT FOUND"}")

        return sb.toString()
    }

    private fun buildNetworkInterfacesInfo(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Network Interfaces ===")
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (iface in ifaces) {
                val up = runCatching { iface.isUp }.getOrDefault(false)
                val loopback = runCatching { iface.isLoopback }.getOrDefault(false)
                val addrs = iface.interfaceAddresses.joinToString(", ") {
                    "${it.address.hostAddress}/${it.networkPrefixLength}"
                }
                sb.appendLine("${iface.name} (${iface.displayName}) | up=$up, loopback=$loopback | [$addrs]")
            }
        } catch (e: Exception) {
            sb.appendLine("Error enumerating interfaces: ${e.message}")
        }
        return sb.toString()
    }
}
