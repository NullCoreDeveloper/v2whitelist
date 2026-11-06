package com.kiktor.v2whitelist.desktop.engine

import com.kiktor.v2whitelist.core.domain.network.NetworkStatusChecker
import com.kiktor.v2whitelist.core.domain.personal.PersonalConnectDecision
import com.kiktor.v2whitelist.core.domain.personal.PersonalServerEvaluator
import com.kiktor.v2whitelist.core.domain.ping.NodePingTester
import com.kiktor.v2whitelist.core.domain.selector.LocationFilterMode
import com.kiktor.v2whitelist.core.domain.selector.SmartServerFilterConfig
import com.kiktor.v2whitelist.core.domain.selector.SmartServerSelector
import com.kiktor.v2whitelist.core.model.AppScenario
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.NetworkBindingRule
import com.kiktor.v2whitelist.core.model.PersonalServersConfig
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import com.kiktor.v2whitelist.core.subscription.model.CustomSubData
import com.kiktor.v2whitelist.core.repository.ServerRepository
import com.kiktor.v2whitelist.core.repository.SettingsRepository
import com.kiktor.v2whitelist.core.subscription.tag.ServerTagResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json

enum class SmartConnectStage {
    IDLE,
    CHECKING_VIP,
    V2W_SCANNING,
    VERIFYING_NODE,
    FALLBACK_SCANNING,
    SUCCESS,
    FAILED
}

/**
 * Архитектурный менеджер интеллектуального поиска серверов на Desktop (1-в-1 архитектура Android):
 * 1. Проверка VIP-кэша проверенных серверов (Fast Path).
 * 2. Запуск v2w-core сканера (быстрый TCP + e2e handshake).
 * 3. Потоковая проверка кандидатов через Xray (HTTP 204 + порог скорости Мбит/с).
 * 4. Фоновое наполнение VIP-кэша накопившимися ответившими узлами (leftovers).
 * 5. Надежный фоллбек на классический сканер при сбое v2w-core.
 */
