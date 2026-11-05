package com.kiktor.v2whitelist.handler

import android.content.Context
import android.util.Log
import com.kiktor.v2whitelist.handler.GeekModeLogger
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.dto.ProfileItem
import com.kiktor.v2whitelist.dto.SubscriptionItem
import com.kiktor.v2whitelist.dto.SubscriptionCache
import com.kiktor.v2whitelist.enums.EConfigType
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.AngConfigManager
import com.kiktor.v2whitelist.handler.V2rayConfigManager
import com.kiktor.v2whitelist.handler.V2RayNativeManager
import com.kiktor.v2whitelist.handler.V2RayServiceManager
import com.kiktor.v2whitelist.util.MessageUtil
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.core.model.NetworkBindingRule
import com.kiktor.v2whitelist.ui.LocationFilterActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlin.random.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController

class ScanningStateHolder {
    fun get(): Boolean {
        val isScanning = MmkvManager.decodeSettingsBool(AppConfig.PREF_IS_SCANNING, false)
        if (!isScanning) return false
        val startTime = MmkvManager.decodeSettingsLong("pref_scanning_start_time", 0L)
        if (startTime > 0 && System.currentTimeMillis() - startTime > 45_000L) {
            // Защита от зависшего лока (процесс был убит или упал во время сканирования)
            set(false)
            return false
        }
        return true
    }
    fun set(value: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_IS_SCANNING, value)
        if (value) {
            MmkvManager.encodeSettings("pref_scanning_start_time", System.currentTimeMillis())
        } else {
            MmkvManager.encodeSettings("pref_scanning_start_time", 0L)
        }
    }
    fun forceReset() {
        set(false)
    }
    fun compareAndSet(expect: Boolean, update: Boolean): Boolean {
        synchronized(this) {
            val current = get()
            if (current == expect) {
                set(update)
                return true
            }
            return false
        }
    }
}

