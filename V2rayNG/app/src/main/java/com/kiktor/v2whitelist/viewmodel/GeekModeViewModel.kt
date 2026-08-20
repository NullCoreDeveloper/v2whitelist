package com.kiktor.v2whitelist.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.handler.GeekModeLogger
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.NodeTesterManager
import com.kiktor.v2whitelist.handler.NotificationManager
import com.kiktor.v2whitelist.handler.SettingsManager
import com.kiktor.v2whitelist.handler.SmartConnectManager
import com.kiktor.v2whitelist.handler.SpeedtestManager
import com.kiktor.v2whitelist.handler.V2RayServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


class GeekModeViewModel(application: Application) : AndroidViewModel(application) {

    private val _exitIp = MutableStateFlow<String?>(null)
    val exitIp: StateFlow<String?> = _exitIp.asStateFlow()

    // Логи живут в синглтоне GeekModeLogger, переживают закрытие фрагмента
    val logs: StateFlow<List<String>> = GeekModeLogger.logs

    
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
            GeekModeLogger.log("GeekMode", "Searching for additional VIP servers...")
            SmartConnectManager.findMoreVipServers(getApplication())
            loadVipServers() // Update list after search
        }
    }

    fun removeVipServer(guid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            MmkvManager.removeVipServer(guid)
            GeekModeLogger.log("GeekMode", "Server manually removed from VIP cache")
            loadVipServers()
        }
    }

    fun fetchExitIp() {
        viewModelScope.launch(Dispatchers.IO) {
            GeekModeLogger.log("GeekMode", "Fetching exit IP...")
            val ip = SpeedtestManager.getRemoteIPInfo()
            if (ip != null) {
                _exitIp.value = ip
                GeekModeLogger.log("GeekMode", "Exit IP: $ip")
            } else {
                GeekModeLogger.log("GeekMode", "Could not fetch exit IP (VPN may not be running)")
            }
        }
    }

    fun forcePingTest() {
        viewModelScope.launch(Dispatchers.IO) {
            val serverGuid = MmkvManager.getSelectServer()
            if (serverGuid.isNullOrEmpty()) {
                GeekModeLogger.log("GeekMode", "No server selected")
                return@launch
            }

            val config = MmkvManager.decodeServerConfig(serverGuid)
            if (config != null) {
                val address = config.server ?: ""
                val port = config.serverPort?.toIntOrNull() ?: 0
                if (address.isNotEmpty() && port > 0) {
                    GeekModeLogger.log("GeekMode", "TCP ping to $address:$port...")
                    val delayStr = SpeedtestManager.tcping(address, port)
                    GeekModeLogger.log("GeekMode", "TCP Ping: $delayStr")
                }
            }

            // 204 test through the running proxy (HTTP port)
            if (V2RayServiceManager.isRunning() == true) {
                val httpPort = SettingsManager.getHttpPort()
                GeekModeLogger.log("GeekMode", "HTTP 204 test via proxy port $httpPort...")
                val (elapsed, result) = SpeedtestManager.testConnection(getApplication(), httpPort)
                if (elapsed > 0) {
                    GeekModeLogger.log("GeekMode", "Proxy latency: ${elapsed}ms")
                    // Update testDelayMillis for current server
                    serverGuid.let { MmkvManager.encodeServerTestDelayMillis(it, elapsed) }
                    loadVipServers() // Refresh chips with new delay
                } else {
                    GeekModeLogger.log("GeekMode", "204 test failed: $result")
                }
            }

            fetchExitIp()
        }
    }

    /** Runs a true proxy ping for every VIP server by spinning up isolated core instances
     *  and updates testDelayMillis for each. */
    fun pingVipServers() {
        viewModelScope.launch(Dispatchers.IO) {
            val guids = MmkvManager.getVipCache()
            GeekModeLogger.log("GeekMode", "Pinging ${guids.size} VIP server(s) via isolated cores...")

            for (guid in guids) {
                val name = MmkvManager.decodeServerConfig(guid)?.remarks ?: guid
                GeekModeLogger.log("GeekMode", "Testing VIP [$name]...")
                NodeTesterManager.verifyProfile(getApplication(), guid)
            }

            loadVipServers() // Refresh chips
            GeekModeLogger.log("GeekMode", "VIP ping complete")
        }
    }
}
