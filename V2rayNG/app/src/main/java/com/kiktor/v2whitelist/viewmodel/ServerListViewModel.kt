package com.kiktor.v2whitelist.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.core.domain.inspection.ServerListEngine
import com.kiktor.v2whitelist.core.domain.inspection.ServerListEntry
import com.kiktor.v2whitelist.core.domain.inspection.ServerListFilter
import com.kiktor.v2whitelist.core.domain.inspection.ServerListStats
import com.kiktor.v2whitelist.core.domain.inspection.ServerListViewResult
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.PersonalServersConfig
import com.kiktor.v2whitelist.core.model.inspection.ScanSessionMeta
import com.kiktor.v2whitelist.handler.DefaultSubscriptions
import com.kiktor.v2whitelist.handler.FullScanManager
import com.kiktor.v2whitelist.handler.FullScanUiState
import com.kiktor.v2whitelist.handler.GeekModeLogger
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.NodeTesterManager
import com.kiktor.v2whitelist.handler.SettingsManager
import com.kiktor.v2whitelist.handler.SmartConnectManager
import com.kiktor.v2whitelist.handler.SpeedtestManager
import com.kiktor.v2whitelist.handler.SubscriptionHelper
import com.kiktor.v2whitelist.handler.V2RayNativeManager
import com.kiktor.v2whitelist.handler.V2RayServiceManager
import com.kiktor.v2whitelist.handler.V2rayConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    val isFindingVip: Boolean = false,
    val isPingingVip: Boolean = false
)

class ServerListViewModel(application: Application) : AndroidViewModel(application) {

    private val filterFlow = MutableStateFlow(ServerListFilter())
    private val expandedGuidsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val speedTestStatesFlow = MutableStateFlow<Map<String, SpeedTestRowState>>(emptyMap())
    private val reinspectingGuidsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val isFindingVipFlow = MutableStateFlow(false)
    private val isPingingVipFlow = MutableStateFlow(false)
    private val reloadTriggerFlow = MutableStateFlow(0L)

    private val _uiState = MutableStateFlow(ServerListScreenState())
    val uiState: StateFlow<ServerListScreenState> = _uiState.asStateFlow()

    private data class AuxiliaryState(
        val expanded: Set<String>,
        val speeds: Map<String, SpeedTestRowState>,
        val reinspecting: Set<String>,
        val isFindingVip: Boolean,
        val isPingingVip: Boolean,
        val reloadTick: Long
    )

    init {
        viewModelScope.launch {
            val auxBaseFlow = combine(
                expandedGuidsFlow,
                speedTestStatesFlow,
                reinspectingGuidsFlow,
                reloadTriggerFlow
            ) { expanded, speeds, reinspecting, tick ->
                AuxiliaryState(expanded, speeds, reinspecting, false, false, tick)
            }
            val auxFlow = combine(
                auxBaseFlow,
                isFindingVipFlow,
                isPingingVipFlow
            ) { base, findingVip, pingingVip ->
                base.copy(isFindingVip = findingVip, isPingingVip = pingingVip)
            }

            combine(
                FullScanManager.state,
                filterFlow,
                auxFlow
            ) { scanState, filter, aux ->
                Triple(scanState, filter, aux)
            }.collect { (scanState, filter, aux) ->
                val computed = withContext(Dispatchers.IO) {
                    buildScreenState(scanState, filter, aux)
                }
                _uiState.value = computed
            }
        }
    }

    private fun buildScreenState(
        scanState: FullScanUiState,
        filter: ServerListFilter,
        aux: AuxiliaryState
    ): ServerListScreenState {
        val guids = MmkvManager.decodeServerList()
        val inspections = MmkvManager.decodeAllServerInspections()
        val vipSet = MmkvManager.getVipCache().toSet()
        val removedDupes = MmkvManager.decodeLastRemovedDuplicatesCount()
        val lastMeta = MmkvManager.decodeFullScanMeta()
        val networkCheckEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_NETWORK_CHECK_ENABLED, true)

