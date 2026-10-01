package com.kiktor.v2whitelist.handler

import android.app.Service
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.util.MessageUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Умный Auto-Failover с пассивным отслеживанием аномалий трафика.
 *
 * Режимы работы:
 * - Heartbeat: периодическая HTTP-проверка туннеля (каждые N секунд)
 * - Smart Stall Detection: пассивный мониторинг трафика раз в секунду через [onTrafficSample]
 *   Если есть исходящий трафик (> 1.5 KB/s) но входящий упал ниже порога → экспресс-проверка → failover
 *
 * Не использует квантинование — просто выбирает следующий сервер из VIP кэша или списка.
 */
object SmartFailoverManager {

    enum class PhysicalNetworkState {
        NO_INTERNET,      // Физического интернета нет совсем (лифт, метро, режим полета)
        WHITELIST_ONLY,   // ya.ru доступен, google.com недоступен (белые списки оператора)
        FULL_INTERNET     // ya.ru и google.com доступны (полноценный интернет)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null

    private val isProbing = AtomicBoolean(false)
    private var consecutiveStallSeconds = 0

    @Volatile
    private var isMonitoring = false

    /**
     * Запускает фоновый мониторинг доступности сервера.
     */
    fun startFailoverMonitor(service: Service) {
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_FAILOVER, false)) {
            Log.d(AppConfig.TAG, "Failover: disabled in preferences")
            return
        }

        stopFailoverMonitor()
        isMonitoring = true
        consecutiveStallSeconds = 0
        isProbing.set(false)

