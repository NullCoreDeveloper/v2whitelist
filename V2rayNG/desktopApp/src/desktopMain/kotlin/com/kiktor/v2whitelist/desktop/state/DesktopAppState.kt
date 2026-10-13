package com.kiktor.v2whitelist.desktop.state

import com.kiktor.v2whitelist.core.domain.network.NetworkStatusChecker
import com.kiktor.v2whitelist.core.domain.ping.NodePingTester
import com.kiktor.v2whitelist.core.model.AppScenario
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import com.kiktor.v2whitelist.core.repository.ServerRepository
import com.kiktor.v2whitelist.core.repository.SettingsRepository
import com.kiktor.v2whitelist.core.subscription.defaults.DefaultSubscriptions
import com.kiktor.v2whitelist.core.subscription.model.CustomSubData
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class DesktopTab {
    MAIN,
    SUBSCRIPTIONS,
    GEEK_MODE,
    APPEARANCE,
    SETTINGS
}

data class SubscriptionStatusInfo(
    val statusText: String,
    val timeText: String,
    val isActual: Boolean,
    val failedCount: Int
)

class DesktopAppState(
    val serverRepo: ServerRepository = DesktopFileServerRepository(),
    val settingsRepo: SettingsRepository = DesktopFileSettingsRepository(),
    val xrayRunner: XrayDesktopRunner = XrayDesktopRunner(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    val autoCheckNetworkOnStart: Boolean = true
) {
    private val internalStateScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val jsonHelper = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    val servers: StateFlow<List<Pair<String, ProfileItem>>> = serverRepo.serversFlow
    val coreState: StateFlow<DesktopCoreState> = xrayRunner.coreState
    val lastError: StateFlow<String?> = xrayRunner.lastError
    val logs: StateFlow<List<String>> = GeekModeLogger.logs

    private val _selectedGuid = MutableStateFlow<String?>(serverRepo.getSelectedServerGuid())
    val selectedGuid: StateFlow<String?> = _selectedGuid.asStateFlow()

    private val _scenario = MutableStateFlow(settingsRepo.getAppScenario())
    val scenario: StateFlow<AppScenario> = _scenario.asStateFlow()

    private val _activeTab = MutableStateFlow(DesktopTab.MAIN)
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

    // --- Управление подписками (полный KMP список как в Android) ---
    private val _subscriptions = MutableStateFlow<List<CustomSubData>>(emptyList())
    val subscriptions: StateFlow<List<CustomSubData>> = _subscriptions.asStateFlow()

    private val _removedSubIds = MutableStateFlow<Set<String>>(emptySet())
    val removedSubIds: StateFlow<Set<String>> = _removedSubIds.asStateFlow()

    // Статус актуальности подписки на главном экране (актуальна, если час совпадает)
    val subscriptionStatus: StateFlow<SubscriptionStatusInfo> = _subscriptions.combine(_isUpdatingSub) { subs, updating ->
        calculateSubscriptionStatus(subs, updating)
    }.stateIn(
        internalStateScope,
        SharingStarted.Eagerly,
        SubscriptionStatusInfo("Нет подписок", "", false, 0)
    )

    // Серверы, отфильтрованные по текущему выбранному сценарию (Авто, БС, ЧС, YouTube)
    val scenarioFilteredServers: StateFlow<List<Pair<String, ProfileItem>>> = combine(servers, _scenario, _subscriptions) { serverList, sc, subList ->
        filterServersByScenario(serverList, sc, subList)
    }.stateIn(internalStateScope, SharingStarted.Eagerly, emptyList())

    val subscriptionUrl = MutableStateFlow(settingsRepo.getString("subscription_url"))
    val xrayBinaryPath = MutableStateFlow(settingsRepo.getString("xray_path"))
    val v2wScanPath = MutableStateFlow(settingsRepo.getString("v2w_scan_path"))
    val httpPort = MutableStateFlow(settingsRepo.getHttpPort())
    val socksPort = MutableStateFlow(settingsRepo.getSocksPort())

    val isSystemProxyActive = MutableStateFlow(DesktopProxyManager.isSystemProxyEnabled())
    val autoEnableSystemProxy = MutableStateFlow(settingsRepo.getBoolean("auto_enable_system_proxy", false))
    val allowLan = MutableStateFlow(settingsRepo.getBoolean("allow_lan", false))
    val clipboardNotice = MutableStateFlow<String?>(null)

    val routingMode = MutableStateFlow(settingsRepo.getString("routing_mode", "PROXY"))
    val uiTheme = MutableStateFlow(settingsRepo.getString("ui_theme", "CYAN"))
    val uiCompactCards = MutableStateFlow(settingsRepo.getBoolean("ui_compact_cards", false))
    val minimizeToTray = MutableStateFlow(settingsRepo.getBoolean("minimize_to_tray", true))

    // Оформление
    val themeMode = MutableStateFlow(settingsRepo.getString("pref_theme_mode", "DARK")) // SYSTEM, LIGHT, DARK
    val themePalette = MutableStateFlow(settingsRepo.getString("pref_theme_palette", "CYAN")) // CYAN, BLUE, MINT, PURPLE, AMBER, ROSE
    val pureBlackAmoled = MutableStateFlow(settingsRepo.getBoolean("pref_amoled_black", false))
    val appLanguage = MutableStateFlow(settingsRepo.getString("pref_app_language", "RU")) // SYSTEM, RU, EN, FA, ZH
    val meshGradientEnabled = MutableStateFlow(settingsRepo.getBoolean("pref_mesh_gradient_enabled", false))
    val meshGradientMode = MutableStateFlow(settingsRepo.getString("pref_mesh_gradient_mode", "STATIC")) // STATIC, BREATHING, REACTIVE

    val networkStatus = MutableStateFlow(NetworkStatusChecker.STATUS_FULL_INTERNET)
    val publicIpInfo = MutableStateFlow<String?>(null)
    val isCheckingNetwork = MutableStateFlow(false)

    val subscriptionUserAgent = MutableStateFlow(settingsRepo.getString("subscription_user_agent", "v2rayNG/1.10.x"))
    val subscriptionUpdateOnlyViaProxy = MutableStateFlow(settingsRepo.getBoolean("subscription_update_only_via_proxy", false))
    val subscriptionThreads = MutableStateFlow(settingsRepo.getInt("subscription_threads", 3))
    val subscriptionAutoUpdate = MutableStateFlow(settingsRepo.getBoolean("subscription_auto_update", true))
    val subscriptionUpdateIntervalMinutes = MutableStateFlow(settingsRepo.getInt("subscription_update_interval_minutes", 60))
    val yandexTranslateUpdateEnabled = MutableStateFlow(settingsRepo.getBoolean("pref_yandex_translate_update", false))

    // Сплит-туннелинг и раздельная маршрутизация
    val domainSplitTunnelingEnabled = MutableStateFlow(settingsRepo.getBoolean("pref_domain_split_tunneling", true))
    val bypassRussianServices = MutableStateFlow(settingsRepo.getBoolean("pref_bypass_russian_services", true))
    val customDirectDomains = MutableStateFlow(settingsRepo.getString("custom_direct_domains", ""))
    val customProxyDomains = MutableStateFlow(settingsRepo.getString("custom_proxy_domains", ""))

    // Личный прокси как конечный узел (цепочка: SmartConnect -> Конечный узел)
    val useCustomEndpoint = MutableStateFlow(settingsRepo.getBoolean("pref_custom_endpoint_enabled", false))
    val customEndpointUrl = MutableStateFlow(settingsRepo.getString("pref_custom_endpoint_url", ""))

    // Geek Mode state
    val globalSni = MutableStateFlow(settingsRepo.getString("pref_global_sni", ""))
    val cloudflareIp = MutableStateFlow(settingsRepo.getString("pref_cloudflare_ip", ""))
    val customUserAgent = MutableStateFlow(settingsRepo.getString("pref_custom_user_agent", "Auto (Default)"))
    val exitIp = MutableStateFlow<String?>(null)
    val isPingingVip = MutableStateFlow(false)
    val isFindingVip = MutableStateFlow(false)
    val isForcePinging = MutableStateFlow(false)
    val vipGuids = MutableStateFlow<Set<String>>(serverRepo.getVipServerGuids())

    // Расширенные настройки ядра и VPN
    val smartConnectBatchSize = MutableStateFlow(settingsRepo.getInt("pref_smart_batch_size", 20))
    val smartDistributionPreset = MutableStateFlow(settingsRepo.getString("pref_smart_distribution", "EQUAL"))
    val useV2WCore = MutableStateFlow(settingsRepo.getBoolean("pref_use_v2w_core", true))
    val useFallbackScanner = MutableStateFlow(settingsRepo.getBoolean("pref_use_fallback_scanner", true))
    val v2wCoreSockets = MutableStateFlow(settingsRepo.getInt("pref_v2w_core_sockets", 35))

    val removeInsecureProfiles = MutableStateFlow(settingsRepo.getBoolean("pref_remove_insecure_profiles", true))
    val testProfileBeforeConnect = MutableStateFlow(settingsRepo.getBoolean("pref_test_before_connect", true))
    val speedTestOnCheck = MutableStateFlow(settingsRepo.getBoolean("pref_speed_test_on_check", true))
    val speedTestDownloadBytes = MutableStateFlow(settingsRepo.getInt("pref_speed_test_bytes", 2000000))
    val speedTestTimeoutMs = MutableStateFlow(settingsRepo.getInt("pref_speed_test_timeout_ms", 3000))
    val minSpeedThresholdMbps = MutableStateFlow(settingsRepo.getString("pref_min_speed_mbps", "1.0").toDoubleOrNull() ?: 1.0)

    val autoFailover = MutableStateFlow(settingsRepo.getBoolean("pref_auto_failover", true))
    val heartbeatIntervalSeconds = MutableStateFlow(settingsRepo.getInt("pref_heartbeat_interval", 30))
    val smartFailover = MutableStateFlow(settingsRepo.getBoolean("pref_smart_failover", true))
    val failoverDegradationThresholdKb = MutableStateFlow(settingsRepo.getInt("pref_failover_degradation_kb", 3))
    val failoverStuckDurationSeconds = MutableStateFlow(settingsRepo.getInt("pref_failover_stuck_sec", 3))

    val networkCheckEnabled = MutableStateFlow(settingsRepo.getBoolean("pref_network_check_enabled", true))
    val networkCheckPrimaryDomain = MutableStateFlow(settingsRepo.getString("pref_net_check_primary", "google.com"))
    val networkCheckFallbackDomain = MutableStateFlow(settingsRepo.getString("pref_net_check_fallback", "ya.ru"))

    val autoCheckUpdates = MutableStateFlow(settingsRepo.getBoolean("pref_auto_check_updates", true))
    val receiveBetaUpdates = MutableStateFlow(settingsRepo.getBoolean("pref_receive_beta_updates", false))
    val connectionCheckServiceUrl = MutableStateFlow(settingsRepo.getString("pref_conn_check_url", "https://api.ip.sb/geoip"))

    // Базовые параметры VPN и ядра
    val preferIpv6 = MutableStateFlow(settingsRepo.getBoolean("pref_prefer_ipv6", false))
    val useLocalDns = MutableStateFlow(settingsRepo.getBoolean("pref_use_local_dns", true))
    val useFakeDns = MutableStateFlow(settingsRepo.getBoolean("pref_use_fakedns", false))
    val additionalHttpProxy = MutableStateFlow(settingsRepo.getBoolean("pref_add_http_proxy", false))
    val vpnDns = MutableStateFlow(settingsRepo.getString("pref_vpn_dns", "1.1.1.1"))
    val vpnBypassLan = MutableStateFlow(settingsRepo.getBoolean("pref_vpn_bypass_lan", true))
    val vpnInterfaceAddress = MutableStateFlow(settingsRepo.getString("pref_vpn_address", "10.10.14.1"))
    val vpnMtu = MutableStateFlow(settingsRepo.getInt("pref_vpn_mtu", 1500))

    val sniffingEnabled = MutableStateFlow(settingsRepo.getBoolean("pref_sniffing_enabled", true))
    val routeOnly = MutableStateFlow(settingsRepo.getBoolean("pref_route_only", false))
    val allowInsecure = MutableStateFlow(settingsRepo.getBoolean("pref_allow_insecure", false))
    val localProxyPort = MutableStateFlow(settingsRepo.getInt("pref_local_proxy_port", 35255))
    val localProxyLogin = MutableStateFlow(settingsRepo.getString("pref_local_proxy_login", "v2w_${UUID.randomUUID().toString().take(6)}"))
    val localProxyPassword = MutableStateFlow(settingsRepo.getString("pref_local_proxy_pass", UUID.randomUUID().toString().take(10)))
    val remoteDns = MutableStateFlow(settingsRepo.getString("pref_remote_dns", "1.1.1.1"))
    val internalDns = MutableStateFlow(settingsRepo.getString("pref_internal_dns", "223.5.5.5"))
    val dnsHosts = MutableStateFlow(settingsRepo.getString("pref_dns_hosts", ""))

    val muxEnabled = MutableStateFlow(settingsRepo.getBoolean("pref_mux_enabled", false))
    val muxTcpConcurrency = MutableStateFlow(settingsRepo.getInt("pref_mux_tcp", 8))
    val muxXudpConcurrency = MutableStateFlow(settingsRepo.getInt("pref_mux_xudp", 8))

    val fragmentEnabled = MutableStateFlow(settingsRepo.getBoolean("pref_fragment_enabled", false))
    val fragmentLength = MutableStateFlow(settingsRepo.getString("pref_fragment_length", "50-100"))
    val fragmentInterval = MutableStateFlow(settingsRepo.getString("pref_fragment_interval", "10-20"))
    val fragmentPackets = MutableStateFlow(settingsRepo.getString("pref_fragment_packets", "tlshello"))

    val smartConnectManager = com.kiktor.v2whitelist.desktop.engine.DesktopSmartConnectManager(
        serverRepo = serverRepo,
        settingsRepo = settingsRepo,
        xrayRunner = xrayRunner,
        scope = scope
    )

    init {
        GeekModeLogger.log("DesktopApp", "v2whitelist Desktop инициализирован по спецификации UI пробел.")

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
            val detectedScanner = V2WCoreScanner.findV2WScanExecutable()
            if (detectedScanner != null) {
                v2wScanPath.value = detectedScanner.absolutePath
                settingsRepo.setString("v2w_scan_path", detectedScanner.absolutePath)
                GeekModeLogger.log("DesktopApp", "Обнаружено ядро v2w-core сканера: ${detectedScanner.absolutePath}")
            }
        }

        // Загрузка и инициализация подписок
        loadSubscriptions()

        // Проверка сети при старте (если разрешено)
        if (autoCheckNetworkOnStart) {
            checkNetworkStatus()
        }
    }

    private fun loadSubscriptions() {
        val removedJson = settingsRepo.getString("pref_removed_custom_sub_ids", "")
        val removedSet = try {
            if (removedJson.isNotBlank()) jsonHelper.decodeFromString<List<String>>(removedJson).toSet() else emptySet()
        } catch (_: Exception) {
            emptySet()
        }
        _removedSubIds.value = removedSet

        val subsJson = settingsRepo.getString("pref_custom_sub_urls", "")
        val loadedList: List<CustomSubData> = try {
            if (subsJson.isNotBlank()) {
                jsonHelper.decodeFromString<List<CustomSubData>>(subsJson)
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }

        // Если список пуст, наполняем встроенными (за исключением тех, что пользователь явно удалял)
        val initialSubs = if (loadedList.isEmpty()) {
            val defaults = DefaultSubscriptions.PREPOPULATED_SUBS.filter { !removedSet.contains(it.id) }
            saveSubscriptionsToStorage(defaults)
            defaults
        } else {
            // Добавляем новые предустановленные, если появились
            val existingIds = loadedList.map { it.id }.toSet()
            val missingDefaults = DefaultSubscriptions.PREPOPULATED_SUBS.filter {
                !existingIds.contains(it.id) && !removedSet.contains(it.id)
            }
            if (missingDefaults.isNotEmpty()) {
                val merged = loadedList + missingDefaults
                saveSubscriptionsToStorage(merged)
                merged
            } else {
                loadedList
            }
        }

        _subscriptions.value = initialSubs
    }

    private fun saveSubscriptionsToStorage(subs: List<CustomSubData>) {
        try {
            val jsonStr = jsonHelper.encodeToString(subs)
            settingsRepo.setString("pref_custom_sub_urls", jsonStr)
        } catch (e: Exception) {
            GeekModeLogger.log("Subscriptions", "Ошибка сохранения подписок: ${e.message}")
        }
    }

    private fun calculateSubscriptionStatus(subs: List<CustomSubData>, isUpdating: Boolean): SubscriptionStatusInfo {
        if (isUpdating) {
            return SubscriptionStatusInfo(
                statusText = "Обновление…",
                timeText = "Загрузка серверов",
                isActual = true,
                failedCount = 0
            )
        }

        val enabledSubs = subs.filter { it.enabled }
        val failedCount = enabledSubs.count { it.lastUpdateFailed }
        val lastUpdateTime = enabledSubs.filter { it.lastUpdated > 0 }
            .minOfOrNull { it.lastUpdated } ?: 0L

        if (failedCount > 0) {
            val timeStr = formatPassedTime(lastUpdateTime)
            return SubscriptionStatusInfo(
                statusText = "Сбой обновления ($failedCount)",
                timeText = if (timeStr.isNotBlank()) timeStr else "",
                isActual = false,
                failedCount = failedCount
            )
        }

        if (lastUpdateTime <= 0L) {
            return SubscriptionStatusInfo(
                statusText = "Нет подписок",
                timeText = "",
                isActual = false,
                failedCount = 0
            )
        }

        val updateCal = Calendar.getInstance().apply { timeInMillis = lastUpdateTime }
        val currentCal = Calendar.getInstance()

        // Правило ui.txt и Android пробел: актуальна ТОЛЬКО если совпадает текущий календарный час!
        val isActual = updateCal.get(Calendar.YEAR) == currentCal.get(Calendar.YEAR) &&
                updateCal.get(Calendar.DAY_OF_YEAR) == currentCal.get(Calendar.DAY_OF_YEAR) &&
                updateCal.get(Calendar.HOUR_OF_DAY) == currentCal.get(Calendar.HOUR_OF_DAY)

        val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        val formattedTime = timeFormatter.format(Date(lastUpdateTime))
        val elapsedMs = (currentCal.timeInMillis - lastUpdateTime).coerceAtLeast(0L)
        val elapsedMins = elapsedMs / (60 * 1000)

        val passedDetail = if (elapsedMins < 60) {
            "$elapsedMins мин"
        } else {
            val h = elapsedMins / 60
            val m = elapsedMins % 60
            "$h ч $m мин"
        }

        val fullTimeText = "$formattedTime (прошло $passedDetail)"

        return SubscriptionStatusInfo(
            statusText = if (isActual) "Актуальна" else "Устарела",
            timeText = fullTimeText,
            isActual = isActual,
            failedCount = 0
        )
    }

    private fun formatPassedTime(lastUpdateTime: Long): String {
        if (lastUpdateTime <= 0) return ""
        val elapsedMins = (System.currentTimeMillis() - lastUpdateTime).coerceAtLeast(0L) / (60 * 1000)
        val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        val formattedTime = timeFormatter.format(Date(lastUpdateTime))
        return if (elapsedMins < 60) {
            "$formattedTime (прошло $elapsedMins мин)"
        } else {
            val h = elapsedMins / 60
            val m = elapsedMins % 60
            "$formattedTime (прошло $h ч $m мин)"
        }
    }

    private fun filterServersByScenario(
        allServers: List<Pair<String, ProfileItem>>,
        sc: AppScenario,
        subList: List<CustomSubData>
    ): List<Pair<String, ProfileItem>> {
        if (allServers.isEmpty()) return emptyList()

        val subScenarioMap = subList.associate { it.id to it.targetScenarios }
        val subNameMap = subList.associate { it.name to it.targetScenarios }

        fun isMatchingSub(subIdOrName: String, target: String): Boolean {
            val scenarios = subScenarioMap[subIdOrName] ?: subNameMap[subIdOrName] ?: emptyList()
            return scenarios.contains(target)
        }

        val matched = when (sc) {
            AppScenario.AUTO -> {
                // Авторежим балансирует между серверами БС и ЧС
                allServers.filter { (_, p) ->
                    val sid = p.subscriptionId
                    isMatchingSub(sid, CustomSubData.SCENARIO_WHITELIST) || isMatchingSub(sid, CustomSubData.SCENARIO_BLACKLIST)
                }
            }
            AppScenario.WHITELIST -> {
                allServers.filter { (_, p) ->
                    isMatchingSub(p.subscriptionId, CustomSubData.SCENARIO_WHITELIST)
                }
            }
            AppScenario.VPN_BLACKLIST -> {
                allServers.filter { (_, p) ->
                    isMatchingSub(p.subscriptionId, CustomSubData.SCENARIO_BLACKLIST)
                }
            }
            AppScenario.YOUTUBE -> {
                allServers.filter { (_, p) ->
                    isMatchingSub(p.subscriptionId, CustomSubData.SCENARIO_YOUTUBE)
                }
            }
            else -> allServers
        }

        // Если для фильтра серверов не нашлось, возвращаем все, чтобы не оставлять список пустым
        return if (matched.isNotEmpty()) matched else allServers
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

    fun setThemeMode(mode: String) {
        themeMode.value = mode
        settingsRepo.setString("pref_theme_mode", mode)
    }

    fun setThemePalette(palette: String) {
        themePalette.value = palette
        settingsRepo.setString("pref_theme_palette", palette)
    }

    fun setPureBlackAmoled(enabled: Boolean) {
        pureBlackAmoled.value = enabled
        settingsRepo.setBoolean("pref_amoled_black", enabled)
    }

    fun setAppLanguage(lang: String) {
        appLanguage.value = lang
        settingsRepo.setString("pref_app_language", lang)
    }

    fun setMeshGradientEnabled(enabled: Boolean) {
        meshGradientEnabled.value = enabled
        settingsRepo.setBoolean("pref_mesh_gradient_enabled", enabled)
    }

    fun setMeshGradientMode(mode: String) {
        meshGradientMode.value = mode
        settingsRepo.setString("pref_mesh_gradient_mode", mode)
    }

    fun setUiCompactCards(compact: Boolean) {
        uiCompactCards.value = compact
        settingsRepo.setBoolean("ui_compact_cards", compact)
    }

    fun setMinimizeToTray(enabled: Boolean) {
        minimizeToTray.value = enabled
        settingsRepo.setBoolean("minimize_to_tray", enabled)
    }

    // --- Методы управления подписками ---
    fun toggleSubscriptionEnabled(subId: String) {
        val updated = _subscriptions.value.map {
            if (it.id == subId) it.copy(enabled = !it.enabled) else it
        }
        _subscriptions.value = updated
        saveSubscriptionsToStorage(updated)
    }

    fun saveSubscription(subData: CustomSubData) {
        val current = _subscriptions.value.toMutableList()
        val index = current.indexOfFirst { it.id == subData.id }
        if (index >= 0) {
            current[index] = subData
        } else {
            val newSub = if (subData.id.isBlank()) subData.copy(id = "custom_sub_${UUID.randomUUID().toString().take(8)}") else subData
            current.add(newSub)
        }
        _subscriptions.value = current
        saveSubscriptionsToStorage(current)
    }

    fun deleteSubscription(subId: String) {
        // Удаляем подписку из списка
        val subToRemove = _subscriptions.value.find { it.id == subId }
        val updated = _subscriptions.value.filter { it.id != subId }
        _subscriptions.value = updated
        saveSubscriptionsToStorage(updated)

        // Если это дефолтная подписка, добавляем в removedSubIds, чтобы не воскресала
        if (subId.startsWith("def_") || subToRemove?.id?.startsWith("def_") == true) {
            val newRemoved = _removedSubIds.value + subId
            _removedSubIds.value = newRemoved
            settingsRepo.setString("pref_removed_custom_sub_ids", jsonHelper.encodeToString(newRemoved.toList()))
        }

        // Удаляем все серверы этой подписки
        val allServers = servers.value
        val toRemoveGuids = allServers.filter {
            it.second.subscriptionId == subId || (subToRemove != null && it.second.subscriptionId == subToRemove.name)
        }.map { it.first }

        for (g in toRemoveGuids) {
            serverRepo.removeServer(g)
        }
        _selectedGuid.value = serverRepo.getSelectedServerGuid()
        GeekModeLogger.log("Subscriptions", "Подписка удалена: $subId (серверов удалено: ${toRemoveGuids.size})")
    }

    fun updateAllActiveSubscriptions() {
        if (_isUpdatingSub.value) return
        scope.launch {
            _isUpdatingSub.value = true
            GeekModeLogger.log("Subscriptions", "Начало обновления активных подписок...")
            try {
                val proxyP = if (subscriptionUpdateOnlyViaProxy.value && coreState.value == DesktopCoreState.RUNNING) httpPort.value else 0
                val enabledSubs = _subscriptions.value.filter { it.enabled }

                if (enabledSubs.isEmpty()) {
                    GeekModeLogger.log("Subscriptions", "Нет включенных подписок для обновления.")
                    return@launch
                }

                val updatedSubsList = _subscriptions.value.toMutableList()
                val newConfigs = mutableListOf<ProfileItem>()

                for (sub in enabledSubs) {
                    val mirrors = SubscriptionFetcher.parseMirrors(sub.url)
                    if (mirrors.isEmpty()) continue

                    GeekModeLogger.log("Subscriptions", "Загрузка: ${sub.name} (зеркал: ${mirrors.size})")
                    val result = SubscriptionFetcher.fetchParallel(
                        urls = mirrors,
                        userAgent = subscriptionUserAgent.value,
                        httpProxyHost = "127.0.0.1",
                        httpProxyPort = proxyP,
                        timeoutMs = 12000L
                    )

                    val subIdx = updatedSubsList.indexOfFirst { it.id == sub.id }
                    if (result.isSuccess) {
                        val content = result.getOrNull().orEmpty()
                        var parsed = SubscriptionParser.parse(content)

                        // Применение regex фильтра подписки
                        if (sub.filter.isNotBlank()) {
                            try {
                                val filterRegex = Regex(sub.filter)
                                parsed = parsed.filter { !filterRegex.containsMatchIn(it.remarks) }
                            } catch (_: Exception) {}
                        }

                        // Привязка серверов к id подписки
                        for (p in parsed) {
                            p.subscriptionId = sub.id
                        }
                        newConfigs.addAll(parsed)

                        if (subIdx >= 0) {
                            updatedSubsList[subIdx] = updatedSubsList[subIdx].copy(
                                lastUpdated = System.currentTimeMillis(),
                                lastUpdateFailed = false
                            )
                        }
                        GeekModeLogger.log("Subscriptions", "${sub.name}: +${parsed.size} узлов")
                    } else {
                        val err = result.exceptionOrNull()?.message ?: "Ошибка загрузки"
                        GeekModeLogger.log("Subscriptions", "${sub.name}: ошибка ($err)")
                        if (subIdx >= 0) {
                            updatedSubsList[subIdx] = updatedSubsList[subIdx].copy(
                                lastUpdateFailed = true
                            )
                        }
                    }
                }

                _subscriptions.value = updatedSubsList
                saveSubscriptionsToStorage(updatedSubsList)

                if (newConfigs.isNotEmpty()) {
                    serverRepo.clearAllServers()
                    for (cfg in newConfigs) {
                        val guid = UUID.randomUUID().toString()
                        serverRepo.saveServerConfig(guid, cfg)
                    }

                    // Автоматическое удаление дубликатов (как в Android версии)
                    val removed = serverRepo.removeDuplicates()
                    GeekModeLogger.log("Subscriptions", "Автоматически устранено дубликатов: $removed")
                    _selectedGuid.value = serverRepo.getSelectedServerGuid()
                    pingAllServers()
                }
            } catch (e: Exception) {
                GeekModeLogger.log("Subscriptions", "Критический сбой обновления: ${e.message}")
            } finally {
                _isUpdatingSub.value = false
            }
        }
    }

    fun checkNetworkStatus() {
        if (isCheckingNetwork.value) return
        scope.launch {
            isCheckingNetwork.value = true
            try {
                val client = NetworkStatusChecker.createCheckClient(timeoutMs = 3000L)
                val ya = NetworkStatusChecker.checkHttpAccess(client, "https://${networkCheckFallbackDomain.value.trim().ifBlank { "ya.ru" }}")
                val google = NetworkStatusChecker.checkHttpAccess(client, "https://${networkCheckPrimaryDomain.value.trim().ifBlank { "google.com" }}")
                client.close()

                networkStatus.value = when {
                    ya && google -> NetworkStatusChecker.STATUS_FULL_INTERNET
                    ya && !google -> NetworkStatusChecker.STATUS_WHITELIST_ONLY
                    else -> NetworkStatusChecker.STATUS_NO_INTERNET
                }

                fetchExitIp()
            } catch (e: Exception) {
                GeekModeLogger.log("NetworkStatus", "Ошибка проверки сети: ${e.message}")
            } finally {
                isCheckingNetwork.value = false
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

    fun connectCurrent() {
        val guid = _selectedGuid.value ?: scenarioFilteredServers.value.firstOrNull()?.first ?: servers.value.firstOrNull()?.first ?: return
        val rawProfile = serverRepo.getServerConfig(guid) ?: return

        var profile = rawProfile
        val cfIp = cloudflareIp.value.trim()
        val gSni = globalSni.value.trim()
        if (cfIp.isNotBlank() && (profile.network == "ws" || profile.network == "httpupgrade" || profile.network == "grpc")) {
            profile = profile.copy(server = cfIp)
            GeekModeLogger.log("GeekMode", "Применён Cloudflare Clean IP: $cfIp для ${profile.remarks}")
        }
        if (gSni.isNotBlank()) {
            profile = profile.copy(sni = gSni)
            GeekModeLogger.log("GeekMode", "Применён Global SNI: $gSni для ${profile.remarks}")
        }

        scope.launch {
            val listenAddr = if (allowLan.value) "0.0.0.0" else "127.0.0.1"
            val directDomains = customDirectDomains.value.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() }
            val proxyDomains = customProxyDomains.value.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() }

            val endpoint = if (useCustomEndpoint.value && customEndpointUrl.value.isNotBlank()) customEndpointUrl.value.trim() else null

            val started = xrayRunner.start(
                profile = profile,
                scenario = _scenario.value,
                xrayBinaryPath = xrayBinaryPath.value.ifBlank { null },
                httpPort = httpPort.value,
                socksPort = socksPort.value,
                listenAddress = listenAddr,
                customDirectDomains = directDomains,
                customProxyDomains = proxyDomains,
                upstreamProxyHost = null,
                upstreamProxyPort = null,
                upstreamProxyType = null
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
        GeekModeLogger.log("GeekMode", "Сервер удален из VIP списка")
    }

    fun setGlobalSni(sni: String) {
        globalSni.value = sni
        settingsRepo.setString("pref_global_sni", sni)
    }

    fun setCloudflareIp(ip: String) {
        cloudflareIp.value = ip
        settingsRepo.setString("pref_cloudflare_ip", ip)
    }

    fun setCustomUserAgent(ua: String) {
        customUserAgent.value = ua
        settingsRepo.setString("pref_custom_user_agent", ua)
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
                val checkUrl = connectionCheckServiceUrl.value.trim().ifBlank { "https://api.myip.la/en?json" }
                val resp = client.get(checkUrl)
                if (resp.status.value in 200..299) {
                    val text = resp.bodyAsText()
                    val ipMatch = Regex("\"ip\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                    val country = Regex("\"country_name\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                    if (ipMatch != null) {
                        val full = "$ipMatch (${country ?: "Online"})"
                        exitIp.value = full
                        publicIpInfo.value = full
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
            GeekModeLogger.log("GeekMode", "Force Ping: проверка ${profile.remarks}...")
            val ping = NodePingTester.testSingleTcp(profile)
            _pingMap.value = _pingMap.value + (guid to ping)
            GeekModeLogger.log("GeekMode", "Результат: ${if (ping > 0) "${ping}ms" else "Ошибка (-1)"}")
            fetchExitIp()
            isForcePinging.value = false
        }
    }

    fun pingVipServers() {
        val vips = serverRepo.getVipServerGuids()
        if (vips.isEmpty()) {
            GeekModeLogger.log("GeekMode", "VIP кэш пуст")
            return
        }
        scope.launch {
            isPingingVip.value = true
            GeekModeLogger.log("GeekMode", "Пинг ${vips.size} VIP узлов...")
            for (guid in vips) {
                val profile = serverRepo.getServerConfig(guid) ?: continue
                val ping = NodePingTester.testSingleTcp(profile)
                _pingMap.value = _pingMap.value + (guid to ping)
            }
            isPingingVip.value = false
        }
    }

    fun findMoreVipServers() {
        val all = servers.value
        if (all.isEmpty()) return
        scope.launch {
            isFindingVip.value = true
            GeekModeLogger.log("GeekMode", "Поиск новых VIP узлов через v2w-core...")
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
                    }
                    false
                }
            )
            vipGuids.value = serverRepo.getVipServerGuids()
            isFindingVip.value = false
        }
    }

    fun deleteServer(guid: String) {
        if (_selectedGuid.value == guid && coreState.value == DesktopCoreState.RUNNING) {
            stopCore()
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
                GeekModeLogger.log("SmartConnect", "Сбой: ${e.message}")
            } finally {
                _isV2WScanning.value = false
            }
        }
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
        subIntervalMins: Int = subscriptionUpdateIntervalMinutes.value,
        subOnlyViaProxy: Boolean = subscriptionUpdateOnlyViaProxy.value,
        customEndpoint: Boolean = useCustomEndpoint.value,
        customEndpointConfig: String = customEndpointUrl.value,
        bypassRu: Boolean = bypassRussianServices.value,
        checkUrl: String = connectionCheckServiceUrl.value
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
        subscriptionUpdateIntervalMinutes.value = subIntervalMins
        subscriptionUpdateOnlyViaProxy.value = subOnlyViaProxy
        useCustomEndpoint.value = customEndpoint
        customEndpointUrl.value = customEndpointConfig
        bypassRussianServices.value = bypassRu
        connectionCheckServiceUrl.value = checkUrl

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
        settingsRepo.setInt("subscription_update_interval_minutes", subIntervalMins)
        settingsRepo.setBoolean("subscription_update_only_via_proxy", subOnlyViaProxy)
        settingsRepo.setBoolean("pref_custom_endpoint_enabled", customEndpoint)
        settingsRepo.setString("pref_custom_endpoint_url", customEndpointConfig)
        settingsRepo.setBoolean("pref_bypass_russian_services", bypassRu)
        settingsRepo.setString("pref_conn_check_url", checkUrl)

        GeekModeLogger.log("Settings", "Настройки успешно сохранены.")
        if (coreState.value == DesktopCoreState.RUNNING) {
            connectCurrent()
        }
    }
}
