package com.kiktor.v2whitelist.desktop.state

import com.kiktor.v2whitelist.core.domain.network.NetworkStatusChecker
import com.kiktor.v2whitelist.core.domain.ping.NodePingTester
import com.kiktor.v2whitelist.core.model.AppScenario
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import com.kiktor.v2whitelist.core.repository.ServerRepository
import com.kiktor.v2whitelist.core.repository.SettingsRepository
import com.kiktor.v2whitelist.core.subscription.defaults.DefaultSubscriptions
import com.kiktor.v2whitelist.core.subscription.network.SubscriptionFetcher
import com.kiktor.v2whitelist.core.subscription.parser.SubscriptionParser
import com.kiktor.v2whitelist.desktop.data.DesktopFileServerRepository
import com.kiktor.v2whitelist.desktop.data.DesktopFileSettingsRepository
import com.kiktor.v2whitelist.desktop.engine.DesktopCoreState
import com.kiktor.v2whitelist.desktop.engine.DesktopProxyManager
import com.kiktor.v2whitelist.desktop.engine.V2WCoreScanner
import com.kiktor.v2whitelist.desktop.engine.XrayDesktopRunner
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class DesktopTab {
    SERVERS,
    SUBSCRIPTIONS,
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

    private val _isV2WScanning = MutableStateFlow(false)
    val isV2WScanning: StateFlow<Boolean> = _isV2WScanning.asStateFlow()

    private val _smartConnectStatus = MutableStateFlow<String?>(null)
    val smartConnectStatus: StateFlow<String?> = _smartConnectStatus.asStateFlow()

    val subscriptionUrl = MutableStateFlow(settingsRepo.getString("subscription_url"))
    val xrayBinaryPath = MutableStateFlow(settingsRepo.getString("xray_path"))
    val v2wScanPath = MutableStateFlow(settingsRepo.getString("v2w_scan_path"))
    val httpPort = MutableStateFlow(settingsRepo.getHttpPort())
    val socksPort = MutableStateFlow(settingsRepo.getSocksPort())

    val isSystemProxyActive = MutableStateFlow(com.kiktor.v2whitelist.desktop.engine.DesktopProxyManager.isSystemProxyEnabled())
    val autoEnableSystemProxy = MutableStateFlow(settingsRepo.getBoolean("auto_enable_system_proxy", false))
    val allowLan = MutableStateFlow(settingsRepo.getBoolean("allow_lan", false))
    val clipboardNotice = MutableStateFlow<String?>(null)

    val routingMode = MutableStateFlow(settingsRepo.getString("routing_mode", "PROXY"))
    val uiTheme = MutableStateFlow(settingsRepo.getString("ui_theme", "CYAN"))
    val uiCompactCards = MutableStateFlow(settingsRepo.getBoolean("ui_compact_cards", false))
    val minimizeToTray = MutableStateFlow(settingsRepo.getBoolean("minimize_to_tray", true))

    val networkStatus = MutableStateFlow(NetworkStatusChecker.STATUS_FULL_INTERNET)
    val publicIpInfo = MutableStateFlow<String?>(null)
    val isCheckingNetwork = MutableStateFlow(false)

    val subscriptionUserAgent = MutableStateFlow(settingsRepo.getString("subscription_user_agent", "v2rayNG/1.10.x"))
    val subscriptionUpdateOnlyViaProxy = MutableStateFlow(settingsRepo.getBoolean("subscription_update_only_via_proxy", false))
    val subscriptionThreads = MutableStateFlow(settingsRepo.getInt("subscription_threads", 3))
    val subscriptionAutoUpdate = MutableStateFlow(settingsRepo.getBoolean("subscription_auto_update", false))
    val subscriptionUpdateIntervalHours = MutableStateFlow(settingsRepo.getInt("subscription_update_interval_hours", 12))
    val enabledDefaultSubIds = MutableStateFlow<Set<String>>(setOf("def_zieng2", "def_rkp_whitelist"))

    val customDirectDomains = MutableStateFlow(settingsRepo.getString("custom_direct_domains", ""))
    val customProxyDomains = MutableStateFlow(settingsRepo.getString("custom_proxy_domains", ""))
    val upstreamProxyHost = MutableStateFlow(settingsRepo.getString("upstream_proxy_host", ""))
    val upstreamProxyPort = MutableStateFlow(settingsRepo.getInt("upstream_proxy_port", 0))
    val upstreamProxyType = MutableStateFlow(settingsRepo.getString("upstream_proxy_type", "socks5"))
    val connectivityCheckUrl = MutableStateFlow(settingsRepo.getString("connectivity_check_url", "https://cp.cloudflare.com/generate_204"))
    val minSpeedThresholdMbps = MutableStateFlow(1.0)
    val tcpPingTimeoutMs = MutableStateFlow(2000L)

    // Geek Mode state
    val globalSni = MutableStateFlow(settingsRepo.getString("pref_global_sni", ""))
    val cloudflareIp = MutableStateFlow(settingsRepo.getString("pref_cloudflare_ip", ""))
    val customUserAgent = MutableStateFlow(settingsRepo.getString("pref_custom_user_agent", "Auto (Default)"))
    val exitIp = MutableStateFlow<String?>(null)
    val isPingingVip = MutableStateFlow(false)
    val isFindingVip = MutableStateFlow(false)
    val isForcePinging = MutableStateFlow(false)
    val vipGuids = MutableStateFlow<Set<String>>(serverRepo.getVipServerGuids())

    val smartConnectManager = com.kiktor.v2whitelist.desktop.engine.DesktopSmartConnectManager(
        serverRepo = serverRepo,
        settingsRepo = settingsRepo,
        xrayRunner = xrayRunner,
        scope = scope
    )

    init {
        GeekModeLogger.log("DesktopApp", "v2whitelist Desktop Beta инициализирован.")

        smartConnectManager.onStageChanged = { _, text ->
            _smartConnectStatus.value = text
        }
        smartConnectManager.onServerConnected = { guid, _ ->
            _selectedGuid.value = guid
        }

        // Автопоиск Xray бинарника, если не задан
        if (xrayBinaryPath.value.isBlank()) {
            val detected = xrayRunner.findXrayExecutable()
            if (detected != null) {
                xrayBinaryPath.value = detected.absolutePath
                settingsRepo.setString("xray_path", detected.absolutePath)
                GeekModeLogger.log("DesktopApp", "Обнаружен исполняемый файл Xray: ${detected.absolutePath}")
            }
        }
        // Автопоиск v2w-scan бинарника, если не задан
        if (v2wScanPath.value.isBlank()) {
            val detectedScanner = com.kiktor.v2whitelist.desktop.engine.V2WCoreScanner.findV2WScanExecutable()
            if (detectedScanner != null) {
                v2wScanPath.value = detectedScanner.absolutePath
                settingsRepo.setString("v2w_scan_path", detectedScanner.absolutePath)
                GeekModeLogger.log("DesktopApp", "Обнаружено ядро v2w-core сканера: ${detectedScanner.absolutePath}")
            }
        }
        // Автопроверка статуса сети при старте
        checkNetworkStatus()
    }

    fun setTab(tab: DesktopTab) {
        _activeTab.value = tab
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleRoutingMode() {
        val next = if (routingMode.value == "PROXY") "TUN" else "PROXY"
        routingMode.value = next
        settingsRepo.setString("routing_mode", next)
        GeekModeLogger.log("AppState", "Режим работы переключен на: $next")
        if (coreState.value == DesktopCoreState.RUNNING) {
            connectCurrent()
        }
    }

    fun setUiTheme(theme: String) {
        uiTheme.value = theme
        settingsRepo.setString("ui_theme", theme)
    }

    fun setUiCompactCards(compact: Boolean) {
        uiCompactCards.value = compact
        settingsRepo.setBoolean("ui_compact_cards", compact)
    }

    fun setMinimizeToTray(enabled: Boolean) {
        minimizeToTray.value = enabled
        settingsRepo.setBoolean("minimize_to_tray", enabled)
    }

    fun removeDuplicateServers(): Int {
        val count = serverRepo.removeDuplicates()
        _selectedGuid.value = serverRepo.getSelectedServerGuid()
        scope.launch {
            if (count > 0) {
                clipboardNotice.value = "Удалено дубликатов: $count"
            } else {
                clipboardNotice.value = "Дубликатов не обнаружено"
            }
            delay(2500)
            clipboardNotice.value = null
        }
        return count
    }

    fun checkNetworkStatus() {
        if (isCheckingNetwork.value) return
        scope.launch {
            isCheckingNetwork.value = true
            try {
                val client = NetworkStatusChecker.createCheckClient(timeoutMs = 3000L)
                val ya = NetworkStatusChecker.checkHttpAccess(client, "https://ya.ru")
                val google = NetworkStatusChecker.checkHttpAccess(client, "https://google.com")
                client.close()

                networkStatus.value = when {
                    ya && google -> NetworkStatusChecker.STATUS_FULL_INTERNET
                    ya && !google -> NetworkStatusChecker.STATUS_WHITELIST_ONLY
                    else -> NetworkStatusChecker.STATUS_NO_INTERNET
                }

                // Определение внешнего IP через активное соединение
                val proxyP = if (coreState.value == DesktopCoreState.RUNNING) httpPort.value else 0
                val ipClient = SubscriptionFetcher.createHttpClient(
                    httpProxyHost = "127.0.0.1",
                    httpProxyPort = proxyP,
                    timeoutMs = 4000L
                )
                try {
                    val resp = ipClient.get("https://api.myip.la/en?json")
                    if (resp.status.value in 200..299) {
                        val text = resp.bodyAsText()
                        val ipMatch = Regex("\"ip\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                        val country = Regex("\"country_name\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                        if (ipMatch != null) {
                            publicIpInfo.value = "$ipMatch (${country ?: "Online"})"
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    ipClient.close()
                }
            } catch (e: Exception) {
                GeekModeLogger.log("NetworkStatus", "Ошибка проверки сети: ${e.message}")
            } finally {
                isCheckingNetwork.value = false
            }
        }
    }

    fun toggleDefaultSub(subId: String) {
        val current = enabledDefaultSubIds.value.toMutableSet()
        if (current.contains(subId)) {
            current.remove(subId)
        } else {
            current.add(subId)
        }
        enabledDefaultSubIds.value = current
    }

    fun updateAllActiveSubscriptions() {
        if (_isUpdatingSub.value) return
        scope.launch {
            _isUpdatingSub.value = true
            GeekModeLogger.log("Subscriptions", "Обновление активных подписок...")
            try {
                val proxyP = if (subscriptionUpdateOnlyViaProxy.value && coreState.value == DesktopCoreState.RUNNING) httpPort.value else 0
                val defaultSubList = DefaultSubscriptions.PREPOPULATED_SUBS.filter { enabledDefaultSubIds.value.contains(it.id) }
                val urls = mutableListOf<String>()

                val primary = subscriptionUrl.value.trim()
                if (primary.isNotBlank()) {
                    urls.addAll(SubscriptionFetcher.parseMirrors(primary))
                }
                for (sub in defaultSubList) {
                    urls.addAll(SubscriptionFetcher.parseMirrors(sub.url))
                }

                if (urls.isEmpty()) {
                    GeekModeLogger.log("Subscriptions", "Нет URL для загрузки подписок.")
                    return@launch
                }

                val result = SubscriptionFetcher.fetchParallel(
                    urls = urls,
                    userAgent = subscriptionUserAgent.value,
                    httpProxyHost = "127.0.0.1",
                    httpProxyPort = proxyP,
                    timeoutMs = 12000L
                )

                if (result.isSuccess) {
                    val content = result.getOrNull().orEmpty()
                    val configs = SubscriptionParser.parse(content)
                    GeekModeLogger.log("Subscriptions", "Загружено серверов: ${configs.size}")
                    if (configs.isNotEmpty()) {
                        serverRepo.clearAllServers()
                        for (cfg in configs) {
                            val guid = UUID.randomUUID().toString()
                            serverRepo.saveServerConfig(guid, cfg)
                        }
                        val removed = serverRepo.removeDuplicates()
                        GeekModeLogger.log("Subscriptions", "Автоматически удалено дубликатов: $removed")
                        _selectedGuid.value = serverRepo.getSelectedServerGuid()
                        pingAllServers()
                    }
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Сбой соединения"
                    GeekModeLogger.log("Subscriptions", "Ошибка загрузки: $err")
                }
            } catch (e: Exception) {
                GeekModeLogger.log("Subscriptions", "Сбой: ${e.message}")
            } finally {
                _isUpdatingSub.value = false
            }
        }
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
            stopCore()
        } else {
            connectCurrent()
        }
    }

    fun stopCore() {
        xrayRunner.stop()
        if (isSystemProxyActive.value) {
            DesktopProxyManager.disableSystemProxy()
            isSystemProxyActive.value = false
        }
    }

    fun toggleSystemProxy() {
        val target = !isSystemProxyActive.value
        if (target) {
            val success = DesktopProxyManager.enableSystemProxy(socksPort.value, httpPort.value)
            isSystemProxyActive.value = success
        } else {
            DesktopProxyManager.disableSystemProxy()
            isSystemProxyActive.value = false
        }
    }

    fun setAutoEnableSystemProxy(enabled: Boolean) {
        autoEnableSystemProxy.value = enabled
        settingsRepo.setBoolean("auto_enable_system_proxy", enabled)
    }

    fun setAllowLan(enabled: Boolean) {
        allowLan.value = enabled
        settingsRepo.setBoolean("allow_lan", enabled)
        if (coreState.value == DesktopCoreState.RUNNING) {
            connectCurrent()
        }
    }

    fun copyToClipboard(text: String, label: String) {
        if (DesktopProxyManager.copyToClipboard(text)) {
            scope.launch {
                clipboardNotice.value = "Скопировано: $label"
                delay(2500)
                clipboardNotice.value = null
            }
        }
    }

    fun openUri(uri: String) {
        DesktopProxyManager.openUri(uri)
    }

    private fun connectCurrent() {
        val guid = _selectedGuid.value ?: servers.value.firstOrNull()?.first ?: return
        val rawProfile = serverRepo.getServerConfig(guid) ?: return

        // Применяем GeekMode оверрайды (как в оригинальном Android GeekMode)
        var profile = rawProfile
        val cfIp = cloudflareIp.value.trim()
        val gSni = globalSni.value.trim()
        if (cfIp.isNotBlank() && (profile.network == "ws" || profile.network == "httpupgrade" || profile.network == "grpc")) {
            profile = profile.copy(server = cfIp)
            GeekModeLogger.log("GeekMode", "Применён оверрайд Cloudflare IP: $cfIp для ${profile.remarks}")
        }
        if (gSni.isNotBlank()) {
            profile = profile.copy(sni = gSni)
            GeekModeLogger.log("GeekMode", "Применён оверрайд Global SNI: $gSni для ${profile.remarks}")
        }

        scope.launch {
            val listenAddr = if (allowLan.value) "0.0.0.0" else "127.0.0.1"
            val directDomains = customDirectDomains.value.split(",", "\n")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            val proxyDomains = customProxyDomains.value.split(",", "\n")
                .map { it.trim() }
                .filter { it.isNotBlank() }

            val started = xrayRunner.start(
                profile = profile,
                scenario = _scenario.value,
                xrayBinaryPath = xrayBinaryPath.value.ifBlank { null },
                httpPort = httpPort.value,
                socksPort = socksPort.value,
                listenAddress = listenAddr,
                customDirectDomains = directDomains,
                customProxyDomains = proxyDomains,
                upstreamProxyHost = upstreamProxyHost.value.ifBlank { null },
                upstreamProxyPort = if (upstreamProxyPort.value > 0) upstreamProxyPort.value else null,
                upstreamProxyType = upstreamProxyType.value
            )
            if (started) {
                if (autoEnableSystemProxy.value || routingMode.value == "PROXY") {
                    DesktopProxyManager.enableSystemProxy(socksPort.value, httpPort.value)
                    isSystemProxyActive.value = true
                }
                checkNetworkStatus()
                fetchExitIp()
            }
        }
    }

    fun toggleVip(guid: String) {
        val currentVips = serverRepo.getVipServerGuids()
        if (currentVips.contains(guid)) {
            serverRepo.removeVipServer(guid)
        } else {
            serverRepo.addVipServer(guid)
        }
        vipGuids.value = serverRepo.getVipServerGuids()
    }

    fun removeVipServer(guid: String) {
        serverRepo.removeVipServer(guid)
        vipGuids.value = serverRepo.getVipServerGuids()
        GeekModeLogger.log("GeekMode", "Сервер удален из VIP списка вручную")
    }

    fun setGlobalSni(sni: String) {
        globalSni.value = sni
        settingsRepo.setString("pref_global_sni", sni)
        GeekModeLogger.log("GeekMode", "Сохранён Global SNI: $sni")
    }

    fun setCloudflareIp(ip: String) {
        cloudflareIp.value = ip
        settingsRepo.setString("pref_cloudflare_ip", ip)
        GeekModeLogger.log("GeekMode", "Сохранён Cloudflare IP: $ip")
    }

    fun setCustomUserAgent(ua: String) {
        customUserAgent.value = ua
        settingsRepo.setString("pref_custom_user_agent", ua)
        GeekModeLogger.log("GeekMode", "Сохранён User-Agent: $ua")
    }

    fun fetchExitIp() {
        scope.launch {
            val proxyP = if (coreState.value == DesktopCoreState.RUNNING) httpPort.value else 0
            val client = SubscriptionFetcher.createHttpClient(
                httpProxyHost = "127.0.0.1",
                httpProxyPort = proxyP,
                timeoutMs = 4000L
            )
            try {
                val resp = client.get("https://api.myip.la/en?json")
                if (resp.status.value in 200..299) {
                    val text = resp.bodyAsText()
                    val ipMatch = Regex("\"ip\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                    val country = Regex("\"country_name\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                    if (ipMatch != null) {
                        val full = "$ipMatch (${country ?: "Online"})"
                        exitIp.value = full
                        GeekModeLogger.log("GeekMode", "Exit IP определён: $full")
                    }
                }
            } catch (e: Exception) {
                GeekModeLogger.log("GeekMode", "Не удалось определить Exit IP: ${e.message}")
            } finally {
                client.close()
            }
        }
    }

    fun forcePingTest() {
        val guid = _selectedGuid.value ?: return
        val profile = serverRepo.getServerConfig(guid) ?: return
        scope.launch {
            isForcePinging.value = true
            GeekModeLogger.log("GeekMode", "Force Ping: проверка ${profile.remarks} (${profile.server}:${profile.serverPort})...")
            val ping = NodePingTester.testSingleTcp(profile)
            _pingMap.value = _pingMap.value + (guid to ping)
            GeekModeLogger.log("GeekMode", "Результат теста задержки: ${if (ping > 0) "${ping}ms" else "Ошибка (-1)"}")
            fetchExitIp()
            isForcePinging.value = false
        }
    }

    fun pingVipServers() {
        val vips = serverRepo.getVipServerGuids()
        if (vips.isEmpty()) {
            GeekModeLogger.log("GeekMode", "Список VIP серверов пуст.")
            return
        }
        scope.launch {
            isPingingVip.value = true
            GeekModeLogger.log("GeekMode", "Пинг ${vips.size} VIP узлов...")
            for (guid in vips) {
                val profile = serverRepo.getServerConfig(guid) ?: continue
                val ping = NodePingTester.testSingleTcp(profile)
                _pingMap.value = _pingMap.value + (guid to ping)
                GeekModeLogger.log("GeekMode", "VIP [${profile.remarks}]: ${ping}ms")
            }
            GeekModeLogger.log("GeekMode", "Пинг VIP узлов завершён.")
            isPingingVip.value = false
        }
    }

    fun findMoreVipServers() {
        val all = servers.value
        if (all.isEmpty()) {
            GeekModeLogger.log("GeekMode", "Нет серверов для сканирования.")
            return
        }
        scope.launch {
            isFindingVip.value = true
            GeekModeLogger.log("GeekMode", "Запуск быстрого сканера для поиска дополнительных VIP...")
            val v2wPath = settingsRepo.getString("v2w_scan_path").ifBlank { null }
            V2WCoreScanner.runStreamingScan(
                servers = all,
                customBinaryPath = v2wPath,
                firstOnly = false,
                onNodeSuccess = { guid: String, profile: ProfileItem, delayMs: Long ->
                    if (delayMs in 1..800) {
                        serverRepo.addVipServer(guid)
                        _pingMap.value = _pingMap.value + (guid to delayMs)
                        vipGuids.value = serverRepo.getVipServerGuids()
                        GeekModeLogger.log("GeekMode", "Добавлен в VIP: ${profile.remarks} (${delayMs}ms)")
                    }
                    false
                }
            )
            vipGuids.value = serverRepo.getVipServerGuids()
            GeekModeLogger.log("GeekMode", "Поиск завершён. Всего VIP: ${vipGuids.value.size}")
            isFindingVip.value = false
        }
    }


    fun deleteServer(guid: String) {
        if (_selectedGuid.value == guid) {
            if (coreState.value == DesktopCoreState.RUNNING) {
                stopCore()
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

    fun runSmartConnect(minSpeedMbps: Double = 1.0) {
        if (_isV2WScanning.value) return
        scope.launch {
            _isV2WScanning.value = true
            try {
                smartConnectManager.startSmartConnect(
                    scenario = _scenario.value,
                    minSpeedMbps = minSpeedMbps,
                    speedCheckEnabled = true
                )
            } catch (e: Exception) {
                GeekModeLogger.log("SmartConnect", "Сбой SmartConnect: ${e.message}")
            } finally {
                _isV2WScanning.value = false
            }
        }
    }

    fun runV2WScan(autoConnectFirst: Boolean = true) {
        runSmartConnect()
    }

    fun saveSettings(
        subUrl: String,
        xrayPath: String,
        v2wPath: String,
        httpP: Int,
        socksP: Int,
        autoSystemProxy: Boolean = autoEnableSystemProxy.value,
        lan: Boolean = allowLan.value,
        directDomains: String = customDirectDomains.value,
        proxyDomains: String = customProxyDomains.value,
        subUserAgent: String = subscriptionUserAgent.value,
        subThreads: Int = subscriptionThreads.value,
        subAutoUpdate: Boolean = subscriptionAutoUpdate.value,
        subInterval: Int = subscriptionUpdateIntervalHours.value,
        subOnlyViaProxy: Boolean = subscriptionUpdateOnlyViaProxy.value,
        upHost: String = upstreamProxyHost.value,
        upPort: Int = upstreamProxyPort.value,
        upType: String = upstreamProxyType.value,
        theme: String = uiTheme.value,
        compact: Boolean = uiCompactCards.value,
        checkUrl: String = connectivityCheckUrl.value,
        minSpeed: Double = minSpeedThresholdMbps.value
    ) {
        subscriptionUrl.value = subUrl
        xrayBinaryPath.value = xrayPath
        v2wScanPath.value = v2wPath
        httpPort.value = httpP
        socksPort.value = socksP
        autoEnableSystemProxy.value = autoSystemProxy
        allowLan.value = lan
        customDirectDomains.value = directDomains
        customProxyDomains.value = proxyDomains
        subscriptionUserAgent.value = subUserAgent
        subscriptionThreads.value = subThreads
        subscriptionAutoUpdate.value = subAutoUpdate
        subscriptionUpdateIntervalHours.value = subInterval
        subscriptionUpdateOnlyViaProxy.value = subOnlyViaProxy
        upstreamProxyHost.value = upHost
        upstreamProxyPort.value = upPort
        upstreamProxyType.value = upType
        uiTheme.value = theme
        uiCompactCards.value = compact
        connectivityCheckUrl.value = checkUrl
        minSpeedThresholdMbps.value = minSpeed

        settingsRepo.setString("subscription_url", subUrl)
        settingsRepo.setString("xray_path", xrayPath)
        settingsRepo.setString("v2w_scan_path", v2wPath)
        settingsRepo.setHttpPort(httpP)
        settingsRepo.setSocksPort(socksP)
        settingsRepo.setBoolean("auto_enable_system_proxy", autoSystemProxy)
        settingsRepo.setBoolean("allow_lan", lan)
        settingsRepo.setString("custom_direct_domains", directDomains)
        settingsRepo.setString("custom_proxy_domains", proxyDomains)
        settingsRepo.setString("subscription_user_agent", subUserAgent)
        settingsRepo.setInt("subscription_threads", subThreads)
        settingsRepo.setBoolean("subscription_auto_update", subAutoUpdate)
        settingsRepo.setInt("subscription_update_interval_hours", subInterval)
        settingsRepo.setBoolean("subscription_update_only_via_proxy", subOnlyViaProxy)
        settingsRepo.setString("upstream_proxy_host", upHost)
        settingsRepo.setInt("upstream_proxy_port", upPort)
        settingsRepo.setString("upstream_proxy_type", upType)
        settingsRepo.setString("ui_theme", theme)
        settingsRepo.setBoolean("ui_compact_cards", compact)
        settingsRepo.setString("connectivity_check_url", checkUrl)

        GeekModeLogger.log("Settings", "Все настройки успешно сохранены.")

        if (coreState.value == DesktopCoreState.RUNNING) {
            connectCurrent()
        }
    }
}
