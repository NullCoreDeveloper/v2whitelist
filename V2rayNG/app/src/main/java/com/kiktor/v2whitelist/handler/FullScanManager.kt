package com.kiktor.v2whitelist.handler

import android.content.Context
import android.net.Uri
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.PersonalServersConfig
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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import libv2ray.V2WFullScanCallback
import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

enum class FullScanMode {
    FULL,
    VIP_PING,
    VIP_FIND
}

data class FullScanUiState(
    val isScanning: Boolean = false,
    val mode: FullScanMode = FullScanMode.FULL,
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
    private val lastProgressEmitAtMs = AtomicLong(0L)
    private val lastListBumpAtMs = AtomicLong(0L)
    private val pendingMmkvWrites = ConcurrentHashMap<String, ServerInspectionInfo>()
    private val pendingFragUpdates = ConcurrentHashMap<String, String>()
    private val activeEndpointJobs = CopyOnWriteArrayList<Job>()
    private val endpointSemaphore = Semaphore(4)

    @Volatile
    private var flushLoopJob: Job? = null

    fun isEndpointCheckActive(): Boolean {
        val personalConfig = PersonalServersManager.getConfig()
        if (!personalConfig.exitNodeEnabled) return false
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_CUSTOM_ENDPOINT_ENABLED, false)) return false
        val url = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_ENDPOINT_URL).orEmpty().trim()
        return url.isNotEmpty()
    }

    private fun isServiceCheckEnabledForScan(endpointEffective: Boolean): Boolean {
        if (endpointEffective) return false
        return MmkvManager.decodeSettingsBool(AppConfig.PREF_FULL_SCAN_CHECK_SERVICES, false)
    }

    private fun shouldIgnoreEndpointForServer(guid: String): Boolean {
        val profile = ServerListStore.getCachedProfile(guid)
            ?: MmkvManager.decodeServerConfig(guid)
            ?: return false
        if (!PersonalServersConfig.isPersonalServer(profile.subscriptionId)) return false
        return PersonalServersManager.getConfig().ignoreExitNodeForPersonal
    }

    private fun buildFullScanOptionsHeader(
        tcpConcurrency: Int,
        verifyConcurrency: Int,
        checkServices: Boolean
    ): String {
        val destUrl = SettingsManager.getDelayTestUrl()
            .trim()
            .takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: AppConfig.DELAY_TEST_URL

        val servicesArray = JSONArray()
        if (checkServices) {
            for (svc in ServiceCheckManager.loadServices()) {
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
        }

        // Важно: конечный узел (E2E) проверяем через реальный Xray в verifyEndpointViaXray(),
        // а не через облегчённый net.Pipe в v2w-core, где сложные транспорты/Vision ложно падают.
        val root = JSONObject().apply {
            put("tcp_concurrency", tcpConcurrency)
            put("verify_concurrency", verifyConcurrency)
            put("timeout_sec", 7)
            put("frag_timeout_sec", 4)
            put("dest_url", destUrl)
            put("endpoint_url", "")
            put("endpoint_e2e", false)
            put("service_check", checkServices)
            put("services", servicesArray)
        }
        return "#v2w-opts:${root}"
    }

    private fun buildPayloadAndLookup(
        guids: List<String>,
        tcpConcurrency: Int,
        verifyConcurrency: Int,
        checkServices: Boolean
    ): Triple<String, Map<String, String>, Map<String, String>> {
        val sb = StringBuilder()
        sb.append(buildFullScanOptionsHeader(tcpConcurrency, verifyConcurrency, checkServices)).append("\n")

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
        val cacheKey = ServerListStore.getCachedFingerprint(guid) ?: guid
        MmkvManager.encodeServerInspectionByKey(cacheKey, info)
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
            val cacheKey = ServerListStore.getCachedFingerprint(guid) ?: guid
            MmkvManager.encodeServerInspectionByKey(cacheKey, info)
            MmkvManager.encodeServerTestDelayMillis(guid, if (info.alive) info.delayMs else -1L)
        }
        val fragKeys = pendingFragUpdates.keys.toList()
        for (guid in fragKeys) {
            val mode = pendingFragUpdates.remove(guid) ?: continue
            applyFragmentTagIfNeeded(guid, mode)
        }
    }

    private suspend fun verifyEndpointViaXray(context: Context, guid: String): Pair<Boolean, Long> {
        var chainedController: CoreController? = null
        var chainedPort = 0
        return try {
            val profile = ServerListStore.getCachedProfile(guid) ?: MmkvManager.decodeServerConfig(guid)
            val isHysteria = profile?.configType == EConfigType.HYSTERIA2
            chainedPort = ServerSocket(0).use { it.localPort }
            com.kiktor.v2whitelist.core.domain.security.LoopbackProxyAuthenticator.allowPort(chainedPort)
            val chainedConfig = V2rayConfigManager.getV2rayConfig4Speedtest(
                context,
                guid,
                chainedPort,
                ignoreCustomEndpoint = false
            )
            if (!chainedConfig.status) {
                return false to -1L
            }

            chainedController = V2RayNativeManager.newCoreController(object : CoreCallbackHandler {
                override fun startup(): Long = 0
                override fun shutdown(): Long = 0
                override fun onEmitStatus(p0: Long, p1: String?): Long = 0
            })
            chainedController.startLoop(chainedConfig.content, 0)
            delay(if (isHysteria) 750L else 450L)

            val (elapsed, _) = SpeedtestManager.testConnection(context, chainedPort, 7000)
            (elapsed > 0L) to elapsed
        } catch (e: Exception) {
            GeekModeLogger.log("FullScan", "Xray endpoint E2E check error for $guid: ${e.message}")
            false to -1L
        } finally {
            if (chainedPort > 0) {
                com.kiktor.v2whitelist.core.domain.security.LoopbackProxyAuthenticator.disallowPort(chainedPort)
            }
            try {
                chainedController?.stopLoop()
            } catch (_: Exception) {}
        }
    }

    fun startFullScan(context: Context, targetGuids: List<String>? = null): Boolean {
        val mode = FullScanMode.FULL
        return startScanInternal(
            context = context,
            targetGuids = targetGuids,
            mode = mode,
            stopAfterAliveCount = 0
        )
    }

    fun pingVipNative(context: Context): Boolean {
        val vipGuids = MmkvManager.getVipCache()
        if (vipGuids.isEmpty()) return false
        return startScanInternal(
            context = context,
            targetGuids = vipGuids,
            mode = FullScanMode.VIP_PING,
            stopAfterAliveCount = 0
        )
    }

    fun findVipNative(context: Context): Boolean {
        val allServers = MmkvManager.decodeServerList()
        val currentVipSet = MmkvManager.getVipCache().toSet()
        val filtered = SmartConnectManager.filterServers(allServers, null)
            .map { it.first }
            .filter { it !in currentVipSet }
            .shuffled()
        if (filtered.isEmpty()) return false

        val vipLimit = MmkvManager.getVipCacheLimit()
        val needed = (vipLimit - currentVipSet.size).coerceAtLeast(3)
        return startScanInternal(
            context = context,
            targetGuids = filtered,
            mode = FullScanMode.VIP_FIND,
            stopAfterAliveCount = needed
        )
    }

    private fun startScanInternal(
        context: Context,
        targetGuids: List<String>?,
        mode: FullScanMode,
        stopAfterAliveCount: Int
    ): Boolean {
        if (_state.value.isScanning || SmartConnectManager.isScanning.get()) {
            return false
        }

        val sessionId = sessionSeq.incrementAndGet()
        val startedAt = System.currentTimeMillis()
        activeStartedAtMs.set(startedAt)

        activeEndpointJobs.forEach { it.cancel() }
        activeEndpointJobs.clear()

        _state.update {
            it.copy(
                isScanning = true,
                mode = mode,
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

            PersonalServersManager.sanitizeExitNode()
            val endpointEffective = isEndpointCheckActive()
            val checkServices = isServiceCheckEnabledForScan(endpointEffective)

            ServerListStore.ensureLoaded(appContext)
            ServerListStore.reloadBaseIfNeeded(appContext)

            val rawGuids = targetGuids ?: MmkvManager.decodeServerList()
            pendingMmkvWrites.clear()
            pendingFragUpdates.clear()

            val guids: List<String> = when (mode) {
                FullScanMode.FULL -> {
                    ServerListStore.clearInspectionsAndPingsInMemory()
                    MmkvManager.clearAllServerInspections()
                    MmkvManager.clearAllTestDelayResults(rawGuids)
                    val vipSet = MmkvManager.getVipCache().toSet()
                    val vipGuids = rawGuids.filter { it in vipSet }
                    // Перемешиваем обычные сервера, чтобы пачки зависающих нод одной подписки
                    // не забивали одновременно все verify-воркеры.
                    val regularShuffled = rawGuids.filterNot { it in vipSet }.shuffled()
                    vipGuids + regularShuffled
                }
                FullScanMode.VIP_PING -> {
                    ServerListStore.clearInspectionsAndPingsForGuids(rawGuids.toSet())
                    val keysToRemove = LinkedHashSet<String>(rawGuids.size * 2)
                    for (g in rawGuids) {
                        ServerListStore.getCachedFingerprint(g)?.let { keysToRemove.add(it) }
                        keysToRemove.add(g)
                    }
                    MmkvManager.removeServerInspectionByKeys(keysToRemove)
                    MmkvManager.clearAllTestDelayResults(rawGuids)
                    rawGuids
                }
                FullScanMode.VIP_FIND -> rawGuids
            }

            val tcpConcurrency = MmkvManager.getV2wTcpConcurrency()
            val verifyConcurrency = MmkvManager.getV2wCoreConcurrency()
            val (payload, exactMap, fallbackMap) = buildPayloadAndLookup(
                guids = guids,
                tcpConcurrency = tcpConcurrency,
                verifyConcurrency = verifyConcurrency,
                checkServices = checkServices
            )

            val totalCount = exactMap.size
            if (totalCount == 0) {
                if (mode == FullScanMode.FULL) {
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
                }
                _state.update {
                    it.copy(
                        isScanning = false,
                        mode = mode,
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
                    mode = mode,
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
            val nowInit = System.currentTimeMillis()
            lastProgressEmitAtMs.set(nowInit)
            lastListBumpAtMs.set(nowInit)

            val completedCounter = AtomicInteger(0)
            val aliveCounter = AtomicInteger(0)
            val failedCounter = AtomicInteger(0)
            val vipAddedCounter = AtomicInteger(0)
            val processedGuids = ConcurrentHashMap.newKeySet<String>()

            GeekModeLogger.log(
                "FullScan",
                "Starting $mode scan: total=$totalCount, tcp=$tcpConcurrency, verify=$verifyConcurrency, services=$checkServices, endpoint=$endpointEffective"
            )

            flushLoopJob?.cancel()
            flushLoopJob = scope.launch {
                while (isActive && sessionSeq.get() == sessionId) {
                    delay(1200L)
                    flushPendingToMmkv()
                }
            }

            fun checkAndStopEarlyIfVipTargetReached() {
                if (mode == FullScanMode.VIP_FIND && stopAfterAliveCount > 0 && vipAddedCounter.get() >= stopAfterAliveCount) {
                    try {
                        Libv2ray.stopV2WFullScan()
                    } catch (_: Exception) {}
                }
            }

            val callback = object : V2WFullScanCallback {
                override fun onServerResult(resultJson: String?) {
                    if (sessionSeq.get() != sessionId) return
                    val dto = FullScanNodeResultDto.fromJson(resultJson) ?: return
                    val guid = resolveGuid(dto.link, exactMap, fallbackMap) ?: return
                    if (!processedGuids.add(guid)) return

                    // Пока E2E через Xray не завершён, не ставим ложный "не прошёл"
                    val baseInfo = dto.toInspectionInfo(testedAtMs = System.currentTimeMillis()).let { rawInfo ->
                        if (endpointEffective) rawInfo.copy(endpointPassed = null) else rawInfo
                    }

                    ServerListStore.putInspectionInMemory(guid, baseInfo)
                    pendingMmkvWrites[guid] = baseInfo
                    if (baseInfo.alive && (baseInfo.fragMode == "tcp" || baseInfo.fragMode == "record")) {
                        pendingFragUpdates[guid] = baseInfo.fragMode
                    }

                    if (baseInfo.alive) {
                        if (endpointEffective && !shouldIgnoreEndpointForServer(guid)) {
                            val epJob = scope.launch {
                                endpointSemaphore.withPermit {
                                    if (sessionSeq.get() != sessionId || !isActive) return@withPermit
                                    val (passed, e2eDelay) = verifyEndpointViaXray(appContext, guid)
                                    if (sessionSeq.get() != sessionId) return@withPermit

                                    val updated = baseInfo.copy(
                                        endpointPassed = passed,
                                        delayMs = if (passed && e2eDelay > 0L) e2eDelay else baseInfo.delayMs,
                                        testedAtMs = System.currentTimeMillis()
                                    )
                                    ServerListStore.putInspectionInMemory(guid, updated)
                                    pendingMmkvWrites[guid] = updated
                                    if (mode == FullScanMode.VIP_FIND && passed) {
                                        MmkvManager.addVipServer(guid)
                                        ServerListStore.refreshVipFromMmkv()
                                        vipAddedCounter.incrementAndGet()
                                        checkAndStopEarlyIfVipTargetReached()
                                    } else {
                                        ServerListStore.bumpRevision()
                                    }
                                    _state.update { it.copy(revision = it.revision + 1) }
                                }
                            }
                            activeEndpointJobs.add(epJob)
                        } else if (mode == FullScanMode.VIP_FIND) {
                            MmkvManager.addVipServer(guid)
                            ServerListStore.refreshVipFromMmkv()
                            vipAddedCounter.incrementAndGet()
                            checkAndStopEarlyIfVipTargetReached()
                        }
                    }

                    val done = completedCounter.incrementAndGet()
                    val alive = if (baseInfo.alive) aliveCounter.incrementAndGet() else aliveCounter.get()
                    val failed = if (!baseInfo.alive) failedCounter.incrementAndGet() else failedCounter.get()

                    val now = System.currentTimeMillis()
                    // Лёгкое обновление счётчиков прогресс-бара (без пересортировки списка)
                    val prevProg = lastProgressEmitAtMs.get()
                    if (now - prevProg >= 180L && lastProgressEmitAtMs.compareAndSet(prevProg, now)) {
                        _state.update {
                            it.copy(
                                completedCount = done,
                                aliveCount = alive,
                                failedCount = failed,
                                revision = it.revision + 1
                            )
                        }
                    }

                    // Пересчёт и сортировка списка: первые 12 живых — быстро (150мс), далее — раз в 400мс для снижения GC
                    val listBumpMinMs = if (baseInfo.alive && alive <= 12) 150L else 400L
                    val prevBump = lastListBumpAtMs.get()
                    if (now - prevBump >= listBumpMinMs && lastListBumpAtMs.compareAndSet(prevBump, now)) {
                        ServerListStore.bumpRevision()
                    }
                }

                override fun onScanComplete(totalSuccess: Long, totalFailed: Long) {
                    if (sessionSeq.get() != sessionId) return
                    scope.launch {
                        val pendingEp = activeEndpointJobs.toList()
                        if (pendingEp.isNotEmpty()) {
                            pendingEp.joinAll()
                        }
                        if (sessionSeq.get() != sessionId) return@launch

                        flushLoopJob?.cancel()
                        flushPendingToMmkv()

                        val finalAlive = aliveCounter.get()
                        val finalFailed = failedCounter.get()
                        val finalDone = completedCounter.get()

                        if (mode == FullScanMode.FULL) {
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
                        }
                        GeekModeLogger.log(
                            "FullScan",
                            "Scan ($mode) complete: scanned=$finalDone, alive=$finalAlive, failed=$finalFailed"
                        )
                        ServerListStore.bumpRevision()
                        _state.update {
                            it.copy(
                                isScanning = false,
                                mode = mode,
                                completedCount = finalDone,
                                aliveCount = finalAlive,
                                failedCount = finalFailed,
                                revision = it.revision + 1
                            )
                        }
                    }
                }
            }

            try {
                Libv2ray.runV2WFullScan(payload, verifyConcurrency.toLong(), callback)
            } catch (e: Exception) {
                flushLoopJob?.cancel()
                activeEndpointJobs.forEach { it.cancel() }
                activeEndpointJobs.clear()
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
        activeEndpointJobs.forEach { it.cancel() }
        activeEndpointJobs.clear()
        try {
            Libv2ray.stopV2WFullScan()
        } catch (_: Exception) {}
        flushPendingToMmkv()

        val cur = _state.value
        if (cur.mode == FullScanMode.FULL && cur.completedCount > 0) {
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
        GeekModeLogger.log("FullScan", "Scan (${cur.mode}) stopped by user (${cur.completedCount}/${cur.totalCount})")
        ServerListStore.bumpRevision()
        _state.update {
            it.copy(isScanning = false, revision = it.revision + 1)
        }
    }

    suspend fun inspectSingleServer(context: Context, guid: String): ServerInspectionInfo? = withContext(Dispatchers.IO) {
        if (_state.value.isScanning || SmartConnectManager.isScanning.get()) {
            return@withContext null
        }

        val appContext = context.applicationContext
        PersonalServersManager.sanitizeExitNode()
        val endpointEffective = isEndpointCheckActive()
        val checkServices = isServiceCheckEnabledForScan(endpointEffective)

        val (payload, exactMap, _) = buildPayloadAndLookup(
            guids = listOf(guid),
            tcpConcurrency = 1,
            verifyConcurrency = 1,
            checkServices = checkServices
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
                    val raw = dto.toInspectionInfo(testedAtMs = System.currentTimeMillis())
                    val sanitized = if (endpointEffective) raw.copy(endpointPassed = null) else raw
                    resultRef.compareAndSet(null, sanitized)
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

        var info = resultRef.get()
        if (info != null) {
            if (info.alive && endpointEffective && !shouldIgnoreEndpointForServer(guid)) {
                val (passed, e2eDelay) = verifyEndpointViaXray(appContext, guid)
                info = info.copy(
                    endpointPassed = passed,
                    delayMs = if (passed && e2eDelay > 0L) e2eDelay else info.delayMs,
                    testedAtMs = System.currentTimeMillis()
                )
            }
            applyInspectionResultSync(guid, info)
            _state.update { it.copy(revision = it.revision + 1) }
        }
        return@withContext info
    }
}
