package com.kiktor.v2whitelist.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.core.domain.inspection.ServerListEngine
import com.kiktor.v2whitelist.core.domain.inspection.ServerListEntry
import com.kiktor.v2whitelist.core.domain.inspection.ServerListFilter
import com.kiktor.v2whitelist.core.domain.inspection.ServerListStats
import com.kiktor.v2whitelist.core.domain.inspection.ServerListViewResult
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.inspection.ScanSessionMeta
import com.kiktor.v2whitelist.handler.FullScanManager
import com.kiktor.v2whitelist.handler.FullScanUiState
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.ServerListStore
import com.kiktor.v2whitelist.handler.SettingsManager
import com.kiktor.v2whitelist.handler.SmartConnectManager
import com.kiktor.v2whitelist.handler.SpeedtestManager
import com.kiktor.v2whitelist.handler.V2RayNativeManager
import com.kiktor.v2whitelist.handler.V2RayServiceManager
import com.kiktor.v2whitelist.handler.V2rayConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import java.net.ServerSocket

sealed class SpeedTestRowState {
    data object Idle : SpeedTestRowState()
    data object Testing : SpeedTestRowState()
    data class Success(val mbps: Double) : SpeedTestRowState()
    data object Failed : SpeedTestRowState()
}

data class SubscriptionFilterOption(
    val id: String,
    val name: String,
    val count: Int
)

data class ServerListScreenState(
    val isLoading: Boolean = true,
    val viewResult: ServerListViewResult = ServerListViewResult(
        vipServers = emptyList(),
        regularServers = emptyList(),
        availableCountryCounts = emptyMap(),
        availableServices = emptyList(),
        stats = ServerListStats(0, 0, 0, 0, 0, 0, 0)
    ),
    val filter: ServerListFilter = ServerListFilter(),
    val scanState: FullScanUiState = FullScanUiState(),
    val lastScanMeta: ScanSessionMeta? = null,
    val networkCheckEnabled: Boolean = true,
    val endpointEnabled: Boolean = false,
    val availableSubscriptions: List<SubscriptionFilterOption> = emptyList(),
    val availableProtocols: List<EConfigType> = emptyList(),
    val expandedGuids: Set<String> = emptySet(),
    val connectedGuid: String? = null,
    val isVpnRunning: Boolean = false,
    val speedTestStates: Map<String, SpeedTestRowState> = emptyMap(),
    val reinspectingGuids: Set<String> = emptySet(),
    val isSmartConnectScanning: Boolean = false,
    val scanCheckServicesEnabled: Boolean = false,
    val totalRegularMatching: Int = 0,
    val hasMoreRegular: Boolean = false,
    val fallbackFlags: Map<String, String> = emptyMap()
)

