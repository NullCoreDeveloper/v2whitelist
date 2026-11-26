package com.kiktor.v2whitelist.desktop.engine

import com.kiktor.v2whitelist.core.config.UniversalConfigParser
import com.kiktor.v2whitelist.core.domain.inspection.ServerFingerprint
import com.kiktor.v2whitelist.core.domain.inspection.ServerInspectionCachePolicy
import com.kiktor.v2whitelist.core.domain.inspection.ServerListEngine
import com.kiktor.v2whitelist.core.domain.inspection.ServerListEntry
import com.kiktor.v2whitelist.core.domain.inspection.ServerListFilter
import com.kiktor.v2whitelist.core.domain.inspection.ServerListStats
import com.kiktor.v2whitelist.core.domain.inspection.ServerListViewResult
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.PersonalServersConfig
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.inspection.ScanNetworkCondition
import com.kiktor.v2whitelist.core.model.inspection.ScanSessionMeta
import com.kiktor.v2whitelist.core.model.inspection.ServerInspectionInfo
import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckItem
import com.kiktor.v2whitelist.core.repository.ServerRepository
import com.kiktor.v2whitelist.core.repository.SettingsRepository
import com.kiktor.v2whitelist.core.subscription.defaults.DefaultSubscriptions
import com.kiktor.v2whitelist.core.subscription.model.CustomSubData
import com.kiktor.v2whitelist.core.subscription.tag.ServerTagResolver
import com.kiktor.v2whitelist.desktop.data.DesktopInspectionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class DesktopFullScanMode {
    FULL,
    VIP_PING,
    VIP_FIND
}

data class DesktopFullScanUiState(
    val isScanning: Boolean = false,
    val mode: DesktopFullScanMode = DesktopFullScanMode.FULL,
    val totalCount: Int = 0,
    val completedCount: Int = 0,
    val aliveCount: Int = 0,
    val failedCount: Int = 0,
    val networkCondition: ScanNetworkCondition = ScanNetworkCondition.UNKNOWN,
    val networkCheckEnabled: Boolean = true,
    val endpointEnabled: Boolean = false,
    val revision: Long = 0L
)

sealed class DesktopSpeedTestState {
    data object Idle : DesktopSpeedTestState()
    data object Testing : DesktopSpeedTestState()
    data class Success(val mbps: Double) : DesktopSpeedTestState()
    data object Failed : DesktopSpeedTestState()
}

data class DesktopSubscriptionFilterOption(
    val id: String,
    val name: String,
    val count: Int
)

data class DesktopServerListUiState(
    val viewResult: ServerListViewResult = ServerListViewResult(
        vipServers = emptyList(),
        regularServers = emptyList(),
        availableCountryCounts = emptyMap(),
        availableServices = emptyList(),
        stats = ServerListStats(0, 0, 0, 0, 0, 0, 0)
    ),
    val filter: ServerListFilter = ServerListFilter(),
    val scanState: DesktopFullScanUiState = DesktopFullScanUiState(),
    val lastScanMeta: ScanSessionMeta? = null,
    val networkCheckEnabled: Boolean = true,
    val endpointEnabled: Boolean = false,
    val availableSubscriptions: List<DesktopSubscriptionFilterOption> = emptyList(),
    val availableProtocols: List<EConfigType> = emptyList(),
    val connectedGuid: String? = null,
    val isVpnRunning: Boolean = false,
    val speedTestStates: Map<String, DesktopSpeedTestState> = emptyMap(),
    val reinspectingGuids: Set<String> = emptySet(),
    val isSmartConnectScanning: Boolean = false,
    val scanCheckServicesEnabled: Boolean = false,
    val fallbackFlags: Map<String, String> = emptyMap()
)

