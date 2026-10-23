package com.kiktor.v2whitelist.desktop.engine

import com.kiktor.v2whitelist.core.domain.ping.NodePingTester
import com.kiktor.v2whitelist.core.domain.selector.LocationFilterMode
import com.kiktor.v2whitelist.core.domain.selector.SmartServerFilterConfig
import com.kiktor.v2whitelist.core.domain.selector.SmartServerSelector
import com.kiktor.v2whitelist.core.model.AppScenario
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import com.kiktor.v2whitelist.core.domain.network.NetworkStatusChecker
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
            val allServers = serverRepo.serversFlow.value
            if (allServers.isEmpty()) {
                GeekModeLogger.log("SmartConnect", "Нет доступных серверов для поиска.")
                onStageChanged?.invoke(SmartConnectStage.FAILED, "Список серверов пуст")
                return@withContext false
            }

            // Фильтрация кандидатов по сценарию (БС / ЧС / YouTube / Авто) + фильтр локаций 1-в-1 как на Android
            val filterConfig = loadLocationFilterConfig(scenario = scenario)
            val candidates = SmartServerSelector.filterServerPairs(allServers, filterConfig).shuffled()
            if (candidates.isEmpty()) {
                GeekModeLogger.log("SmartConnect", "Нет серверов, соответствующих сценарию $scenario с учетом фильтра стран.")
                onStageChanged?.invoke(SmartConnectStage.FAILED, "Нет серверов под сценарий $scenario")
                return@withContext false
            }

            val xrayPath = settingsRepo.getString("xray_path").ifBlank { null }
            val v2wPath = settingsRepo.getString("v2w_scan_path").ifBlank { null }
            val customEndpointUrl = if (settingsRepo.getBoolean("pref_custom_endpoint_enabled", false)) {
                settingsRepo.getString("pref_custom_endpoint_url").ifBlank { null }
            } else null
            val endpointE2eCheck = settingsRepo.getBoolean("pref_endpoint_e2e_check_enabled", true)

            // ══════════════════════════════════════════════════════════════════════════════
            // ЭТАП 1: Проверка VIP-серверов (Fast Path)
            // ══════════════════════════════════════════════════════════════════════════════
            val vipGuids = serverRepo.getVipServerGuids()
            if (vipGuids.isNotEmpty()) {
                onStageChanged?.invoke(SmartConnectStage.CHECKING_VIP, "Быстрое подключение через топ серверов…")
                GeekModeLogger.log("SmartConnect", "VIP Cache: проверка ${vipGuids.size} узлов...")

                val vipCandidates = candidates.filter { vipGuids.contains(it.first) }
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
                        onStatusUpdate = { text ->
                            onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, text)
                        }
                    )
                    if (res.success) {
                        vipFailCounters[guid] = 0
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
                        onStatusUpdate = { text ->
                            onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, text)
                        }
                    )

                    if (verifyRes.success) {
                        winner = candidate.first to candidate.second
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
                            warmupLeftoversInVip(leftovers, xrayPath, minSpeedMbps)
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
                        onStatusUpdate = { text ->
                            onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, text)
                        }
                    )
                    if (res.success) {
                        val limit = settingsRepo.getInt("pref_vip_cache_limit", 5)
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
            if (allServers.isEmpty()) return@withContext false
            val filterConfig = loadLocationFilterConfig(excludeGuid = currentGuid, scenario = settingsRepo.getAppScenario())
            val candidates = SmartServerSelector.filterServerPairs(allServers, filterConfig)
            if (candidates.isEmpty()) return@withContext false

            val xrayPath = settingsRepo.getString("xray_path").ifBlank { null }
            val customEndpointUrl = if (settingsRepo.getBoolean("pref_custom_endpoint_enabled", false)) {
                settingsRepo.getString("pref_custom_endpoint_url").ifBlank { null }
            } else null
            val endpointE2eCheck = settingsRepo.getBoolean("pref_endpoint_e2e_check_enabled", true)
            val limit = settingsRepo.getInt("pref_vip_cache_limit", 5)

            // 1. Быстрый путь: VIP Cache (как на Android)
            val vipGuids = serverRepo.getVipServerGuids().filter { it != currentGuid }
            if (vipGuids.isNotEmpty()) {
                onStageChanged?.invoke(SmartConnectStage.CHECKING_VIP, "Быстрое подключение через топ серверов…")
                GeekModeLogger.log("SmartConnect", "Смена сервера: проверка ${vipGuids.size} узлов из VIP кэша...")
                val vipCandidates = candidates.filter { vipGuids.contains(it.first) }
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
                    onStatusUpdate = { text ->
                        onStageChanged?.invoke(SmartConnectStage.VERIFYING_NODE, text)
                    }
                )
                if (res.success) {
                    val limit = settingsRepo.getInt("pref_vip_cache_limit", 5)
                    serverRepo.addVipServer(guid, limit)
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
        minSpeedMbps: Double
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
                    minSpeedMbps = minSpeedMbps
                )
                if (res.success) {
                    serverRepo.addVipServer(guid, limit)
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

