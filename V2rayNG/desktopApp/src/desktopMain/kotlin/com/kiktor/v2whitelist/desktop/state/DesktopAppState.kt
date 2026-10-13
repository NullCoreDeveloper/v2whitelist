package com.kiktor.v2whitelist.desktop.state

import com.kiktor.v2whitelist.core.domain.ping.NodePingTester
import com.kiktor.v2whitelist.core.model.AppScenario
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import com.kiktor.v2whitelist.core.repository.ServerRepository
import com.kiktor.v2whitelist.core.repository.SettingsRepository
import com.kiktor.v2whitelist.core.subscription.network.SubscriptionFetcher
import com.kiktor.v2whitelist.core.subscription.parser.SubscriptionParser
import com.kiktor.v2whitelist.desktop.data.DesktopFileServerRepository
import com.kiktor.v2whitelist.desktop.data.DesktopFileSettingsRepository
import com.kiktor.v2whitelist.desktop.engine.DesktopCoreState
import com.kiktor.v2whitelist.desktop.engine.XrayDesktopRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class DesktopTab {
    SERVERS,
    LOGS,
    SETTINGS
}

class DesktopAppState(
    val serverRepo: ServerRepository = DesktopFileServerRepository(),
    val settingsRepo: SettingsRepository = DesktopFileSettingsRepository(),
    val xrayRunner: XrayDesktopRunner = XrayDesktopRunner(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    val servers: StateFlow<List<Pair<String, ProfileItem>>> = serverRepo.serversFlow
    val coreState: StateFlow<DesktopCoreState> = xrayRunner.coreState
    val lastError: StateFlow<String?> = xrayRunner.lastError
    val logs: StateFlow<List<String>> = GeekModeLogger.logs

    private val _selectedGuid = MutableStateFlow<String?>(serverRepo.getSelectedServerGuid())
    val selectedGuid: StateFlow<String?> = _selectedGuid.asStateFlow()

    private val _scenario = MutableStateFlow(settingsRepo.getAppScenario())
    val scenario: StateFlow<AppScenario> = _scenario.asStateFlow()

    private val _activeTab = MutableStateFlow(DesktopTab.SERVERS)
    val activeTab: StateFlow<DesktopTab> = _activeTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _pingMap = MutableStateFlow<Map<String, Long>>(emptyMap())
    val pingMap: StateFlow<Map<String, Long>> = _pingMap.asStateFlow()

    private val _isPinging = MutableStateFlow(false)
    val isPinging: StateFlow<Boolean> = _isPinging.asStateFlow()

    private val _isUpdatingSub = MutableStateFlow(false)
    val isUpdatingSub: StateFlow<Boolean> = _isUpdatingSub.asStateFlow()

    val subscriptionUrl = MutableStateFlow(settingsRepo.getString("subscription_url"))
    val xrayBinaryPath = MutableStateFlow(settingsRepo.getString("xray_path"))
    val httpPort = MutableStateFlow(settingsRepo.getHttpPort())
    val socksPort = MutableStateFlow(settingsRepo.getSocksPort())

    init {
        GeekModeLogger.log("DesktopApp", "v2whitelist Desktop Beta инициализирован.")
        // Автопоиск Xray бинарника, если не задан
        if (xrayBinaryPath.value.isBlank()) {
            val detected = xrayRunner.findXrayExecutable()
            if (detected != null) {
                xrayBinaryPath.value = detected.absolutePath
                settingsRepo.setString("xray_path", detected.absolutePath)
                GeekModeLogger.log("DesktopApp", "Обнаружен исполняемый файл Xray: ${detected.absolutePath}")
            }
        }
    }

    fun setTab(tab: DesktopTab) {
        _activeTab.value = tab
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectServer(guid: String) {
        _selectedGuid.value = guid
        serverRepo.setSelectedServerGuid(guid)
        if (coreState.value == DesktopCoreState.RUNNING) {
            connectCurrent()
        }
    }

    fun setScenario(sc: AppScenario) {
        _scenario.value = sc
        settingsRepo.setAppScenario(sc)
        if (coreState.value == DesktopCoreState.RUNNING) {
            connectCurrent()
        }
    }

    fun toggleConnect() {
        if (coreState.value == DesktopCoreState.RUNNING || coreState.value == DesktopCoreState.STARTING) {
            xrayRunner.stop()
        } else {
            connectCurrent()
        }
    }

    private fun connectCurrent() {
        val guid = _selectedGuid.value ?: servers.value.firstOrNull()?.first ?: return
        val profile = serverRepo.getServerConfig(guid) ?: return

        scope.launch {
            xrayRunner.start(
                profile = profile,
                scenario = _scenario.value,
                xrayBinaryPath = xrayBinaryPath.value.ifBlank { null },
                httpPort = httpPort.value,
                socksPort = socksPort.value
            )
        }
    }

    fun toggleVip(guid: String) {
        val vipGuids = serverRepo.getVipServerGuids()
        if (vipGuids.contains(guid)) {
            serverRepo.removeVipServer(guid)
        } else {
            serverRepo.addVipServer(guid)
        }
    }

    fun deleteServer(guid: String) {
        if (_selectedGuid.value == guid) {
            if (coreState.value == DesktopCoreState.RUNNING) {
                xrayRunner.stop()
            }
        }
        serverRepo.removeServer(guid)
        _selectedGuid.value = serverRepo.getSelectedServerGuid()
    }

    fun pingServer(guid: String) {
        val profile = serverRepo.getServerConfig(guid) ?: return
        scope.launch {
            val ping = NodePingTester.testSingleTcp(profile)
            _pingMap.value = _pingMap.value + (guid to ping)
        }
    }

    fun pingAllServers() {
        if (_isPinging.value) return
        val currentServers = servers.value
        if (currentServers.isEmpty()) return

        scope.launch {
            _isPinging.value = true
            try {
                val results = NodePingTester.testBatchTcp(currentServers)
                val newMap = _pingMap.value.toMutableMap()
                for ((g, _, delay) in results) {
                    newMap[g] = delay
                }
                // Для серверов, которые не ответили в results, ставим -1L
                for ((g, _) in currentServers) {
                    if (!newMap.containsKey(g)) {
                        newMap[g] = -1L
                    }
                }
                _pingMap.value = newMap
            } finally {
                _isPinging.value = false
            }
        }
    }

    fun updateSubscription() {
        val url = subscriptionUrl.value.trim()
        if (url.isBlank() || _isUpdatingSub.value) return

        scope.launch {
            _isUpdatingSub.value = true
            GeekModeLogger.log("Subscription", "Загрузка подписки: $url")
            try {
                val urls = SubscriptionFetcher.parseMirrors(url)
                val result = SubscriptionFetcher.fetchParallel(urls)
                if (result.isSuccess) {
                    val content = result.getOrNull().orEmpty()
                    val configs = SubscriptionParser.parse(content)
                    GeekModeLogger.log("Subscription", "Распарсено серверов: ${configs.size}")
                    if (configs.isNotEmpty()) {
                        serverRepo.clearAllServers()
                        for (cfg in configs) {
                            val guid = UUID.randomUUID().toString()
                            serverRepo.saveServerConfig(guid, cfg)
                        }
                        _selectedGuid.value = serverRepo.getSelectedServerGuid()
                        settingsRepo.setString("subscription_url", url)
                        pingAllServers()
                    }
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Неизвестная ошибка"
                    GeekModeLogger.log("Subscription", "Ошибка загрузки подписки: $err")
                }
            } catch (e: Exception) {
                GeekModeLogger.log("Subscription", "Сбой обновления: ${e.message}")
            } finally {
                _isUpdatingSub.value = false
            }
        }
    }

    fun saveSettings(
        subUrl: String,
        xrayPath: String,
        httpP: Int,
        socksP: Int
    ) {
        subscriptionUrl.value = subUrl
        xrayBinaryPath.value = xrayPath
        httpPort.value = httpP
        socksPort.value = socksP

        settingsRepo.setString("subscription_url", subUrl)
        settingsRepo.setString("xray_path", xrayPath)
        settingsRepo.setHttpPort(httpP)
        settingsRepo.setSocksPort(socksP)
        GeekModeLogger.log("Settings", "Настройки сохранены.")
    }
}
