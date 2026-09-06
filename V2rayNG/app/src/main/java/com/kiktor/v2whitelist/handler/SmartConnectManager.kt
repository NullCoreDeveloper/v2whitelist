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

object SmartConnectManager {
    private fun sendStatus(context: Context, status: String) {
        MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE, status)
    }


    private fun loadCustomSubs(): List<CustomSubData> {
        val json = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_SUB_URLS)
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            com.kiktor.v2whitelist.util.JsonUtil.fromJson(json, Array<CustomSubData>::class.java)?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Дата-класс для JSON-десериализации кастомных подписок */
    data class CustomSubData(
        val id: String = "",
        val name: String = "",
        val url: String = "",
        val filter: String = "",
        val groupRegex: String = "",
        val enabled: Boolean = true,
        val sharePercent: Int? = null
    )


    private fun filterServers(allServers: List<String>, excludeGuid: String? = null): List<Pair<String, ProfileItem>> {
        // Получаем список выключенных подписок, чтобы не подключаться к их серверам
        val disabledSubIds = loadCustomSubs().filter { !it.enabled }.map { "custom_sub_${it.id}" }.toSet()
        
        // Загружаем настройки фильтра
        val filterMode = MmkvManager.decodeSettingsString(
            AppConfig.PREF_LOCATION_FILTER_MODE,
            AppConfig.LOCATION_FILTER_MODE_EXCLUDE
        ) ?: AppConfig.LOCATION_FILTER_MODE_EXCLUDE

        val filterSet = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_LOCATION_FILTER_SET)
            ?: com.kiktor.v2whitelist.ui.LocationFilterActivity.getDefaultFilterSet()
            
        val groupRegexMap = com.kiktor.v2whitelist.ui.LocationFilterActivity.getGroupRegexMap()

        return allServers.mapNotNull { guid ->
            val profile = MmkvManager.decodeServerConfig(guid)
            if (profile != null && (excludeGuid == null || guid != excludeGuid)) {
                if (disabledSubIds.contains(profile.subscriptionId)) {
                    null // Пропускаем серверы из выключенных подписок
                } else {
                    guid to profile
                }
            } else null
        }.filter { it.second.configType != com.kiktor.v2whitelist.enums.EConfigType.POLICYGROUP }
            .filter {
                // Фильтр по локациям (эмодзи-флаги или кастомные группы)
                if (filterSet.isEmpty()) return@filter true
                
                var tag: String? = null
                val regexStr = groupRegexMap[it.second.subscriptionId]
                if (!regexStr.isNullOrEmpty()) {
                    try {
                        val match = Regex(regexStr).find(it.second.remarks)
                        if (match != null && match.groupValues.size > 1) {
                            tag = match.groupValues[1]
                        }
                    } catch (e: Exception) {}
                }
                if (tag.isNullOrEmpty()) {
                    tag = com.kiktor.v2whitelist.ui.LocationFilterActivity.extractFirstFlagEmoji(it.second.remarks)
                }
                if (tag.isNullOrEmpty()) {
                    tag = com.kiktor.v2whitelist.ui.LocationFilterActivity.TAG_UNKNOWN // Fallback tag for servers without any emojis or regex match
                }
                
                when (filterMode) {
                    AppConfig.LOCATION_FILTER_MODE_EXCLUDE -> {
                        // Режим исключения: если тег в наборе — исключаем
                        tag == null || !filterSet.contains(tag)
                    }
                    AppConfig.LOCATION_FILTER_MODE_WHITELIST -> {
                        // Режим белого списка: если тег в наборе — разрешаем
                        tag != null && filterSet.contains(tag)
                    }
                    else -> true
                }
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
    private suspend fun checkVipCacheAndConnect(context: Context, isStartup: Boolean = false): Boolean {
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
        
        if (vipCandidates.isEmpty()) return false
        
        sendStatus(context, context.getString(R.string.status_checking_vip_servers))
        
        val results = NodeTesterManager.testServers(context, vipCandidates)
        val profileCheckEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROFILE_CHECK_ENABLED, true)
        
        val validResults = results.filter { it.third < Long.MAX_VALUE }
        
        if (profileCheckEnabled) {
            for (candidate in validResults) {
                if (NodeTesterManager.verifyProfile(context, candidate.first)) {
                    connectToBest(context, candidate, isStartup, isFromVipCache = true)
                    val leftovers = validResults.filter { it.first != candidate.first }
                    if (leftovers.isNotEmpty()) {
                        NodeTesterManager.verifyAndCacheLeftovers(context.applicationContext, leftovers)
                    }
                    return true
                } else {
                    GeekModeLogger.log("SmartConnect", "VIP Cache: server ${candidate.first} failed deep check, removing")
                    MmkvManager.removeVipServer(candidate.first)
                }
            }
        } else {
            val candidate = validResults.firstOrNull()
            if (candidate != null) {
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
        GeekModeLogger.log("SmartConnect", "Smart Connect: Selected ${best.second.remarks} (${best.third}ms)")
        if (isFromVipCache) {
            sendStatus(context, context.getString(R.string.status_using_cached_server, best.second.remarks))
        } else {
            sendStatus(context, context.getString(R.string.status_connecting_to, best.second.remarks))
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
                if (context is com.kiktor.v2whitelist.ui.MainActivity) {
                    context.startV2Ray()
                } else {
                    V2RayServiceManager.startVService(context)
                }
            }
        }
        
        if (isStartup) {
            val internetStatus = NetworkManager.checkInternetStatus()
            if (internetStatus == 1) { // JAMMED
                // ВАЖНО: используем GlobalScope.launch, а НЕ coroutineScope!
                // coroutineScope блокировал возврат из connectToBest на 5+ секунд,
                // и если updateSubscription падал — убивал весь smartConnect → серая кнопка.
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        delay(5000) // Ждем пока VPN разгонится
                        GeekModeLogger.log("SmartConnect", "Survival logic: Jamming detected, triggering background update via VPN")
                        SubscriptionHelper.updateSubscription(context, sequential = true)
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
                            // sequential = true, чтобы обновлять плавно и не убить пул потоков
                            SubscriptionHelper.updateSubscription(context, isStartup = true, sequential = true)
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
        
        // Ждем появления интернета (dzen.ru) перед тем, как трогать кэш и удалять мертвые серверы
        NetworkManager.waitForInternet(context)

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
            return@withContext V2WScannerEngine.runV2WCoreScan(
                context, 
                filteredServers, 
                isStartup = true,
                internetStatus = internetStatus,
                sendStatus = { status -> sendStatus(context, status) },
                connectToBest = { candidate, startup -> connectToBest(context, Triple(candidate.first, candidate.second, 0L), startup) }
            )
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
                connectToBest(context, best, isStartup = true)
                val leftovers = results.filter { it.first != best!!.first && it.third < Long.MAX_VALUE }
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

        GeekModeLogger.log("SmartConnect", "No working server found after checking all chunks")
        sendStatus(context, context.getString(R.string.status_no_servers))
        return@withContext false
    }

    /**
     * Switches to the next best server.
     */
    suspend fun switchServer(context: Context): Boolean = withContext(Dispatchers.IO) {
        // Ждем появления интернета (dzen.ru) перед переключением
        NetworkManager.waitForInternet(context)
        
        val currentGuid = MmkvManager.getSelectServer()
        
        // Если пользователь вручную нажал "Сменить сервер", значит текущий сервер его чем-то
        // не устроил (например, забанен IP). Удаляем его из VIP-кэша, чтобы:
        // 1. Не зацикливаться между одними и теми же серверами при многократном нажатии.
        // 2. Не подключаться к этому отвергнутому серверу при следующем запуске.
        if (currentGuid != null) {
            GeekModeLogger.log("SmartConnect", "switchServer: user manually rejected current server, removing from VIP cache")
            MmkvManager.removeVipServer(currentGuid)
        }
        
        val allServers = MmkvManager.decodeServerList()
        val filteredServers = filterServers(allServers, excludeGuid = currentGuid).shuffled()

        if (filteredServers.isEmpty()) {
            return@withContext false
        }

        if (MmkvManager.isV2wCoreEnabled()) {
            return@withContext V2WScannerEngine.runV2WCoreScan(
                context, 
                filteredServers, 
                isStartup = false,
                sendStatus = { status -> sendStatus(context, status) },
                connectToBest = { candidate, startup -> connectToBest(context, Triple(candidate.first, candidate.second, 0L), startup) }
            )
        }

        // ── Быстрый путь: VIP Кэш (Auto Failover) ──────────────────────────────
        if (checkVipCacheAndConnect(context, isStartup = false)) {
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
                connectToBest(context, nextBest, isStartup = false)
                val leftovers = results.filter { it.first != nextBest!!.first && it.third < Long.MAX_VALUE }
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

        GeekModeLogger.log("SmartConnect", "switchServer: No working server found")
        sendStatus(context, context.getString(R.string.status_no_servers))
        return@withContext false
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
        if (servers.isEmpty()) return emptyList()

        val chunkSizeStr = MmkvManager.decodeSettingsString(AppConfig.PREF_CHUNK_SIZE) ?: "20"
        val chunkSize = chunkSizeStr.toIntOrNull()?.takeIf { it > 0 } ?: 20
        val preset = MmkvManager.decodeSettingsString(AppConfig.PREF_CHUNK_PRESET) ?: "equal"
        
        if (preset == "random") {
            return servers.chunked(chunkSize)
        }

        // Group by subscription
        val subGroups = servers.groupBy { it.second.subscriptionId }
            .mapValues { it.value.toMutableList() }
            .toMutableMap()
        
        // Remove null or empty subscription keys if any
        val allSubs = MmkvManager.decodeSubscriptions().associateBy { it.guid }
        
        // Calculate shares
        val subShares = mutableMapOf<String, Double>()
        var remainingShare = 100.0
        val unassignedSubs = mutableListOf<String>()

        for ((subId, _) in subGroups) {
            val subItem = allSubs[subId]?.subscription
            if (subItem?.sharePercent != null) {
                subShares[subId] = subItem.sharePercent!!.toDouble()
                remainingShare -= subItem.sharePercent!!.toDouble()
            } else {
                unassignedSubs.add(subId)
            }
        }

        // Normalize if hardcoded shares > 100%
        val totalHardcoded = subShares.values.sum()
        if (totalHardcoded > 100.0) {
            val ratio = 100.0 / totalHardcoded
            for (key in subShares.keys) {
                subShares[key] = subShares[key]!! * ratio
            }
            remainingShare = 0.0
        } else if (remainingShare < 0.0) {
            remainingShare = 0.0
        }

        // Distribute remaining share based on preset
        if (unassignedSubs.isNotEmpty() && remainingShare > 0) {
            when (preset) {
                "proportional" -> {
                    val totalUnassignedServers = unassignedSubs.sumOf { subGroups[it]?.size ?: 0 }
                    for (subId in unassignedSubs) {
                        val count = subGroups[subId]?.size ?: 0
                        subShares[subId] = if (totalUnassignedServers > 0) {
                            (count.toDouble() / totalUnassignedServers) * remainingShare
                        } else 0.0
                    }
                }
                "inverse" -> {
                    // Inverse: 1/size
                    val totalServers = unassignedSubs.sumOf { subGroups[it]?.size ?: 0 }
                    if (totalServers > 0) {
                        val maxCount = unassignedSubs.maxOf { subGroups[it]?.size ?: 0 }
                        val inverseScores = unassignedSubs.associateWith { subId ->
                            maxCount - (subGroups[subId]?.size ?: 0) + 1.0
                        }
                        val sumInverse = inverseScores.values.sum()
                        for (subId in unassignedSubs) {
                            subShares[subId] = (inverseScores[subId]!! / sumInverse) * remainingShare
                        }
                    }
                }
                else -> { // "equal"
                    val sharePerSub = remainingShare / unassignedSubs.size
                    for (subId in unassignedSubs) {
                        subShares[subId] = sharePerSub
                    }
                }
            }
        }

        val chunks = mutableListOf<List<Pair<String, ProfileItem>>>()
        
        while (subGroups.values.any { it.isNotEmpty() }) {
            val currentChunk = mutableListOf<Pair<String, ProfileItem>>()
            val targetSizes = mutableMapOf<String, Int>()
            var activeSharesSum = 0.0
            
            // active subscriptions for this chunk
            val activeSubs = subGroups.filter { it.value.isNotEmpty() }.keys
            for (subId in activeSubs) {
                activeSharesSum += subShares[subId] ?: 0.0
            }

            if (activeSharesSum <= 0.0) activeSharesSum = 1.0 // fallback
            
            var remainingChunkSlots = chunkSize
            for (subId in activeSubs) {
                val share = subShares[subId] ?: 0.0
                var slots = Math.round((share / activeSharesSum) * chunkSize).toInt()
                if (slots > remainingChunkSlots) slots = remainingChunkSlots
                targetSizes[subId] = slots
                remainingChunkSlots -= slots
            }
            
            // Distribute leftovers if any
            if (remainingChunkSlots > 0 && activeSubs.isNotEmpty()) {
                val activeList = activeSubs.toList()
                for (i in 0 until remainingChunkSlots) {
                    val subId = activeList[Random.nextInt(activeList.size)]
                    targetSizes[subId] = (targetSizes[subId] ?: 0) + 1
                }
            }
            
            // Pull servers
            var actuallyNeeded = chunkSize
            for (subId in activeSubs) {
                var quota = targetSizes[subId] ?: 0
                val groupList = subGroups[subId]!!
                while (quota > 0 && groupList.isNotEmpty()) {
                    currentChunk.add(groupList.removeAt(0))
                    quota--
                    actuallyNeeded--
                }
            }
            
            // If some sub couldn't fulfill quota, fill with others
            while (actuallyNeeded > 0 && subGroups.values.any { it.isNotEmpty() }) {
                val activeList = subGroups.filter { it.value.isNotEmpty() }.keys.toList()
                if (activeList.isEmpty()) break
                val subId = activeList[Random.nextInt(activeList.size)]
                currentChunk.add(subGroups[subId]!!.removeAt(0))
                actuallyNeeded--
            }
            
            if (currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.shuffled()) // shuffle within chunk
            }
        }
        
        return chunks
    }


}
