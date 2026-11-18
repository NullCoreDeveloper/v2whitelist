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
            val speedTestBytes = settingsRepo.getInt("pref_speed_test_bytes", 2_000_000).let {
                if (it < 10_000) 2_000_000L else it.toLong()
            }
            val speedTestTimeoutMs = settingsRepo.getInt("pref_speed_test_timeout_ms", 4000).let {
                if (it < 1000) 4000L else it.toLong()
            }
            val verifyConcurrency = settingsRepo.getInt("pref_verify_concurrency", 4).coerceIn(1, 10)
            val tcpPrecheckConcurrency = settingsRepo.getInt("pref_tcp_precheck_concurrency", 50).coerceIn(1, 200)
            val v2wCoreSockets = settingsRepo.getInt("pref_v2w_core_sockets", 35).coerceIn(1, 100)
            val v2wFullVerifyMode = settingsRepo.getBoolean("pref_v2w_full_verify_mode", true)
            val testBeforeConnect = settingsRepo.getBoolean("pref_test_before_connect", true)

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
                        val personalChannel = Channel<V2WScanCandidate>(Channel.UNLIMITED)

                        val pScanJob = launch(Dispatchers.IO) {
                            try {
                                V2WCoreScanner.runStreamingScan(
                                    servers = decision.candidates,
                                    customBinaryPath = v2wPath,
                                    firstOnly = false,
                                    concurrency = v2wCoreSockets,
                                    tcpConcurrency = tcpPrecheckConcurrency,
                                    fullVerifyMode = v2wFullVerifyMode,
                                    endpointUrl = customEndpointUrl,
                                    endpointE2e = endpointE2eCheck,
                                    serviceCheck = serviceCheckEnabled,
                                    services = servicesList,
                                    onNodeHit = { guid, profile, delayMs, checkedByV2W ->
                                        personalChannel.trySend(V2WScanCandidate(guid, profile, delayMs, checkedByV2W))
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
                                val pName = candidate.profile.remarks.ifBlank { candidate.profile.server }
                                onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, "Проверяем личный сервер: $pName…")
                                val vRes = DesktopProfileVerifier.verifyProfile(
                                    profile = candidate.profile,
                                    xrayBinaryPath = xrayPath,
                                    speedCheckEnabled = speedCheckEnabled,
                                    minSpeedMbps = minSpeedMbps,
                                    customEndpointUrl = customEndpointUrl,
                                    endpointE2eCheckEnabled = endpointE2eCheck,
                                    serviceCheckEnabled = serviceCheckEnabled,
                                    servicesToCheck = servicesList,
                                    speedOnlyMode = candidate.checkedByV2W && v2wFullVerifyMode,
                                    preMeasuredLatencyMs = candidate.delayMs,
                                    onStatusUpdate = { text -> onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, text) }
                                )
                                if (vRes.success) {
                                    personalWinner = candidate.guid to candidate.profile
                                    val reportedLatency = if (vRes.latencyMs > 0) vRes.latencyMs else candidate.delayMs
                                    if (reportedLatency > 0) {
                                        onNodeVerified?.invoke(candidate.guid, reportedLatency)
                                    }
                                    pScanJob.cancel()
                                    V2WCoreScanner.stop()
                                    onStageChanged?.invoke(SmartConnectStage.SUCCESS, "Подключено к личному серверу: $pName")
                                    delay(250)
                                    connectAndFinish(candidate.guid, candidate.profile)
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
            // ЭТАП 1: Проверка VIP-серверов (Fast Path, параллельно)
            // ══════════════════════════════════════════════════════════════════════════════
            val vipGuids = serverRepo.getVipServerGuids()
            if (vipGuids.isNotEmpty()) {
                onStageChanged?.invoke(SmartConnectStage.CHECKING_VIP, "Быстрое подключение через топ серверов…")
                GeekModeLogger.log("SmartConnect", "VIP Cache: параллельная проверка ${vipGuids.size} узлов (потоков: $verifyConcurrency)...")

                val vipCandidates = vipGuids.mapNotNull { guid -> candidates.find { it.first == guid } }
                val vipWinner = verifyListInParallel(
                    items = vipCandidates,
                    concurrency = verifyConcurrency,
                    xrayPath = xrayPath,
                    timeoutMs = speedTestTimeoutMs,
                    speedCheckEnabled = speedCheckEnabled,
                    minSpeedMbps = minSpeedMbps,
                    bytesToDownload = speedTestBytes,
                    customEndpointUrl = customEndpointUrl,
                    endpointE2eCheck = endpointE2eCheck,
                    serviceCheckEnabled = serviceCheckEnabled,
                    servicesList = servicesList,
                    onFailed = { guid, profile ->
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
                )
                if (vipWinner != null) {
                    val (winPair, res) = vipWinner
                    val (guid, profile) = winPair
                    val serverName = profile.remarks.ifBlank { profile.server }
                    vipFailCounters[guid] = 0
                    if (res.latencyMs > 0) {
                        onNodeVerified?.invoke(guid, res.latencyMs)
                    }
                    GeekModeLogger.log("SmartConnect", "VIP сервер подтвержден: ${profile.remarks} (${res.latencyMs}ms, ${res.speedMbps?.let { "%.2f".format(it) } ?: "—"} Мбит/с)")
                    onStageChanged?.invoke(SmartConnectStage.SUCCESS, "Нашли отличный сервер! Подключаемся к $serverName…")
                    delay(250)
                    connectAndFinish(guid, profile)
                    return@withContext true
                }
            }

            // ══════════════════════════════════════════════════════════════════════════════
            // ЭТАП 2: Потоковое сканирование через ядро v2w-core + параллельная Xray-проверка
            // ══════════════════════════════════════════════════════════════════════════════
            onStageChanged?.invoke(SmartConnectStage.V2W_SCANNING, "Ищем лучший сервер для вас…")
            GeekModeLogger.log(
                "SmartConnect",
                "Запуск ядра v2w-core для ${candidates.size} узлов (TCP pre-check: $tcpPrecheckConcurrency, проверка v2w: $v2wCoreSockets, Xray-замер: $verifyConcurrency, fullVerify=$v2wFullVerifyMode)..."
            )

            val channel = Channel<V2WScanCandidate>(Channel.UNLIMITED)
            val winnerFound = java.util.concurrent.atomic.AtomicBoolean(false)
            val winnerResult = CompletableDeferred<Pair<V2WScanCandidate, VerifyResult>?>()
            val leftovers = java.util.Collections.synchronizedList(mutableListOf<V2WScanCandidate>())

            val scanJob = launch(Dispatchers.IO) {
                try {
                    V2WCoreScanner.runStreamingScan(
                        servers = candidates,
                        customBinaryPath = v2wPath,
                        firstOnly = false,
                        concurrency = v2wCoreSockets,
                        tcpConcurrency = tcpPrecheckConcurrency,
                        fullVerifyMode = v2wFullVerifyMode,
                        endpointUrl = customEndpointUrl,
                        endpointE2e = endpointE2eCheck,
                        serviceCheck = serviceCheckEnabled,
                        services = servicesList,
                        onNodeHit = { guid, profile, delayMs, checkedByV2W ->
                            channel.trySend(V2WScanCandidate(guid, profile, delayMs, checkedByV2W))
                            winnerFound.get()
                        }
                    )
                } finally {
                    channel.close()
                }
            }

            var stage2Connected = false
            try {
                coroutineScope {
                    val workers = (0 until verifyConcurrency).map {
                        launch(Dispatchers.IO) {
                            for (candidate in channel) {
                                if (!isActive || winnerFound.get()) break
                                val serverName = candidate.profile.remarks.ifBlank { candidate.profile.server }
                                val isSpeedOnly = candidate.checkedByV2W && v2wFullVerifyMode
                                val stageLabel = if (isSpeedOnly) {
                                    "Замер скорости: $serverName…"
                                } else {
                                    "Проверяем профиль: $serverName…"
                                }
                                onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, stageLabel)
                                GeekModeLogger.log(
                                    "SmartConnect",
                                    "v2w-core нашел узел $serverName (${candidate.delayMs}ms, v2wChecked=${candidate.checkedByV2W}) -> Xray верификация..."
                                )

                                val verifyRes = if (!testBeforeConnect) {
                                    VerifyResult(success = true, latencyMs = candidate.delayMs.coerceAtLeast(1L))
                                } else {
                                    DesktopProfileVerifier.verifyProfile(
                                        profile = candidate.profile,
                                        xrayBinaryPath = xrayPath,
                                        timeoutMs = speedTestTimeoutMs,
                                        speedCheckEnabled = speedCheckEnabled,
                                        minSpeedMbps = minSpeedMbps,
                                        bytesToDownload = speedTestBytes,
                                        customEndpointUrl = customEndpointUrl,
                                        endpointE2eCheckEnabled = endpointE2eCheck,
                                        serviceCheckEnabled = serviceCheckEnabled,
                                        servicesToCheck = servicesList,
                                        speedOnlyMode = candidate.checkedByV2W && v2wFullVerifyMode,
                                        preMeasuredLatencyMs = candidate.delayMs,
                                        onStatusUpdate = { text ->
                                            if (!winnerFound.get()) {
                                                onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, text)
                                            }
                                        }
                                    )
                                }

                                if (verifyRes.success) {
                                    if (winnerFound.compareAndSet(false, true)) {
                                        winnerResult.complete(candidate to verifyRes)
                                        scanJob.cancel()
                                        V2WCoreScanner.stop()
                                        channel.close()
                                    }
                                    break
                                } else {
                                    GeekModeLogger.log("SmartConnect", "Узел $serverName не прошел проверку (${verifyRes.errorMessage ?: "таймаут"}), берем следующий...")
                                }
                            }
                        }
                    }

                    // Запускаем фонового наблюдателя завершения всех воркеров на случай 0 победителей
                    launch {
                        workers.joinAll()
                        if (!winnerResult.isCompleted) {
                            winnerResult.complete(null)
                        }
                    }

                    val win = winnerResult.await()
                    workers.forEach { it.cancel() }

                    if (win != null) {
                        val (winCandidate, verifyRes) = win
                        val serverName = winCandidate.profile.remarks.ifBlank { winCandidate.profile.server }
                        val reportedLatency = if (verifyRes.latencyMs > 0) verifyRes.latencyMs else winCandidate.delayMs
                        if (reportedLatency > 0) {
                            onNodeVerified?.invoke(winCandidate.guid, reportedLatency)
                        }
                        GeekModeLogger.log(
                            "SmartConnect",
                            "Победитель найден и верифицирован: $serverName (${reportedLatency}ms, ${verifyRes.speedMbps?.let { "%.2f".format(it) } ?: "—"} Мбит/с)!"
                        )

                        val limit = settingsRepo.getInt("pref_vip_cache_limit", 5)
                        serverRepo.addVipServer(winCandidate.guid, limit)

                        scanJob.cancel()
                        V2WCoreScanner.stop()

                        while (true) {
                            val extra = channel.tryReceive().getOrNull() ?: break
                            if (extra.guid != winCandidate.guid) {
                                leftovers.add(extra)
                            }
                        }

                        if (leftovers.isNotEmpty()) {
                            warmupLeftoversInVip(
                                leftovers = leftovers.toList(),
                                xrayPath = xrayPath,
                                speedCheckEnabled = speedCheckEnabled,
                                minSpeedMbps = minSpeedMbps,
                                customEndpointUrl = customEndpointUrl,
                                endpointE2eCheck = endpointE2eCheck,
                                serviceCheckEnabled = serviceCheckEnabled,
                                servicesList = servicesList,
                                v2wFullVerifyMode = v2wFullVerifyMode,
                                bytesToDownload = speedTestBytes,
                                timeoutMs = speedTestTimeoutMs
                            )
                        }

                        onStageChanged?.invoke(SmartConnectStage.SUCCESS, "Нашли отличный сервер! Подключаемся к $serverName…")
                        delay(250)
                        connectAndFinish(winCandidate.guid, winCandidate.profile)
                        stage2Connected = true
                    }
                }
            } finally {
                scanJob.cancel()
                V2WCoreScanner.stop()
            }
            if (stage2Connected) return@withContext true

            // ══════════════════════════════════════════════════════════════════════════════
            // ЭТАП 3: Фоллбек на классический сканер (параллельная проверка)
            // ══════════════════════════════════════════════════════════════════════════════
            GeekModeLogger.log("SmartConnect", "v2w-core не нашел подходящих узлов. Фоллбек на классический сканер...")
            onStageChanged?.invoke(SmartConnectStage.FALLBACK_SCANNING, "v2w-core: переключение на старый сканер…")

            val tcpResults = NodePingTester.testBatchTcp(candidates, timeoutMs = 1500L)
            if (tcpResults.isNotEmpty()) {
                val fallbackWin = verifyListInParallel(
                    items = tcpResults.map { it.first to it.second },
                    concurrency = verifyConcurrency,
                    xrayPath = xrayPath,
                    timeoutMs = speedTestTimeoutMs,
                    speedCheckEnabled = speedCheckEnabled,
                    minSpeedMbps = minSpeedMbps,
                    bytesToDownload = speedTestBytes,
                    customEndpointUrl = customEndpointUrl,
                    endpointE2eCheck = endpointE2eCheck,
                    serviceCheckEnabled = serviceCheckEnabled,
                    servicesList = servicesList
                )
                if (fallbackWin != null) {
                    val (winPair, res) = fallbackWin
                    val (guid, profile) = winPair
                    val serverName = profile.remarks.ifBlank { profile.server }
                    val limit = settingsRepo.getInt("pref_vip_cache_limit", 5)
                    if (res.latencyMs > 0) {
                        onNodeVerified?.invoke(guid, res.latencyMs)
                    }
                    GeekModeLogger.log("SmartConnect", "Классический фоллбек нашел узел: $serverName")
                    serverRepo.addVipServer(guid, limit)
                    onStageChanged?.invoke(SmartConnectStage.SUCCESS, "Нашли отличный сервер! Подключаемся к $serverName…")
                    delay(250)
                    connectAndFinish(guid, profile)
                    return@withContext true
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
            val verifyConcurrency = settingsRepo.getInt("pref_verify_concurrency", 4).coerceIn(1, 10)
            val speedTestBytes = settingsRepo.getInt("pref_speed_test_bytes", 2_000_000).let {
                if (it < 10_000) 2_000_000L else it.toLong()
            }
            val speedTestTimeoutMs = settingsRepo.getInt("pref_speed_test_timeout_ms", 4000).let {
                if (it < 1000) 4000L else it.toLong()
            }

            // 1. Быстрый путь: VIP Cache (как на Android, параллельно)
            val vipGuids = serverRepo.getVipServerGuids().filter { it != currentGuid }
            if (vipGuids.isNotEmpty()) {
                onStageChanged?.invoke(SmartConnectStage.CHECKING_VIP, "Быстрое подключение через топ серверов…")
                GeekModeLogger.log("SmartConnect", "Смена сервера: проверка ${vipGuids.size} узлов из VIP кэша...")
                val vipCandidates = vipGuids.mapNotNull { guid -> candidates.find { it.first == guid } }
                val vipWin = verifyListInParallel(
                    items = vipCandidates,
                    concurrency = verifyConcurrency,
                    xrayPath = xrayPath,
                    timeoutMs = speedTestTimeoutMs,
                    speedCheckEnabled = false,
                    minSpeedMbps = 0.5,
                    bytesToDownload = speedTestBytes,
                    customEndpointUrl = customEndpointUrl,
                    endpointE2eCheck = endpointE2eCheck,
                    serviceCheckEnabled = serviceCheckEnabled,
                    servicesList = servicesList
                )
                if (vipWin != null) {
                    val (guid, profile) = vipWin.first
                    val serverName = profile.remarks.ifBlank { profile.server }
                    GeekModeLogger.log("SmartConnect", "VIP сервер подтвержден при смене: $serverName")
                    onStageChanged?.invoke(SmartConnectStage.SUCCESS, "Подключаемся к $serverName…")
                    delay(250)
                    connectAndFinish(guid, profile)
                    return@withContext true
                }
            }

            // 2. Если VIP не ответил — параллельный перебор остальных кандидатов
            onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, "Выбираем другой сервер…")
            GeekModeLogger.log("SmartConnect", "Смена сервера: перебор ${candidates.size} кандидатов (потоков: $verifyConcurrency)...")
            val nonVipCandidates = candidates.shuffled().filter { !vipGuids.contains(it.first) }
            val switchWin = verifyListInParallel(
                items = nonVipCandidates,
                concurrency = verifyConcurrency,
                xrayPath = xrayPath,
                timeoutMs = speedTestTimeoutMs,
                speedCheckEnabled = false,
                minSpeedMbps = 0.5,
                bytesToDownload = speedTestBytes,
                customEndpointUrl = customEndpointUrl,
                endpointE2eCheck = endpointE2eCheck,
                serviceCheckEnabled = serviceCheckEnabled,
                servicesList = servicesList,
                acceptPredicate = { res -> res.success && (res.latencyMs <= 0 || res.latencyMs < 3000L) }
            )
            if (switchWin != null) {
                val (winPair, res) = switchWin
                val (guid, profile) = winPair
                val serverName = profile.remarks.ifBlank { profile.server }
                serverRepo.addVipServer(guid, limit)
                if (res.latencyMs > 0) {
                    onNodeVerified?.invoke(guid, res.latencyMs)
                }
                GeekModeLogger.log("SmartConnect", "Новый узел подтвержден при смене: $serverName")
                onStageChanged?.invoke(SmartConnectStage.SUCCESS, "Подключаемся к $serverName…")
                delay(250)
                connectAndFinish(guid, profile)
                return@withContext true
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

    private suspend fun verifyListInParallel(
        items: List<Pair<String, ProfileItem>>,
        concurrency: Int,
        xrayPath: String?,
        timeoutMs: Long,
        speedCheckEnabled: Boolean,
        minSpeedMbps: Double,
        bytesToDownload: Long,
        customEndpointUrl: String?,
        endpointE2eCheck: Boolean,
        serviceCheckEnabled: Boolean,
        servicesList: List<com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckItem>,
        acceptPredicate: (VerifyResult) -> Boolean = { it.success },
        onFailed: ((String, ProfileItem) -> Unit)? = null
    ): Pair<Pair<String, ProfileItem>, VerifyResult>? = coroutineScope {
        if (items.isEmpty()) return@coroutineScope null
        val queue = Channel<Pair<String, ProfileItem>>(items.size)
        for (item in items) queue.trySend(item)
        queue.close()

        val found = java.util.concurrent.atomic.AtomicBoolean(false)
        val resultDeferred = CompletableDeferred<Pair<Pair<String, ProfileItem>, VerifyResult>?>()
        val workerCount = concurrency.coerceIn(1, items.size)

        val workers = (0 until workerCount).map {
            launch(Dispatchers.IO) {
                for ((guid, profile) in queue) {
                    if (!isActive || found.get()) break
                    val serverName = profile.remarks.ifBlank { profile.server }
                    onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, "Проверяем профиль: $serverName…")
                    val res = DesktopProfileVerifier.verifyProfile(
                        profile = profile,
                        xrayBinaryPath = xrayPath,
                        timeoutMs = timeoutMs,
                        speedCheckEnabled = speedCheckEnabled,
                        minSpeedMbps = minSpeedMbps,
                        bytesToDownload = bytesToDownload,
                        customEndpointUrl = customEndpointUrl,
                        endpointE2eCheckEnabled = endpointE2eCheck,
                        serviceCheckEnabled = serviceCheckEnabled,
                        servicesToCheck = servicesList,
                        onStatusUpdate = { text ->
                            if (!found.get()) {
                                onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, text)
                            }
                        }
                    )
                    if (acceptPredicate(res)) {
                        if (found.compareAndSet(false, true)) {
                            resultDeferred.complete((guid to profile) to res)
                            queue.close()
                        }
                        break
                    } else {
                        onFailed?.invoke(guid, profile)
                    }
                }
            }
        }

        launch {
            workers.joinAll()
            if (!resultDeferred.isCompleted) {
                resultDeferred.complete(null)
            }
        }

        val win = resultDeferred.await()
        workers.forEach { it.cancel() }
        win
    }

    private data class V2WScanCandidate(
        val guid: String,
        val profile: ProfileItem,
        val delayMs: Long,
        val checkedByV2W: Boolean
    )

    /**
     * Фоновая допроверка ответивших в v2w-core узлов для пополнения VIP-кэша.
     */
    private fun warmupLeftoversInVip(
        leftovers: List<V2WScanCandidate>,
        xrayPath: String?,
        speedCheckEnabled: Boolean,
        minSpeedMbps: Double,
        customEndpointUrl: String? = null,
        endpointE2eCheck: Boolean = true,
        serviceCheckEnabled: Boolean = false,
        servicesList: List<com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckItem> = emptyList(),
        v2wFullVerifyMode: Boolean = true,
        bytesToDownload: Long = 2_000_000L,
        timeoutMs: Long = 4000L
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
            for (candidate in leftovers) {
                if (serverRepo.getVipServerGuids().size >= limit) break
                val res = DesktopProfileVerifier.verifyProfile(
                    profile = candidate.profile,
                    xrayBinaryPath = xrayPath,
                    timeoutMs = timeoutMs,
                    speedCheckEnabled = speedCheckEnabled,
                    minSpeedMbps = minSpeedMbps,
                    bytesToDownload = bytesToDownload,
                    customEndpointUrl = customEndpointUrl,
                    endpointE2eCheckEnabled = endpointE2eCheck,
                    serviceCheckEnabled = serviceCheckEnabled,
                    servicesToCheck = servicesList,
                    speedOnlyMode = candidate.checkedByV2W && v2wFullVerifyMode,
                    preMeasuredLatencyMs = candidate.delayMs
                )
                if (res.success) {
                    serverRepo.addVipServer(candidate.guid, limit)
                    val lat = if (res.latencyMs > 0) res.latencyMs else candidate.delayMs
                    if (lat > 0) {
                        onNodeVerified?.invoke(candidate.guid, lat)
                    }
                    added++
                    GeekModeLogger.log("SmartConnect", "Узел ${candidate.profile.remarks} добавлен в VIP кэш.")
                    if (added >= needed) break
                }
            }
            if (added > 0) {
                GeekModeLogger.log("SmartConnect", "Фоновое пополнение VIP завершено. Добавлено: $added узлов.")
            }
        }
    }
}

