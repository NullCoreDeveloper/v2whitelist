package com.kiktor.v2whitelist.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kiktor.v2whitelist.handler.GeekModeLogger
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.SpeedtestManager
import com.kiktor.v2whitelist.handler.V2RayServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import libv2ray.V2RayPoint

import com.kiktor.v2whitelist.handler.SmartConnectManager


class GeekModeViewModel(application: Application) : AndroidViewModel(application) {

    private val _rxTxSpeeds = MutableStateFlow<Pair<Double, Double>>(Pair(0.0, 0.0))
    val rxTxSpeeds: StateFlow<Pair<Double, Double>> = _rxTxSpeeds.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private var pollingJob: Job? = null

    init {
        viewModelScope.launch {
            GeekModeLogger.logs.collect { newLog ->
                val currentLogs = _logs.value.toMutableList()
                currentLogs.add(newLog)
                if (currentLogs.size > 200) {
                    currentLogs.removeAt(0)
                }
                _logs.value = currentLogs
            }
        }
    }

    
    data class VipServerItem(val guid: String, val name: String, val ping: Long)

    private val _vipServersFlow = MutableStateFlow<List<VipServerItem>>(emptyList())
    val vipServersFlow: StateFlow<List<VipServerItem>> = _vipServersFlow.asStateFlow()

    fun loadVipServers() {
        viewModelScope.launch(Dispatchers.IO) {
            val guids = MmkvManager.getVipCache()
            val items = guids.mapNotNull { guid ->
                val config = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
                val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
                VipServerItem(guid, config.remarks ?: "Unknown", delay)
            }
            _vipServersFlow.value = items
        }
    }

    fun findAdditionalVipServers() {
        viewModelScope.launch(Dispatchers.IO) {
            GeekModeLogger.log("GeekMode", "Ищем дополнительные VIP сервера...")
            SmartConnectManager.findMoreVipServers(getApplication())
            loadVipServers() // Обновляем список после поиска
        }
    }

    fun removeVipServer(guid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            MmkvManager.removeVipServer(guid)
            GeekModeLogger.log("GeekMode", "Сервер удален из VIP кэша вручную")
            loadVipServers()
        }
    }

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            var lastQueryTime = System.currentTimeMillis()
            while (true) {
                if (V2RayServiceManager.isRunning() == true) {
                    val now = System.currentTimeMillis()
                    val sinceLastQueryInSeconds = (now - lastQueryTime) / 1000.0
                    var rx = 0.0
                    var tx = 0.0

                    try {
                        val stats = V2RayPoint.queryStats("outbound", "")
                        val proxyTotal = V2RayPoint.queryStats("proxy", "outbound")
                        val directUplink = V2RayPoint.queryStats("direct", "outbound/uplink")
                        val directDownlink = V2RayPoint.queryStats("direct", "outbound/downlink")
                        
                        rx = (proxyTotal + directDownlink) / sinceLastQueryInSeconds
                        tx = (proxyTotal + directUplink) / sinceLastQueryInSeconds
                    } catch (e: Exception) {
                        // V2Ray Core stats error
                    }
                    
                    _rxTxSpeeds.value = Pair(rx, tx)
                    lastQueryTime = now
                } else {
                    _rxTxSpeeds.value = Pair(0.0, 0.0)
                }
                delay(1000)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun forcePingTest() {
        viewModelScope.launch(Dispatchers.IO) {
            val serverGuid = MmkvManager.getSelectServer()
            if (serverGuid.isNullOrEmpty()) {
                GeekModeLogger.log("GeekMode", "No server selected")
                return@launch
            }
            
            GeekModeLogger.log("GeekMode", "Forcing TCP ping test for selected server...")
            
            val config = MmkvManager.decodeServerConfig(serverGuid)
            if (config != null) {
                val address = config.server ?: ""
                val port = config.serverPort?.toIntOrNull() ?: 0
                if (address.isNotEmpty() && port > 0) {
                    val delayStr = SpeedtestManager.tcping(address, port)
                    GeekModeLogger.log("GeekMode", "Ping to $address:$port = $delayStr")
                } else {
                    GeekModeLogger.log("GeekMode", "Invalid address/port in config")
                }
            }
        }
    }
}
