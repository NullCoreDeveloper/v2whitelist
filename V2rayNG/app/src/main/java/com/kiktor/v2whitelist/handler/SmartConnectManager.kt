package com.kiktor.v2whitelist.handler

import android.content.Context
import android.util.Log
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
    private val testSemaphore = Semaphore(48)

    const val SUBSCRIPTION_ID = "v2whitelist_hardcoded_sub"
    const val UPDATE_INTERVAL_MS = 60 * 60 * 1000L // 1 hour

    /**
     * Блокирует выполнение до тех пор, пока не появится реальный доступ в интернет (проверка dzen.ru).
     * Защищает кэш серверов от удаления при выключенном WiFi или отсутствии сети.
     */
    private suspend fun waitForInternet(context: Context) {
        var isWaiting = false
        while (true) {
            val dzenOk = try {
                Socket().use { it.connect(InetSocketAddress("dzen.ru", 443), 1500); true }
            } catch (_: Exception) { false }

            if (dzenOk) {
                if (isWaiting) {
                    Log.i(AppConfig.TAG, "waitForInternet: Интернет появился (dzen.ru ответил)")
                }
                break
            }

            if (!isWaiting) {
                Log.w(AppConfig.TAG, "waitForInternet: Нет прямого доступа в интернет. Ожидание сети...")
                isWaiting = true
            }
            sendStatus(context, context.getString(R.string.status_waiting_for_network))
            
            delay(2000)
        }
    }

    /**
     * Проверяет состояние интернета.
     * @return 0 - OK (всё доступно), 1 - JAMMED (только Яндекс), 2 - NO_INTERNET (ничего не доступно)
     */
    fun checkInternetStatus(): Int {
        val googleOk = try {
            Socket().use { it.connect(InetSocketAddress("8.8.8.8", 53), 1500); true }
        } catch (_: Exception) { false }

        val yandexOk = try {
            Socket().use { it.connect(InetSocketAddress("77.88.8.8", 53), 1500); true }
        } catch (_: Exception) { false }

        return when {
            googleOk && yandexOk -> 0   // Все отлично
            !googleOk && yandexOk -> 1  // Глушат (Яндекс жив, Гугл нет)
            else -> 2                   // Интернета нет совсем
        }
    }

    /**
     * Pre-populates the zieng2/wl subscription with mirrors on first launch,
     * and sets up all custom subscriptions.
     */
    suspend fun checkAndSetupSubscription(context: Context) = withContext(Dispatchers.IO) {
        // Мигрируем серверы из старой хардкод-подписки (матрешки), чтобы не оставить пользователя без связи
        val subscriptions = MmkvManager.decodeSubscriptions()
        if (subscriptions.any { it.guid == SUBSCRIPTION_ID }) {
            Log.d(AppConfig.TAG, "Migrating old hardcoded subscription servers to the new custom sub")
            val newSubId = "custom_sub_def_zieng2"
            val serverList = MmkvManager.decodeServerList()
            var migratedCount = 0
            for (guid in serverList) {
                val profile = MmkvManager.decodeServerConfig(guid)
                if (profile != null && profile.subscriptionId == SUBSCRIPTION_ID) {
                    profile.subscriptionId = newSubId
                    MmkvManager.encodeServerConfig(guid, profile)
                    migratedCount++
                }
            }
            Log.d(AppConfig.TAG, "Migrated $migratedCount servers. Removing old subscription object.")
            
            MmkvManager.removeSubscription(SUBSCRIPTION_ID)
            MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_RELOAD_SERVER_LIST, "")
        }

        val defaultsAdded = MmkvManager.decodeSettingsBool("pref_defaults_added_v1", false)
        if (!defaultsAdded) {
            val customSubs = loadCustomSubs().toMutableList()
            var changed = false
            for (defaultSub in DefaultSubscriptions.PREPOPULATED_SUBS) {
                if (customSubs.none { it.name == defaultSub.name }) {
                    Log.d(AppConfig.TAG, "Pre-populating subscription: ${defaultSub.name}")
                    customSubs.add(defaultSub)
                    changed = true
                }
            }
            if (changed) {
                MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_SUB_URLS, com.kiktor.v2whitelist.util.JsonUtil.toJson(customSubs))
            }
            MmkvManager.encodeSettings("pref_defaults_added_v1", true)
        }

        // Обработка кастомных подписок (zieng2/wl теперь обычная кастомная подписка)
        setupCustomSubscriptions(context)
    }

    /**
     * Настраивает кастомные подписки из MMKV.
     */
    private suspend fun setupCustomSubscriptions(context: Context) {
        val customSubs = loadCustomSubs()
        for (sub in customSubs.filter { it.enabled }) {
            val subId = "custom_sub_${sub.id}"
            val subscriptions = MmkvManager.decodeSubscriptions()
            val existing = subscriptions.find { it.guid == subId }

            if (existing == null) {
                val subItem = SubscriptionItem().apply {
                    remarks = sub.name
                    url = sub.url
                    filter = sub.filter
                    enabled = true
                    sharePercent = sub.sharePercent
                }
                MmkvManager.encodeSubscription(subId, subItem)
                AngConfigManager.updateConfigViaSub(SubscriptionCache(subId, subItem))
            } else {
                val subItem = existing.subscription
                if (subItem.url != sub.url || subItem.filter != sub.filter || subItem.remarks != sub.name || subItem.sharePercent != sub.sharePercent) {
                    subItem.url = sub.url
                    subItem.remarks = sub.name
                    subItem.filter = sub.filter
                    subItem.sharePercent = sub.sharePercent
                    MmkvManager.encodeSubscription(subId, subItem)
                    // URL или фильтр изменились — перезагружаем серверы немедленно
                    AngConfigManager.updateConfigViaSub(SubscriptionCache(subId, subItem))
                }
            }
        }
    }

    /**
     * Загружает кастомные подписки из MMKV.
     */
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

    private fun sendStatus(context: Context, status: String) {
        MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE, status)
    }

    /**
     * Force updates all active subscriptions.
     * Сбрасывает кэш последнего сервера — после обновления старый GUID может не существовать.
     * @param sequential если true — последовательная подкачка (фоновый воркер),
     *                    false — параллельная гонка зеркал (UI).
     */
    suspend fun updateSubscription(context: Context, isStartup: Boolean = false, sequential: Boolean = false) = withContext(Dispatchers.IO) {
        // ══════════════════════════════════════════════════════════════════════
        // СНИМОК КЭШЕЙ ПЕРЕД ОБНОВЛЕНИЕМ
        // parseBatchConfig() удаляет ВСЕ старые серверы и создаёт НОВЫЕ GUID-ы.
        // Поэтому нужно запомнить identity серверов (server+port+remarks),
        // чтобы потом найти их новые GUID-ы и ремаппить кэши.
        // ══════════════════════════════════════════════════════════════════════
        data class ServerIdentity(val server: String?, val port: String?, val remarks: String)

        val lastServerGuid = MmkvManager.getValidLastServer()
        val lastServerIdentity = lastServerGuid?.let { guid ->
            MmkvManager.decodeServerConfig(guid)?.let { p ->
                ServerIdentity(p.server, p.serverPort, p.remarks)
            }
        }

        val vipGuids = MmkvManager.getVipCache()
        val vipIdentities = vipGuids.mapNotNull { guid ->
            MmkvManager.decodeServerConfig(guid)?.let { p ->
                ServerIdentity(p.server, p.serverPort, p.remarks)
            }
        }

        if (vipIdentities.isNotEmpty()) {
            Log.i(AppConfig.TAG, "updateSubscription: снимок VIP-кэша: ${vipIdentities.size} серверов (${vipIdentities.joinToString { it.remarks }})")
        }

        val candidateSocksPort = SettingsManager.getSocksPort()
        var socksPort = 0
        var vpnStarted = false
        
        // Ожидаем запуска прокси (дольше при старте приложения, так как он может запускаться SmartConnect'ом)
        val waitLoops = if (isStartup) 8 else 1
        for (i in 0 until waitLoops) {
            if (isProxyRunning(candidateSocksPort)) {
                socksPort = candidateSocksPort
                vpnStarted = true
                break
            }
            if (i < waitLoops - 1) delay(1000)
        }
        
        Log.i(AppConfig.TAG, "updateSubscription: VPN=$vpnStarted, socksPort=$socksPort, sequential=$sequential")

        // Ensure base subscriptions are initialized if this is the first launch
        checkAndSetupSubscription(context)

        // Обновляем кастомные подписки
        val customSubs = loadCustomSubs()
        for (sub in customSubs.filter { it.enabled }) {
            val subId = "custom_sub_${sub.id}"
            val subscriptions = MmkvManager.decodeSubscriptions()
            val existing = subscriptions.find { it.guid == subId }
            if (existing != null) {
                Log.d(AppConfig.TAG, "Manually updating custom subscription: ${sub.name}")
                AngConfigManager.updateConfigViaSub(existing, socksPort, sequential)
            } else {
                // Создаём если нет
                val subItem = SubscriptionItem().apply {
                    remarks = sub.name
                    url = sub.url
                    enabled = true
                }
                MmkvManager.encodeSubscription(subId, subItem)
                AngConfigManager.updateConfigViaSub(SubscriptionCache(subId, subItem), socksPort, sequential)
            }
        }

        // Обновляем обычные подписки (добавленные пользователем вручную)
        val allSubscriptions = MmkvManager.decodeSubscriptions()
        val regularSubs = allSubscriptions.filter { !it.guid.startsWith("custom_sub_") && it.subscription.enabled }
        for (sub in regularSubs) {
            Log.d(AppConfig.TAG, "Manually updating regular subscription: ${sub.subscription.remarks}")
            AngConfigManager.updateConfigViaSub(sub, socksPort, sequential)
        }

        // ══════════════════════════════════════════════════════════════════════
        // РЕМАППИНГ КЭШЕЙ ПОСЛЕ ОБНОВЛЕНИЯ
        // Строим индекс identity → newGuid для быстрого поиска.
        // MMKV — memory-mapped, декодинг 300+ профилей занимает ~20мс.
        // ══════════════════════════════════════════════════════════════════════
        if (lastServerIdentity != null || vipIdentities.isNotEmpty()) {
            val updatedServers = MmkvManager.decodeServerList()
            val identityIndex = mutableMapOf<String, String>() // "server|port|remarks" → newGuid
            for (guid in updatedServers) {
                val profile = MmkvManager.decodeServerConfig(guid) ?: continue
                val key = "${profile.server}|${profile.serverPort}|${profile.remarks}"
                if (!identityIndex.containsKey(key)) {
                    identityIndex[key] = guid
                }
            }

            // ── Ремаппим LastServerCache ──
            if (lastServerIdentity != null) {
                val key = "${lastServerIdentity.server}|${lastServerIdentity.port}|${lastServerIdentity.remarks}"
                val newGuid = identityIndex[key]
                if (newGuid != null) {
                    MmkvManager.remapLastConnectedServer(newGuid)
                    Log.i(AppConfig.TAG, "updateSubscription: LastServerCache ремаппирован → ${lastServerIdentity.remarks}")
                } else {
                    MmkvManager.clearLastConnectedServer()
                    Log.w(AppConfig.TAG, "updateSubscription: LastServerCache сервер исчез после обновления, кэш сброшен")
                }
            }

            // ── Ремаппим VIP-кэш ──
            if (vipIdentities.isNotEmpty()) {
                val remapped = vipIdentities.mapNotNull { id ->
                    val key = "${id.server}|${id.port}|${id.remarks}"
                    identityIndex[key]
                }
                if (remapped.isNotEmpty()) {
                    MmkvManager.replaceVipCache(remapped)
                    Log.i(AppConfig.TAG, "updateSubscription: VIP-кэш ремаппирован: ${remapped.size}/${vipIdentities.size} серверов сохранено")
                } else {
                    MmkvManager.clearVipCache()
                    Log.w(AppConfig.TAG, "updateSubscription: все VIP-серверы исчезли после обновления, кэш очищен")
                }
            }
        }
    }

    /**
     * Фильтрует серверы: убирает не поддерживаемые и применяет фильтр локаций из настроек.
     */
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
                    tag = "🌐" // Fallback tag for servers without any emojis or regex match
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

    /**
     * Тестирует серверы параллельно и возвращает результаты, отсортированные по задержке.
     */
    private suspend fun testServers(
        context: Context,
        servers: List<Pair<String, ProfileItem>>,
        totalTimeoutMs: Long = 6000,
        perServerTimeoutMs: Long = 1500
    ): List<Triple<String, ProfileItem, Long>> {
        val testUrls = listOf(
            AppConfig.DELAY_TEST_URL,
            "https://www.google.com/generate_204",
            "https://www.cloudflare.com/cdn-cgi/trace",
            "https://connectivitycheck.gstatic.com/generate_204"
        )

        // AtomicBoolean вместо cancelChildren() — не ломает awaitAll() CancellationException-ом
        val foundFastServer = AtomicBoolean(false)
        val resultsList = mutableListOf<Triple<String, ProfileItem, Long>>()

        withTimeoutOrNull(totalTimeoutMs) {
            coroutineScope {
                val jobs = servers.map { (guid, profile) ->
                    async {
                        testSemaphore.withPermit {
                            // Ранний выход если уже нашли хороший сервер — не через cancelChildren!
                            if (foundFastServer.get()) return@withPermit null

                            try {
                                val randomUrl = testUrls[Random.nextInt(testUrls.size)]
                                val config = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid)
                                val delay = if (config.status) {
                                    withTimeoutOrNull(perServerTimeoutMs) {
                                        V2RayNativeManager.measureOutboundDelay(config.content, randomUrl)
                                    } ?: -1L
                                } else -1L

                                val finalDelay = if (delay <= 0) Long.MAX_VALUE else delay
                                val result = Triple(guid, profile, finalDelay)
                                
                                // Добавляем результат сразу, чтобы не потерять при таймауте чанка
                                synchronized(resultsList) {
                                    if (resultsList.none { it.first == guid }) {
                                        resultsList.add(result)
                                    }
                                }
                                
                                if (finalDelay < 500) {
                                    // Атомарно помечаем — остальные корутины пропустят тест
                                    foundFastServer.set(true)
                                }
                                result
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e // Прокидываем CancellationException дальше
                            } catch (e: Exception) {
                                Log.e(AppConfig.TAG, "testServers error for $guid", e)
                                null
                            }
                        }
                    }
                }
                
                try {
                    jobs.awaitAll()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.w(AppConfig.TAG, "Chunk testing timed out, proceeding with partial results (${resultsList.size})")
                }
            }
        }

        return resultsList.sortedBy { it.third }
    }

    /**
     * Проверяет профиль: поднимает настоящий экземпляр V2Ray-ядра с локальным SOCKS-прокси
     * и делает реальный HTTP-запрос через него.
     * Только так можно достоверно убедиться, что сервер рабочий — проверка протокольного
     * рукопожатия, авторизации и прохождения трафика, а не просто TCP-доступности.
     */
    private suspend fun verifyProfile(context: Context, guid: String): Boolean {
        // Выделяем свободный локальный порт для SOCKS-прокси
        val port = try {
            ServerSocket(0).use { it.localPort }
        } catch (e: Exception) {
            Log.w(AppConfig.TAG, "verifyProfile: не удалось выделить порт для $guid")
            return false
        }

        // Получаем конфиг с реальным SOCKS inbound на выделенном порту
        val configResult = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid, port)
        if (!configResult.status) {
            Log.w(AppConfig.TAG, "verifyProfile: не удалось создать конфиг speedtest для $guid")
            return false
        }

        sendStatus(context, context.getString(R.string.status_verifying_profile))

        var coreController: CoreController? = null
        return try {
            // Запускаем отдельный экземпляр ядра V2Ray
            coreController = V2RayNativeManager.newCoreController(object : CoreCallbackHandler {
                override fun startup(): Long = 0
                override fun shutdown(): Long = 0
                override fun onEmitStatus(p0: Long, p1: String?): Long = 0
            })

            // fd=0: запуск без TUN (только SOCKS прокси на локальном порту)
            coreController.startLoop(configResult.content, 0)

            // Ждём пока ядро поднимется и установит соединение с сервером
            delay(500L)

            // Реальная проверка: HTTP-запрос через SOCKS прокси → VPN сервер → интернет
            // testConnection делает запрос к gstatic.com/generate_204 и ожидает HTTP 204
            val (elapsed, _) = SpeedtestManager.testConnection(context, port)

            if (elapsed <= 0) {
                Log.w(AppConfig.TAG, "verifyProfile: трафик через сервер не прошёл для $guid")
                false
            } else {
                Log.i(AppConfig.TAG, "verifyProfile: сервер $guid рабочий, задержка = ${elapsed}ms")
                sendStatus(context, context.getString(R.string.status_profile_check_passed))
                true
            }
        } catch (e: Exception) {
            Log.w(AppConfig.TAG, "verifyProfile: исключение для $guid: ${e.message}")
            false
        } finally {
            // Обязательно останавливаем ядро чтобы освободить порт и ресурсы
            try {
                coreController?.stopLoop()
            } catch (_: Exception) {}
        }
    }

    /**
     * Быстрый путь (Fast Path): проверяем серверы из VIP-кэша.
     */
    private suspend fun checkVipCacheAndConnect(context: Context, isStartup: Boolean = false): Boolean {
        val vipGuids = MmkvManager.getVipCache()
        if (vipGuids.isEmpty()) return false

        Log.i(AppConfig.TAG, "VIP Cache: checking ${vipGuids.size} servers")
        
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
        
        val results = testServers(context, vipCandidates)
        val profileCheckEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROFILE_CHECK_ENABLED, true)
        
        val validResults = results.filter { it.third < Long.MAX_VALUE }
        
        if (profileCheckEnabled) {
            for (candidate in validResults) {
                if (verifyProfile(context, candidate.first)) {
                    connectToBest(context, candidate, isStartup, isFromVipCache = true)
                    val leftovers = validResults.filter { it.first != candidate.first }
                    if (leftovers.isNotEmpty()) {
                        verifyAndCacheLeftovers(context.applicationContext, leftovers)
                    }
                    return true
                } else {
                    Log.w(AppConfig.TAG, "VIP Cache: server ${candidate.first} failed deep check, removing")
                    MmkvManager.removeVipServer(candidate.first)
                }
            }
        } else {
            val candidate = validResults.firstOrNull()
            if (candidate != null) {
                connectToBest(context, candidate, isStartup, isFromVipCache = true)
                val leftovers = validResults.filter { it.first != candidate.first }
                if (leftovers.isNotEmpty()) {
                    verifyAndCacheLeftovers(context.applicationContext, leftovers)
                }
                return true
            }
        }
        
        Log.w(AppConfig.TAG, "VIP Cache: all valid servers failed")
        return false
    }

    private fun verifyAndCacheLeftovers(context: Context, candidates: List<Triple<String, ProfileItem, Long>>) {
        GlobalScope.launch(Dispatchers.IO) {
            val profileCheckEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROFILE_CHECK_ENABLED, true)
            for (candidate in candidates) {
                if (MmkvManager.getVipCache().size >= 5) break
                if (profileCheckEnabled) {
                    if (verifyProfile(context, candidate.first)) {
                        Log.i(AppConfig.TAG, "Background: added ${candidate.second.remarks} to VIP cache")
                        MmkvManager.addVipServer(candidate.first)
                    }
                } else {
                    MmkvManager.addVipServer(candidate.first)
                }
            }
        }
    }

    private suspend fun connectToBest(context: Context, best: Triple<String, ProfileItem, Long>, isStartup: Boolean = false, isFromVipCache: Boolean = false) {
        Log.i(AppConfig.TAG, "Smart Connect: Selected ${best.second.remarks} (${best.third}ms)")
        if (isFromVipCache) {
            sendStatus(context, context.getString(R.string.status_using_cached_server, best.second.remarks))
        } else {
            sendStatus(context, context.getString(R.string.status_connecting_to, best.second.remarks))
        }
        MmkvManager.setSelectServer(best.first)

        MmkvManager.addVipServer(best.first)
        MmkvManager.saveLastConnectedServer(best.first)
        Log.i(AppConfig.TAG, "SmartConnect: сервер ${best.second.remarks} сохранён в топ VIP-кэша")

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
            val internetStatus = checkInternetStatus()
            if (internetStatus == 1) { // JAMMED
                // ВАЖНО: используем GlobalScope.launch, а НЕ coroutineScope!
                // coroutineScope блокировал возврат из connectToBest на 5+ секунд,
                // и если updateSubscription падал — убивал весь smartConnect → серая кнопка.
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        delay(5000) // Ждем пока VPN разгонится
                        Log.i(AppConfig.TAG, "Survival logic: Jamming detected, triggering background update via VPN")
                        updateSubscription(context, sequential = true)
                    } catch (e: Exception) {
                        Log.e(AppConfig.TAG, "Survival logic: background update failed", e)
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
                    Log.i(AppConfig.TAG, "smartConnect: triggering background subscription update")
                    GlobalScope.launch(Dispatchers.IO) {
                        try {
                            // sequential = true, чтобы обновлять плавно и не убить пул потоков
                            updateSubscription(context, isStartup = true, sequential = true)
                        } catch (e: Exception) {
                            Log.e(AppConfig.TAG, "smartConnect: background update failed", e)
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
        waitForInternet(context)

        // ── Быстрый путь: кэш проверенных VIP-серверов ──────────────────────────────
        if (checkVipCacheAndConnect(context, isStartup = true)) {
            return@withContext true
        }

        // ── Полный SmartConnect ────────────────────────────────────────────────


        // Проверяем состояние интернета и запоминаем — чтобы не перезаписать в цикле
        val internetStatus = checkInternetStatus()
        when (internetStatus) {
            0 -> {
                Log.i(AppConfig.TAG, "SmartConnect: интернет доступен")
                sendStatus(context, context.getString(R.string.status_testing_servers))
            }
            1 -> {
                Log.w(AppConfig.TAG, "SmartConnect: интернет глушат (только Яндекс доступен)")
                sendStatus(context, context.getString(R.string.status_jamming_detected))
            }
            else -> {
                Log.w(AppConfig.TAG, "SmartConnect: интернета нет совсем")
                sendStatus(context, context.getString(R.string.status_no_internet))
            }
        }

        checkAndSetupSubscription(context)
        val allServers = MmkvManager.decodeServerList()
        val filteredServers = filterServers(allServers).shuffled()

        if (filteredServers.isEmpty()) {
            Log.e(AppConfig.TAG, "No servers found in hardcoded subscription")
            sendStatus(context, context.getString(R.string.status_no_servers))
            return@withContext false
        }

        val chunkedServers = buildProportionalChunks(filteredServers)
        var best: Triple<String, ProfileItem, Long>? = null
        val profileCheckEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROFILE_CHECK_ENABLED, true)

        for ((index, chunk) in chunkedServers.withIndex()) {
            Log.i(AppConfig.TAG, "Starting Smart Connect for chunk ${index + 1}/${chunkedServers.size} (${chunk.size} servers)")
            // Обновляем статус "тестируем" только если интернет в норме.
            // При "глушат" (1) и "нет интернета" (2) строки уже говорят "ищём серверы" —
            // перезаписывать их бессмысленно, иначе пользователь не увидит важный контекст.
            if (internetStatus == 0) {
                sendStatus(context, context.getString(R.string.status_testing_servers))
            }

            val results = testServers(context, chunk)

            // Если включена проверка профиля — проверяем кандидатов по порядку
            if (profileCheckEnabled) {
                for (candidate in results.filter { it.third < Long.MAX_VALUE }) {
                    if (verifyProfile(context, candidate.first)) {
                        best = candidate
                        break
                    } else {
                        sendStatus(context, context.getString(R.string.status_profile_check_failed))
                    }
                }
            } else {
                best = results.firstOrNull { it.third < Long.MAX_VALUE }
            }

            if (best != null) {
                connectToBest(context, best, isStartup = true)
                val leftovers = results.filter { it.first != best!!.first && it.third < Long.MAX_VALUE }
                if (leftovers.isNotEmpty()) {
                    verifyAndCacheLeftovers(context.applicationContext, leftovers)
                }
                break // Found a working server, stop testing other chunks
            }
            Log.w(AppConfig.TAG, "No working server found in chunk ${index + 1}, moving to next chunk...")
        }

        // Fallback: if no server found in time, just pick the first one from list
        if (best == null && filteredServers.isNotEmpty()) {
            Log.w(AppConfig.TAG, "No servers found within timeout, picking first available")
            best = Triple(filteredServers[0].first, filteredServers[0].second, Long.MAX_VALUE)
        }

        if (best != null) {
            if (!chunkedServers.any { chunk -> chunk.any { it.first == best!!.first } }) {
                connectToBest(context, best, isStartup = true)
            }
            return@withContext true
        } else {
            Log.e(AppConfig.TAG, "Critical: No servers available to connect")
            sendStatus(context, context.getString(R.string.status_no_servers))
            return@withContext false
        }
    }

    /**
     * Switches to the next best server.
     */
    suspend fun switchServer(context: Context): Boolean = withContext(Dispatchers.IO) {
        // Ждем появления интернета (dzen.ru) перед переключением
        waitForInternet(context)
        
        val currentGuid = MmkvManager.getSelectServer()
        
        // Если пользователь вручную нажал "Сменить сервер", значит текущий сервер его чем-то
        // не устроил (например, забанен IP). Удаляем его из VIP-кэша, чтобы:
        // 1. Не зацикливаться между одними и теми же серверами при многократном нажатии.
        // 2. Не подключаться к этому отвергнутому серверу при следующем запуске.
        if (currentGuid != null) {
            Log.i(AppConfig.TAG, "switchServer: user manually rejected current server, removing from VIP cache")
            MmkvManager.removeVipServer(currentGuid)
        }
        
        val allServers = MmkvManager.decodeServerList()
        val filteredServers = filterServers(allServers, excludeGuid = currentGuid).shuffled()

        if (filteredServers.isEmpty()) {
            return@withContext false
        }

        // ── Быстрый путь: VIP Кэш (Auto Failover) ──────────────────────────────
        if (checkVipCacheAndConnect(context, isStartup = false)) {
            return@withContext true
        }

        sendStatus(context, context.getString(R.string.status_switching_server))

        val chunkedServers = buildProportionalChunks(filteredServers)
        var nextBest: Triple<String, ProfileItem, Long>? = null
        val profileCheckEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROFILE_CHECK_ENABLED, true)

        for ((index, chunk) in chunkedServers.withIndex()) {
            Log.i(AppConfig.TAG, "Switching server: testing chunk ${index + 1}/${chunkedServers.size} (${chunk.size} servers)")
            sendStatus(context, context.getString(R.string.status_testing_servers))

            val results = testServers(context, chunk)

            if (profileCheckEnabled) {
                for (candidate in results.filter { it.third < Long.MAX_VALUE }) {
                    if (verifyProfile(context, candidate.first)) {
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
                    verifyAndCacheLeftovers(context.applicationContext, leftovers)
                }
                break
            }
            Log.w(AppConfig.TAG, "No working server found in chunk ${index + 1}, moving to next chunk...")
        }

        if (nextBest == null && filteredServers.isNotEmpty()) {
            nextBest = Triple(filteredServers[Random.nextInt(filteredServers.size)].first, filteredServers[0].second, Long.MAX_VALUE)
        }

        if (nextBest != null) {
            if (!chunkedServers.any { chunk -> chunk.any { it.first == nextBest!!.first } }) {
                connectToBest(context, nextBest, isStartup = false)
            }
            return@withContext true
        }
        return@withContext false
    }

    /**
     * Надежно проверяет, работает ли прокси на локальном порту (межпроцессная проверка)
     */
    private fun isProxyRunning(port: Int): Boolean {
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