@OptIn(FlowPreview::class)
class ServerListViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val DEFAULT_PAGE_SIZE = 80
        const val PAGE_INCREMENT = 80
    }

    private val baseFilterFlow = MutableStateFlow(ServerListFilter())
    private val searchQueryFlow = MutableStateFlow("")
    private val effectiveFilterFlow = MutableStateFlow(ServerListFilter())

    private val expandedGuidsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val speedTestStatesFlow = MutableStateFlow<Map<String, SpeedTestRowState>>(emptyMap())
    private val reinspectingGuidsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val scanCheckServicesFlow = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_FULL_SCAN_CHECK_SERVICES, false)
    )
    private val visibleRegularLimitFlow = MutableStateFlow(DEFAULT_PAGE_SIZE)

    private val computedFlow = MutableStateFlow(ComputedState())
    private val _uiState = MutableStateFlow(
        ServerListScreenState(
            isLoading = !ServerListStore.isLoaded.value,
            scanState = FullScanManager.state.value,
            isSmartConnectScanning = SmartConnectManager.isScanning.get(),
            scanCheckServicesEnabled = scanCheckServicesFlow.value
        )
    )
    val uiState: StateFlow<ServerListScreenState> = _uiState.asStateFlow()

    private data class ComputedState(
        val isLoading: Boolean = true,
        val viewResult: ServerListViewResult = ServerListViewResult(
            vipServers = emptyList(),
            regularServers = emptyList(),
            availableCountryCounts = emptyMap(),
            availableServices = emptyList(),
            stats = ServerListStats(0, 0, 0, 0, 0, 0, 0)
        ),
        val filter: ServerListFilter = ServerListFilter(),
        val scanState: FullScanUiState = FullScanUiState(),
        val lastScanMeta: ScanSessionMeta? = null,
        val networkCheckEnabled: Boolean = true,
        val endpointEnabled: Boolean = false,
        val availableSubscriptions: List<SubscriptionFilterOption> = emptyList(),
        val availableProtocols: List<EConfigType> = emptyList(),
        val flagsByGuid: Map<String, String> = emptyMap(),
        val connectedGuid: String? = null,
        val isVpnRunning: Boolean = false
    )

    private data class RowOverlayState(
        val expanded: Set<String>,
        val speeds: Map<String, SpeedTestRowState>,
        val reinspecting: Set<String>,
        val isSmartConnectScanning: Boolean,
        val scanCheckServices: Boolean
    )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            ServerListStore.ensureLoaded(getApplication())
            ServerListStore.reloadBaseIfNeeded(getApplication())
        }

        // Debounce для поиска, чтобы не пересчитывать список на каждый нажатый символ
        viewModelScope.launch {
            combine(
                baseFilterFlow,
                searchQueryFlow.debounce(180L).distinctUntilChanged()
            ) { baseFilter, query ->
                baseFilter.copy(searchQuery = query)
            }.collect { combined ->
                effectiveFilterFlow.value = combined
            }
        }

        // Слой 1: фильтрация и сортировка по горячему in-memory кэшу (без чтения MMKV на каждый тик)
        viewModelScope.launch(Dispatchers.Default) {
            combine(
                ServerListStore.isLoaded,
                ServerListStore.revision,
                FullScanManager.state,
                effectiveFilterFlow
            ) { loaded, _, scanState, filter ->
                Triple(loaded, scanState, filter)
            }.collectLatest { (loaded, scanState, filter) ->
                if (!loaded) {
                    computedFlow.value = ComputedState(isLoading = true, scanState = scanState, filter = filter)
                    return@collectLatest
                }

                val snapshot = ServerListStore.snapshot()
                val lastMeta = MmkvManager.decodeFullScanMeta()
                val networkCheckEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_NETWORK_CHECK_ENABLED, true)
                val endpointEnabled = FullScanManager.isEndpointCheckActive()

                val viewResult = ServerListEngine.buildView(
                    entries = snapshot.entries,
                    filter = filter,
                    endpointEnabled = endpointEnabled,
                    removedDuplicatesCount = snapshot.removedDuplicatesCount
                )

                val isRunning = V2RayServiceManager.isRunning()
                val connectedGuid = if (isRunning) MmkvManager.getSelectServer() else null

                computedFlow.value = ComputedState(
                    isLoading = false,
                    viewResult = viewResult,
                    filter = filter,
                    scanState = scanState,
                    lastScanMeta = lastMeta,
                    networkCheckEnabled = networkCheckEnabled,
                    endpointEnabled = endpointEnabled,
                    availableSubscriptions = snapshot.availableSubscriptions,
                    availableProtocols = snapshot.availableProtocols,
                    flagsByGuid = snapshot.flagsByGuid,
                    connectedGuid = connectedGuid,
                    isVpnRunning = isRunning
                )
            }
        }

        // Слой 2: лёгкая нарезка видимого окна + интерактивные состояния карточек (без пересортировки)
        viewModelScope.launch(Dispatchers.Default) {
            val baseOverlay = combine(
                expandedGuidsFlow,
                speedTestStatesFlow,
                reinspectingGuidsFlow
            ) { expanded, speeds, reinspecting ->
                Triple(expanded, speeds, reinspecting)
            }
            val fullOverlay = combine(
                baseOverlay,
                SmartConnectManager.isScanning.flow,
                scanCheckServicesFlow
            ) { (expanded, speeds, reinspecting), smartScanning, scanServices ->
                RowOverlayState(expanded, speeds, reinspecting, smartScanning, scanServices)
            }

            combine(
                computedFlow,
                visibleRegularLimitFlow,
                fullOverlay
            ) { computed, regularLimit, overlay ->
                val totalRegular = computed.viewResult.regularServers.size
                val slicedRegular = if (totalRegular > regularLimit) {
                    computed.viewResult.regularServers.take(regularLimit)
                } else {
                    computed.viewResult.regularServers
                }

                ServerListScreenState(
                    isLoading = computed.isLoading,
                    viewResult = computed.viewResult.copy(regularServers = slicedRegular),
                    filter = computed.filter,
                    scanState = computed.scanState,
                    lastScanMeta = computed.lastScanMeta,
                    networkCheckEnabled = computed.networkCheckEnabled,
                    endpointEnabled = computed.endpointEnabled,
                    availableSubscriptions = computed.availableSubscriptions,
                    availableProtocols = computed.availableProtocols,
                    expandedGuids = overlay.expanded,
                    connectedGuid = computed.connectedGuid,
                    isVpnRunning = computed.isVpnRunning,
                    speedTestStates = overlay.speeds,
                    reinspectingGuids = overlay.reinspecting,
                    isSmartConnectScanning = overlay.isSmartConnectScanning,
                    scanCheckServicesEnabled = overlay.scanCheckServices,
                    totalRegularMatching = totalRegular,
                    hasMoreRegular = totalRegular > slicedRegular.size,
                    fallbackFlags = computed.flagsByGuid
                )
            }.collectLatest { state ->
                _uiState.value = state
            }
        }
    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            ServerListStore.ensureLoaded(getApplication())
            ServerListStore.reloadBaseIfNeeded(getApplication(), force = false)
            ServerListStore.bumpRevision()
        }
    }

    fun loadMoreRegular() {
        val currentState = _uiState.value
        if (!currentState.hasMoreRegular) return
        visibleRegularLimitFlow.update { current ->
            val maxNeeded = currentState.totalRegularMatching
            if (current >= maxNeeded) current else (current + PAGE_INCREMENT)
        }
    }

    fun toggleExpand(guid: String) {
        expandedGuidsFlow.update { current ->
            if (guid in current) current - guid else current + guid
        }
    }

    fun setSearchQuery(query: String) {
        if (searchQueryFlow.value == query) return
        visibleRegularLimitFlow.value = DEFAULT_PAGE_SIZE
        searchQueryFlow.value = query
    }

    fun updateFilter(newFilter: ServerListFilter) {
        visibleRegularLimitFlow.value = DEFAULT_PAGE_SIZE
        baseFilterFlow.value = newFilter
    }

    fun resetFilters() {
        visibleRegularLimitFlow.value = DEFAULT_PAGE_SIZE
        searchQueryFlow.value = ""
        baseFilterFlow.value = ServerListFilter()
        effectiveFilterFlow.value = ServerListFilter()
    }

    fun setScanCheckServicesEnabled(enabled: Boolean) {
        if (scanCheckServicesFlow.value == enabled) return
        scanCheckServicesFlow.value = enabled
        MmkvManager.encodeSettings(AppConfig.PREF_FULL_SCAN_CHECK_SERVICES, enabled)
    }

    fun startOrStopFullScan(): Boolean {
        if (FullScanManager.state.value.isScanning) {
            FullScanManager.stopFullScan()
            return true
        }
        return FullScanManager.startFullScan(getApplication())
    }

    fun findMoreVipServers(): Boolean {
        if (FullScanManager.state.value.isScanning) {
            FullScanManager.stopFullScan()
            return true
        }
        return FullScanManager.findVipNative(getApplication())
    }

    fun pingVipServers(): Boolean {
        if (FullScanManager.state.value.isScanning) {
            FullScanManager.stopFullScan()
            return true
        }
        return FullScanManager.pingVipNative(getApplication())
    }

    fun toggleVip(guid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ServerListStore.toggleVip(guid)
        }
    }

    fun reinspectServer(guid: String) {
        if (guid in reinspectingGuidsFlow.value) return
        viewModelScope.launch(Dispatchers.IO) {
            reinspectingGuidsFlow.update { it + guid }
            try {
                FullScanManager.inspectSingleServer(getApplication(), guid)
            } finally {
                reinspectingGuidsFlow.update { it - guid }
            }
        }
    }

    fun runSpeedTest(guid: String) {
        if (speedTestStatesFlow.value[guid] is SpeedTestRowState.Testing) return
        speedTestStatesFlow.update { it + (guid to SpeedTestRowState.Testing) }

        viewModelScope.launch(Dispatchers.IO) {
            val mbps: Double? = try {
                val isRunning = V2RayServiceManager.isRunning()
                val hideTunnel = SettingsManager.isVpnMode() &&
                    !SettingsManager.isUsingHevTun() &&
                    SettingsManager.isHideTunnelEnabled()
                if (isRunning && MmkvManager.getSelectServer() == guid && !hideTunnel) {
                    SpeedtestManager.measureSpeedThroughProxy(SettingsManager.getSocksPort())
                } else {
                    measureIsolatedSpeed(guid)
                }
            } catch (_: Exception) {
                null
            }

            val finalState = if (mbps != null && mbps > 0.0) {
                SpeedTestRowState.Success(mbps)
            } else {
                SpeedTestRowState.Failed
            }
            speedTestStatesFlow.update { it + (guid to finalState) }
        }
    }

    private suspend fun measureIsolatedSpeed(guid: String): Double? {
        var coreController: CoreController? = null
        return try {
            val ephemeralPort = ServerSocket(0).use { it.localPort }
            val speedtestConfig = V2rayConfigManager.getV2rayConfig4Speedtest(
                getApplication(),
                guid,
                ephemeralPort,
                ignoreCustomEndpoint = false
            )
            if (!speedtestConfig.status) return null

            coreController = V2RayNativeManager.newCoreController(object : CoreCallbackHandler {
                override fun startup(): Long = 0
                override fun shutdown(): Long = 0
                override fun onEmitStatus(p0: Long, p1: String?): Long = 0
            })
            coreController.startLoop(speedtestConfig.content, 0)
            delay(400L)
            SpeedtestManager.measureSpeedThroughProxy(ephemeralPort)
        } finally {
            try {
                coreController?.stopLoop()
            } catch (_: Exception) {}
        }
    }
}