@OptIn(FlowPreview::class)
class DesktopFullScanManager(
    private val serverRepo: ServerRepository,
    private val settingsRepo: SettingsRepository,
    private val inspectionStore: DesktopInspectionStore,
    private val subscriptionsFlow: StateFlow<List<CustomSubData>>,
    private val vipCandidateServersProvider: () -> List<Pair<String, ProfileItem>>,
    private val networkStatusProvider: () -> Int,
    private val networkCheckEnabledProvider: () -> Boolean,
    private val useCustomEndpointProvider: () -> Boolean,
    private val customEndpointUrlProvider: () -> String,
    private val personalIgnoreExitNodeProvider: () -> Boolean,
    private val v2wScanPathProvider: () -> String?,
    private val xrayBinaryPathProvider: () -> String?,
    private val verifyConcurrencyProvider: () -> Int,
    private val tcpConcurrencyProvider: () -> Int,
    private val destUrlProvider: () -> String?,
    private val servicesProvider: () -> List<ServiceCheckItem>,
    private val vipCacheLimitProvider: () -> Int,
    private val isSmartConnectRunningProvider: () -> Boolean,
    private val isSmartConnectRunningFlow: StateFlow<Boolean>,
    private val selectedGuidFlow: StateFlow<String?>,
    private val coreStateFlow: StateFlow<DesktopCoreState>,
    private val activeSocksPortProvider: () -> Int?,
    private val scope: CoroutineScope
) {
    companion object {
        const val PREF_FULL_SCAN_CHECK_SERVICES = "pref_full_scan_check_services"
    }

    private data class CachedBaseNode(
        val guid: String,
        val fingerprint: String,
        val profile: ProfileItem,
        val remarksLower: String,
        val subscriptionId: String,
        val subscriptionName: String,
        val isPersonal: Boolean,
        val fallbackFlagEmoji: String
    )

    private data class ComputedListView(
        val viewResult: ServerListViewResult = ServerListViewResult(
            vipServers = emptyList(),
            regularServers = emptyList(),
            availableCountryCounts = emptyMap(),
            availableServices = emptyList(),
            stats = ServerListStats(0, 0, 0, 0, 0, 0, 0)
        ),
        val filter: ServerListFilter = ServerListFilter(),
        val lastScanMeta: ScanSessionMeta? = null,
        val networkCheckEnabled: Boolean = true,
        val endpointEnabled: Boolean = false,
        val availableSubscriptions: List<DesktopSubscriptionFilterOption> = emptyList(),
        val availableProtocols: List<EConfigType> = emptyList(),
        val fallbackFlags: Map<String, String> = emptyMap(),
        val connectedGuid: String? = null,
        val isVpnRunning: Boolean = false
    )

    private val _scanState = MutableStateFlow(DesktopFullScanUiState())
    val scanState: StateFlow<DesktopFullScanUiState> = _scanState.asStateFlow()

    private val baseFilterFlow = MutableStateFlow(ServerListFilter())
    private val searchQueryFlow = MutableStateFlow("")
    private val effectiveFilterFlow = MutableStateFlow(ServerListFilter())

    private val speedTestStatesFlow = MutableStateFlow<Map<String, DesktopSpeedTestState>>(emptyMap())
    private val reinspectingGuidsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val _scanCheckServicesEnabled = MutableStateFlow(
        settingsRepo.getBoolean(PREF_FULL_SCAN_CHECK_SERVICES, false)
    )
    val scanCheckServicesEnabled: StateFlow<Boolean> = _scanCheckServicesEnabled.asStateFlow()

    private val listRevisionFlow = MutableStateFlow(0L)
    private val configTickFlow = MutableStateFlow(0L)

    private val _uiState = MutableStateFlow(
        DesktopServerListUiState(scanCheckServicesEnabled = _scanCheckServicesEnabled.value)
    )
    val uiState: StateFlow<DesktopServerListUiState> = _uiState.asStateFlow()

    private val baseCacheMutex = Mutex()
    @Volatile
    private var cachedRawServers: List<Pair<String, ProfileItem>> = emptyList()
    @Volatile
    private var cachedRawSubs: List<CustomSubData> = emptyList()
    @Volatile
    private var cachedNodes: List<CachedBaseNode> = emptyList()
    @Volatile
    private var cachedAvailableSubs: List<DesktopSubscriptionFilterOption> = emptyList()
    @Volatile
    private var cachedAvailableProtocols: List<EConfigType> = emptyList()
    @Volatile
    private var cachedFallbackFlags: Map<String, String> = emptyMap()
    private val cachedByGuid = ConcurrentHashMap<String, CachedBaseNode>()

    private val sessionSeq = AtomicLong(0L)
    private val activeStartedAtMs = AtomicLong(0L)
    private val lastProgressEmitAtMs = AtomicLong(0L)
    private val lastListBumpAtMs = AtomicLong(0L)
    private val activeEndpointJobs = CopyOnWriteArrayList<Job>()
    private val endpointSemaphore = Semaphore(4)

    @Volatile
    private var activeScanJob: Job? = null
    @Volatile
    private var flushLoopJob: Job? = null

    init {
        scope.launch(Dispatchers.Default) {
            combine(
                baseFilterFlow,
                searchQueryFlow.debounce(180L).distinctUntilChanged()
            ) { base, query ->
                base.copy(searchQuery = query)
            }.collect { combined ->
                effectiveFilterFlow.value = combined
            }
        }

        val computedFlow = MutableStateFlow(ComputedListView())

        // Слой 1: тяжёлая фильтрация и сортировка вынесены в Dispatchers.Default и изолированы от прогресс-бара
        scope.launch(Dispatchers.Default) {
            val baseInputs = combine(
                serverRepo.serversFlow,
                serverRepo.vipGuidsFlow,
                subscriptionsFlow,
                listRevisionFlow
            ) { srv, vips, subs, _ ->
                Triple(srv, vips, subs)
            }
            val controlInputs = combine(
                effectiveFilterFlow,
                configTickFlow,
                selectedGuidFlow,
                coreStateFlow
            ) { filter, _, selectedGuid, coreState ->
                Triple(filter, selectedGuid, coreState)
            }

            combine(baseInputs, controlInputs) { (srv, vips, subs), (filter, selectedGuid, coreState) ->
                PayloadForEngine(srv, vips, subs, filter, selectedGuid, coreState)
            }.collectLatest { payload ->
                rebuildBaseCacheIfNeeded(payload.servers, payload.subs)

                val nodes = cachedNodes
                val nowMs = System.currentTimeMillis()
                val entries = ArrayList<ServerListEntry>(nodes.size)
                for (node in nodes) {
                    val latestNode = cachedByGuid[node.guid] ?: node
                    val insp = inspectionStore.getByFingerprint(latestNode.fingerprint, nowMs)
                    val fallbackPing = if (insp == null) 0L else if (insp.alive) insp.delayMs else -1L
                    entries.add(
                        ServerListEntry(
                            guid = latestNode.guid,
                            profile = latestNode.profile,
                            subscriptionId = latestNode.subscriptionId,
                            subscriptionName = latestNode.subscriptionName,
                            isVip = latestNode.guid in payload.vips,
                            isPersonal = latestNode.isPersonal,
                            fallbackPingMs = fallbackPing,
                            inspection = insp,
                            remarksLower = latestNode.remarksLower
                        )
                    )
                }

                val endpointEnabled = isEndpointCheckActive()
                val viewResult = ServerListEngine.buildView(
                    entries = entries,
                    filter = payload.filter,
                    endpointEnabled = endpointEnabled,
                    removedDuplicatesCount = inspectionStore.getLastRemovedDuplicates()
                )
                val isRunning = payload.coreState == DesktopCoreState.RUNNING
                val connectedGuid = if (isRunning) payload.selectedGuid else null

                computedFlow.value = ComputedListView(
                    viewResult = viewResult,
                    filter = payload.filter,
                    lastScanMeta = inspectionStore.getLastMeta(),
                    networkCheckEnabled = networkCheckEnabledProvider(),
                    endpointEnabled = endpointEnabled,
                    availableSubscriptions = cachedAvailableSubs,
                    availableProtocols = cachedAvailableProtocols,
                    fallbackFlags = cachedFallbackFlags,
                    connectedGuid = connectedGuid,
                    isVpnRunning = isRunning
                )
            }
        }

        // Слой 2: лёгкое объединение с прогрессом сканирования и карточными действиями
        scope.launch(Dispatchers.Default) {
            val overlays = combine(
                speedTestStatesFlow,
                reinspectingGuidsFlow,
                isSmartConnectRunningFlow,
                _scanCheckServicesEnabled
            ) { speeds, reinspecting, smartRunning, scanServices ->
                OverlayBundle(speeds, reinspecting, smartRunning, scanServices)
            }

            combine(computedFlow, _scanState, overlays) { computed, scan, overlay ->
                DesktopServerListUiState(
                    viewResult = computed.viewResult,
                    filter = computed.filter,
                    scanState = scan,
                    lastScanMeta = computed.lastScanMeta,
                    networkCheckEnabled = computed.networkCheckEnabled,
                    endpointEnabled = computed.endpointEnabled,
                    availableSubscriptions = computed.availableSubscriptions,
                    availableProtocols = computed.availableProtocols,
                    connectedGuid = computed.connectedGuid,
                    isVpnRunning = computed.isVpnRunning,
                    speedTestStates = overlay.speeds,
                    reinspectingGuids = overlay.reinspecting,
                    isSmartConnectScanning = overlay.smartRunning,
                    scanCheckServicesEnabled = overlay.scanServices,
                    fallbackFlags = computed.fallbackFlags
                )
            }.collectLatest { state ->
                _uiState.value = state
            }
        }
    }

    private data class PayloadForEngine(
        val servers: List<Pair<String, ProfileItem>>,
        val vips: Set<String>,
        val subs: List<CustomSubData>,
        val filter: ServerListFilter,
        val selectedGuid: String?,
        val coreState: DesktopCoreState
    )

    private data class OverlayBundle(
        val speeds: Map<String, DesktopSpeedTestState>,
        val reinspecting: Set<String>,
        val smartRunning: Boolean,
        val scanServices: Boolean
    )

    private suspend fun rebuildBaseCacheIfNeeded(
        rawServers: List<Pair<String, ProfileItem>>,
        rawSubs: List<CustomSubData>
    ) {
        if (rawServers === cachedRawServers && rawSubs === cachedRawSubs) return
        baseCacheMutex.withLock {
            if (rawServers === cachedRawServers && rawSubs === cachedRawSubs) return

            val subNames = buildSubscriptionNameMap(rawSubs)
            val newNodes = ArrayList<CachedBaseNode>(rawServers.size)
            val newFlags = HashMap<String, String>(rawServers.size)
            val subCountMap = LinkedHashMap<String, Pair<String, Int>>()
            val protocolSet = LinkedHashSet<EConfigType>()

            cachedByGuid.clear()
            for ((guid, profile) in rawServers) {
                val isPersonal = PersonalServersConfig.isPersonalServer(profile.subscriptionId)
                val normSubId = when {
                    isPersonal -> "personal"
                    profile.subscriptionId.isBlank() -> "manual"
                    else -> profile.subscriptionId
                }
                val subName = when {
                    isPersonal -> "Доверенные серверы"
                    profile.subscriptionId.isBlank() -> "Вручную"
                    else -> subNames[profile.subscriptionId]
                        ?: subNames[profile.subscriptionId.removePrefix("custom_sub_")]
                        ?: profile.subscriptionId
                }

                val prevCount = subCountMap[normSubId]?.second ?: 0
                subCountMap[normSubId] = subName to (prevCount + 1)
                protocolSet.add(profile.configType)

                val fallbackFlag = ServerTagResolver.extractFirstFlagEmoji(profile.remarks).orEmpty()
                val node = CachedBaseNode(
                    guid = guid,
                    fingerprint = ServerFingerprint.compute(profile),
                    profile = profile,
                    remarksLower = profile.remarks.lowercase(),
                    subscriptionId = normSubId,
                    subscriptionName = subName,
                    isPersonal = isPersonal,
                    fallbackFlagEmoji = fallbackFlag
                )
                newNodes.add(node)
                cachedByGuid[guid] = node
                if (fallbackFlag.isNotEmpty()) {
                    newFlags[guid] = fallbackFlag
                }
            }

            cachedNodes = newNodes
            cachedFallbackFlags = newFlags
            cachedAvailableSubs = subCountMap.entries
                .map { (id, pair) -> DesktopSubscriptionFilterOption(id = id, name = pair.first, count = pair.second) }
                .sortedByDescending { it.count }
            cachedAvailableProtocols = protocolSet.sortedBy { it.name }
            cachedRawServers = rawServers
            cachedRawSubs = rawSubs
        }
    }

    private fun buildSubscriptionNameMap(customSubs: List<CustomSubData>): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (def in DefaultSubscriptions.PREPOPULATED_SUBS) {
            if (def.name.isNotBlank()) {
                map["custom_sub_${def.id}"] = def.name
                map[def.id] = def.name
            }
        }
        for (sub in customSubs) {
            if (sub.name.isNotBlank()) {
                map["custom_sub_${sub.id}"] = sub.name
                map[sub.id] = sub.name
            }
        }
        return map
    }

    fun isEndpointCheckActive(): Boolean {
        if (!useCustomEndpointProvider()) return false
        return customEndpointUrlProvider().trim().isNotEmpty()
    }

    fun notifyConfigChanged() {
        configTickFlow.update { it + 1 }
    }

    fun setSearchQuery(query: String) {
        if (searchQueryFlow.value == query) return
        searchQueryFlow.value = query
    }

    fun updateFilter(filter: ServerListFilter) {
        baseFilterFlow.value = filter
    }

    fun resetFilters() {
        searchQueryFlow.value = ""
        baseFilterFlow.value = ServerListFilter()
        effectiveFilterFlow.value = ServerListFilter()
    }

    fun setScanCheckServicesEnabled(enabled: Boolean) {
        if (_scanCheckServicesEnabled.value == enabled) return
        _scanCheckServicesEnabled.value = enabled
        settingsRepo.setBoolean(PREF_FULL_SCAN_CHECK_SERVICES, enabled)
    }

    fun startFullScan(targetGuids: List<String>? = null): Boolean {
        return startScanInternal(
            targetGuids = targetGuids,
            mode = DesktopFullScanMode.FULL,
            stopAfterAliveCount = 0
        )
    }

    fun pingVipNative(): Boolean {
        val vips = serverRepo.getVipServerGuids().toList()
        if (vips.isEmpty()) {
            GeekModeLogger.log("FullScan", "Список VIP пуст — нечего пинговать.")
            return false
        }
        return startScanInternal(
            targetGuids = vips,
            mode = DesktopFullScanMode.VIP_PING,
            stopAfterAliveCount = 0
        )
    }

    fun findVipNative(): Boolean {
        val allCandidates = vipCandidateServersProvider()
        val currentVipSet = serverRepo.getVipServerGuids()
        val filtered = allCandidates
            .filter { it.first !in currentVipSet }
            .map { it.first }
            .shuffled()
        if (filtered.isEmpty()) {
            GeekModeLogger.log("FullScan", "Нет подходящих серверов для пополнения VIP.")
            return false
        }
        val vipLimit = vipCacheLimitProvider().coerceAtLeast(1)
        val needed = (vipLimit - currentVipSet.size).coerceAtLeast(3)
        return startScanInternal(
            targetGuids = filtered,
            mode = DesktopFullScanMode.VIP_FIND,
            stopAfterAliveCount = needed
        )
    }

    private fun startScanInternal(
        targetGuids: List<String>?,
        mode: DesktopFullScanMode,
        stopAfterAliveCount: Int
    ): Boolean {
        if (_scanState.value.isScanning || isSmartConnectRunningProvider()) {
            return false
        }

        val sessionId = sessionSeq.incrementAndGet()
        val startedAt = System.currentTimeMillis()
        activeStartedAtMs.set(startedAt)

        activeEndpointJobs.forEach { it.cancel() }
        activeEndpointJobs.clear()
        flushLoopJob?.cancel()
        activeScanJob?.cancel()

        _scanState.update {
            it.copy(
                isScanning = true,
                mode = mode,
                totalCount = 0,
                completedCount = 0,
                aliveCount = 0,
                failedCount = 0,
                revision = it.revision + 1
            )
        }

        activeScanJob = scope.launch(Dispatchers.IO) {
            if (sessionSeq.get() != sessionId) return@launch

            val allServerPairs = serverRepo.serversFlow.value
            rebuildBaseCacheIfNeeded(allServerPairs, subscriptionsFlow.value)

            val networkCheckEnabled = networkCheckEnabledProvider()
            val networkCondition = ScanNetworkCondition.fromInternetStatus(
                status = networkStatusProvider(),
                networkCheckEnabled = networkCheckEnabled
            )

            val customEpUrl = customEndpointUrlProvider().trim()
            val endpointEffective = useCustomEndpointProvider() && customEpUrl.isNotEmpty()
            val checkServices = if (endpointEffective) false else _scanCheckServicesEnabled.value

            val byGuid = allServerPairs.associateBy({ it.first }, { it.second })
            val rawPairs: List<Pair<String, ProfileItem>> = if (targetGuids != null) {
                targetGuids.mapNotNull { g -> byGuid[g]?.let { g to it } }
            } else {
                allServerPairs
            }

            val orderedPairs: List<Pair<String, ProfileItem>> = when (mode) {
                DesktopFullScanMode.FULL -> {
                    val fps = rawPairs.map { cachedByGuid[it.first]?.fingerprint ?: ServerFingerprint.compute(it.second) }
                    if (targetGuids == null) {
                        inspectionStore.clearAllInspections()
                    } else {
                        inspectionStore.removeByFingerprints(fps)
                    }
                    listRevisionFlow.update { it + 1 }

                    val vipSet = serverRepo.getVipServerGuids()
                    val vips = rawPairs.filter { it.first in vipSet }
                    val regularShuffled = rawPairs.filterNot { it.first in vipSet }.shuffled()
                    vips + regularShuffled
                }
                DesktopFullScanMode.VIP_PING -> {
                    val fps = rawPairs.map { cachedByGuid[it.first]?.fingerprint ?: ServerFingerprint.compute(it.second) }
                    inspectionStore.removeByFingerprints(fps)
                    listRevisionFlow.update { it + 1 }
                    rawPairs
                }
                DesktopFullScanMode.VIP_FIND -> rawPairs
            }

            val totalCount = orderedPairs.size
            if (totalCount == 0) {
                if (mode == DesktopFullScanMode.FULL) {
                    inspectionStore.setLastMeta(
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
                    inspectionStore.flushToDisk()
                }
                _scanState.update {
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
                listRevisionFlow.update { it + 1 }
                return@launch
            }

            val nowInit = System.currentTimeMillis()
            lastProgressEmitAtMs.set(nowInit)
            lastListBumpAtMs.set(nowInit)
            _scanState.update {
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

            val tcpConcurrency = tcpConcurrencyProvider().coerceIn(1, 200)
            val verifyConcurrency = verifyConcurrencyProvider().coerceIn(1, 100)
            val destUrl = destUrlProvider()
            val v2wPath = v2wScanPathProvider()
            val xrayPath = xrayBinaryPathProvider()
            val vipLimit = vipCacheLimitProvider().coerceAtLeast(1)

            GeekModeLogger.log(
                "FullScan",
                "Запуск $mode скана: total=$totalCount, tcp=$tcpConcurrency, verify=$verifyConcurrency, services=$checkServices, endpoint=$endpointEffective"
            )

            flushLoopJob = scope.launch(Dispatchers.IO) {
                while (isActive && sessionSeq.get() == sessionId) {
                    delay(1500L)
                    inspectionStore.flushToDisk()
                }
            }

            val completedCounter = AtomicInteger(0)
            val aliveCounter = AtomicInteger(0)
            val failedCounter = AtomicInteger(0)
            val vipAddedCounter = AtomicInteger(0)
            val processedGuids = ConcurrentHashMap.newKeySet<String>()

            try {
                V2WCoreScanner.runFullScan(
                    servers = orderedPairs,
                    customBinaryPath = v2wPath,
                    concurrency = verifyConcurrency,
                    tcpConcurrency = tcpConcurrency,
                    timeoutSec = 4,
                    fragTimeoutSec = 2,
                    destUrl = destUrl,
                    serviceCheck = checkServices,
                    services = if (checkServices) servicesProvider() else emptyList()
                ) { guid, profile, dto ->
                    if (sessionSeq.get() != sessionId || !isActive) return@runFullScan true
                    if (!processedGuids.add(guid)) return@runFullScan false

                    val fp = cachedByGuid[guid]?.fingerprint ?: ServerFingerprint.compute(profile)
                    val baseInfo = dto.toInspectionInfo(testedAtMs = System.currentTimeMillis()).let { raw ->
                        if (endpointEffective) raw.copy(endpointPassed = null) else raw
                    }

                    inspectionStore.put(fp, baseInfo)
                    if (baseInfo.alive && (baseInfo.fragMode == "tcp" || baseInfo.fragMode == "record")) {
                        applyFragmentTagIfNeeded(guid, profile, baseInfo.fragMode)
                    }

                    if (baseInfo.alive) {
                        if (endpointEffective && !shouldIgnoreEndpointForProfile(profile, customEpUrl)) {
                            val epJob = scope.launch(Dispatchers.IO) {
                                endpointSemaphore.withPermit {
                                    if (sessionSeq.get() != sessionId || !isActive) return@withPermit
                                    val (passed, e2eDelay) = DesktopProfileVerifier.verifyEndpointChainedOnly(
                                        profile = profile,
                                        customEndpointUrl = customEpUrl,
                                        xrayBinaryPath = xrayPath,
                                        timeoutMs = 7000L
                                    )
                                    if (sessionSeq.get() != sessionId) return@withPermit

                                    val updated = baseInfo.copy(
                                        endpointPassed = passed,
                                        delayMs = if (passed && e2eDelay > 0L) e2eDelay else baseInfo.delayMs,
                                        testedAtMs = System.currentTimeMillis()
                                    )
                                    inspectionStore.put(fp, updated)
                                    if (mode == DesktopFullScanMode.VIP_FIND && passed) {
                                        serverRepo.addVipServer(guid, vipLimit)
                                        val added = vipAddedCounter.incrementAndGet()
                                        if (stopAfterAliveCount > 0 && added >= stopAfterAliveCount) {
                                            V2WCoreScanner.stopFullScan()
                                        }
                                    }
                                    listRevisionFlow.update { it + 1 }
                                    _scanState.update { it.copy(revision = it.revision + 1) }
                                }
                            }
                            activeEndpointJobs.add(epJob)
                        } else if (mode == DesktopFullScanMode.VIP_FIND) {
                            serverRepo.addVipServer(guid, vipLimit)
                            val added = vipAddedCounter.incrementAndGet()
                            if (stopAfterAliveCount > 0 && added >= stopAfterAliveCount) {
                                return@runFullScan true
                            }
                        }
                    }

                    val done = completedCounter.incrementAndGet()
                    val alive = if (baseInfo.alive) aliveCounter.incrementAndGet() else aliveCounter.get()
                    val failed = if (!baseInfo.alive) failedCounter.incrementAndGet() else failedCounter.get()

                    val now = System.currentTimeMillis()
                    val prevProg = lastProgressEmitAtMs.get()
                    if (now - prevProg >= 180L && lastProgressEmitAtMs.compareAndSet(prevProg, now)) {
                        _scanState.update {
                            it.copy(
                                completedCount = done,
                                aliveCount = alive,
                                failedCount = failed,
                                revision = it.revision + 1
                            )
                        }
                    }

                    val listBumpMinMs = if (baseInfo.alive && alive <= 12) 150L else 400L
                    val prevBump = lastListBumpAtMs.get()
                    if (now - prevBump >= listBumpMinMs && lastListBumpAtMs.compareAndSet(prevBump, now)) {
                        listRevisionFlow.update { it + 1 }
                    }

                    false
                }

                val pendingEp = activeEndpointJobs.toList()
                if (pendingEp.isNotEmpty() && sessionSeq.get() == sessionId) {
                    pendingEp.joinAll()
                }
            } catch (e: Exception) {
                GeekModeLogger.log("FullScan", "Ошибка сканирования ($mode): ${e.message}")
            } finally {
                flushLoopJob?.cancel()
                inspectionStore.flushToDisk()

                if (sessionSeq.get() == sessionId) {
                    val finalDone = completedCounter.get()
                    val finalAlive = aliveCounter.get()
                    val finalFailed = failedCounter.get()

                    if (mode == DesktopFullScanMode.FULL) {
                        inspectionStore.setLastMeta(
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
                        inspectionStore.flushToDisk()
                    }
                    GeekModeLogger.log("FullScan", "Скан ($mode) завершён: scanned=$finalDone, alive=$finalAlive, failed=$finalFailed")
                    listRevisionFlow.update { it + 1 }
                    _scanState.update {
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
        return true
    }

    fun stopFullScan() {
        if (!_scanState.value.isScanning) return
        sessionSeq.incrementAndGet()
        flushLoopJob?.cancel()
        activeEndpointJobs.forEach { it.cancel() }
        activeEndpointJobs.clear()
        activeScanJob?.cancel()
        V2WCoreScanner.stopFullScan()

        val cur = _scanState.value
        scope.launch(Dispatchers.IO) {
            if (cur.mode == DesktopFullScanMode.FULL && cur.completedCount > 0) {
                inspectionStore.setLastMeta(
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
            inspectionStore.flushToDisk()
        }

        GeekModeLogger.log("FullScan", "Скан (${cur.mode}) остановлен пользователем (${cur.completedCount}/${cur.totalCount})")
        listRevisionFlow.update { it + 1 }
        _scanState.update {
            it.copy(isScanning = false, revision = it.revision + 1)
        }
    }

    fun reinspectServer(guid: String) {
        if (guid in reinspectingGuidsFlow.value) return
        if (_scanState.value.isScanning || isSmartConnectRunningProvider()) return
        val profile = serverRepo.getServerConfig(guid) ?: return

        scope.launch(Dispatchers.IO) {
            reinspectingGuidsFlow.update { it + guid }
            try {
                val customEpUrl = customEndpointUrlProvider().trim()
                val endpointEffective = useCustomEndpointProvider() && customEpUrl.isNotEmpty()
                val checkServices = if (endpointEffective) false else _scanCheckServicesEnabled.value

                var resultInfo: ServerInspectionInfo? = null
                V2WCoreScanner.runFullScan(
                    servers = listOf(guid to profile),
                    customBinaryPath = v2wScanPathProvider(),
                    concurrency = 1,
                    tcpConcurrency = 1,
                    timeoutSec = 4,
                    fragTimeoutSec = 2,
                    destUrl = destUrlProvider(),
                    serviceCheck = checkServices,
                    services = if (checkServices) servicesProvider() else emptyList()
                ) { _, _, dto ->
                    val raw = dto.toInspectionInfo(testedAtMs = System.currentTimeMillis())
                    resultInfo = if (endpointEffective) raw.copy(endpointPassed = null) else raw
                    false
                }

                var finalInfo = resultInfo
                if (finalInfo != null) {
                    if (finalInfo.alive && endpointEffective && !shouldIgnoreEndpointForProfile(profile, customEpUrl)) {
                        val (passed, e2eDelay) = DesktopProfileVerifier.verifyEndpointChainedOnly(
                            profile = profile,
                            customEndpointUrl = customEpUrl,
                            xrayBinaryPath = xrayBinaryPathProvider(),
                            timeoutMs = 7000L
                        )
                        finalInfo = finalInfo.copy(
                            endpointPassed = passed,
                            delayMs = if (passed && e2eDelay > 0L) e2eDelay else finalInfo.delayMs,
                            testedAtMs = System.currentTimeMillis()
                        )
                    }
                    val fp = cachedByGuid[guid]?.fingerprint ?: ServerFingerprint.compute(profile)
                    inspectionStore.put(fp, finalInfo)
                    if (finalInfo.alive && (finalInfo.fragMode == "tcp" || finalInfo.fragMode == "record")) {
                        applyFragmentTagIfNeeded(guid, profile, finalInfo.fragMode)
                    }
                    inspectionStore.flushToDisk()
                    listRevisionFlow.update { it + 1 }
                    _scanState.update { it.copy(revision = it.revision + 1) }
                }
            } finally {
                reinspectingGuidsFlow.update { it - guid }
            }
        }
    }

    fun runSpeedTest(guid: String) {
        if (speedTestStatesFlow.value[guid] is DesktopSpeedTestState.Testing) return
        val profile = serverRepo.getServerConfig(guid) ?: return

        speedTestStatesFlow.update { it + (guid to DesktopSpeedTestState.Testing) }
        scope.launch(Dispatchers.IO) {
            val mbps: Double? = try {
                val activeSocks = activeSocksPortProvider()
                if (activeSocks != null && coreStateFlow.value == DesktopCoreState.RUNNING && selectedGuidFlow.value == guid) {
                    DesktopProfileVerifier.measureSpeed(activeSocks, 2_000_000L, 4500L)
                } else {
                    val customEpUrl = customEndpointUrlProvider().trim()
                    val ep = if (useCustomEndpointProvider() && customEpUrl.isNotEmpty() && !shouldIgnoreEndpointForProfile(profile, customEpUrl)) {
                        customEpUrl
                    } else {
                        null
                    }
                    val res = DesktopProfileVerifier.verifyProfile(
                        profile = profile,
                        xrayBinaryPath = xrayBinaryPathProvider(),
                        timeoutMs = 5000L,
                        speedCheckEnabled = true,
                        minSpeedMbps = 0.0,
                        bytesToDownload = 2_000_000L,
                        customEndpointUrl = ep,
                        endpointE2eCheckEnabled = false,
                        speedOnlyMode = false
                    )
                    res.speedMbps
                }
            } catch (_: Exception) {
                null
            }

            val finalState = if (mbps != null && mbps > 0.0) {
                DesktopSpeedTestState.Success(mbps)
            } else {
                DesktopSpeedTestState.Failed
            }
            speedTestStatesFlow.update { it + (guid to finalState) }
        }
    }

    private fun shouldIgnoreEndpointForProfile(profile: ProfileItem, customEndpointUrl: String): Boolean {
        if (PersonalServersConfig.isPersonalServer(profile.subscriptionId) && personalIgnoreExitNodeProvider()) {
            return true
        }
        val epParsed = UniversalConfigParser.parse(customEndpointUrl)
        return epParsed != null &&
            epParsed.server.equals(profile.server, ignoreCase = true) &&
            epParsed.serverPort == profile.serverPort
    }

    private suspend fun applyFragmentTagIfNeeded(guid: String, profile: ProfileItem, fragMode: String) = withContext(Dispatchers.IO) {
        var changed = false
        val targetPackets = if (fragMode == "tcp") "1-3" else "tlshello"
        val tag = if (fragMode == "tcp") "⚡[FRAG:TCP]" else "⚡[FRAG:RECORD]"
        val updated = profile.copy()

        if (updated.fragmentEnabled != true || updated.fragmentPackets != targetPackets) {
            updated.fragmentEnabled = true
            updated.fragmentPackets = targetPackets
            updated.fragmentLength = "50-100"
            updated.fragmentInterval = "10-20"
            changed = true
        }
        if (!updated.remarks.contains("[FRAG:")) {
            updated.remarks = "${updated.remarks} $tag".trim()
            changed = true
        }
        if (changed) {
            serverRepo.saveServerConfig(guid, updated)
        }
    }
}
