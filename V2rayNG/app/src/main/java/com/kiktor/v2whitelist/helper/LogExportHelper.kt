package com.kiktor.v2whitelist.helper

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.BuildConfig
import com.kiktor.v2whitelist.core.model.PersonalServersConfig
import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.NetworkManager
import com.kiktor.v2whitelist.handler.SettingsManager
import com.kiktor.v2whitelist.handler.SubscriptionHelper
import com.kiktor.v2whitelist.handler.V2RayServiceManager
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
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Сборщик диагностических логов и состояния приложения для Android.
 * Формирует ZIP-архив в cacheDir/logs/ с санитизацией ключей и токенов.
 */
object LogExportHelper {

    suspend fun exportDiagnosticsZip(context: Context): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val logsDir = File(context.cacheDir, "logs")
            if (!logsDir.exists()) logsDir.mkdirs()

            // Чистим старые временные архивы старше 1 часа
            val cutoff = System.currentTimeMillis() - 3600_000L
            logsDir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach {
                it.delete()
            }

            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val zipFile = File(logsDir, "probel-logs-$ts.zip")

            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                writeTextEntry(zos, "summary.txt", buildSummary(context))
                writeTextEntry(zos, "network_info.txt", buildNetworkInfo(context))

                val geekLogs = GeekModeLogger.snapshot()
                    .joinToString("\n") { GeekModeLogger.sanitizeLogLine(it) }
                writeTextEntry(zos, "geekmode.log", geekLogs.ifBlank { "(empty)" })