        val customEpEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_CUSTOM_ENDPOINT_ENABLED, false)
        val endpointUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_ENDPOINT_URL).orEmpty().trim()
        val endpointEnabled = customEpEnabled && endpointUrl.isNotEmpty()

        val subNameMap = buildSubscriptionNameMap()
        val manualLabel = getApplication<Application>().getString(R.string.server_list_manual_sub)
        val personalLabel = getApplication<Application>().getString(R.string.title_personal_servers)

        val entries = ArrayList<ServerListEntry>(guids.size)
        val subCountMap = LinkedHashMap<String, Pair<String, Int>>()
        val protocolSet = LinkedHashSet<EConfigType>()

        for (guid in guids) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            val isPersonal = PersonalServersConfig.isPersonalServer(profile.subscriptionId)
            val normSubId = when {
                isPersonal -> "personal"
                profile.subscriptionId.isBlank() -> "manual"
                else -> profile.subscriptionId
            }
            val subName = when {
                isPersonal -> personalLabel
                profile.subscriptionId.isBlank() -> manualLabel
                else -> subNameMap[profile.subscriptionId]
                    ?: subNameMap[profile.subscriptionId.removePrefix("custom_sub_")]
                    ?: profile.subscriptionId
            }

            val prevCount = subCountMap[normSubId]?.second ?: 0
            subCountMap[normSubId] = subName to (prevCount + 1)
            protocolSet.add(profile.configType)

            val fallbackPing = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
            entries.add(
                ServerListEntry(
                    guid = guid,
                    profile = profile,
                    subscriptionId = normSubId,
                    subscriptionName = subName,
                    isVip = guid in vipSet,
                    isPersonal = isPersonal,
                    fallbackPingMs = fallbackPing,
                    inspection = inspections[guid]
                )
            )
        }

        val viewResult = ServerListEngine.buildView(
            entries = entries,
            filter = filter,
            endpointEnabled = endpointEnabled,
            removedDuplicatesCount = removedDupes
        )

        val availableSubscriptions = subCountMap.entries
            .map { (id, pair) -> SubscriptionFilterOption(id = id, name = pair.first, count = pair.second) }
            .sortedByDescending { it.count }

        val availableProtocols = protocolSet.sortedBy { it.name }
        val isRunning = V2RayServiceManager.isRunning()
        val connectedGuid = if (isRunning) MmkvManager.getSelectServer() else null

        return ServerListScreenState(
            viewResult = viewResult,
            filter = filter,
            scanState = scanState,
            lastScanMeta = lastMeta,
            networkCheckEnabled = networkCheckEnabled,
            endpointEnabled = endpointEnabled,
            availableSubscriptions = availableSubscriptions,
            availableProtocols = availableProtocols,
            expandedGuids = aux.expanded,
            connectedGuid = connectedGuid,
            isVpnRunning = isRunning,
            speedTestStates = aux.speeds,
            reinspectingGuids = aux.reinspecting,
            isFindingVip = aux.isFindingVip,
            isPingingVip = aux.isPingingVip
        )
    }

    private fun buildSubscriptionNameMap(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (def in DefaultSubscriptions.PREPOPULATED_SUBS) {
            if (def.name.isNotBlank()) {
                map["custom_sub_${def.id}"] = def.name
                map[def.id] = def.name
            }
        }
        for (custom in SubscriptionHelper.loadCustomSubs()) {
            if (custom.name.isNotBlank()) {
                map["custom_sub_${custom.id}"] = custom.name
                map[custom.id] = custom.name
            }
        }
        for (sub in MmkvManager.decodeSubscriptions()) {
            if (sub.subscription.remarks.isNotBlank()) {
                map[sub.guid] = sub.subscription.remarks
            }
        }
        return map
    }

    fun reload() {
        reloadTriggerFlow.update { it + 1 }
    }

    fun toggleExpand(guid: String) {
        expandedGuidsFlow.update { current ->
            if (guid in current) current - guid else current + guid
        }
    }

    fun setSearchQuery(query: String) {
        filterFlow.update { current ->
            if (current.searchQuery == query) current else current.copy(searchQuery = query)
        }
    }

    fun updateFilter(newFilter: ServerListFilter) {
        filterFlow.value = newFilter
    }

    fun resetFilters() {
        filterFlow.value = ServerListFilter()
    }

    fun startOrStopFullScan(): Boolean {
        if (FullScanManager.state.value.isScanning) {
            FullScanManager.stopFullScan()
            return true
        }
        return FullScanManager.startFullScan(getApplication())
    }

    fun findMoreVipServers() {
        if (isFindingVipFlow.value) return
        isFindingVipFlow.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                GeekModeLogger.log("ServerList", "Searching for additional VIP servers...")
                SmartConnectManager.findMoreVipServers(getApplication())
            } finally {
                isFindingVipFlow.value = false
                reload()
            }
        }
    }

    fun pingVipServers() {
        if (isPingingVipFlow.value) return
        val guids = MmkvManager.getVipCache()
        if (guids.isEmpty()) return
        isPingingVipFlow.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                GeekModeLogger.log("ServerList", "Pinging ${guids.size} VIP server(s) via isolated cores...")
                for (guid in guids) {
                    val name = MmkvManager.decodeServerConfig(guid)?.remarks ?: guid
                    GeekModeLogger.log("ServerList", "Testing VIP [$name]...")
                    NodeTesterManager.verifyProfile(getApplication(), guid)
                    reload()
                }
                GeekModeLogger.log("ServerList", "VIP ping complete")
            } finally {
                isPingingVipFlow.value = false
                reload()
            }
        }
    }

    fun toggleVip(guid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val vipSet = MmkvManager.getVipCache().toSet()
            if (guid in vipSet) {
                MmkvManager.removeVipServer(guid)
            } else {
                MmkvManager.addVipServer(guid)
            }
            reload()
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
                reload()
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