        monitorJob = scope.launch {
            Log.i(AppConfig.TAG, "Failover monitor started. Waiting 10s warmup...")
            delay(10_000L) // Начальный прогрев туннеля

            while (isActive && isMonitoring && V2RayServiceManager.isRunning()) {
                val intervalSec = MmkvManager.decodeSettingsString(AppConfig.PREF_AUTO_FAILOVER_INTERVAL_SEC, "30")?.toLongOrNull() ?: 30L
                val checkIntervalMs = (intervalSec.coerceAtLeast(5L)) * 1000L

                delay(checkIntervalMs)
                if (!isActive || !isMonitoring || !V2RayServiceManager.isRunning()) break

                // Периодический Heartbeat
                runHeartbeatCheck(service)
            }
        }
    }

    /**
     * Останавливает фоновый мониторинг.
     */
    fun stopFailoverMonitor() {
        isMonitoring = false
        monitorJob?.cancel()
        monitorJob = null
        consecutiveStallSeconds = 0
        isProbing.set(false)
        Log.d(AppConfig.TAG, "Failover monitor stopped")
    }

    /**
     * Принимает сэмпл скорости трафика от коллектора (вызывается раз в секунду).
     * Используется для мгновенной детекции затыков:
     * если есть исходящий трафик но входящий упал — туннель заглушен.
     */
    fun onTrafficSample(service: Service?, proxyUpRate: Double, proxyDownRate: Double) {
        if (!isMonitoring || service == null || !V2RayServiceManager.isRunning()) return
        val smartEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_SMART_FAILOVER_ENABLED, false)
        if (!smartEnabled) return
        if (isProbing.get()) return

        val thresholdKbps = MmkvManager.decodeSettingsString(AppConfig.PREF_SMART_FAILOVER_THRESHOLD_KBPS, "3")?.toDoubleOrNull() ?: 3.0
        val stallSec = MmkvManager.decodeSettingsString(AppConfig.PREF_SMART_FAILOVER_STALL_SEC, "3")?.toIntOrNull() ?: 3

        val thresholdBytes = thresholdKbps * 1024.0
        val minUplinkBytes = 1500.0 // 1.5 KB/s порог исходящей активности

        if (proxyUpRate >= minUplinkBytes && proxyDownRate < thresholdBytes) {
            consecutiveStallSeconds++
            GeekModeLogger.log(
                "Failover",
                "Smart Failover: Potential stall #$consecutiveStallSeconds (Up: ${"%.1f".format(proxyUpRate / 1024)} KB/s, Down: ${"%.1f".format(proxyDownRate / 1024)} KB/s)"
            )

            if (consecutiveStallSeconds >= stallSec) {
                consecutiveStallSeconds = 0
                triggerExpressProbe(
                    service,
                    "Traffic stall: Up ${"%.1f".format(proxyUpRate / 1024)} KB/s >= 1.5 KB/s but Down ${"%.1f".format(proxyDownRate / 1024)} KB/s < ${thresholdKbps} KB/s for ${stallSec}s"
                )
            }
        } else {
            consecutiveStallSeconds = 0
        }
    }

    /**
     * Выполняет экспресс-проверку текущего туннеля при подозрении на затык.
     */
    private fun triggerExpressProbe(service: Service, reason: String) {
        scope.launch {
            if (!isProbing.compareAndSet(false, true)) return@launch
            try {
                GeekModeLogger.log("Failover", "Triggering express probe. Reason: $reason")
                val delay = testTunnelDelay(2000L)
                if (delay <= 0L || delay > 3000L) {
                    GeekModeLogger.log("Failover", "Express probe confirmed tunnel dead ($delay ms). Initiating failover...")
                    handleServerFailure(service, reason)
                } else {
                    GeekModeLogger.log("Failover", "Express probe passed ($delay ms). Tunnel is alive, false alarm.")
                }
            } finally {
                isProbing.set(false)
            }
        }
    }

    /**
     * Выполняет плановую проверку туннеля (Heartbeat).
     */
    private suspend fun runHeartbeatCheck(service: Service) {
        if (!isProbing.compareAndSet(false, true)) return
        try {
            val delay = testTunnelDelay(3000L)
            if (delay <= 0L || delay > 5000L) {
                GeekModeLogger.log("Failover", "Heartbeat probe failed ($delay ms). Retrying in 1.5s...")
                delay(1500L)
                val retryDelay = testTunnelDelay(3000L)
                if (retryDelay <= 0L || retryDelay > 5000L) {
                    GeekModeLogger.log("Failover", "Heartbeat retry failed ($retryDelay ms). Initiating failover...")
                    handleServerFailure(service, "Heartbeat check failed twice")
                } else {
                    GeekModeLogger.log("Failover", "Heartbeat retry succeeded ($retryDelay ms)")
                }
            }
        } finally {
            isProbing.set(false)
        }
    }

    /**
     * Тестирует задержку через активный туннель xray с жестким таймаутом.
     */
    private suspend fun testTunnelDelay(timeoutMs: Long): Long = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(timeoutMs) {
                val delayUrl = SettingsManager.getDelayTestUrl()
                val res = V2RayServiceManager.measureDelay(delayUrl)
                if (res > 0) res else -1L
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    /**
     * Обрабатывает подтверждённый сбой сервера.
     * Проводит арбитраж физической сети и переключает сервер если интернет есть.
     */
    private suspend fun handleServerFailure(service: Service, reason: String) {
        val physicalState = arbitratePhysicalNetwork(service)
        GeekModeLogger.log("Failover", "Physical network arbitration result: $physicalState (Trigger: $reason)")

        when (physicalState) {
            PhysicalNetworkState.NO_INTERNET -> {
                GeekModeLogger.log("Failover", "Physical network is down. Aborting failover, staying on current node.")
            }
            PhysicalNetworkState.WHITELIST_ONLY,
            PhysicalNetworkState.FULL_INTERNET -> {
                val currentGuid = MmkvManager.getSelectServer()
                val nextGuid = selectNextServer(currentGuid)
                if (nextGuid == null || nextGuid == currentGuid) {
                    GeekModeLogger.log("Failover", "Cannot failover: no alternative server found")
                    return
                }
                val profile = MmkvManager.decodeServerConfig(nextGuid)
                val remarks = profile?.remarks ?: nextGuid
                GeekModeLogger.log("Failover", "Hot-swapping from $currentGuid → $nextGuid ($remarks)")
                NotificationManager.showFailoverNotification()
                MmkvManager.setSelectServer(nextGuid)
                MmkvManager.saveLastConnectedServer(nextGuid)
                MessageUtil.sendMsg2Service(service, AppConfig.MSG_STATE_SWITCH_SERVER, "")
                stopFailoverMonitor()
            }
        }
    }

    /**
     * Выбор следующего сервера:
     * 1. Приоритет VIP Cache (быстрый путь)
     * 2. Следующий по порядку в основном списке
     */
    fun selectNextServer(currentGuid: String?): String? {
        val allServers = MmkvManager.decodeServerList()
        if (allServers.size <= 1) return null

        // 1. VIP Cache
        val vipGuids = MmkvManager.getVipCache()
        for (guid in vipGuids) {
            if (guid == currentGuid) continue
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            if (!allServers.contains(guid)) continue
            GeekModeLogger.log("Failover", "Selected next server from VIP cache: ${profile.remarks} ($guid)")
            return guid
        }

        // 2. Следующий по кругу из основного списка
        val currentIndex = if (currentGuid != null) allServers.indexOf(currentGuid) else -1
        val startIndex = if (currentIndex != -1) (currentIndex + 1) % allServers.size else 0
        for (i in allServers.indices) {
            val idx = (startIndex + i) % allServers.size
            val guid = allServers[idx]
            if (guid == currentGuid) continue
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            GeekModeLogger.log("Failover", "Selected next server from main list: ${profile.remarks} ($guid)")
            return guid
        }

        return null
    }

    /**
     * Физический арбитраж сети в обход VPN.
     * Проверяет ya.ru и google.com через реальные сетевые адаптеры устройства.
     */
    suspend fun arbitratePhysicalNetwork(context: Context): PhysicalNetworkState {
        val checkEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_NETWORK_CHECK_ENABLED, true)
        if (!checkEnabled) return PhysicalNetworkState.FULL_INTERNET // assume full internet if check is disabled

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return PhysicalNetworkState.NO_INTERNET

        val networks = cm.allNetworks
        var physicalNet: android.net.Network? = null
        for (net in networks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                physicalNet = net
                break
            }
        }

        if (physicalNet == null) return PhysicalNetworkState.NO_INTERNET

        var yaOk = false
        var googleOk = false
        val net = physicalNet

        var url1 = MmkvManager.decodeSettingsString(AppConfig.PREF_NETWORK_CHECK_URL_1, "google.com") ?: "google.com"
        var url2 = MmkvManager.decodeSettingsString(AppConfig.PREF_NETWORK_CHECK_URL_2, "ya.ru") ?: "ya.ru"
        if (url1.isBlank()) url1 = "google.com"
        if (url2.isBlank()) url2 = "ya.ru"
        
        // Add scheme if missing
        if (!url1.startsWith("http")) url1 = "https://$url1"
        if (!url2.startsWith("http")) url2 = "https://$url2"

        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val yaDef = async { checkDirectUrl(net, url2, "HEAD", 1500) }
            val googleDef = async { checkDirectUrl(net, url1, "GET", 1500) }
            yaOk = yaDef.await()
            googleOk = googleDef.await()
        }

        return when {
            yaOk && googleOk -> PhysicalNetworkState.FULL_INTERNET
            !yaOk && googleOk -> PhysicalNetworkState.FULL_INTERNET  // зарубежный Wi-Fi
            yaOk && !googleOk -> PhysicalNetworkState.WHITELIST_ONLY // оператор блокирует внешний интернет
            else -> PhysicalNetworkState.NO_INTERNET
        }
    }

    private fun checkDirectUrl(network: android.net.Network, urlStr: String, method: String, timeoutMs: Int): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = network.openConnection(url) as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            code in 200..399
        } catch (_: Exception) {
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