class DesktopSmartConnectManager(
    private val serverRepo: ServerRepository,
    private val settingsRepo: SettingsRepository,
    private val xrayRunner: XrayDesktopRunner,
    private val subscriptionsProvider: () -> List<CustomSubData> = { emptyList() },
    private val networkStatusProvider: () -> Int = { NetworkStatusChecker.STATUS_FULL_INTERNET },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    var onStageChanged: ((SmartConnectStage, String) -> Unit)? = null
    var onServerConnected: ((guid: String, profile: ProfileItem) -> Unit)? = null
    var connectAction: ((guid: String, profile: ProfileItem) -> Unit)? = null
    var onNodeVerified: ((guid: String, latencyMs: Long) -> Unit)? = null

    private val vipFailCounters = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun loadLocationFilterConfig(excludeGuid: String? = null, scenario: AppScenario): SmartServerFilterConfig {
        val filterModeStr = settingsRepo.getString("pref_location_filter_mode", "EXCLUDE")
        val locMode = LocationFilterMode.fromString(filterModeStr)
        val locSetJson = settingsRepo.getString("pref_location_filter_set", "")
        val locSet = try {
            if (locSetJson.isNotBlank()) Json.decodeFromString<Set<String>>(locSetJson) else ServerTagResolver.getDefaultFilterSet()
        } catch (_: Exception) {
            ServerTagResolver.getDefaultFilterSet()
        }

        val subs = subscriptionsProvider()
        val disabledSubIds = subs.filter { !it.enabled }
            .flatMap { listOf(it.id, it.name, "custom_sub_${it.id}") }
            .toSet()

        val groupRegexMap = mutableMapOf<String, String>()
        for (sub in subs) {
            if (sub.groupRegex.isNotBlank()) {
                groupRegexMap[sub.id] = sub.groupRegex
                groupRegexMap[sub.name] = sub.groupRegex
                groupRegexMap["custom_sub_${sub.id}"] = sub.groupRegex
            }
        }

        val subScenariosMap = mutableMapOf<String, Set<String>>()
        for (sub in subs) {
            val scSet = sub.targetScenarios.toSet()
            if (scSet.isNotEmpty()) {
                subScenariosMap[sub.id] = scSet
                subScenariosMap[sub.name] = scSet
                subScenariosMap["custom_sub_${sub.id}"] = scSet
            }
        }

        return SmartServerFilterConfig(
            excludeGuid = excludeGuid,
            disabledSubscriptionIds = disabledSubIds,
            locationFilterMode = locMode,
            locationFilterSet = locSet,
            groupRegexMap = groupRegexMap,
            appScenario = scenario,
            internetStatus = networkStatusProvider(),
            subscriptionScenariosMap = subScenariosMap,
            filterByScenario = true
        )
    }

    private var activeJob: Job? = null

    fun stop() {
        activeJob?.cancel()
        activeJob = null
        V2WCoreScanner.stop()
        DesktopProfileVerifier.stop()
    }

    suspend fun resolveEffectiveScenario(scenario: AppScenario): AppScenario {
        if (scenario != AppScenario.AUTO) {
            return when (scenario) {
                AppScenario.WHITELIST -> AppScenario.WHITELIST
                else -> AppScenario.VPN_BLACKLIST
            }
        }
        val status = try {
            withTimeoutOrNull(1200L) {
                NetworkStatusChecker.checkTcpInternetStatus()
            } ?: networkStatusProvider()
        } catch (_: Exception) {
            networkStatusProvider()
        }
        return if (status == NetworkStatusChecker.STATUS_WHITELIST_ONLY) {
            AppScenario.WHITELIST
        } else {
            AppScenario.VPN_BLACKLIST
        }
    }

    suspend fun startSmartConnect(
        scenario: AppScenario = settingsRepo.getAppScenario(),
        minSpeedMbps: Double = 1.0,
        speedCheckEnabled: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val currentJob = coroutineContext[Job]
        if (activeJob != null && activeJob != currentJob) {
            activeJob?.cancel()
        }
        activeJob = currentJob
        V2WCoreScanner.stop()
        DesktopProfileVerifier.stop()

        try {
            val effectiveScenario = resolveEffectiveScenario(scenario)
            GeekModeLogger.log("SmartConnect", "SmartConnect: выбранный сценарий: $scenario, эффективный: $effectiveScenario")

            val xrayPath = settingsRepo.getString("xray_path").takeIf { it.isNotBlank() && java.io.File(it).exists() }
            val v2wPath = settingsRepo.getString("v2w_scan_path").takeIf { it.isNotBlank() && java.io.File(it).exists() }
            val customEndpointUrl = if (settingsRepo.getBoolean("pref_custom_endpoint_enabled", false)) {
                settingsRepo.getString("pref_custom_endpoint_url").ifBlank { null }
            } else null
            val endpointE2eCheck = settingsRepo.getBoolean("pref_endpoint_e2e_check_enabled", true)
            val serviceCheckEnabled = settingsRepo.getBoolean("pref_service_check_enabled", false)
            val servicesList = com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckItem.parseList(
                settingsRepo.getString("pref_service_check_items", "")
            )

            // ══════════════════════════════════════════════════════════════════════════════
            // ЭТАП 0: Проверка и применение доверенных серверов (Personal Servers)
            // ══════════════════════════════════════════════════════════════════════════════
            val personalEnabled = settingsRepo.getBoolean("pref_personal_servers_enabled", false)
            if (personalEnabled) {
                val personalConfig = PersonalServersConfig(
                    enabled = true,
                    selectedServerGuid = settingsRepo.getString("pref_personal_selected_guid", "").takeIf { it.isNotBlank() },
                    ignoreExitNodeForPersonal = settingsRepo.getBoolean("pref_personal_ignore_exit_node", true),
                    autoConnectOnStartup = settingsRepo.getBoolean("pref_personal_auto_connect", true),
                    fallbackToPublicOnFailure = settingsRepo.getBoolean("pref_personal_fallback_public", true),
                    blacklistRule = try {
                        NetworkBindingRule.valueOf(settingsRepo.getString("pref_personal_blacklist_rule", NetworkBindingRule.USE_PERSONAL_AUTO.name))
                    } catch (_: Exception) {
                        NetworkBindingRule.USE_PERSONAL_AUTO
                    },
                    whitelistRule = try {
                        NetworkBindingRule.valueOf(settingsRepo.getString("pref_personal_whitelist_rule", NetworkBindingRule.USE_PUBLIC_SUBS.name))
                    } catch (_: Exception) {
                        NetworkBindingRule.USE_PUBLIC_SUBS
                    }
                )

                val allPersonalCandidates = serverRepo.serversFlow.value.filter {
                    PersonalServersConfig.isPersonalServer(it.second.subscriptionId)
                }

                val decision = PersonalServerEvaluator.evaluateScenario(
                    config = personalConfig,
                    effectiveScenario = effectiveScenario,
                    allPersonalCandidates = allPersonalCandidates
                )
                GeekModeLogger.log("SmartConnect", "PersonalServers: решение для $effectiveScenario -> $decision")

                when (decision) {
                    is PersonalConnectDecision.DirectNoVpn -> {
                        GeekModeLogger.log("SmartConnect", "PersonalServers: правило сценария DirectNoVpn")
                        onStageChanged?.invoke(SmartConnectStage.FAILED, "Правило сценария: Прямой интернет без VPN")
                        return@withContext false
                    }
                    is PersonalConnectDecision.ConnectPersonalDirect -> {
                        val sName = decision.profile.remarks.ifBlank { decision.profile.server }
                        onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, "Проверка личного сервера: $sName…")
                        GeekModeLogger.log("SmartConnect", "Прямое подключение к личному серверу $sName (${decision.guid})")
                        val res = DesktopProfileVerifier.verifyProfile(
                            profile = decision.profile,
                            xrayBinaryPath = xrayPath,
                            speedCheckEnabled = false,
                            customEndpointUrl = customEndpointUrl,
                            endpointE2eCheckEnabled = endpointE2eCheck,
                            serviceCheckEnabled = serviceCheckEnabled,
                            servicesToCheck = servicesList,
                            onStatusUpdate = { text -> onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, text) }
                        )
                        if (res.success) {
                            if (res.latencyMs > 0) {
                                onNodeVerified?.invoke(decision.guid, res.latencyMs)
                            }
                            onStageChanged?.invoke(SmartConnectStage.SUCCESS, "Подключено к личному серверу: $sName")
                            delay(250)
                            connectAndFinish(decision.guid, decision.profile)
                            return@withContext true
                        } else {
                            GeekModeLogger.log("SmartConnect", "Выбранный личный сервер $sName не отвечает.")
                            if (!personalConfig.fallbackToPublicOnFailure) {
                                onStageChanged?.invoke(SmartConnectStage.FAILED, "Выбранный личный сервер не отвечает")
                                return@withContext false
                            }
                            onStageChanged?.invoke(SmartConnectStage.V2W_SCANNING, "Личный сервер недоступен, переключение на сторонние подписки…")
                        }
                    }
                    is PersonalConnectDecision.ScanPersonalPool -> {
                        onStageChanged?.invoke(SmartConnectStage.V2W_SCANNING, "Поиск лучшего доверенного сервера (${decision.candidates.size} узлов)…")
                        GeekModeLogger.log("SmartConnect", "PersonalServers: сканирование пула из ${decision.candidates.size} узлов...")

                        var personalWinner: Pair<String, ProfileItem>? = null
                        val personalChannel = Channel<Triple<String, ProfileItem, Long>>(Channel.UNLIMITED)

                        val pScanJob = launch(Dispatchers.IO) {
                            try {
                                V2WCoreScanner.runStreamingScan(
                                    servers = decision.candidates,
                                    customBinaryPath = v2wPath,
                                    firstOnly = false,
                                    onNodeSuccess = { guid, profile, delayMs ->
                                        personalChannel.trySend(Triple(guid, profile, delayMs))
                                        personalWinner != null
                                    }
                                )
                            } finally {
                                personalChannel.close()
                            }
                        }

                        try {
                            for (candidate in personalChannel) {
                                if (!isActive) break
                                val pName = candidate.second.remarks.ifBlank { candidate.second.server }
                                onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, "Проверяем личный сервер: $pName…")
                                val vRes = DesktopProfileVerifier.verifyProfile(
                                    profile = candidate.second,
                                    xrayBinaryPath = xrayPath,
                                    speedCheckEnabled = speedCheckEnabled,
                                    minSpeedMbps = minSpeedMbps,
                                    customEndpointUrl = customEndpointUrl,
                                    endpointE2eCheckEnabled = endpointE2eCheck,
                                    serviceCheckEnabled = serviceCheckEnabled,
                                    servicesToCheck = servicesList,
                                    onStatusUpdate = { text -> onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, text) }
                                )
                                if (vRes.success) {
                                    personalWinner = candidate.first to candidate.second
                                    if (vRes.latencyMs > 0) {
                                        onNodeVerified?.invoke(candidate.first, vRes.latencyMs)
                                    }
                                    pScanJob.cancel()
                                    V2WCoreScanner.stop()
                                    onStageChanged?.invoke(SmartConnectStage.SUCCESS, "Подключено к личному серверу: $pName")
                                    delay(250)
                                    connectAndFinish(candidate.first, candidate.second)
                                    return@withContext true
                                }
                            }
                        } finally {
                            pScanJob.cancel()
                            V2WCoreScanner.stop()
                        }

                        // Fallback на классический TCP пинг для личных узлов
                        GeekModeLogger.log("SmartConnect", "v2w-core не нашел рабочих личных узлов, пробуем TCP пинг...")
                        val pTcpResults = NodePingTester.testBatchTcp(decision.candidates, timeoutMs = 1500L)
                        for ((guid, profile, _) in pTcpResults) {
                            if (!isActive) return@withContext false
                            val pName = profile.remarks.ifBlank { profile.server }
                            onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, "Проверяем скорость личного сервера: $pName…")
                            val vRes = DesktopProfileVerifier.verifyProfile(
                                profile = profile,
                                xrayBinaryPath = xrayPath,
                                speedCheckEnabled = speedCheckEnabled,
                                minSpeedMbps = minSpeedMbps,
                                customEndpointUrl = customEndpointUrl,
                                endpointE2eCheckEnabled = endpointE2eCheck,
                                serviceCheckEnabled = serviceCheckEnabled,
                                servicesToCheck = servicesList,
                                onStatusUpdate = { text -> onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, text) }
                            )
                            if (vRes.success) {
                                if (vRes.latencyMs > 0) {
                                    onNodeVerified?.invoke(guid, vRes.latencyMs)
                                }
                                onStageChanged?.invoke(SmartConnectStage.SUCCESS, "Подключено к личному серверу: $pName")
                                delay(250)
                                connectAndFinish(guid, profile)
                                return@withContext true
                            }
                        }

                        GeekModeLogger.log("SmartConnect", "Все личные серверы не прошли проверку.")
                        if (!personalConfig.fallbackToPublicOnFailure) {
                            onStageChanged?.invoke(SmartConnectStage.FAILED, "Все доверенные серверы недоступны")
                            return@withContext false
                        }
                        onStageChanged?.invoke(SmartConnectStage.V2W_SCANNING, "Доверенные серверы недоступны, переключение на сторонние подписки…")
                    }
                    is PersonalConnectDecision.UsePublicSubscriptions -> {
                        GeekModeLogger.log("SmartConnect", "PersonalServers: ${decision.reason}")
                    }
                }
            }

            // ══════════════════════════════════════════════════════════════════════════════
            // Поиск по сторонним подпискам ПроБел (Public Subscriptions)
            // ══════════════════════════════════════════════════════════════════════════════
            val allServers = serverRepo.serversFlow.value
            val allPublicServers = allServers.filter {
                !PersonalServersConfig.isPersonalServer(it.second.subscriptionId)
            }
            if (allPublicServers.isEmpty()) {
                GeekModeLogger.log("SmartConnect", "Нет доступных сторонних серверов для поиска.")
                onStageChanged?.invoke(SmartConnectStage.FAILED, "Список сторонних серверов пуст")
                return@withContext false
            }

            // Фильтрация кандидатов по сценарию (БС / ЧС / YouTube / Авто) + фильтр локаций 1-в-1 как на Android
            val filterConfig = loadLocationFilterConfig(scenario = scenario)
            val candidates = SmartServerSelector.filterServerPairs(allPublicServers, filterConfig).shuffled()
            if (candidates.isEmpty()) {
                GeekModeLogger.log("SmartConnect", "Нет серверов, соответствующих сценарию $scenario с учетом фильтра стран.")
                onStageChanged?.invoke(SmartConnectStage.FAILED, "Нет серверов под сценарий $scenario")
                return@withContext false
            }

            // ══════════════════════════════════════════════════════════════════════════════
            // ЭТАП 1: Проверка VIP-серверов (Fast Path)
            // ══════════════════════════════════════════════════════════════════════════════
            val vipGuids = serverRepo.getVipServerGuids()
            if (vipGuids.isNotEmpty()) {
                onStageChanged?.invoke(SmartConnectStage.CHECKING_VIP, "Быстрое подключение через топ серверов…")
                GeekModeLogger.log("SmartConnect", "VIP Cache: проверка ${vipGuids.size} узлов...")

                val vipCandidates = vipGuids.mapNotNull { guid -> candidates.find { it.first == guid } }
                for ((guid, profile) in vipCandidates) {
                    if (!isActive) return@withContext false
                    val serverName = profile.remarks.ifBlank { profile.server }
                    onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, "Быстрое подключение к $serverName…")
                    val res = DesktopProfileVerifier.verifyProfile(
                        profile = profile,
                        xrayBinaryPath = xrayPath,
                        speedCheckEnabled = speedCheckEnabled,
                        minSpeedMbps = minSpeedMbps,
                        customEndpointUrl = customEndpointUrl,
                        endpointE2eCheckEnabled = endpointE2eCheck,
                        serviceCheckEnabled = serviceCheckEnabled,
                        servicesToCheck = servicesList,
                        onStatusUpdate = { text ->
                            onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, text)
                        }
                    )
                    if (res.success) {
                        vipFailCounters[guid] = 0
                        if (res.latencyMs > 0) {
                            onNodeVerified?.invoke(guid, res.latencyMs)
                        }
                        GeekModeLogger.log("SmartConnect", "VIP сервер подтвержден: ${profile.remarks} (${res.latencyMs}ms, ${res.speedMbps ?: "—"} Мбит/с)")
                        onStageChanged?.invoke(SmartConnectStage.SUCCESS, "Нашли отличный сервер! Подключаемся к $serverName…")
                        delay(350)
                        connectAndFinish(guid, profile)
                        return@withContext true
                    } else {
                        val fails = (vipFailCounters[guid] ?: 0) + 1
                        vipFailCounters[guid] = fails
                        if (fails >= 3) {
                            GeekModeLogger.log("SmartConnect", "VIP сервер ${profile.remarks} не прошел проверку 3 раза подряд ($fails/3), удаляем из VIP.")
                            serverRepo.removeVipServer(guid)
                            vipFailCounters.remove(guid)
                        } else {
                            GeekModeLogger.log("SmartConnect", "VIP сервер ${profile.remarks} временно недоступен (неудача $fails/3), сохраняем в VIP.")
                        }
                    }
                }
            }

            // ══════════════════════════════════════════════════════════════════════════════
            // ЭТАП 2: Потоковое сканирование через ядро v2w-core
            // ══════════════════════════════════════════════════════════════════════════════
            onStageChanged?.invoke(SmartConnectStage.V2W_SCANNING, "Ищем лучший сервер для вас…")
            GeekModeLogger.log("SmartConnect", "Запуск ядра v2w-core для ${candidates.size} узлов...")

            val channel = Channel<Triple<String, ProfileItem, Long>>(Channel.UNLIMITED)
            var winner: Pair<String, ProfileItem>? = null
            val leftovers = mutableListOf<Triple<String, ProfileItem, Long>>()

            val scanJob = launch(Dispatchers.IO) {
                try {
                    V2WCoreScanner.runStreamingScan(
                        servers = candidates,
                        customBinaryPath = v2wPath,
                        firstOnly = false,
                        onNodeSuccess = { guid, profile, delayMs ->
                            channel.trySend(Triple(guid, profile, delayMs))
                            winner != null // Если победитель найден, останавливаем сканер
                        }
                    )
                } finally {
                    channel.close()
                }
            }

            try {
                // Читаем поступающие от v2w-core узлы и верифицируем через Xray
                for (candidate in channel) {
                    if (!isActive) break

                    val serverName = candidate.second.remarks.ifBlank { candidate.second.server }
                    onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, "Проверяем профиль: $serverName…")
                    GeekModeLogger.log("SmartConnect", "v2w-core нашел узел $serverName (${candidate.third}ms) -> Xray верификация...")

                    val verifyRes = DesktopProfileVerifier.verifyProfile(
                        profile = candidate.second,
                        xrayBinaryPath = xrayPath,
                        speedCheckEnabled = speedCheckEnabled,
                        minSpeedMbps = minSpeedMbps,
                        customEndpointUrl = customEndpointUrl,
                        endpointE2eCheckEnabled = endpointE2eCheck,
                        serviceCheckEnabled = serviceCheckEnabled,
                        servicesToCheck = servicesList,
                        onStatusUpdate = { text ->
                            onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, text)
                        }
                    )

                    if (verifyRes.success) {
                        winner = candidate.first to candidate.second
                        if (verifyRes.latencyMs > 0) {
                            onNodeVerified?.invoke(candidate.first, verifyRes.latencyMs)
                        }
                        GeekModeLogger.log("SmartConnect", "Победитель найден и верифицирован: $serverName!")

                        // Добавляем победителя в VIP
                        val limit = settingsRepo.getInt("pref_vip_cache_limit", 5)
                        serverRepo.addVipServer(candidate.first, limit)

                        // Останавливаем сканирование v2w-core
                        scanJob.cancel()
                        V2WCoreScanner.stop()

                        // Сбор хвоста (leftovers)
                        while (true) {
                            val extra = channel.tryReceive().getOrNull() ?: break
                            if (extra.first != candidate.first) {
                                leftovers.add(extra)
                            }
                        }

                        // Фоновая допроверка хвоста в VIP
                        if (leftovers.isNotEmpty()) {
                            warmupLeftoversInVip(leftovers, xrayPath, minSpeedMbps, serviceCheckEnabled, servicesList)
                        }

                        onStageChanged?.invoke(SmartConnectStage.SUCCESS, "Нашли отличный сервер! Подключаемся к $serverName…")
                        delay(350)
                        connectAndFinish(candidate.first, candidate.second)
                        return@withContext true
                    } else {
                        GeekModeLogger.log("SmartConnect", "Узел $serverName не прошел порог скорости/e2e, берем следующий из очереди...")
                    }
                }
            } finally {
                scanJob.cancel()
                V2WCoreScanner.stop()
            }

            // ══════════════════════════════════════════════════════════════════════════════
            // ЭТАП 3: Фоллбек на классический сканер (Fallback)
            // ══════════════════════════════════════════════════════════════════════════════
            GeekModeLogger.log("SmartConnect", "v2w-core не нашел подходящих узлов. Фоллбек на классический сканер...")
            onStageChanged?.invoke(SmartConnectStage.FALLBACK_SCANNING, "v2w-core: переключение на старый сканер…")

            val tcpResults = NodePingTester.testBatchTcp(candidates, timeoutMs = 1500L)
            if (tcpResults.isNotEmpty()) {
                for ((guid, profile, delayMs) in tcpResults) {
                    if (!isActive) return@withContext false
                    val serverName = profile.remarks.ifBlank { profile.server }
                    onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, "Проверяем скорость серверов…")
                    val res = DesktopProfileVerifier.verifyProfile(
                        profile = profile,
                        xrayBinaryPath = xrayPath,
                        speedCheckEnabled = speedCheckEnabled,
                        minSpeedMbps = minSpeedMbps,
                        customEndpointUrl = customEndpointUrl,
                        endpointE2eCheckEnabled = endpointE2eCheck,
                        serviceCheckEnabled = serviceCheckEnabled,
                        servicesToCheck = servicesList,
                        onStatusUpdate = { text ->
                            onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, text)
                        }
                    )
                    if (res.success) {
                        val limit = settingsRepo.getInt("pref_vip_cache_limit", 5)
                        if (res.latencyMs > 0) {
                            onNodeVerified?.invoke(guid, res.latencyMs)
                        }
                        GeekModeLogger.log("SmartConnect", "Классический фоллбек нашел узел: $serverName")
                        serverRepo.addVipServer(guid, limit)
                        onStageChanged?.invoke(SmartConnectStage.SUCCESS, "Нашли отличный сервер! Подключаемся к $serverName…")
                        delay(350)
                        connectAndFinish(guid, profile)
                        return@withContext true
                    }
                }
            }

            GeekModeLogger.log("SmartConnect", "Все этапы завершены. Ни один узел не прошел проверку.")
            onStageChanged?.invoke(SmartConnectStage.FAILED, "Серверы не найдены. Попробуйте обновить список.")
            return@withContext false
        } finally {
            if (activeJob == currentJob) {
                activeJob = null
            }
            V2WCoreScanner.stop()
            DesktopProfileVerifier.stop()
        }
    }

    private fun connectAndFinish(guid: String, profile: ProfileItem) {
        serverRepo.setSelectedServerGuid(guid)
        if (connectAction != null) {
            connectAction?.invoke(guid, profile)
        } else {
            xrayRunner.start(
                profile = profile,
                scenario = settingsRepo.getAppScenario(),
                xrayBinaryPath = settingsRepo.getString("xray_path").ifBlank { null },
                httpPort = settingsRepo.getHttpPort(),
                socksPort = settingsRepo.getSocksPort(),
                customEndpointUrl = if (settingsRepo.getBoolean("pref_custom_endpoint_enabled", false)) {
                    settingsRepo.getString("pref_custom_endpoint_url").ifBlank { null }
                } else null
            )
        }
        onServerConnected?.invoke(guid, profile)
    }

    /**
     * Очистка VIP-кэша только от удаленных серверов (не удаляет сервера других локаций/сценариев).
     */
    fun cleanVipCacheForFilter() {
        val vips = serverRepo.getVipServerGuids()
        val allGuids = serverRepo.serversFlow.value.map { it.first }.toSet()
        for (g in vips) {
            if (!allGuids.contains(g)) {
                serverRepo.removeVipServer(g)
                vipFailCounters.remove(g)
            }
        }
    }

    /**
     * Быстрое переключение на следующий рабочий сервер (кнопка Сменить сервер).
     */
    suspend fun switchToNextServer(currentGuid: String?): Boolean = withContext(Dispatchers.IO) {
        val currentJob = coroutineContext[Job]
        if (activeJob != null && activeJob != currentJob) {
            activeJob?.cancel()
        }
        activeJob = currentJob
        V2WCoreScanner.stop()
        DesktopProfileVerifier.stop()

        try {
            val allServers = serverRepo.serversFlow.value
            val allPublicServers = allServers.filter {
                !PersonalServersConfig.isPersonalServer(it.second.subscriptionId)
            }
            if (allPublicServers.isEmpty()) return@withContext false
            val filterConfig = loadLocationFilterConfig(excludeGuid = currentGuid, scenario = settingsRepo.getAppScenario())
            val candidates = SmartServerSelector.filterServerPairs(allPublicServers, filterConfig)
            if (candidates.isEmpty()) return@withContext false

            val xrayPath = settingsRepo.getString("xray_path").takeIf { it.isNotBlank() && java.io.File(it).exists() }
            val customEndpointUrl = if (settingsRepo.getBoolean("pref_custom_endpoint_enabled", false)) {
                settingsRepo.getString("pref_custom_endpoint_url").ifBlank { null }
            } else null
            val endpointE2eCheck = settingsRepo.getBoolean("pref_endpoint_e2e_check_enabled", true)
            val serviceCheckEnabled = settingsRepo.getBoolean("pref_service_check_enabled", false)
            val servicesList = com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckItem.parseList(
                settingsRepo.getString("pref_service_check_items", "")
            )
            val limit = settingsRepo.getInt("pref_vip_cache_limit", 5)

            // 1. Быстрый путь: VIP Cache (как на Android)
            val vipGuids = serverRepo.getVipServerGuids().filter { it != currentGuid }
            if (vipGuids.isNotEmpty()) {
                onStageChanged?.invoke(SmartConnectStage.CHECKING_VIP, "Быстрое подключение через топ серверов…")
                GeekModeLogger.log("SmartConnect", "Смена сервера: проверка ${vipGuids.size} узлов из VIP кэша...")
                val vipCandidates = vipGuids.mapNotNull { guid -> candidates.find { it.first == guid } }
                for ((guid, profile) in vipCandidates) {
                    if (!isActive) return@withContext false
                    val serverName = profile.remarks.ifBlank { profile.server }
                    onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, "Быстрое подключение к $serverName…")
                    val res = DesktopProfileVerifier.verifyProfile(
                        profile = profile,
                        xrayBinaryPath = xrayPath,
                        speedCheckEnabled = false,
                        minSpeedMbps = 0.5,
                        customEndpointUrl = customEndpointUrl,
                        endpointE2eCheckEnabled = endpointE2eCheck,
                        serviceCheckEnabled = serviceCheckEnabled,
                        servicesToCheck = servicesList,
                        onStatusUpdate = { text ->
                            onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, text)
                        }
                    )
                    if (res.success) {
                        GeekModeLogger.log("SmartConnect", "VIP сервер подтвержден при смене: $serverName")
                        onStageChanged?.invoke(SmartConnectStage.SUCCESS, "Подключаемся к $serverName…")
                        delay(350)
                        connectAndFinish(guid, profile)
                        return@withContext true
                    }
                }
            }

            // 2. Если VIP не ответил — перебор остальных кандидатов
            onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, "Выбираем другой сервер…")
            GeekModeLogger.log("SmartConnect", "Смена сервера: перебор ${candidates.size} кандидатов...")
            for ((guid, profile) in candidates.shuffled()) {
                if (!isActive) return@withContext false
                if (vipGuids.contains(guid)) continue
                val serverName = profile.remarks.ifBlank { profile.server }
                onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, "Проверяем профиль: $serverName…")
                val res = DesktopProfileVerifier.verifyProfile(
                    profile = profile,
                    xrayBinaryPath = xrayPath,
                    speedCheckEnabled = false,
                    minSpeedMbps = 0.5,
                    customEndpointUrl = customEndpointUrl,
                    endpointE2eCheckEnabled = endpointE2eCheck,
                    serviceCheckEnabled = serviceCheckEnabled,
                    servicesToCheck = servicesList,
                    onStatusUpdate = { text ->
                        onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, text)
                    }
                )
                if (res.success && (res.latencyMs <= 0 || res.latencyMs < 3000L)) {
                    val limit = settingsRepo.getInt("pref_vip_cache_limit", 5)
                    serverRepo.addVipServer(guid, limit)
                    if (res.latencyMs > 0) {
                        onNodeVerified?.invoke(guid, res.latencyMs)
                    }
                    GeekModeLogger.log("SmartConnect", "Новый узел подтвержден при смене: $serverName")
                    onStageChanged?.invoke(SmartConnectStage.SUCCESS, "Подключаемся к $serverName…")
                    delay(350)
                    connectAndFinish(guid, profile)
                    return@withContext true
                }
            }
            GeekModeLogger.log("SmartConnect", "Смена сервера: ни один узел не подошел.")
            onStageChanged?.invoke(SmartConnectStage.FAILED, "Серверы не найдены. Попробуйте обновить список.")
            return@withContext false
        } finally {
            if (activeJob == currentJob) {
                activeJob = null
            }
            V2WCoreScanner.stop()
            DesktopProfileVerifier.stop()
        }
    }

    /**
     * Фоновая допроверка ответивших в v2w-core узлов для пополнения VIP-кэша.
     */
    private fun warmupLeftoversInVip(
        leftovers: List<Triple<String, ProfileItem, Long>>,
        xrayPath: String?,
        minSpeedMbps: Double,
        serviceCheckEnabled: Boolean = false,
        servicesList: List<com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckItem> = emptyList()
    ) {
        scope.launch(Dispatchers.IO) {
            val limit = settingsRepo.getInt("pref_vip_cache_limit", 5)
            val currentVips = serverRepo.getVipServerGuids()
            if (currentVips.size >= limit) {
                return@launch
            }
            val needed = limit - currentVips.size
            GeekModeLogger.log("SmartConnect", "Фоновое пополнение VIP-кэша (свободно мест: $needed)...")
            var added = 0
            for ((guid, profile, _) in leftovers) {
                if (serverRepo.getVipServerGuids().size >= limit) break
                val res = DesktopProfileVerifier.verifyProfile(
                    profile = profile,
                    xrayBinaryPath = xrayPath,
                    speedCheckEnabled = true,
                    minSpeedMbps = minSpeedMbps,
                    serviceCheckEnabled = serviceCheckEnabled,
                    servicesToCheck = servicesList
                )
                if (res.success) {
                    serverRepo.addVipServer(guid, limit)
                    if (res.latencyMs > 0) {
                        onNodeVerified?.invoke(guid, res.latencyMs)
                    }
                    added++
                    GeekModeLogger.log("SmartConnect", "Узел ${profile.remarks} добавлен в VIP кэш.")
                    if (added >= needed) break
                }
            }
            if (added > 0) {
                GeekModeLogger.log("SmartConnect", "Фоновое пополнение VIP завершено. Добавлено: $added узлов.")
            }
        }
    }
}