                writeTextEntry(zos, "logcat.txt", collectLogcat())
            }

            zipFile
        }
    }

    private fun writeTextEntry(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun buildSummary(context: Context): String {
        val sb = StringBuilder()
        val isoFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
        val subTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        sb.appendLine("=== ProBel Android Diagnostics ===")
        sb.appendLine("Generated: ${isoFmt.format(Date())} (TZ: ${TimeZone.getDefault().id})")
        sb.appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.FLAVOR}/${BuildConfig.BUILD_TYPE})")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        sb.appendLine()

        sb.appendLine("--- Runtime & Network ---")
        sb.appendLine("V2RayService Running: ${V2RayServiceManager.isRunning()}")
        sb.appendLine("isVpnActive: ${NetworkManager.isVpnActive(context)}")
        sb.appendLine("hasTunInterface: ${NetworkManager.hasTunInterface()}")
        sb.appendLine("isSystemVpnTransport: ${NetworkManager.isSystemVpnTransport(context)}")
        sb.appendLine("checkInternetStatus: ${NetworkManager.checkInternetStatus()} (0=online, 1=bs/whitelist, -1=offline)")
        sb.appendLine("SocksPort: ${SettingsManager.getSocksPort()}, HttpPort: ${SettingsManager.getHttpPort()}")
        sb.appendLine()

        val allGuids = MmkvManager.decodeServerList()
        var personalCount = 0
        val countBySub = mutableMapOf<String, Int>()
        for (guid in allGuids) {
            val p = MmkvManager.decodeServerConfig(guid) ?: continue
            if (PersonalServersConfig.isPersonalServer(p.subscriptionId)) {
                personalCount++
            }
            if (p.subscriptionId.isNotBlank()) {
                countBySub[p.subscriptionId] = (countBySub[p.subscriptionId] ?: 0) + 1
            }
        }
        val regularCount = allGuids.size - personalCount
        val selectedGuid = MmkvManager.getSelectServer()
        val selectedConfig = selectedGuid?.let { MmkvManager.decodeServerConfig(it) }
        val selectedPing = selectedGuid?.let { MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis }

        sb.appendLine("--- Servers ---")
        sb.appendLine("Total: ${allGuids.size} (regular=$regularCount, personal=$personalCount), VIP cache=${MmkvManager.getVipCache().size}")
        if (selectedConfig != null) {
            sb.appendLine(
                "Selected: remarks='${selectedConfig.remarks}', type=${selectedConfig.configType}, " +
                    "port=${selectedConfig.serverPort}, personal=${PersonalServersConfig.isPersonalServer(selectedConfig.subscriptionId)}, " +
                    "ping=${selectedPing ?: "unknown"}"
            )
        } else {
            sb.appendLine("Selected: (none)")
        }
        sb.appendLine()

        sb.appendLine("--- Subscriptions (URLs redacted) ---")
        val subs = MmkvManager.decodeSubscriptions()
        if (subs.isEmpty()) {
            sb.appendLine("(none)")
        } else {
            for (sub in subs) {
                val isPersonal = PersonalServersConfig.isPersonalServer(sub.guid)
                val updatedStr = if (sub.subscription.lastUpdated > 0L) {
                    subTimeFmt.format(Date(sub.subscription.lastUpdated))
                } else {
                    "never"
                }
                val srvCount = countBySub[sub.guid] ?: 0
                sb.appendLine(
                    "- id=${sub.guid}, name='${sub.subscription.remarks}', enabled=${sub.subscription.enabled}, " +
                        "personal=$isPersonal, servers=$srvCount, lastUpdated=$updatedStr, failed=${sub.subscription.lastUpdateFailed}"
                )
            }
        }
        sb.appendLine()

        sb.appendLine("--- Settings ---")
        sb.appendLine("mode=${MmkvManager.decodeSettingsString(AppConfig.PREF_MODE, AppConfig.VPN)}")
        sb.appendLine("smartFailover=${MmkvManager.decodeSettingsBool(AppConfig.PREF_SMART_FAILOVER_ENABLED, true)}")
        sb.appendLine("v2wCoreEnabled=${MmkvManager.decodeSettingsBool(AppConfig.PREF_V2W_CORE_ENABLED, true)}, fallback=${MmkvManager.decodeSettingsBool(AppConfig.PREF_V2W_CORE_FALLBACK, true)}, chunkSize=${MmkvManager.decodeSettingsString(AppConfig.PREF_CHUNK_SIZE, "20")}")
        sb.appendLine("subAutoUpdate=${MmkvManager.decodeSettingsBool(AppConfig.SUBSCRIPTION_AUTO_UPDATE, true)}, intervalMin=${MmkvManager.decodeSettingsString(AppConfig.SUBSCRIPTION_AUTO_UPDATE_INTERVAL, AppConfig.SUBSCRIPTION_DEFAULT_UPDATE_INTERVAL)}, onlyViaVpn=${MmkvManager.decodeSettingsBool(AppConfig.PREF_UPDATE_SUB_ONLY_VIA_VPN, false)}, yandexBs=${MmkvManager.decodeSettingsBool(AppConfig.PREF_UPDATE_VIA_YANDEX_ON_BS, false)}")
        sb.appendLine("perAppProxy=${MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false)}, preferIpv6=${MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6, false)}, allowInsecure=${MmkvManager.decodeSettingsBool(AppConfig.PREF_ALLOW_INSECURE, false)}")
        sb.appendLine("globalSniSet=${!MmkvManager.decodeSettingsString(AppConfig.PREF_GLOBAL_SNI, "").isNullOrBlank()}, cloudflareIpSet=${!MmkvManager.decodeSettingsString(AppConfig.PREF_CLOUDFLARE_IP, "").isNullOrBlank()}")
        sb.appendLine("customSubsCount=${SubscriptionHelper.loadCustomSubs().size}")

        return sb.toString()
    }

    private fun buildNetworkInfo(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== Android Network Info ===")
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val active = cm.activeNetwork
                sb.appendLine("ActiveNetwork: $active")
                @Suppress("DEPRECATION")
                for (net in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(net)
                    val lp = cm.getLinkProperties(net)
                    val transports = buildList {
                        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("WIFI")
                        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("CELLULAR")
                        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
                        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ETHERNET")
                    }.joinToString(",")
                    val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                    val dns = lp?.dnsServers?.joinToString(",") { it.hostAddress ?: "?" }.orEmpty()
                    sb.appendLine("Network $net | iface=${lp?.interfaceName} | transports=[$transports] | validated=$validated | dns=[$dns]")
                }
            }
        } catch (e: Exception) {
            sb.appendLine("ConnectivityManager error: ${e.message}")
        }

        sb.appendLine()
        sb.appendLine("=== Network Interfaces ===")
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (iface in ifaces) {
                val up = runCatching { iface.isUp }.getOrDefault(false)
                val addrs = iface.interfaceAddresses.joinToString(", ") {
                    "${it.address.hostAddress}/${it.networkPrefixLength}"
                }
                sb.appendLine("${iface.name} | up=$up | [$addrs]")
            }
        } catch (e: Exception) {
            sb.appendLine("NetworkInterface error: ${e.message}")
        }
        return sb.toString()
    }

    private fun collectLogcat(): String {
        return try {
            val pid = Process.myPid()
            val proc = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "1500", "--pid=$pid")
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            proc.waitFor(3, TimeUnit.SECONDS)
            runCatching { proc.destroy() }
            output.lineSequence()
                .map { GeekModeLogger.sanitizeLogLine(it) }
                .joinToString("\n")
                .ifBlank { "(empty)" }
        } catch (e: Exception) {
            "Failed to collect logcat: ${e.message}"
        }
    }
}