object SmartConnectManager {
    val isScanning = ScanningStateHolder()
    private val vipFailCounters = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun sendStatus(context: Context, status: String) {
        MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE, status)
        // Если идёт поиск — обновляем текст уведомления (беззвучно)
        if (isScanning.get()) {
            NotificationManager.updateScanningNotification(context, status)
        }
    }


    private fun loadCustomSubs(): List<com.kiktor.v2whitelist.core.subscription.model.CustomSubData> {
        val json = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_SUB_URLS)
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            com.kiktor.v2whitelist.util.JsonUtil.fromJson(json, Array<com.kiktor.v2whitelist.core.subscription.model.CustomSubData>::class.java)?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }



    fun filterServerPairs(
        candidates: List<Pair<String, ProfileItem>>, 
        excludeGuid: String? = null,
        filterByScenario: Boolean = true
    ): List<Pair<String, ProfileItem>> {
        val disabledSubIds = loadCustomSubs().filter { !it.enabled }.map { "custom_sub_${it.id}" }.toSet()
        
        val filterModeStr = MmkvManager.decodeSettingsString(
            AppConfig.PREF_LOCATION_FILTER_MODE,
            AppConfig.LOCATION_FILTER_MODE_EXCLUDE
        ) ?: AppConfig.LOCATION_FILTER_MODE_EXCLUDE
        val locationMode = com.kiktor.v2whitelist.core.domain.selector.LocationFilterMode.fromString(filterModeStr)

        val filterSet = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_LOCATION_FILTER_SET)
            ?: com.kiktor.v2whitelist.ui.LocationFilterActivity.getDefaultFilterSet()
            
        val groupRegexMap = com.kiktor.v2whitelist.ui.LocationFilterActivity.getGroupRegexMap()
        val filterInsecure = MmkvManager.decodeSettingsBool(AppConfig.PREF_FILTER_INSECURE_PROFILES, false)

        val currentScenarioStr = MmkvManager.decodeSettingsString(
            AppConfig.PREF_CURRENT_APP_SCENARIO, 
            SubscriptionHelper.AppScenario.AUTO.name
        ) ?: SubscriptionHelper.AppScenario.AUTO.name
        val scenario = try {
            com.kiktor.v2whitelist.core.model.AppScenario.valueOf(currentScenarioStr)
        } catch (_: Exception) {
            com.kiktor.v2whitelist.core.model.AppScenario.AUTO
        }

        val internetStatus = NetworkManager.checkInternetStatus()
        val subScenariosMap = SubscriptionHelper.getSubScenariosMap()

        val config = com.kiktor.v2whitelist.core.domain.selector.SmartServerFilterConfig(
            excludeGuid = excludeGuid,
            disabledSubscriptionIds = disabledSubIds,
            filterInsecure = filterInsecure,
            locationFilterMode = locationMode,
            locationFilterSet = filterSet,
            groupRegexMap = groupRegexMap,
            appScenario = scenario,
            internetStatus = internetStatus,
            subscriptionScenariosMap = subScenariosMap,
            filterByScenario = filterByScenario
        )

        return com.kiktor.v2whitelist.core.domain.selector.SmartServerSelector.filterServerPairs(candidates, config)
    }

    fun filterServers(allServers: List<String>, excludeGuid: String? = null, filterByScenario: Boolean = true): List<Pair<String, ProfileItem>> {
        val candidates = allServers.mapNotNull { guid ->
            val profile = MmkvManager.decodeServerConfig(guid)
            if (profile != null && !com.kiktor.v2whitelist.core.model.PersonalServersConfig.isPersonalServer(profile.subscriptionId)) {
                guid to profile
            } else null
        }
        return filterServerPairs(candidates, excludeGuid, filterByScenario)
    }

    /**
     * Прямое мгновенное подключение к конкретному серверу (например, из экрана Личных серверов).
     */
    suspend fun connectDirectlyTo(context: Context, guid: String): Boolean = withContext(Dispatchers.IO) {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return@withContext false
        sendStatus(context, context.getString(R.string.status_connecting_to, profile.remarks))
        connectToBest(context, Triple(guid, profile, 0L), isStartup = false)
        NotificationManager.cancelFailoverNotification()
        return@withContext true
    }

    fun cleanVipCacheForFilter() {
        val allServers = MmkvManager.decodeServerList().toSet()
        val currentVipGuids = MmkvManager.getVipCache()
        for (guid in currentVipGuids) {
            if (!allServers.contains(guid)) {
                MmkvManager.removeVipServer(guid)
                vipFailCounters.remove(guid)
            }
        }
        val currentSelected = MmkvManager.getSelectServer()
        if (currentSelected != null && currentSelected.isNotEmpty() && !allServers.contains(currentSelected)) {
            MmkvManager.setSelectServer("")
        }
    }




    suspend fun findMoreVipServers(context: Context): Boolean = withContext(Dispatchers.IO) {
        NetworkManager.waitForInternet(context)
        val allServers = MmkvManager.decodeServerList()
        val currentVipGuids = MmkvManager.getVipCache().toSet()
        val candidates = filterServers(allServers, null)
            .filter { !currentVipGuids.contains(it.first) }
            .shuffled()
        
        if (candidates.isEmpty()) {
            GeekModeLogger.log("SmartConnect", "findMoreVipServers: no new servers available to check")
            return@withContext false
        }
        
        val chunkedServers = buildProportionalChunks(candidates)
        if (chunkedServers.isEmpty()) return@withContext false
        
        val chunk = chunkedServers.first()
        GeekModeLogger.log("SmartConnect", "findMoreVipServers: starting chunk check of ${chunk.size} servers to replenish VIP")
        
        val results = NodeTesterManager.testServers(context, chunk)
        if (results.isEmpty()) {
            GeekModeLogger.log("SmartConnect", "findMoreVipServers: chunk yielded no results")
            return@withContext false
        }
        
        var added = 0
        for (candidate in results) {
            val success = NodeTesterManager.verifyProfile(context, candidate.first)
            if (success) {
                MmkvManager.addVipServer(candidate.first)
                GeekModeLogger.log("SmartConnect", "findMoreVipServers: server ${candidate.second.remarks} added to VIP cache")
                added++
            }
        }
        
        GeekModeLogger.log("SmartConnect", "findMoreVipServers: completed, added $added servers")
        return@withContext added > 0
    }

    

    const val SUBSCRIPTION_ID = "v2whitelist_hardcoded_sub"
    const val UPDATE_INTERVAL_MS = 60 * 60 * 1000L // 1 hour



    /**
     * Быстрый путь (Fast Path): проверяем серверы из VIP-кэша.
     */
    private suspend fun checkVipCacheAndConnect(context: Context, isStartup: Boolean = false, excludeGuid: String? = null): Boolean {
        val vipGuids = MmkvManager.getVipCache()
        if (vipGuids.isEmpty()) return false

        GeekModeLogger.log("SmartConnect", "VIP Cache: checking ${vipGuids.size} servers")
        
        val vipCandidates = mutableListOf<Pair<String, ProfileItem>>()
        val invalidGuids = mutableListOf<String>()
        for (guid in vipGuids) {
            val profile = MmkvManager.decodeServerConfig(guid)
            if (profile != null) {
                vipCandidates.add(Pair(guid, profile))
            } else {
                invalidGuids.add(guid)
            }
        }
        
        invalidGuids.forEach { MmkvManager.removeVipServer(it) }
        
        val filteredVipCandidates = filterServerPairs(vipCandidates, excludeGuid = excludeGuid)
        if (filteredVipCandidates.isEmpty()) {
            GeekModeLogger.log("SmartConnect", "VIP Cache: no VIP servers match current filters")
            return false
        }
        
        sendStatus(context, context.getString(R.string.status_checking_vip_servers))
        
        val results = NodeTesterManager.testServers(context, filteredVipCandidates)
        val profileCheckEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROFILE_CHECK_ENABLED, true)
        
        val validResults = results.filter { it.third < Long.MAX_VALUE }
        
        if (profileCheckEnabled) {
            for (candidate in validResults) {
                if (NodeTesterManager.verifyProfile(context, candidate.first)) {
                    vipFailCounters[candidate.first] = 0
                    connectToBest(context, candidate, isStartup, isFromVipCache = true)
                    val leftovers = validResults.filter { it.first != candidate.first }
                    if (leftovers.isNotEmpty()) {
                        NodeTesterManager.verifyAndCacheLeftovers(context.applicationContext, leftovers)
                    }
                    return true
                } else {
                    val fails = (vipFailCounters[candidate.first] ?: 0) + 1
                    vipFailCounters[candidate.first] = fails
                    if (fails >= 3) {
                        GeekModeLogger.log("SmartConnect", "VIP Cache: server ${candidate.first} failed deep check 3 times ($fails/3), removing")
                        MmkvManager.removeVipServer(candidate.first)
                        vipFailCounters.remove(candidate.first)
                    } else {
                        GeekModeLogger.log("SmartConnect", "VIP Cache: server ${candidate.first} temporarily failed check ($fails/3), keeping in VIP")
                    }
                }
            }
        } else {
            val candidate = validResults.firstOrNull()
            if (candidate != null) {
                vipFailCounters[candidate.first] = 0
                connectToBest(context, candidate, isStartup, isFromVipCache = true)
                val leftovers = validResults.filter { it.first != candidate.first }
                if (leftovers.isNotEmpty()) {
                    NodeTesterManager.verifyAndCacheLeftovers(context.applicationContext, leftovers)
                }
                return true
            }
        }
        
        GeekModeLogger.log("SmartConnect", "VIP Cache: all valid servers failed")
        return false
    }

    

    private suspend fun connectToBest(context: Context, best: Triple<String, ProfileItem, Long>, isStartup: Boolean = false, isFromVipCache: Boolean = false) {
        val subId = best.second.subscriptionId
        val subName = if (subId.isNotEmpty()) MmkvManager.decodeSubscription(subId)?.remarks?.takeIf { it.isNotBlank() } else null
        val subSuffix = if (subName != null) " [$subName]" else ""
        val displayName = "${best.second.remarks}$subSuffix"
        GeekModeLogger.log("SmartConnect", "Smart Connect: Selected $displayName (${best.third}ms)")
        if (isFromVipCache) {
            sendStatus(context, context.getString(R.string.status_using_cached_server, displayName))
        } else {
            sendStatus(context, context.getString(R.string.status_connecting_to, displayName))
        }
        MmkvManager.setSelectServer(best.first)

        MmkvManager.addVipServer(best.first)
        MmkvManager.saveLastConnectedServer(best.first)
        GeekModeLogger.log("SmartConnect", "SmartConnect: server ${best.second.remarks} saved to top VIP cache")

        val isRunning = V2RayServiceManager.isRunning()
        if (isRunning) {
            MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_SWITCH_SERVER, "")
        } else {
            withContext(Dispatchers.Main) {
                V2RayServiceManager.startVService(context.applicationContext)
            }
        }
        
        if (isStartup) {
            val internetStatus = NetworkManager.checkInternetStatus()
            if (internetStatus == 1) { // JAMMED
                // ВАЖНО: используем GlobalScope.launch, а НЕ coroutineScope!
                // coroutineScope блокировал возврат из connectToBest на 5+ секунд,
                // и если updateSubscription падал — убивал весь smartConnect → серая кнопка.
                CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob()).launch {
                    try {
                        delay(5000) // Ждем пока VPN разгонится
                        GeekModeLogger.log("SmartConnect", "Survival logic: Jamming detected, triggering background update via VPN")
                        SubscriptionHelper.updateSubscription(context, sequential = false)
                    } catch (e: Exception) {
                        GeekModeLogger.log("SmartConnect", "Survival logic: background update failed" + ": " + e)
                    }
                }
            }

            val isAutoUpdateEnabled = MmkvManager.decodeSettingsBool(AppConfig.SUBSCRIPTION_AUTO_UPDATE, true)
            if (isAutoUpdateEnabled) {
                // Ищем самую старую подписку среди включенных (или 0, если еще не обновляли)
                val allSubs = MmkvManager.decodeSubscriptions()
                val oldestUpdate = allSubs.filter { it.subscription.enabled }
                    .minOfOrNull { it.subscription.lastUpdated } ?: 0L

                if (System.currentTimeMillis() - oldestUpdate > UPDATE_INTERVAL_MS) {
                    GeekModeLogger.log("SmartConnect", "smartConnect: triggering background subscription update")
                    GlobalScope.launch(Dispatchers.IO) {
                        try {
                            // Быстрое параллельное обновление подписок с mirror racing
                            SubscriptionHelper.updateSubscription(context, isStartup = true, sequential = false)
                        } catch (e: Exception) {
                            GeekModeLogger.log("SmartConnect", "smartConnect: background update failed" + ": " + e)
                        }
                    }
                }
            }
        }
    }

    /**
     * Logic for "Smart Connect" - filter, sort by RealPing, and connect to best.
     */
    suspend fun smartConnect(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isScanning.compareAndSet(false, true)) {
            GeekModeLogger.log("SmartConnect", "smartConnect: already scanning, ignoring duplicate call")
            return@withContext false
        }
        val testingMsg = context.getString(R.string.status_testing_servers)
        NotificationManager.showScanningNotification(context, testingMsg)
        sendStatus(context, testingMsg)
        try {
            // Ждем появления интернета (dzen.ru) перед тем, как трогать кэш и удалять мертвые серверы
            NetworkManager.waitForInternet(context)

            // ── Приоритет личных серверов (Personal Servers) ──────────────────────────
            val personalConfig = PersonalServersManager.getConfig()
            if (personalConfig.enabled) {
                val personalCandidates = PersonalServersManager.getPersonalServers()
                val isWifi = NetworkManager.isWifiActive(context)
                val isCellular = NetworkManager.isCellularActive(context)
                val isBsJamming = NetworkManager.checkInternetStatus() == 1

                val decision = com.kiktor.v2whitelist.core.domain.personal.PersonalServerEvaluator.evaluate(
                    config = personalConfig,
                    isWifi = isWifi,
                    isCellular = isCellular,
                    isBsJamming = isBsJamming,
                    allPersonalCandidates = personalCandidates
                )

                when (decision) {
                    is com.kiktor.v2whitelist.core.domain.personal.PersonalConnectDecision.ConnectPersonalDirect -> {
                        GeekModeLogger.log("SmartConnect", "PersonalServers: Direct connect to ${decision.profile.remarks}")
                        sendStatus(context, context.getString(R.string.status_connecting_to, decision.profile.remarks))
                        connectToBest(context, Triple(decision.guid, decision.profile, 0L), isStartup = true)
                        NotificationManager.cancelFailoverNotification()
                        return@withContext true
                    }
                    is com.kiktor.v2whitelist.core.domain.personal.PersonalConnectDecision.ScanPersonalPool -> {
                        GeekModeLogger.log("SmartConnect", "PersonalServers: Scanning personal pool of ${decision.candidates.size} servers")
                        sendStatus(context, context.getString(R.string.status_personal_scanning))
                        val results = NodeTesterManager.testServers(context, decision.candidates)
                        val valid = results.filter { it.third < Long.MAX_VALUE }
                        val bestPersonal = valid.firstOrNull()
                        if (bestPersonal != null) {
                            GeekModeLogger.log("SmartConnect", "PersonalServers: Found working personal server: ${bestPersonal.second.remarks}")
                            connectToBest(context, bestPersonal, isStartup = true)
                            NotificationManager.cancelFailoverNotification()
                            return@withContext true
                        }
                        GeekModeLogger.log("SmartConnect", "PersonalServers: All personal servers failed")
                        if (!personalConfig.fallbackToPublicOnFailure) {
                            sendStatus(context, context.getString(R.string.status_personal_all_failed))
                            return@withContext false
                        }
                        sendStatus(context, context.getString(R.string.status_personal_failed_fallback))
                        // Продолжаем к публичному пулу ниже
                    }
                    is com.kiktor.v2whitelist.core.domain.personal.PersonalConnectDecision.DirectNoVpn -> {
                        GeekModeLogger.log("SmartConnect", "PersonalServers: Rule specifies DirectNoVpn")
                        return@withContext false
                    }
                    is com.kiktor.v2whitelist.core.domain.personal.PersonalConnectDecision.UsePublicSubscriptions -> {
                        GeekModeLogger.log("SmartConnect", "PersonalServers: ${decision.reason}")
                        // Продолжаем к публичному пулу ниже
                    }
                }
            }

            // ── Быстрый путь: кэш проверенных VIP-серверов ──────────────────────────────
            if (checkVipCacheAndConnect(context, isStartup = true)) {
                NotificationManager.cancelFailoverNotification()
                return@withContext true
            }

            // ── Полный SmartConnect ────────────────────────────────────────────────


            // Проверяем состояние интернета и запоминаем — чтобы не перезаписать в цикле
            val internetStatus = NetworkManager.checkInternetStatus()
            when (internetStatus) {
                0 -> {
                    GeekModeLogger.log("SmartConnect", "SmartConnect: internet is available")
                    sendStatus(context, context.getString(R.string.status_testing_servers))
                }
                1 -> {
                    GeekModeLogger.log("SmartConnect", "SmartConnect: internet is jammed (only local resources available)")
                    sendStatus(context, context.getString(R.string.status_jamming_detected))
                }
                else -> {
                    GeekModeLogger.log("SmartConnect", "SmartConnect: no internet connection")
                    sendStatus(context, context.getString(R.string.status_no_internet))
                }
            }

            SubscriptionHelper.checkAndSetupSubscription(context)
            val allServers = MmkvManager.decodeServerList()
            val filteredServers = filterServers(allServers).shuffled()

            if (filteredServers.isEmpty()) {
                GeekModeLogger.log("SmartConnect", "No servers found in hardcoded subscription")
                sendStatus(context, context.getString(R.string.status_no_servers))
                return@withContext false
            }

            if (MmkvManager.isV2wCoreEnabled()) {
                val v2wSuccess = V2WScannerEngine.runV2WCoreScan(
                    context, 
                    filteredServers, 
                    isStartup = true,
                    internetStatus = internetStatus,
                    sendStatus = { status -> sendStatus(context, status) },
                    connectToBest = { candidate, startup -> connectToBest(context, Triple(candidate.first, candidate.second, 0L), startup) }
                )
                if (v2wSuccess) {
                    return@withContext true
                }
                if (!MmkvManager.isV2wFallbackEnabled()) {
                    return@withContext false
                }
                GeekModeLogger.log("SmartConnect", "v2w-core found no working servers, falling back to classic scanner")
                sendStatus(context, context.getString(R.string.status_v2w_fallback_to_classic))
                delay(1000)
            }

            val chunkedServers = buildProportionalChunks(filteredServers)
            var best: Triple<String, ProfileItem, Long>? = null
            val profileCheckEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROFILE_CHECK_ENABLED, true)

            for ((index, chunk) in chunkedServers.withIndex()) {
                GeekModeLogger.log("SmartConnect", "Starting Smart Connect for chunk ${index + 1}/${chunkedServers.size} (${chunk.size} servers)")
                // Обновляем статус "тестируем" только если интернет в норме.
                // При "глушат" (1) и "нет интернета" (2) строки уже говорят "ищём серверы" —
                // перезаписывать их бессмысленно, иначе пользователь не увидит важный контекст.
                if (internetStatus == 0) {
                    sendStatus(context, context.getString(R.string.status_testing_servers))
                }

                val results = NodeTesterManager.testServers(context, chunk)

                if (profileCheckEnabled) {
                    for (candidate in results.filter { it.third < Long.MAX_VALUE }) {
                        if (NodeTesterManager.verifyProfile(context, candidate.first, showStatus = (internetStatus == 0))) {
                            best = candidate
                            break
                        } else {
                            if (internetStatus == 0) {
                                sendStatus(context, context.getString(R.string.status_profile_check_failed))
                            }
                        }
                    }
                } else {
                    best = results.firstOrNull { it.third < Long.MAX_VALUE }
                }

                if (best != null) {
                    val leftovers = results.filter { it.first != best.first && it.third < Long.MAX_VALUE }
                    if (leftovers.isNotEmpty()) {
                        NodeTesterManager.verifyAndCacheLeftovers(context.applicationContext, leftovers)
                    }
                    break // Found a working server, stop testing other chunks
                }
                GeekModeLogger.log("SmartConnect", "No working server found in chunk ${index + 1}, moving to next chunk...")
            }

            if (best != null) {
                connectToBest(context, best, isStartup = true)
                NotificationManager.cancelFailoverNotification()
                return@withContext true
            }

            val customEndpointEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_CUSTOM_ENDPOINT_ENABLED, false)
            val endpointUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_ENDPOINT_URL)
            if (customEndpointEnabled && !endpointUrl.isNullOrBlank() && filteredServers.isNotEmpty()) {
                val testCandidate = filteredServers.first()
                val worksDirectly = NodeTesterManager.verifyProfile(context, testCandidate.first, showStatus = false, ignoreCustomEndpoint = true)
                if (worksDirectly) {
                    val endpointProfile = AngConfigManager.identifyConfigType(endpointUrl)
                    val endpointName = endpointProfile?.remarks?.takeIf { it.isNotBlank() } ?: "Конечный узел"
                    GeekModeLogger.log("SmartConnect", "⚠️ Servers work directly, but Custom Endpoint ($endpointName) is DOWN!")
                    sendStatus(context, "⚠️ Конечный узел ($endpointName) не отвечает")
                    return@withContext false
                }
            }

            GeekModeLogger.log("SmartConnect", "No working server found after checking all chunks")
            sendStatus(context, context.getString(R.string.status_no_servers))
            return@withContext false
        } finally {
            isScanning.set(false)
            NotificationManager.cancelScanningNotification(context)
        }
    }

    /**
     * Switches to the next best server.
     */
    suspend fun switchServer(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isScanning.compareAndSet(false, true)) {
            GeekModeLogger.log("SmartConnect", "switchServer: already scanning, ignoring duplicate call")
            return@withContext false
        }
        val switchingMsg = context.getString(R.string.status_switching_server)
        NotificationManager.showScanningNotification(context, switchingMsg)
        sendStatus(context, switchingMsg)
        try {
            val currentGuid = MmkvManager.getSelectServer()
            val currentProfile = currentGuid?.let { MmkvManager.decodeServerConfig(it) }
            val isCurrentPersonal = currentProfile != null && com.kiktor.v2whitelist.core.model.PersonalServersConfig.isPersonalServer(currentProfile.subscriptionId)

            // ── Учет правил сети и приоритета серверов при смене сервера ────────────────
            val personalConfig = PersonalServersManager.getConfig()
            val isWifi = NetworkManager.isWifiActive(context)
            val currentRule = if (isWifi) personalConfig.wifiRule else personalConfig.cellularRule

            // Переключаемся среди личных серверов только если:
            // 1) Личные сервера включены
            // 2) Правило сети НЕ требует использовать сторонние подписки (USE_PUBLIC_SUBS)
            // 3) Текущий сервер является личным, ЛИБО правило требует личные серверы
            val shouldSwitchPersonal = personalConfig.enabled &&
                currentRule != NetworkBindingRule.USE_PUBLIC_SUBS &&
                (isCurrentPersonal || currentRule == NetworkBindingRule.USE_PERSONAL_AUTO || currentRule == NetworkBindingRule.USE_PERSONAL_SELECTED)

            if (shouldSwitchPersonal) {
                val personalCandidates = PersonalServersManager.getPersonalServers()
                val otherPersonalCandidates = personalCandidates.filter { it.first != currentGuid }

                if (otherPersonalCandidates.isNotEmpty()) {
                    GeekModeLogger.log("SmartConnect", "switchServer: Switching among ${otherPersonalCandidates.size} personal servers")
                    sendStatus(context, context.getString(R.string.status_personal_scanning))

                    val results = NodeTesterManager.testServers(context, otherPersonalCandidates)
                    val valid = results.filter { it.third < Long.MAX_VALUE }
                    val profileCheckEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROFILE_CHECK_ENABLED, true)

                    var bestPersonal: Triple<String, ProfileItem, Long>? = null
                    if (profileCheckEnabled) {
                        for (candidate in valid) {
                            if (NodeTesterManager.verifyProfile(context, candidate.first)) {
                                bestPersonal = candidate
                                break
                            }
                        }
                    } else {
                        bestPersonal = valid.firstOrNull()
                    }

                    if (bestPersonal != null) {
                        GeekModeLogger.log("SmartConnect", "PersonalServers: Switched to next personal server: ${bestPersonal.second.remarks}")
                        PersonalServersManager.setSelectedServer(bestPersonal.first)
                        connectToBest(context, bestPersonal, isStartup = false)
                        NotificationManager.cancelFailoverNotification()
                        return@withContext true
                    }
                }

                GeekModeLogger.log("SmartConnect", "PersonalServers: No other working personal server found in switchServer")
                if (!personalConfig.fallbackToPublicOnFailure) {
                    sendStatus(context, context.getString(R.string.status_personal_all_failed))
                    return@withContext false
                }
                sendStatus(context, context.getString(R.string.status_personal_failed_fallback))
                // Продолжаем ниже к публичным серверам при включенном фоллбеке
            }

            val allServers = MmkvManager.decodeServerList()
            val filteredServers = filterServers(allServers, excludeGuid = currentGuid).shuffled()

            if (filteredServers.isEmpty()) {
                return@withContext false
            }

            if (MmkvManager.isV2wCoreEnabled()) {
                val v2wSuccess = V2WScannerEngine.runV2WCoreScan(
                    context, 
                    filteredServers, 
                    isStartup = false,
                    sendStatus = { status -> sendStatus(context, status) },
                    connectToBest = { candidate, startup -> connectToBest(context, Triple(candidate.first, candidate.second, 0L), startup) }
                )
                if (v2wSuccess) {
                    return@withContext true
                }
                if (!MmkvManager.isV2wFallbackEnabled()) {
                    return@withContext false
                }
                GeekModeLogger.log("SmartConnect", "v2w-core found no working servers in switchToNextServer, falling back to classic scanner")
                sendStatus(context, context.getString(R.string.status_v2w_fallback_to_classic))
                delay(1000)
            }

            // ── Быстрый путь: VIP Кэш (Auto Failover) ──────────────────────────────
            if (checkVipCacheAndConnect(context, isStartup = false, excludeGuid = currentGuid)) {
                NotificationManager.cancelFailoverNotification()
                return@withContext true
            }

            sendStatus(context, context.getString(R.string.status_switching_server))

            val chunkedServers = buildProportionalChunks(filteredServers)
            var nextBest: Triple<String, ProfileItem, Long>? = null
            val profileCheckEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROFILE_CHECK_ENABLED, true)

            for ((index, chunk) in chunkedServers.withIndex()) {
                GeekModeLogger.log("SmartConnect", "Switching server: testing chunk ${index + 1}/${chunkedServers.size} (${chunk.size} servers)")
                sendStatus(context, context.getString(R.string.status_testing_servers))

                val results = NodeTesterManager.testServers(context, chunk)

                if (profileCheckEnabled) {
                    for (candidate in results.filter { it.third < Long.MAX_VALUE }) {
                        if (NodeTesterManager.verifyProfile(context, candidate.first)) {
                            nextBest = candidate
                            break
                        }
                    }
                } else {
                    nextBest = results.firstOrNull { it.third < Long.MAX_VALUE }
                }

                if (nextBest != null) {
                    val leftovers = results.filter { it.first != nextBest.first && it.third < Long.MAX_VALUE }
                    if (leftovers.isNotEmpty()) {
                        NodeTesterManager.verifyAndCacheLeftovers(context.applicationContext, leftovers)
                    }
                    break
                }
                GeekModeLogger.log("SmartConnect", "No working server found in chunk ${index + 1}, moving to next chunk...")
            }

            if (nextBest != null) {
                connectToBest(context, nextBest, isStartup = false)
                NotificationManager.cancelFailoverNotification()
                return@withContext true
            }

            val customEndpointEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_CUSTOM_ENDPOINT_ENABLED, false)
            val endpointUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_ENDPOINT_URL)
            if (customEndpointEnabled && !endpointUrl.isNullOrBlank() && filteredServers.isNotEmpty()) {
                val testCandidate = filteredServers.first()
                val worksDirectly = NodeTesterManager.verifyProfile(context, testCandidate.first, showStatus = false, ignoreCustomEndpoint = true)
                if (worksDirectly) {
                    val endpointProfile = AngConfigManager.identifyConfigType(endpointUrl)
                    val endpointName = endpointProfile?.remarks?.takeIf { it.isNotBlank() } ?: "Конечный узел"
                    GeekModeLogger.log("SmartConnect", "switchServer: ⚠️ Servers work directly, but Custom Endpoint ($endpointName) is DOWN!")
                    sendStatus(context, "⚠️ Конечный узел ($endpointName) не отвечает")
                    return@withContext false
                }
            }

            GeekModeLogger.log("SmartConnect", "switchServer: No working server found")
            sendStatus(context, context.getString(R.string.status_no_servers))
            return@withContext false
        } finally {
            isScanning.set(false)
            NotificationManager.cancelScanningNotification(context)
        }
    }

    /**
     * Надежно проверяет, работает ли прокси на локальном порту (межпроцессная проверка)
     */
    fun isProxyRunning(port: Int): Boolean {
        if (port <= 0) return false
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Splits filtered servers into chunks based on proportional logic.
     */
    private fun buildProportionalChunks(servers: List<Pair<String, ProfileItem>>): List<List<Pair<String, ProfileItem>>> {
        return ChunkingHelper.buildProportionalChunks(servers)
    }


}
