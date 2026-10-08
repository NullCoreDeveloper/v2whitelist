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
import com.kiktor.v2whitelist.handler.V2RayNativeManager
import com.kiktor.v2whitelist.handler.V2rayConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


class GeekModeViewModel(application: Application) : AndroidViewModel(application) {

    private val _exitIp = MutableStateFlow<String?>(null)
    val exitIp: StateFlow<String?> = _exitIp.asStateFlow()

    private val _pingUpdated = MutableStateFlow<Long>(0L)
    val pingUpdated: StateFlow<Long> = _pingUpdated.asStateFlow()

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

            if (V2RayServiceManager.isRunning() == true) {
                GeekModeLogger.log("GeekMode", "Testing proxy latency via active core...")
                var delay = V2RayServiceManager.measureDelay()
                if (delay <= 0) {
                    val socksPort = SettingsManager.getSocksPort()
                    val (elapsed, _) = SpeedtestManager.testConnection(getApplication(), socksPort)
                    if (elapsed > 0) delay = elapsed
                }

                if (delay > 0) {
                    GeekModeLogger.log("GeekMode", "Proxy latency: ${delay}ms")
                    MmkvManager.encodeServerTestDelayMillis(serverGuid, delay)
                } else {
                    GeekModeLogger.log("GeekMode", "Proxy latency test failed")
                    MmkvManager.encodeServerTestDelayMillis(serverGuid, -1L)
                }
            } else {
                GeekModeLogger.log("GeekMode", "VPN not running, testing via isolated core...")
                val speedtestConfig = V2rayConfigManager.getV2rayConfig4Speedtest(getApplication(), serverGuid, ignoreCustomEndpoint = true)
                var delay = -1L
                if (speedtestConfig.status) {
                    val testUrl = SettingsManager.getDelayTestUrl()
                    val realDelay = V2RayNativeManager.measureOutboundDelay(speedtestConfig.content, testUrl)
                    if (realDelay > 0) {
                        delay = realDelay
                    }
                }
                if (delay <= 0) {
                    val ok = NodeTesterManager.verifyProfile(getApplication(), serverGuid, showStatus = false, ignoreCustomEndpoint = true)
                    val testedDelay = MmkvManager.decodeServerAffiliationInfo(serverGuid)?.testDelayMillis ?: -1L
                    if (ok && testedDelay > 0) {
                        delay = testedDelay
                    }
                }

                if (delay > 0) {
                    GeekModeLogger.log("GeekMode", "Isolated core latency: ${delay}ms")
                    MmkvManager.encodeServerTestDelayMillis(serverGuid, delay)
                } else {
                    GeekModeLogger.log("GeekMode", "Isolated core test failed")
                    MmkvManager.encodeServerTestDelayMillis(serverGuid, -1L)
                }
            }

            loadVipServers()
            _pingUpdated.value = System.currentTimeMillis()
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
