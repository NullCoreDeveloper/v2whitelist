package com.kiktor.v2whitelist.desktop.state

import com.kiktor.v2whitelist.core.domain.network.NetworkStatusChecker
import com.kiktor.v2whitelist.core.domain.ping.NodePingTester
import com.kiktor.v2whitelist.core.domain.selector.LocationFilterMode
import com.kiktor.v2whitelist.core.domain.selector.SmartServerFilterConfig
import com.kiktor.v2whitelist.core.domain.selector.SmartServerSelector
import com.kiktor.v2whitelist.core.domain.update.UpdateChecker
import com.kiktor.v2whitelist.core.model.AppScenario
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import com.kiktor.v2whitelist.core.model.update.CheckUpdateResult
import com.kiktor.v2whitelist.core.model.update.TargetPlatform
import com.kiktor.v2whitelist.core.repository.ServerRepository
import com.kiktor.v2whitelist.core.repository.SettingsRepository
import com.kiktor.v2whitelist.core.subscription.defaults.DefaultSubscriptions
import com.kiktor.v2whitelist.core.subscription.model.CustomSubData
import com.kiktor.v2whitelist.core.subscription.network.SubscriptionFetcher
import com.kiktor.v2whitelist.core.subscription.parser.SubscriptionParser
import com.kiktor.v2whitelist.core.subscription.tag.ServerTagResolver
import com.kiktor.v2whitelist.desktop.data.DesktopFileServerRepository
import com.kiktor.v2whitelist.desktop.data.DesktopFileSettingsRepository
import com.kiktor.v2whitelist.desktop.data.DesktopStorageManager
import com.kiktor.v2whitelist.desktop.engine.DesktopCoreState
import com.kiktor.v2whitelist.desktop.engine.DesktopProfileVerifier
import com.kiktor.v2whitelist.desktop.engine.DesktopProxyManager
import com.kiktor.v2whitelist.desktop.engine.DesktopTunManager
import com.kiktor.v2whitelist.desktop.engine.V2WCoreScanner
import com.kiktor.v2whitelist.desktop.engine.XrayDesktopRunner
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean
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

    // Фильтр локаций серверов (1-в-1 как в Android версии)
    val showLocationFilterDialog = MutableStateFlow(false)
    val locationFilterMode = MutableStateFlow(LocationFilterMode.fromString(settingsRepo.getString("pref_location_filter_mode", "EXCLUDE")))
    val locationFilterSet = MutableStateFlow<Set<String>>(
        try {
            val s = settingsRepo.getString("pref_location_filter_set", "")
            if (s.isNotBlank()) jsonHelper.decodeFromString<Set<String>>(s) else ServerTagResolver.getDefaultFilterSet()
        } catch (_: Exception) {
            ServerTagResolver.getDefaultFilterSet()
        }
    )

    val availableLocations: StateFlow<List<Map.Entry<String, Int>>> = combine(servers, _subscriptions) { serverList, subList ->
        val disabledSubIds = subList.filter { !it.enabled }.map { it.id }.toSet()
        val subRegexMap = subList.associate { it.id to it.groupRegex }
        val map = mutableMapOf<String, Int>()
        for ((_, profile) in serverList) {
            if (disabledSubIds.contains(profile.subscriptionId)) continue
            val regex = subRegexMap[profile.subscriptionId] ?: ""
            val tag = ServerTagResolver.resolveServerTag(profile.remarks, regex)
            map[tag] = (map[tag] ?: 0) + 1
        }
        map.entries.sortedByDescending { it.value }
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

    // Режим маршрутизации при старте всегда сбрасывается на PROXY (сессионный выбор, исключающий запросы sudo при обычном запуске)
    val routingMode = MutableStateFlow("PROXY")
    val uiTheme = MutableStateFlow(settingsRepo.getString("ui_theme", "CYAN"))
    val uiCompactCards = MutableStateFlow(settingsRepo.getBoolean("ui_compact_cards", false))
    val minimizeToTray = MutableStateFlow(settingsRepo.getBoolean("minimize_to_tray", true))

    // TUN & Sudo сессия (хранится только в памяти на время работы приложения)
    var sessionSudoPassword: String? = null
        private set
    val showSudoDialog = MutableStateFlow(false)
    val sudoError = MutableStateFlow<String?>(null)
    val isVerifyingSudo = MutableStateFlow(false)

    // Предупреждение о несохраненных настройках при смене экрана
    val showUnsavedSettingsDialog = MutableStateFlow(false)
    var pendingTabToNavigate: DesktopTab? = null
    var isSettingsDirty: (() -> Boolean)? = null
    var saveSettingsAction: (() -> Unit)? = null
    var discardSettingsAction: (() -> Unit)? = null

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
    val subscriptionUpdateOnStartup = MutableStateFlow(settingsRepo.getBoolean("subscription_update_on_startup", true))
    val yandexTranslateUpdateEnabled = MutableStateFlow(settingsRepo.getBoolean("pref_yandex_translate_update", false))

    // Сплит-туннелинг и раздельная маршрутизация
    val domainSplitTunnelingEnabled = MutableStateFlow(settingsRepo.getBoolean("pref_domain_split_tunneling", true))
    val domainWhitelistMode = MutableStateFlow(settingsRepo.getBoolean("pref_domain_whitelist_mode", false))
    val bypassRussianServices = MutableStateFlow(settingsRepo.getBoolean("pref_bypass_russian_services", true))
    val customDirectDomains = MutableStateFlow(settingsRepo.getString("custom_direct_domains", ""))
    val customProxyDomains = MutableStateFlow(settingsRepo.getString("custom_proxy_domains", ""))

    // Личный прокси как конечный узел (цепочка: SmartConnect -> Конечный узел)
    val useCustomEndpoint = MutableStateFlow(settingsRepo.getBoolean("pref_custom_endpoint_enabled", false))
    val customEndpointUrl = MutableStateFlow(settingsRepo.getString("pref_custom_endpoint_url", ""))
    val customEndpointSubUrl = MutableStateFlow(settingsRepo.getString("pref_custom_endpoint_sub_url", ""))
    val endpointE2eCheckEnabled = MutableStateFlow(settingsRepo.getBoolean("pref_endpoint_e2e_check_enabled", true))

    // Ручной выбор сервера vs сценарий (Авто, БС, ЧС, YouTube)
    val isManualSelection = MutableStateFlow(settingsRepo.getBoolean("pref_manual_server_selected", false))

    // Geek Mode state
    val globalSni = MutableStateFlow(settingsRepo.getString("pref_global_sni", ""))
    val cloudflareIp = MutableStateFlow(settingsRepo.getString("pref_cloudflare_ip", ""))
    val customUserAgent = MutableStateFlow(settingsRepo.getString("pref_custom_user_agent", "Auto (Default)"))
    val exitIp = MutableStateFlow<String?>(null)
    val isPingingVip = MutableStateFlow(false)
    val isFindingVip = MutableStateFlow(false)
    val isForcePinging = MutableStateFlow(false)
    val vipGuids: StateFlow<Set<String>> = serverRepo.vipGuidsFlow
    val vipCacheLimit = MutableStateFlow(settingsRepo.getInt("pref_vip_cache_limit", 5))

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

    // Обновления приложения ПроБел
    val isCheckingAppUpdate = MutableStateFlow(false)
    val availableAppUpdate = MutableStateFlow<CheckUpdateResult?>(null)
    val showAppUpdateDialog = MutableStateFlow(false)
    val appUpdateCheckMessage = MutableStateFlow<String?>(null)

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
        subscriptionsProvider = { _subscriptions.value },
        networkStatusProvider = { networkStatus.value },
        scope = scope
    )

    init {
        GeekModeLogger.log("DesktopApp", "v2whitelist Desktop инициализирован по спецификации UI ПроБел.")

        smartConnectManager.onStageChanged = { _, text ->
            _smartConnectStatus.value = text
        }
        smartConnectManager.onServerConnected = { guid, _ ->
            _selectedGuid.value = guid
            isManualSelection.value = false
            settingsRepo.setBoolean("pref_manual_server_selected", false)
        }
        smartConnectManager.connectAction = { guid, _ ->
            _selectedGuid.value = guid
            isManualSelection.value = false
            settingsRepo.setBoolean("pref_manual_server_selected", false)
            connectCurrent()
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

        // Запуск планировщика фонового обновления подписок
        restartPeriodicSubscriptionUpdates()

        // Проверка автообновления подписок при старте (если разрешено в настройках)
        checkStartupSubscriptionUpdate()

        // Наблюдатель за состоянием VPN: отложенное обновление через VPN при подключении
        scope.launch {
            coreState.collect { state ->
                if (state == DesktopCoreState.RUNNING && subscriptionUpdateOnlyViaProxy.value && subscriptionAutoUpdate.value) {
                    val enabledSubs = _subscriptions.value.filter { it.enabled }
                    val oldestUpdate = enabledSubs.minOfOrNull { it.lastUpdated } ?: 0L
                    val intervalMs = subscriptionUpdateIntervalMinutes.value.coerceAtLeast(15) * 60 * 1000L
                    if (oldestUpdate <= 0L || (System.currentTimeMillis() - oldestUpdate) >= intervalMs) {
                        delay(3000L)
                        GeekModeLogger.log("Subscriptions", "VPN активен: запуск отложенного обновления подписок через защищённое соединение...")
                        updateAllActiveSubscriptions()
                    }
                }
            }
        }

        // Автоматическая проверка обновлений при старте (если включено в настройках)
        if (autoCheckUpdates.value) {
            scope.launch(Dispatchers.Default) {
                delay(3000L)
                checkAppUpdate(manual = false)
            }
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

    fun promptTabChange(tab: DesktopTab) {
        if (_activeTab.value == DesktopTab.SETTINGS && tab != DesktopTab.SETTINGS && isSettingsDirty?.invoke() == true) {
            pendingTabToNavigate = tab
            showUnsavedSettingsDialog.value = true
        } else {
            setTab(tab)
        }
    }

    fun confirmSaveAndNavigate() {
        saveSettingsAction?.invoke()
        showUnsavedSettingsDialog.value = false
        val next = pendingTabToNavigate
        pendingTabToNavigate = null
        if (next != null) {
            setTab(next)
        }
    }

    fun confirmDiscardAndNavigate() {
        discardSettingsAction?.invoke()
        showUnsavedSettingsDialog.value = false
        val next = pendingTabToNavigate
        pendingTabToNavigate = null
        if (next != null) {
            setTab(next)
        }
    }

    fun cancelUnsavedDialog() {
        showUnsavedSettingsDialog.value = false
        pendingTabToNavigate = null
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleRoutingMode() {
        val next = if (routingMode.value == "PROXY") "TUN" else "PROXY"
        setRoutingMode(next)
    }

    fun setRoutingMode(mode: String) {
        if (mode == "TUN" && DesktopProxyManager.isLinux && sessionSudoPassword == null) {
            sudoError.value = null
            showSudoDialog.value = true
            return
        }
        applyRoutingMode(mode)
    }

    private fun applyRoutingMode(mode: String) {
        routingMode.value = mode
        GeekModeLogger.log("AppState", "Режим работы переключен на: $mode")
        if (coreState.value == DesktopCoreState.RUNNING) {
            if (mode == "PROXY" && DesktopTunManager.isTunActive) {
                DesktopTunManager.stop(sessionSudoPassword)
            }
            connectCurrent()
        }
    }

    fun submitSudoPassword(password: String) {
        if (password.isBlank()) {
            sudoError.value = "Пароль не может быть пустым"
            return
        }

        scope.launch(Dispatchers.IO) {
            isVerifyingSudo.value = true
            sudoError.value = null

            val (valid, errorMsg) = verifySudoPassword(password)
            isVerifyingSudo.value = false

            if (valid) {
                sessionSudoPassword = password
                showSudoDialog.value = false
                sudoError.value = null
                applyRoutingMode("TUN")
                GeekModeLogger.log("TUN", "Права sudo успешно подтверждены. Режим TUN активирован.")
            } else {
                sudoError.value = errorMsg ?: "Неверный пароль sudo. Пожалуйста, попробуйте снова."
                GeekModeLogger.log("TUN", "Сбой подтверждения прав sudo: ${sudoError.value}")
            }
        }
    }

    fun dismissSudoDialog() {
        showSudoDialog.value = false
        sudoError.value = null
    }

    private fun verifySudoPassword(password: String): Pair<Boolean, String?> {
        return try {
            val proc = ProcessBuilder("sudo", "-S", "-k", "-v")
                .redirectErrorStream(true)
                .start()
            proc.outputStream.bufferedWriter().use { writer ->
                writer.write("$password\n")
                writer.flush()
            }
            val output = StringBuilder()
            val reader = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }
            val exited = proc.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)
            if (!exited) {
                proc.destroyForcibly()
                return false to "Таймаут проверки sudo (процесс не ответил вовремя)."
            }
            val exitCode = proc.exitValue()
            if (exitCode == 0) {
                true to null
            } else {
                val outStr = output.toString().trim()
                val msg = when {
                    outStr.contains("без новых привилегий", ignoreCase = true) || outStr.contains("no new privs", ignoreCase = true) ->
                        "В системе задействован флаг PR_SET_NO_NEW_PRIVS (контейнер/песочница). Запуск sudo от root заблокирован ядром системы."
                    outStr.contains("incorrect password", ignoreCase = true) || outStr.contains("неверный пароль", ignoreCase = true) ->
                        "Неверный пароль sudo. Попробуйте снова."
                    outStr.isNotBlank() ->
                        "Ошибка sudo: $outStr"
                    else ->
                        "Неверный пароль sudo (код ошибки $exitCode)."
                }
                false to msg
            }
        } catch (e: Exception) {
            GeekModeLogger.log("TUN", "Исключение при проверке sudo: ${e.message}")
            false to "Сбой выполнения sudo: ${e.message}"
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

        // Удаляем все серверы этой подписки атомарным пакетом
        val allServers = servers.value
        val toRemoveGuids = allServers.filter {
            it.second.subscriptionId == subId || (subToRemove != null && it.second.subscriptionId == subToRemove.name)
        }.map { it.first }

        serverRepo.removeServers(toRemoveGuids)
        _selectedGuid.value = serverRepo.getSelectedServerGuid()
        GeekModeLogger.log("Subscriptions", "Подписка удалена: $subId (серверов удалено: ${toRemoveGuids.size})")
    }

    /**
     * Генерирует детерминированный стабильный GUID для сервера на основе его конфигурации.
     * Это гарантирует, что при повторных обновлениях подписок ID серверов не меняются,
     * а VIP-кэш и пинг не превращаются в осиротевшие shortid.
     */
    fun generateStableServerGuid(profile: ProfileItem): String {
        val hostOrSni = (profile.sni?.takeIf { it.isNotBlank() } ?: profile.host).orEmpty().trim().lowercase()
        val srv = profile.server.orEmpty().trim().lowercase()
        val pwd = profile.password.orEmpty().trim()
        val key = "${profile.configType}:$srv:${profile.serverPort}:$pwd:$hostOrSni"
        return try {
            val md = java.security.MessageDigest.getInstance("MD5")
            val digest = md.digest(key.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            for (b in digest) {
                sb.append(String.format("%02x", b))
            }
            val hex = sb.toString()
            "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
        } catch (_: Exception) {
            UUID.randomUUID().toString()
        }
    }

    private var periodicSubUpdateJob: Job? = null

    fun restartPeriodicSubscriptionUpdates() {
        periodicSubUpdateJob?.cancel()
        if (!subscriptionAutoUpdate.value) {
            GeekModeLogger.log("Subscriptions", "Фоновое автообновление подписок отключено в настройках.")
            return
        }

        val intervalMins = subscriptionUpdateIntervalMinutes.value.coerceAtLeast(15)
        GeekModeLogger.log("Subscriptions", "Служба фонового автообновления активна (интервал: $intervalMins мин).")

        periodicSubUpdateJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val currentIntervalMs = subscriptionUpdateIntervalMinutes.value.coerceAtLeast(15) * 60 * 1000L
                delay(currentIntervalMs)

                if (!subscriptionAutoUpdate.value) break

                if (subscriptionUpdateOnlyViaProxy.value && coreState.value != DesktopCoreState.RUNNING) {
                    GeekModeLogger.log(
                        "Subscriptions",
                        "Плановое фоновое обновление пропущено: в настройках включено «Только через VPN», но VPN сейчас не запущен."
                    )
                    continue
                }

                GeekModeLogger.log(
                    "Subscriptions",
                    "Плановое фоновое обновление подписок по расписанию (${subscriptionUpdateIntervalMinutes.value} мин)..."
                )
                updateAllActiveSubscriptions()
            }
        }
    }

    private fun checkStartupSubscriptionUpdate() {
        if (!subscriptionUpdateOnStartup.value) {
            GeekModeLogger.log("Subscriptions", "Автообновление подписок при запуске выключено в настройках.")
            return
        }

        scope.launch(Dispatchers.Default) {
            delay(1500L)

            val enabledSubs = _subscriptions.value.filter { it.enabled }
            if (enabledSubs.isEmpty()) return@launch

            val oldestUpdate = enabledSubs.minOfOrNull { it.lastUpdated } ?: 0L
            val intervalMs = subscriptionUpdateIntervalMinutes.value.coerceAtLeast(15) * 60 * 1000L
            val now = System.currentTimeMillis()
            val isExpired = oldestUpdate <= 0L || (now - oldestUpdate) >= intervalMs
            val noServers = serverRepo.serversFlow.value.isEmpty()

            if (isExpired || noServers) {
                if (subscriptionUpdateOnlyViaProxy.value && coreState.value != DesktopCoreState.RUNNING) {
                    GeekModeLogger.log(
                        "Subscriptions",
                        "Автообновление подписок при старте отложено: включено «Только через VPN», ожидаем подключения."
                    )
                } else {
                    val reason = if (noServers) "список серверов пуст" else "подписки устарели (> ${subscriptionUpdateIntervalMinutes.value} мин)"
                    GeekModeLogger.log("Subscriptions", "Запуск автообновления подписок при старте ($reason)...")
                    updateAllActiveSubscriptions()
                }
            } else {
                val minsAgo = (now - oldestUpdate) / (60 * 1000L)
                GeekModeLogger.log("Subscriptions", "Подписки актуальны (обновлены $minsAgo мин назад), автообновление при старте не требуется.")
            }
        }
    }

    private suspend fun fetchViaYandexFirstMirror(
        mirrors: List<String>,
        userAgent: String,
        timeoutMs: Long
    ): Result<String> {
        val client = SubscriptionFetcher.createHttpClient(timeoutMs = timeoutMs)
        return try {
            var lastError: Throwable? = null
            for (mirror in mirrors) {
                try {
                    val encoded = java.net.URLEncoder.encode(mirror, "UTF-8")
                    val yandexUrl = "https://translate.yandex.ru/translate?lang=en-ru&view=compact&url=$encoded"
                    val res = SubscriptionFetcher.fetchSingle(client, yandexUrl, userAgent)
                    if (res.isSuccess) {
                        val body = res.getOrNull().orEmpty()
                        val preMatcher = Regex("<pre[^>]*>([\\s\\S]*?)</pre>", RegexOption.IGNORE_CASE).find(body)
                        val extracted = if (preMatcher != null) {
                            preMatcher.groupValues[1]
                        } else if (!body.contains("<html", ignoreCase = true)) {
                            body
                        } else {
                            body.replace(Regex("(?i)<br\\s*/?>"), "\n")
                                .replace(Regex("(?i)</p>"), "\n")
                                .replace(Regex("<[^>]+>"), "")
                        }
                        val unescaped = extracted
                            .replace("&amp;", "&")
                            .replace("&lt;", "<")
                            .replace("&gt;", ">")
                            .replace("&quot;", "\"")
                            .replace("&#39;", "'")
                            .replace("&#x2F;", "/")
                            .replace("&#47;", "/")
                            .trim()
                        if (unescaped.isNotBlank()) {
                            return Result.success(unescaped)
                        }
                    } else {
                        lastError = res.exceptionOrNull()
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }
            Result.failure(lastError ?: IllegalStateException("Все зеркала через Яндекс.Переводчик не ответили"))
        } finally {
            client.close()
        }
    }

    fun updateAllActiveSubscriptions() {
        if (_isUpdatingSub.value) return
        scope.launch {
            _isUpdatingSub.value = true
            GeekModeLogger.log("Subscriptions", "Начало обновления активных подписок...")
            try {
                if (subscriptionUpdateOnlyViaProxy.value && coreState.value != DesktopCoreState.RUNNING) {
                    GeekModeLogger.log(
                        "Subscriptions",
                        "Обновление отменено: включена опция «Обновлять подписки только через VPN», но VPN сейчас не запущен."
                    )
                    return@launch
                }

                val proxyP = if (coreState.value == DesktopCoreState.RUNNING) httpPort.value else 0
                val enabledSubs = _subscriptions.value.filter { it.enabled }

                if (enabledSubs.isEmpty()) {
                    GeekModeLogger.log("Subscriptions", "Нет включенных подписок для обновления.")
                    return@launch
                }

                val concurrency = subscriptionThreads.value.coerceIn(1, 10)
                val semaphore = Semaphore(concurrency)
                val ua = subscriptionUserAgent.value.takeIf { it.isNotBlank() } ?: "v2rayNG/1.10.x"

                val isBsMode = networkStatus.value == NetworkStatusChecker.STATUS_WHITELIST_ONLY
                val useYandex = yandexTranslateUpdateEnabled.value && isBsMode && coreState.value != DesktopCoreState.RUNNING

                GeekModeLogger.log(
                    "Subscriptions",
                    "Обновление ${enabledSubs.size} подписок (потоков: $concurrency, UA: $ua" +
                            (if (useYandex) ", обход через Яндекс.Переводчик" else "") +
                            (if (proxyP > 0) ", через локальный прокси :$proxyP" else "") +
                            ")..."
                )

                val updatedSubsList = _subscriptions.value.toMutableList()
                val updatedSubsLock = Any()
                val anySubUpdated = AtomicBoolean(false)

                coroutineScope {
                    val jobs = enabledSubs.map { sub ->
                        launch(Dispatchers.IO) {
                            semaphore.withPermit {
                                val mirrors = SubscriptionFetcher.parseMirrors(sub.url)
                                if (mirrors.isEmpty()) return@withPermit

                                GeekModeLogger.log("Subscriptions", "Загрузка: ${sub.name} (зеркал: ${mirrors.size})")

                                var result = if (useYandex) {
                                    fetchViaYandexFirstMirror(mirrors, ua, timeoutMs = 12000L)
                                } else {
                                    SubscriptionFetcher.fetchParallel(
                                        urls = mirrors,
                                        userAgent = ua,
                                        httpProxyHost = "127.0.0.1",
                                        httpProxyPort = proxyP,
                                        timeoutMs = 12000L
                                    )
                                }

                                if (result.isFailure && yandexTranslateUpdateEnabled.value && !useYandex && coreState.value != DesktopCoreState.RUNNING) {
                                    GeekModeLogger.log("Subscriptions", "${sub.name}: прямой запрос не удался, попытка через Яндекс.Переводчик...")
                                    result = fetchViaYandexFirstMirror(mirrors, ua, timeoutMs = 12000L)
                                }

                                synchronized(updatedSubsLock) {
                                    val subIdx = updatedSubsList.indexOfFirst { it.id == sub.id }
                                    if (result.isSuccess) {
                                        val content = result.getOrNull().orEmpty()
                                        var parsed = SubscriptionParser.parse(content)

                                        if (sub.filter.isNotBlank()) {
                                            try {
                                                val filterRegex = Regex(sub.filter)
                                                parsed = parsed.filter { !filterRegex.containsMatchIn(it.remarks) }
                                            } catch (_: Exception) {}
                                        }

                                        for (p in parsed) {
                                            p.subscriptionId = sub.id
                                        }

                                        if (parsed.isNotEmpty()) {
                                            val currentServers = serverRepo.serversFlow.value
                                            val oldForSub = currentServers.filter {
                                                it.second.subscriptionId == sub.id || it.second.subscriptionId == sub.name
                                            }.map { it.first }

                                            val batch = parsed.map { generateStableServerGuid(it) to it }
                                            serverRepo.replaceSubscriptionServers(oldForSub, batch)
                                            anySubUpdated.set(true)
                                        }

                                        if (subIdx >= 0) {
                                            updatedSubsList[subIdx] = updatedSubsList[subIdx].copy(
                                                lastUpdated = System.currentTimeMillis(),
                                                lastUpdateFailed = false
                                            )
                                        }
                                        GeekModeLogger.log("Subscriptions", "${sub.name}: +${parsed.size} узлов")
                                    } else {
                                        val err = result.exceptionOrNull()?.message ?: "Ошибка загрузки"
                                        val existingCount = serverRepo.serversFlow.value.count {
                                            it.second.subscriptionId == sub.id || it.second.subscriptionId == sub.name
                                        }
                                        GeekModeLogger.log("Subscriptions", "${sub.name}: сбой ($err). Ранее полученные серверы сохранены ($existingCount узлов).")
                                        if (subIdx >= 0) {
                                            updatedSubsList[subIdx] = updatedSubsList[subIdx].copy(
                                                lastUpdateFailed = true
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    jobs.joinAll()
                }

                _subscriptions.value = updatedSubsList
                saveSubscriptionsToStorage(updatedSubsList)

                if (anySubUpdated.get()) {
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
        isManualSelection.value = true
        settingsRepo.setBoolean("pref_manual_server_selected", true)
        if (coreState.value == DesktopCoreState.RUNNING) {
            connectCurrent()
        }
    }

    fun selectAndConnectServer(guid: String) {
        _selectedGuid.value = guid
        serverRepo.setSelectedServerGuid(guid)
        isManualSelection.value = true
        settingsRepo.setBoolean("pref_manual_server_selected", true)
        connectCurrent()
    }

    fun setScenario(sc: AppScenario) {
        _scenario.value = sc
        settingsRepo.setAppScenario(sc)
        isManualSelection.value = false
        settingsRepo.setBoolean("pref_manual_server_selected", false)
        if (coreState.value == DesktopCoreState.RUNNING || coreState.value == DesktopCoreState.STARTING) {
            // Запуск автопоиска узлов под выбранный сценарий (Авто / БС / ЧС / YouTube)
            startSmartConnect()
        }
    }

    fun toggleConnect() {
        if (coreState.value == DesktopCoreState.RUNNING || coreState.value == DesktopCoreState.STARTING) {
            stopCore()
        } else {
            // Мгновенный визуальный фидбек для пользователя
            xrayRunner.markStarting()
            if (isManualSelection.value && !_selectedGuid.value.isNullOrBlank()) {
                GeekModeLogger.log("DesktopApp", "Ручной режим: запуск выбранного сервера ${_selectedGuid.value}")
                connectCurrent()
            } else {
                GeekModeLogger.log("DesktopApp", "Авто-режим (${_scenario.value}): запуск SmartConnect...")
                startSmartConnect()
            }
        }
    }

    private var smartConnectJob: Job? = null

    fun startSmartConnect() {
        smartConnectJob?.cancel()
        smartConnectManager.stop()
        V2WCoreScanner.stop()
        _smartConnectStatus.value = "Инициализация автопоиска…"
        xrayRunner.markStarting()
        smartConnectJob = scope.launch {
            try {
                val ok = smartConnectManager.startSmartConnect(
                    scenario = _scenario.value,
                    minSpeedMbps = minSpeedThresholdMbps.value,
                    speedCheckEnabled = speedTestOnCheck.value
                )
                if (!ok && coreState.value == DesktopCoreState.STARTING) {
                    xrayRunner.setError("Нет доступных серверов под сценарий ${_scenario.value}")
                    _smartConnectStatus.value = null
                }
            } catch (e: CancellationException) {
                GeekModeLogger.log("AppState", "Автопоиск отменён пользователем.")
                _smartConnectStatus.value = null
                if (coreState.value == DesktopCoreState.STARTING) {
                    xrayRunner.setStopped()
                }
            } catch (e: Exception) {
                GeekModeLogger.log("AppState", "Ошибка автопоиска: ${e.message}")
                xrayRunner.setError(e.message ?: "Сбой автопоиска")
                _smartConnectStatus.value = null
            } finally {
                smartConnectManager.stop()
                V2WCoreScanner.stop()
            }
        }
    }

    val isSwitchingServer = MutableStateFlow(false)

    fun switchToNextServer() {
        if (isSwitchingServer.value) return
        scope.launch {
            isSwitchingServer.value = true
            isManualSelection.value = false
            settingsRepo.setBoolean("pref_manual_server_selected", false)
            xrayRunner.markStarting()
            _smartConnectStatus.value = "Переключение на следующий сервер…"
            try {
                val current = _selectedGuid.value
                val success = smartConnectManager.switchToNextServer(current)
                if (!success) {
                    GeekModeLogger.log("AppState", "Быстрое переключение не нашло узлов, запуск полного поиска...")
                    _smartConnectStatus.value = "Поиск альтернативных узлов…"
                    val fullSuccess = smartConnectManager.startSmartConnect(
                        scenario = _scenario.value,
                        minSpeedMbps = minSpeedThresholdMbps.value,
                        speedCheckEnabled = speedTestOnCheck.value
                    )
                    if (!fullSuccess) {
                        xrayRunner.setError("Нет доступных серверов под текущий сценарий")
                        _smartConnectStatus.value = null
                    }
                }
            } catch (e: CancellationException) {
                GeekModeLogger.log("AppState", "Смена сервера отменена пользователем.")
                _smartConnectStatus.value = null
            } catch (e: Exception) {
                GeekModeLogger.log("AppState", "Ошибка смены сервера: ${e.message}")
                xrayRunner.setError(e.message ?: "Сбой смены сервера")
                _smartConnectStatus.value = null
            } finally {
                isSwitchingServer.value = false
            }
        }
    }

    fun promptLocationFilterDialog() {
        showLocationFilterDialog.value = true
    }

    fun dismissLocationFilterDialog() {
        showLocationFilterDialog.value = false
    }

    fun setLocationFilterMode(mode: LocationFilterMode) {
        locationFilterMode.value = mode
        settingsRepo.setString("pref_location_filter_mode", mode.name)
        smartConnectManager.cleanVipCacheForFilter()
    }

    fun toggleLocationFilter(tag: String) {
        val current = locationFilterSet.value.toMutableSet()
        if (current.contains(tag)) {
            current.remove(tag)
        } else {
            current.add(tag)
        }
        locationFilterSet.value = current
        settingsRepo.setString("pref_location_filter_set", jsonHelper.encodeToString(current))
        smartConnectManager.cleanVipCacheForFilter()
    }

    fun resetLocationFilterToDefault() {
        val defaults = ServerTagResolver.getDefaultFilterSet()
        locationFilterSet.value = defaults
        settingsRepo.setString("pref_location_filter_set", jsonHelper.encodeToString(defaults))
        smartConnectManager.cleanVipCacheForFilter()
    }

    fun clearAllLocations() {
        val empty = emptySet<String>()
        locationFilterSet.value = empty
        settingsRepo.setString("pref_location_filter_set", jsonHelper.encodeToString(empty))
        smartConnectManager.cleanVipCacheForFilter()
    }

    fun stopCore() {
        smartConnectJob?.cancel()
        smartConnectJob = null
        smartConnectManager.stop()
        V2WCoreScanner.stop()
        isSwitchingServer.value = false
        stopFailoverWatchdog()
        _smartConnectStatus.value = null
        xrayRunner.stop()
        if (DesktopTunManager.isTunActive) {
            DesktopTunManager.stop(sessionSudoPassword)
        }
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

        if (routingMode.value == "TUN" && DesktopProxyManager.isLinux && sessionSudoPassword == null) {
            sudoError.value = null
            showSudoDialog.value = true
            return
        }

        scope.launch {
            val listenAddr = if (allowLan.value) "0.0.0.0" else "127.0.0.1"
            val directDomains = if (domainSplitTunnelingEnabled.value) {
                customDirectDomains.value.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() }
            } else emptyList()
            val proxyDomains = if (domainSplitTunnelingEnabled.value) {
                customProxyDomains.value.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() }
            } else emptyList()

            val endpoint = if (useCustomEndpoint.value && customEndpointUrl.value.isNotBlank()) customEndpointUrl.value.trim() else null

            val currentSocksPort = socksPort.value
            val currentHttpPort = currentSocksPort + 1

            _smartConnectStatus.value = "Запуск сетевого ядра Xray…"
            val started = xrayRunner.start(
                profile = profile,
                scenario = _scenario.value,
                xrayBinaryPath = xrayBinaryPath.value.ifBlank { null },
                httpPort = currentHttpPort,
                socksPort = currentSocksPort,
                listenAddress = listenAddr,
                customDirectDomains = directDomains,
                customProxyDomains = proxyDomains,
                domainWhitelistMode = if (domainSplitTunnelingEnabled.value) domainWhitelistMode.value else false,
                upstreamProxyHost = null,
                upstreamProxyPort = null,
                upstreamProxyType = null,
                sudoPassword = if (routingMode.value == "TUN") sessionSudoPassword else null,
                socksUsername = localProxyLogin.value.ifBlank { null },
                socksPassword = localProxyPassword.value.ifBlank { null },
                remoteDns = remoteDns.value.ifBlank { null },
                internalDns = internalDns.value.ifBlank { null },
                dnsHosts = dnsHosts.value.ifBlank { null },
                allowInsecure = allowInsecure.value,
                sniffingEnabled = sniffingEnabled.value,
                routeOnly = routeOnly.value,
                muxEnabled = muxEnabled.value,
                muxConcurrency = muxTcpConcurrency.value,
                muxXudpConcurrency = muxXudpConcurrency.value,
                customEndpointUrl = endpoint,
                bypassRussianServices = bypassRussianServices.value,
                preferIpv6 = preferIpv6.value,
                fragmentEnabled = fragmentEnabled.value,
                fragmentPackets = fragmentPackets.value,
                fragmentLength = fragmentLength.value,
                fragmentInterval = fragmentInterval.value
            )
            if (started) {
                _smartConnectStatus.value = "Настройка маршрутизации (${routingMode.value})…"
                if (routingMode.value == "PROXY") {
                    if (DesktopTunManager.isTunActive) {
                        DesktopTunManager.stop(sessionSudoPassword)
                    }
                    if (autoEnableSystemProxy.value) {
                        DesktopProxyManager.enableSystemProxy(currentSocksPort, currentHttpPort)
                        isSystemProxyActive.value = true
                    }
                } else {
                    // Режим TUN: запуск сетевого адаптера v2w-tun через tun2socks
                    val tunStarted = DesktopTunManager.start(
                        socksPort = currentSocksPort,
                        serverAddress = profile.server,
                        sudoPassword = sessionSudoPassword
                    )
                    if (tunStarted) {
                        GeekModeLogger.log("TUN", "Сетевой интерфейс v2w-tun успешно поднят. Весь трафик ОС направлен через TUN.")
                        if (isSystemProxyActive.value) {
                            DesktopProxyManager.disableSystemProxy()
                            isSystemProxyActive.value = false
                        }
                    } else {
                        GeekModeLogger.log("TUN", "TUN интерфейс не может быть поднят без прав root (драйвер tun не загружен или нет прав). Включен системный прокси как резерв.")
                        if (autoEnableSystemProxy.value) {
                            DesktopProxyManager.enableSystemProxy(currentSocksPort, currentHttpPort)
                            isSystemProxyActive.value = true
                        }
                    }
                }
                checkNetworkStatus()
                fetchExitIp()
                _smartConnectStatus.value = null
                startFailoverWatchdog()
            } else {
                stopFailoverWatchdog()
                _smartConnectStatus.value = null
            }
        }
    }

    private var failoverJob: Job? = null

    private fun startFailoverWatchdog() {
        failoverJob?.cancel()
        if (!autoFailover.value) {
            GeekModeLogger.log("Failover", "Auto-Failover отключён в настройках.")
            return
        }

        failoverJob = scope.launch(Dispatchers.IO) {
            GeekModeLogger.log("Failover", "Watchdog авто-переключения запущен. Ожидание прогрева туннеля (10 сек)...")
            delay(10_000L)
            var consecutiveFails = 0
            while (isActive && coreState.value == DesktopCoreState.RUNNING) {
                val intervalSec = heartbeatIntervalSeconds.value.coerceAtLeast(5)
                delay(intervalSec * 1000L)
                if (!isActive || coreState.value != DesktopCoreState.RUNNING) break

                val isAlive = testTunnelConnectivity()
                if (!isAlive) {
                    consecutiveFails++
                    GeekModeLogger.log("Failover", "Heartbeat проверка туннеля завершилась неудачей ($consecutiveFails/2)")
                    if (consecutiveFails >= 2) {
                        val physOk = isPhysicalNetworkAlive()
                        if (physOk) {
                            GeekModeLogger.log("Failover", "Физическая сеть доступна, но туннель не отвечает. Запуск Auto-Failover...")
                            consecutiveFails = 0
                            withContext(Dispatchers.Main) {
                                switchToNextServer()
                            }
                            break
                        } else {
                            GeekModeLogger.log("Failover", "Физическая сеть недоступна, сбой не связан с сервером. Ожидание сети...")
                        }
                    }
                } else {
                    consecutiveFails = 0
                }
            }
        }
    }

    private fun stopFailoverWatchdog() {
        failoverJob?.cancel()
        failoverJob = null
    }

    private fun testTunnelConnectivity(): Boolean {
        return try {
            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", socksPort.value))
            val checkUrl = connectionCheckServiceUrl.value.ifBlank { "http://cp.cloudflare.com/generate_204" }
            val url = java.net.URL(checkUrl)
            val conn = url.openConnection(proxy) as java.net.HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.instanceFollowRedirects = false
            conn.requestMethod = "HEAD"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        } catch (_: Exception) {
            false
        }
    }

    private fun isPhysicalNetworkAlive(): Boolean {
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("1.1.1.1", 53), 2000)
                true
            }
        } catch (_: Exception) {
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("77.88.8.8", 53), 2000)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    fun toggleVip(guid: String) {
        val currentVips = serverRepo.getVipServerGuids()
        if (currentVips.contains(guid)) {
            serverRepo.removeVipServer(guid)
        } else {
            serverRepo.addVipServer(guid, vipCacheLimit.value)
        }
    }

    fun removeVipServer(guid: String) {
        serverRepo.removeVipServer(guid)
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
            GeekModeLogger.log("GeekMode", "HTTP 204 верификация ${vips.size} VIP узлов через Xray...")
            val xrayPath = settingsRepo.getString("xray_path").ifBlank { null }
            for (guid in vips) {
                val profile = serverRepo.getServerConfig(guid) ?: continue
                val res = DesktopProfileVerifier.verifyProfile(
                    profile = profile,
                    xrayBinaryPath = xrayPath,
                    timeoutMs = 3500L,
                    speedCheckEnabled = false
                )
                val latency = if (res.success && res.latencyMs > 0) res.latencyMs else -1L
                _pingMap.value = _pingMap.value + (guid to latency)
                GeekModeLogger.log("GeekMode", "VIP 204: ${profile.remarks} -> ${if (latency > 0) "${latency}ms" else "Сбой"}")
            }
            isPingingVip.value = false
        }
    }

    fun findMoreVipServers() {
        val all = servers.value
        if (all.isEmpty()) return
        scope.launch {
            isFindingVip.value = true
            val limit = vipCacheLimit.value
            GeekModeLogger.log("GeekMode", "Поиск новых VIP узлов (v2w-core + Xray HTTP 204 + скорость, лимит $limit)...")
            val v2wPath = settingsRepo.getString("v2w_scan_path").ifBlank { null }
            val xrayPath = settingsRepo.getString("xray_path").ifBlank { null }

            V2WCoreScanner.runStreamingScan(
                servers = all,
                customBinaryPath = v2wPath,
                firstOnly = false,
                onNodeSuccess = { guid: String, profile: ProfileItem, delayMs: Long ->
                    if (delayMs in 1..1500) {
                        // Верификация через Xray ядро и скорость
                        val verifyRes = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                            DesktopProfileVerifier.verifyProfile(
                                profile = profile,
                                xrayBinaryPath = xrayPath,
                                timeoutMs = 4000L,
                                speedCheckEnabled = speedTestOnCheck.value,
                                minSpeedMbps = minSpeedThresholdMbps.value,
                                bytesToDownload = speedTestDownloadBytes.value.toLong()
                            )
                        }
                        if (verifyRes.success) {
                            serverRepo.addVipServer(guid, limit)
                            _pingMap.value = _pingMap.value + (guid to (if (verifyRes.latencyMs > 0) verifyRes.latencyMs else delayMs))
                            GeekModeLogger.log("GeekMode", "Узел принят в VIP: ${profile.remarks} (204: ${verifyRes.latencyMs}ms, скорость: ${verifyRes.speedMbps ?: "—"} Мбит/с)")
                        } else {
                            GeekModeLogger.log("GeekMode", "Узел ${profile.remarks} отклонен: ${verifyRes.errorMessage}")
                        }
                    }
                    serverRepo.getVipServerGuids().size >= limit
                }
            )
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
        subUpdateOnStart: Boolean = subscriptionUpdateOnStartup.value,
        subOnlyViaProxy: Boolean = subscriptionUpdateOnlyViaProxy.value,
        customEndpoint: Boolean = useCustomEndpoint.value,
        customEndpointConfig: String = customEndpointUrl.value,
        customEndpointSub: String = customEndpointSubUrl.value,
        endpointE2eCheck: Boolean = endpointE2eCheckEnabled.value,
        bypassRu: Boolean = bypassRussianServices.value,
        checkUrl: String = connectionCheckServiceUrl.value,
        smartBatch: Int = smartConnectBatchSize.value,
        smartPreset: String = smartDistributionPreset.value,
        v2wCore: Boolean = useV2WCore.value,
        fallbackScanner: Boolean = useFallbackScanner.value,
        v2wSockets: Int = v2wCoreSockets.value,
        removeInsecure: Boolean = removeInsecureProfiles.value,
        testBeforeConnect: Boolean = testProfileBeforeConnect.value,
        speedTestCheck: Boolean = speedTestOnCheck.value,
        speedTestBytes: Int = speedTestDownloadBytes.value,
        speedTestTimeout: Int = speedTestTimeoutMs.value,
        minSpeed: Double = minSpeedThresholdMbps.value,
        autoFailoverEnabled: Boolean = autoFailover.value,
        heartbeatSec: Int = heartbeatIntervalSeconds.value,
        smartFailoverEnabled: Boolean = smartFailover.value,
        degradationKb: Int = failoverDegradationThresholdKb.value,
        stuckSec: Int = failoverStuckDurationSeconds.value,
        netCheckEnabled: Boolean = networkCheckEnabled.value,
        primaryDomain: String = networkCheckPrimaryDomain.value,
        fallbackDomain: String = networkCheckFallbackDomain.value,
        betaUpdates: Boolean = receiveBetaUpdates.value,
        prefIpv6: Boolean = preferIpv6.value,
        localDnsVal: Boolean = useLocalDns.value,
        fakeDnsVal: Boolean = useFakeDns.value,
        addHttpProxyVal: Boolean = additionalHttpProxy.value,
        vpnDnsVal: String = vpnDns.value,
        vpnBypassLanVal: Boolean = vpnBypassLan.value,
        vpnAddressVal: String = vpnInterfaceAddress.value,
        vpnMtuVal: Int = vpnMtu.value,
        sniffingVal: Boolean = sniffingEnabled.value,
        routeOnlyVal: Boolean = routeOnly.value,
        allowInsecureVal: Boolean = allowInsecure.value,
        localPortVal: Int = localProxyPort.value,
        localLoginVal: String = localProxyLogin.value,
        localPassVal: String = localProxyPassword.value,
        remoteDnsVal: String = remoteDns.value,
        internalDnsVal: String = internalDns.value,
        dnsHostsVal: String = dnsHosts.value,
        muxVal: Boolean = muxEnabled.value,
        muxTcpVal: Int = muxTcpConcurrency.value,
        muxXudpVal: Int = muxXudpConcurrency.value,
        fragmentVal: Boolean = fragmentEnabled.value,
        fragmentLenVal: String = fragmentLength.value,
        fragmentIntVal: String = fragmentInterval.value,
        fragmentPackVal: String = fragmentPackets.value,
        yandexUpdateVal: Boolean = yandexTranslateUpdateEnabled.value,
        domainSplitVal: Boolean = domainSplitTunnelingEnabled.value,
        domainWhitelistVal: Boolean = domainWhitelistMode.value,
        vipLimit: Int = vipCacheLimit.value
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
        subscriptionUpdateOnStartup.value = subUpdateOnStart
        subscriptionUpdateOnlyViaProxy.value = subOnlyViaProxy
        useCustomEndpoint.value = customEndpoint
        customEndpointUrl.value = customEndpointConfig
        customEndpointSubUrl.value = customEndpointSub
        endpointE2eCheckEnabled.value = endpointE2eCheck
        bypassRussianServices.value = bypassRu
        connectionCheckServiceUrl.value = checkUrl

        smartConnectBatchSize.value = smartBatch
        smartDistributionPreset.value = smartPreset
        useV2WCore.value = v2wCore
        useFallbackScanner.value = fallbackScanner
        v2wCoreSockets.value = v2wSockets
        removeInsecureProfiles.value = removeInsecure
        testProfileBeforeConnect.value = testBeforeConnect
        speedTestOnCheck.value = speedTestCheck
        speedTestDownloadBytes.value = speedTestBytes
        speedTestTimeoutMs.value = speedTestTimeout
        minSpeedThresholdMbps.value = minSpeed
        autoFailover.value = autoFailoverEnabled
        heartbeatIntervalSeconds.value = heartbeatSec
        smartFailover.value = smartFailoverEnabled
        failoverDegradationThresholdKb.value = degradationKb
        failoverStuckDurationSeconds.value = stuckSec
        networkCheckEnabled.value = netCheckEnabled
        networkCheckPrimaryDomain.value = primaryDomain
        networkCheckFallbackDomain.value = fallbackDomain
        receiveBetaUpdates.value = betaUpdates
        preferIpv6.value = prefIpv6
        useLocalDns.value = localDnsVal
        useFakeDns.value = fakeDnsVal
        additionalHttpProxy.value = addHttpProxyVal
        vpnDns.value = vpnDnsVal
        vpnBypassLan.value = vpnBypassLanVal
        vpnInterfaceAddress.value = vpnAddressVal
        vpnMtu.value = vpnMtuVal
        sniffingEnabled.value = sniffingVal
        routeOnly.value = routeOnlyVal
        allowInsecure.value = allowInsecureVal
        val effectiveSocks = if (socksP > 0) socksP else localPortVal
        val effectiveHttp = if (httpP > 0 && httpP != effectiveSocks) httpP else effectiveSocks + 1
        localProxyPort.value = effectiveSocks
        socksPort.value = effectiveSocks
        httpPort.value = effectiveHttp

        localProxyLogin.value = localLoginVal
        localProxyPassword.value = localPassVal
        remoteDns.value = remoteDnsVal
        internalDns.value = internalDnsVal
        dnsHosts.value = dnsHostsVal
        muxEnabled.value = muxVal
        muxTcpConcurrency.value = muxTcpVal
        muxXudpConcurrency.value = muxXudpVal
        fragmentEnabled.value = fragmentVal
        fragmentLength.value = fragmentLenVal
        fragmentInterval.value = fragmentIntVal
        fragmentPackets.value = fragmentPackVal
        yandexTranslateUpdateEnabled.value = yandexUpdateVal
        domainSplitTunnelingEnabled.value = domainSplitVal

        settingsRepo.setString("subscription_url", subUrl)
        settingsRepo.setString("xray_path", xrayPath)
        settingsRepo.setString("v2w_scan_path", v2wPath)
        settingsRepo.setHttpPort(effectiveHttp)
        settingsRepo.setSocksPort(effectiveSocks)
        settingsRepo.setInt("pref_local_proxy_port", effectiveSocks)
        settingsRepo.setBoolean("auto_enable_system_proxy", autoSystemProxy)
        settingsRepo.setBoolean("allow_lan", lan)
        settingsRepo.setString("custom_direct_domains", directDomains)
        settingsRepo.setString("custom_proxy_domains", proxyDomains)
        settingsRepo.setString("subscription_user_agent", subUserAgent)
        settingsRepo.setInt("subscription_threads", subThreads)
        settingsRepo.setBoolean("subscription_auto_update", subAutoUpdate)
        settingsRepo.setInt("subscription_update_interval_minutes", subIntervalMins)
        settingsRepo.setBoolean("subscription_update_on_startup", subUpdateOnStart)
        settingsRepo.setBoolean("subscription_update_only_via_proxy", subOnlyViaProxy)
        settingsRepo.setBoolean("pref_custom_endpoint_enabled", customEndpoint)
        settingsRepo.setString("pref_custom_endpoint_url", customEndpointConfig)
        settingsRepo.setString("pref_custom_endpoint_sub_url", customEndpointSub)
        settingsRepo.setBoolean("pref_endpoint_e2e_check_enabled", endpointE2eCheck)
        settingsRepo.setBoolean("pref_bypass_russian_services", bypassRu)
        settingsRepo.setString("pref_conn_check_url", checkUrl)

        settingsRepo.setInt("pref_smart_batch_size", smartBatch)
        settingsRepo.setString("pref_smart_distribution", smartPreset)
        settingsRepo.setBoolean("pref_use_v2w_core", v2wCore)
        settingsRepo.setBoolean("pref_use_fallback_scanner", fallbackScanner)
        settingsRepo.setInt("pref_v2w_core_sockets", v2wSockets)
        settingsRepo.setBoolean("pref_remove_insecure_profiles", removeInsecure)
        settingsRepo.setBoolean("pref_test_before_connect", testBeforeConnect)
        settingsRepo.setBoolean("pref_speed_test_on_check", speedTestCheck)
        settingsRepo.setInt("pref_speed_test_bytes", speedTestBytes)
        settingsRepo.setInt("pref_speed_test_timeout_ms", speedTestTimeout)
        settingsRepo.setString("pref_min_speed_mbps", minSpeed.toString())
        settingsRepo.setBoolean("pref_auto_failover", autoFailoverEnabled)
        settingsRepo.setInt("pref_heartbeat_interval", heartbeatSec)
        settingsRepo.setBoolean("pref_smart_failover", smartFailoverEnabled)
        settingsRepo.setInt("pref_failover_degradation_kb", degradationKb)
        settingsRepo.setInt("pref_failover_stuck_sec", stuckSec)
        settingsRepo.setBoolean("pref_network_check_enabled", netCheckEnabled)
        settingsRepo.setString("pref_net_check_primary", primaryDomain)
        settingsRepo.setString("pref_net_check_fallback", fallbackDomain)
        settingsRepo.setBoolean("pref_receive_beta_updates", betaUpdates)
        settingsRepo.setBoolean("pref_prefer_ipv6", prefIpv6)
        settingsRepo.setBoolean("pref_use_local_dns", localDnsVal)
        settingsRepo.setBoolean("pref_use_fakedns", fakeDnsVal)
        settingsRepo.setBoolean("pref_add_http_proxy", addHttpProxyVal)
        settingsRepo.setString("pref_vpn_dns", vpnDnsVal)
        settingsRepo.setBoolean("pref_vpn_bypass_lan", vpnBypassLanVal)
        settingsRepo.setString("pref_vpn_address", vpnAddressVal)
        settingsRepo.setInt("pref_vpn_mtu", vpnMtuVal)
        settingsRepo.setBoolean("pref_sniffing_enabled", sniffingVal)
        settingsRepo.setBoolean("pref_route_only", routeOnlyVal)
        settingsRepo.setBoolean("pref_allow_insecure", allowInsecureVal)
        settingsRepo.setString("pref_local_proxy_login", localLoginVal)
        settingsRepo.setString("pref_local_proxy_pass", localPassVal)
        settingsRepo.setString("pref_remote_dns", remoteDnsVal)
        settingsRepo.setString("pref_internal_dns", internalDnsVal)
        settingsRepo.setString("pref_dns_hosts", dnsHostsVal)
        settingsRepo.setBoolean("pref_mux_enabled", muxVal)
        settingsRepo.setInt("pref_mux_tcp", muxTcpVal)
        settingsRepo.setInt("pref_mux_xudp", muxXudpVal)
        settingsRepo.setBoolean("pref_fragment_enabled", fragmentVal)
        settingsRepo.setString("pref_fragment_length", fragmentLenVal)
        settingsRepo.setString("pref_fragment_interval", fragmentIntVal)
        settingsRepo.setString("pref_fragment_packets", fragmentPackVal)
        settingsRepo.setBoolean("pref_yandex_translate_update", yandexUpdateVal)
        settingsRepo.setBoolean("pref_domain_split_tunneling", domainSplitVal)
        domainWhitelistMode.value = domainWhitelistVal
        settingsRepo.setBoolean("pref_domain_whitelist_mode", domainWhitelistVal)
        vipCacheLimit.value = vipLimit
        settingsRepo.setInt("pref_vip_cache_limit", vipLimit)

        restartPeriodicSubscriptionUpdates()

        GeekModeLogger.log("Settings", "Настройки успешно сохранены.")
        if (coreState.value == DesktopCoreState.RUNNING) {
            connectCurrent()
        }
    }

    /**
     * Сброс всех настроек, серверов, подписок и кэша к заводскому состоянию по одной кнопке.
     */
    fun resetAllSettingsAndData() {
        stopCore()
        DesktopProxyManager.disableSystemProxy()
        isSystemProxyActive.value = false

        serverRepo.clearAllServers()
        settingsRepo.clearAll()
        DesktopStorageManager.clearDiskStorage()

        // Сброс StateFlow к дефолтным значениям
        _scenario.value = AppScenario.AUTO
        _selectedGuid.value = null
        socksPort.value = 10808
        httpPort.value = 10809
        localProxyPort.value = 10808
        autoEnableSystemProxy.value = false
        allowLan.value = false
        routingMode.value = "PROXY"
        minSpeedThresholdMbps.value = 1.0
        speedTestOnCheck.value = true
        smartConnectBatchSize.value = 20
        smartFailover.value = true

        // Восстановление подписок по умолчанию
        _subscriptions.value = DefaultSubscriptions.PREPOPULATED_SUBS
        saveSubscriptionsToStorage(_subscriptions.value)

        clipboardNotice.value = "Все настройки, серверы и кэш успешно сброшены к начальным значениям."
        GeekModeLogger.log("AppState", "Полный сброс всех настроек, подписок и серверов выполнен.")
    }

    fun dismissUpdateBanner() {
        availableAppUpdate.value = null
    }

    fun openWebUrl(url: String) {
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI(url))
            } else {
                ProcessBuilder("xdg-open", url).start()
            }
        } catch (_: Exception) {
            try {
                ProcessBuilder("xdg-open", url).start()
            } catch (_: Exception) {}
        }
    }

    fun getDesktopTargetPlatform(): TargetPlatform.Desktop {
        val osProp = System.getProperty("os.name")?.lowercase().orEmpty()
        val archProp = System.getProperty("os.arch")?.lowercase().orEmpty()
        val os = when {
            osProp.contains("win") -> "windows"
            osProp.contains("mac") -> "mac"
            else -> "linux"
        }
        val arch = when {
            archProp.contains("aarch64") || archProp.contains("arm64") -> "arm64"
            archProp.contains("64") -> "x64"
            else -> "x86"
        }
        return TargetPlatform.Desktop(os = os, arch = arch)
    }

    fun checkAppUpdate(manual: Boolean = false) {
        if (isCheckingAppUpdate.value) return
        scope.launch(Dispatchers.IO) {
            isCheckingAppUpdate.value = true
            appUpdateCheckMessage.value = null
            try {
                GeekModeLogger.log("UpdateChecker", "Проверка обновлений ПроБел (manual=$manual, beta=${receiveBetaUpdates.value})...")
                val platform = getDesktopTargetPlatform()
                val result = UpdateChecker.checkForUpdate(
                    apiUrl = GITHUB_REPO_API,
                    currentVersion = APP_VERSION,
                    targetPlatform = platform,
                    includePreRelease = receiveBetaUpdates.value
                )
                if (result.hasUpdate) {
                    GeekModeLogger.log("UpdateChecker", "Найдено обновление: v${result.latestVersion}")
                    availableAppUpdate.value = result
                    showAppUpdateDialog.value = true
                } else {
                    if (manual) {
                        if (result.error != null) {
                            appUpdateCheckMessage.value = "Ошибка проверки: ${result.error}"
                            GeekModeLogger.log("UpdateChecker", "Ошибка проверки: ${result.error}")
                        } else {
                            appUpdateCheckMessage.value = "У вас установлена последняя версия (v$APP_VERSION)"
                            GeekModeLogger.log("UpdateChecker", "Установлена актуальная версия (v$APP_VERSION).")
                        }
                    }
                }
            } catch (e: Exception) {
                GeekModeLogger.log("UpdateChecker", "Сбой проверки обновлений: ${e.message}")
                if (manual) {
                    appUpdateCheckMessage.value = "Ошибка: ${e.message}"
                }
            } finally {
                isCheckingAppUpdate.value = false
            }
        }
    }

    companion object {
        const val APP_VERSION = "2.0.0"
        const val GITHUB_REPO_API = "https://api.github.com/repos/NullCoreDeveloper/v2whitelist/releases"
        const val GITHUB_RELEASES_WEB = "https://github.com/NullCoreDeveloper/v2whitelist/releases"
    }
}
