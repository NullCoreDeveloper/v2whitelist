package com.kiktor.v2whitelist.handler

import android.content.Context
import android.net.Uri
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.inspection.FullScanNodeResultDto
import com.kiktor.v2whitelist.core.model.inspection.ScanNetworkCondition
import com.kiktor.v2whitelist.core.model.inspection.ScanSessionMeta
import com.kiktor.v2whitelist.core.model.inspection.ServerInspectionInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import libv2ray.Libv2ray
import libv2ray.V2WFullScanCallback
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

data class FullScanUiState(
    val isScanning: Boolean = false,
    val totalCount: Int = 0,
    val completedCount: Int = 0,
    val aliveCount: Int = 0,
    val failedCount: Int = 0,
    val networkCondition: ScanNetworkCondition = ScanNetworkCondition.UNKNOWN,
    val networkCheckEnabled: Boolean = true,
    val endpointEnabled: Boolean = false,
    val revision: Long = 0L
)

object FullScanManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(FullScanUiState())
    val state: StateFlow<FullScanUiState> = _state.asStateFlow()

    private val sessionSeq = AtomicLong(0L)
    private val activeStartedAtMs = AtomicLong(0L)
    private val lastUiEmitAtMs = AtomicLong(0L)
    private val pendingMmkvWrites = ConcurrentHashMap<String, ServerInspectionInfo>()
    private val pendingFragUpdates = ConcurrentHashMap<String, String>()
    @Volatile
    private var flushLoopJob: Job? = null

    private fun buildFullScanOptionsHeader(
        tcpConcurrency: Int,
        verifyConcurrency: Int,
        endpointUrl: String,
        endpointEnabled: Boolean
    ): String {
        val services = ServiceCheckManager.loadServices()
        val destUrl = SettingsManager.getDelayTestUrl()
            .trim()
            .takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: AppConfig.DELAY_TEST_URL

        val servicesArray = JSONArray()
        for (svc in services) {
            servicesArray.put(
                JSONObject().apply {
                    put("id", svc.id)
                    put("name", svc.name)
                    put("url", svc.url)
                    put("enabled", svc.enabled)
                    put("isCustom", svc.isCustom)
                }
            )
        }

        val effectiveEndpoint = if (endpointEnabled) endpointUrl.trim() else ""
        val root = JSONObject().apply {
            put("tcp_concurrency", tcpConcurrency)
            put("verify_concurrency", verifyConcurrency)
            put("timeout_sec", 4)
            put("frag_timeout_sec", 2)
            put("dest_url", destUrl)
            put("endpoint_url", effectiveEndpoint)
            put("endpoint_e2e", effectiveEndpoint.isNotEmpty())
            put("service_check", effectiveEndpoint.isEmpty())
            put("services", servicesArray)
        }
        return "#v2w-opts:${root}"
    }

    private fun buildPayloadAndLookup(
        guids: List<String>,
        tcpConcurrency: Int,
        verifyConcurrency: Int,
        endpointUrl: String,
        endpointEnabled: Boolean
    ): Triple<String, Map<String, String>, Map<String, String>> {
        val sb = StringBuilder()
        sb.append(buildFullScanOptionsHeader(tcpConcurrency, verifyConcurrency, endpointUrl, endpointEnabled)).append("\n")

        val exactMap = LinkedHashMap<String, String>(guids.size)
        val noFragmentMap = LinkedHashMap<String, String>(guids.size)
        val hostPortMap = LinkedHashMap<String, String>(guids.size)

        for (guid in guids) {
            val cachedProfile = ServerListStore.getCachedProfile(guid)
            val url = if (cachedProfile != null) {
                AngConfigManager.shareConfig(cachedProfile).trim()
            } else {
                AngConfigManager.shareConfig(guid).trim()
            }
            if (url.isNotEmpty()) {
                sb.append(url).append("\n")
                exactMap.putIfAbsent(url, guid)
                noFragmentMap.putIfAbsent(url.substringBefore('#'), guid)
                val profile = cachedProfile ?: MmkvManager.decodeServerConfig(guid)
                val host = profile?.server?.trim()?.lowercase()
                val port = profile?.serverPort?.trim()
                if (!host.isNullOrEmpty() && !port.isNullOrEmpty()) {
                    hostPortMap.putIfAbsent("$host:$port", guid)
                }
            }
        }
        return Triple(sb.toString(), exactMap, noFragmentMap + hostPortMap)
    }

    private fun resolveGuid(
        link: String,
        exactMap: Map<String, String>,
        fallbackMap: Map<String, String>
    ): String? {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) return null
        exactMap[trimmed]?.let { return it }
        fallbackMap[trimmed.substringBefore('#')]?.let { return it }
        return try {
            val uri = Uri.parse(trimmed)
            val host = uri.host?.trim()?.lowercase()
            val port = uri.port
            if (!host.isNullOrEmpty() && port > 0) {
                fallbackMap["$host:$port"]
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun applyFragmentTagIfNeeded(guid: String, fragMode: String) {
        val profile: ProfileItem = ServerListStore.getCachedProfile(guid)
            ?: MmkvManager.decodeServerConfig(guid)
            ?: return
        var changed = false
        val targetPackets = if (fragMode == "tcp") "1-3" else "tlshello"
        val tag = if (fragMode == "tcp") "⚡[FRAG:TCP]" else "⚡[FRAG:RECORD]"

        if (profile.fragmentEnabled != true || profile.fragmentPackets != targetPackets) {
            profile.fragmentEnabled = true
            profile.fragmentPackets = targetPackets
            profile.fragmentLength = "50-100"
            profile.fragmentInterval = "10-20"
            changed = true
        }
        if (!profile.remarks.contains("[FRAG:")) {
            profile.remarks = "${profile.remarks} $tag".trim()
            changed = true
        }
        if (changed) {
            MmkvManager.encodeServerConfig(guid, profile)
            ServerListStore.updateProfileInMemory(guid, profile)
        }
    }

    private fun applyInspectionResultSync(guid: String, info: ServerInspectionInfo) {
        ServerListStore.putInspectionInMemory(guid, info)
        MmkvManager.encodeServerInspection(guid, info)
        MmkvManager.encodeServerTestDelayMillis(guid, if (info.alive) info.delayMs else -1L)
        if (info.alive && (info.fragMode == "tcp" || info.fragMode == "record")) {
            applyFragmentTagIfNeeded(guid, info.fragMode)
        }
        ServerListStore.bumpRevision()
    }

    private fun flushPendingToMmkv() {
        val inspKeys = pendingMmkvWrites.keys.toList()
        for (guid in inspKeys) {
            val info = pendingMmkvWrites.remove(guid) ?: continue
            MmkvManager.encodeServerInspection(guid, info)
            MmkvManager.encodeServerTestDelayMillis(guid, if (info.alive) info.delayMs else -1L)
        }
        val fragKeys = pendingFragUpdates.keys.toList()
        for (guid in fragKeys) {
            val mode = pendingFragUpdates.remove(guid) ?: continue
            applyFragmentTagIfNeeded(guid, mode)
        }
    }

    fun startFullScan(context: Context, targetGuids: List<String>? = null): Boolean {
        if (_state.value.isScanning || SmartConnectManager.isScanning.get()) {
            return false
        }

        val sessionId = sessionSeq.incrementAndGet()
        val startedAt = System.currentTimeMillis()
        activeStartedAtMs.set(startedAt)

        _state.update {
            it.copy(
                isScanning = true,
                totalCount = 0,
                completedCount = 0,
                aliveCount = 0,
                failedCount = 0
            )
        }

        val appContext = context.applicationContext
        scope.launch {
            if (sessionSeq.get() != sessionId) return@launch

            val networkCheckEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_NETWORK_CHECK_ENABLED, true)
            val networkCondition = if (networkCheckEnabled) {
                val status = NetworkManager.checkInternetStatus()
                ScanNetworkCondition.fromInternetStatus(status, networkCheckEnabled = true)
            } else {
                ScanNetworkCondition.UNKNOWN
            }

            if (sessionSeq.get() != sessionId) return@launch

            val customEpEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_CUSTOM_ENDPOINT_ENABLED, false)
            val endpointUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_ENDPOINT_URL).orEmpty().trim()
            val endpointEffective = customEpEnabled && endpointUrl.isNotEmpty()

            ServerListStore.ensureLoaded(appContext)
            ServerListStore.reloadBaseIfNeeded(appContext)

            val rawGuids = targetGuids ?: MmkvManager.decodeServerList()
            val guids = if (targetGuids == null) {
                pendingMmkvWrites.clear()
                pendingFragUpdates.clear()
                ServerListStore.clearInspectionsInMemory()
                MmkvManager.clearAllServerInspections()
                val vipSet = MmkvManager.getVipCache().toSet()
                val vipGuids = rawGuids.filter { it in vipSet }
                // Перемешиваем обычные сервера, чтобы пачки зависающих нод одной подписки
                // не забивали одновременно все 35 verify-воркеров (устраняет просадку скорости).
                val regularShuffled = rawGuids.filterNot { it in vipSet }.shuffled()
                vipGuids + regularShuffled
            } else {
                rawGuids
            }

            val tcpConcurrency = MmkvManager.getV2wTcpConcurrency()
            val verifyConcurrency = MmkvManager.getV2wCoreConcurrency()
            val (payload, exactMap, fallbackMap) = buildPayloadAndLookup(
                guids = guids,
                tcpConcurrency = tcpConcurrency,
                verifyConcurrency = verifyConcurrency,
                endpointUrl = endpointUrl,
                endpointEnabled = endpointEffective
            )

            val totalCount = exactMap.size
            if (totalCount == 0) {
                MmkvManager.encodeFullScanMeta(
                    ScanSessionMeta(
                        startedAtMs = startedAt,
                        finishedAtMs = System.currentTimeMillis(),
                        networkCondition = networkCondition,
                        networkCheckEnabled = networkCheckEnabled,
                        endpointEnabled = endpointEffective,
                        totalScanned = 0,
                        aliveCount = 0,
                        failedCount = 0
                    )
                )
                _state.update {
                    it.copy(
                        isScanning = false,
                        totalCount = 0,
                        completedCount = 0,
                        aliveCount = 0,
                        failedCount = 0,
                        networkCondition = networkCondition,
                        networkCheckEnabled = networkCheckEnabled,
                        endpointEnabled = endpointEffective,
                        revision = it.revision + 1
                    )
                }
                return@launch
            }

            _state.update {
                it.copy(
                    isScanning = true,
                    totalCount = totalCount,
                    completedCount = 0,
                    aliveCount = 0,
                    failedCount = 0,
                    networkCondition = networkCondition,
                    networkCheckEnabled = networkCheckEnabled,
                    endpointEnabled = endpointEffective,
                    revision = it.revision + 1
                )
            }
            lastUiEmitAtMs.set(System.currentTimeMillis())

            val completedCounter = AtomicInteger(0)
            val aliveCounter = AtomicInteger(0)
            val failedCounter = AtomicInteger(0)
            val processedGuids = ConcurrentHashMap.newKeySet<String>()

            GeekModeLogger.log(
                "FullScan",
                "Starting full scan: total=$totalCount, condition=$networkCondition, endpoint=$endpointEffective"
            )

            flushLoopJob?.cancel()
            flushLoopJob = scope.launch {
                while (isActive && sessionSeq.get() == sessionId) {
                    delay(1200L)
                    flushPendingToMmkv()
                }
            }

            val callback = object : V2WFullScanCallback {
                override fun onServerResult(resultJson: String?) {
                    if (sessionSeq.get() != sessionId) return
                    val dto = FullScanNodeResultDto.fromJson(resultJson) ?: return
                    val guid = resolveGuid(dto.link, exactMap, fallbackMap) ?: return
                    if (!processedGuids.add(guid)) return

                    val info = dto.toInspectionInfo(testedAtMs = System.currentTimeMillis())
                    ServerListStore.putInspectionInMemory(guid, info)
                    pendingMmkvWrites[guid] = info
                    if (info.alive && (info.fragMode == "tcp" || info.fragMode == "record")) {
                        pendingFragUpdates[guid] = info.fragMode
                    }

                    val done = completedCounter.incrementAndGet()
                    val alive = if (info.alive) aliveCounter.incrementAndGet() else aliveCounter.get()
                    val failed = if (!info.alive) failedCounter.incrementAndGet() else failedCounter.get()

                    // Живые сервера показываем почти сразу (120мс), мёртвые троттлим сильнее (500мс)
                    val minEmitMs = if (info.alive) 120L else 500L
                    val now = System.currentTimeMillis()
                    val prev = lastUiEmitAtMs.get()
                    if (now - prev >= minEmitMs && lastUiEmitAtMs.compareAndSet(prev, now)) {
                        ServerListStore.bumpRevision()
                        _state.update {
                            it.copy(
                                completedCount = done,
                                aliveCount = alive,
                                failedCount = failed,
                                revision = it.revision + 1
                            )
                        }
                    }
                }

                override fun onScanComplete(totalSuccess: Long, totalFailed: Long) {
                    if (sessionSeq.get() != sessionId) return
                    flushLoopJob?.cancel()
                    flushPendingToMmkv()
                    val finalAlive = aliveCounter.get()
                    val finalFailed = failedCounter.get()
                    val finalDone = completedCounter.get()

                    MmkvManager.encodeFullScanMeta(
                        ScanSessionMeta(
                            startedAtMs = startedAt,
                            finishedAtMs = System.currentTimeMillis(),
                            networkCondition = networkCondition,
                            networkCheckEnabled = networkCheckEnabled,
                            endpointEnabled = endpointEffective,
                            totalScanned = finalDone,
                            aliveCount = finalAlive,
                            failedCount = finalFailed
                        )
                    )
                    GeekModeLogger.log(
                        "FullScan",
                        "Full scan complete: scanned=$finalDone, alive=$finalAlive, failed=$finalFailed"
                    )
                    ServerListStore.bumpRevision()
                    _state.update {
                        it.copy(
                            isScanning = false,
                            completedCount = finalDone,
                            aliveCount = finalAlive,
                            failedCount = finalFailed,
                            revision = it.revision + 1
                        )
                    }
                }
            }

            try {
                Libv2ray.runV2WFullScan(payload, verifyConcurrency.toLong(), callback)
            } catch (e: Exception) {
                flushLoopJob?.cancel()
                flushPendingToMmkv()
                GeekModeLogger.log("FullScan", "runV2WFullScan error: ${e.message}")
                if (sessionSeq.get() == sessionId) {
                    ServerListStore.bumpRevision()
                    _state.update { it.copy(isScanning = false, revision = it.revision + 1) }
                }
            }
        }
        return true
    }

    fun stopFullScan() {
        if (!_state.value.isScanning) return
        sessionSeq.incrementAndGet()
        flushLoopJob?.cancel()
        try {
            Libv2ray.stopV2WFullScan()
        } catch (_: Exception) {}
        flushPendingToMmkv()

        val cur = _state.value
        if (cur.completedCount > 0) {
            MmkvManager.encodeFullScanMeta(
                ScanSessionMeta(
                    startedAtMs = activeStartedAtMs.get(),
                    finishedAtMs = System.currentTimeMillis(),
                    networkCondition = cur.networkCondition,
                    networkCheckEnabled = cur.networkCheckEnabled,
                    endpointEnabled = cur.endpointEnabled,
                    totalScanned = cur.completedCount,
                    aliveCount = cur.aliveCount,
                    failedCount = cur.failedCount
                )
            )
        }
        GeekModeLogger.log("FullScan", "Full scan stopped by user (${cur.completedCount}/${cur.totalCount})")
        _state.update {
            it.copy(isScanning = false, revision = it.revision + 1)
        }
    }

    suspend fun inspectSingleServer(context: Context, guid: String): ServerInspectionInfo? = withContext(Dispatchers.IO) {
        if (_state.value.isScanning || SmartConnectManager.isScanning.get()) {
            return@withContext null
        }

        val customEpEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_CUSTOM_ENDPOINT_ENABLED, false)
        val endpointUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_ENDPOINT_URL).orEmpty().trim()
        val endpointEffective = customEpEnabled && endpointUrl.isNotEmpty()

        val (payload, exactMap, _) = buildPayloadAndLookup(
            guids = listOf(guid),
            tcpConcurrency = 1,
            verifyConcurrency = 1,
            endpointUrl = endpointUrl,
            endpointEnabled = endpointEffective
        )
        if (exactMap.isEmpty()) return@withContext null

        val resultRef = AtomicReference<ServerInspectionInfo?>(null)
        suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation {
                try {
                    Libv2ray.stopV2WFullScan()
                } catch (_: Exception) {}
            }

            val callback = object : V2WFullScanCallback {
                override fun onServerResult(resultJson: String?) {
                    val dto = FullScanNodeResultDto.fromJson(resultJson) ?: return
                    val info = dto.toInspectionInfo(testedAtMs = System.currentTimeMillis())
                    resultRef.compareAndSet(null, info)
                }

                override fun onScanComplete(totalSuccess: Long, totalFailed: Long) {
                    if (cont.isActive) {
                        cont.resume(Unit)
                    }
                }
            }

            try {
                Libv2ray.runV2WFullScan(payload, 1L, callback)
            } catch (_: Exception) {
                if (cont.isActive) {
                    cont.resume(Unit)
                }
            }
        }

        val info = resultRef.get()
        if (info != null) {
            applyInspectionResultSync(guid, info)
            _state.update { it.copy(revision = it.revision + 1) }
        }
        return@withContext info
    }
}
