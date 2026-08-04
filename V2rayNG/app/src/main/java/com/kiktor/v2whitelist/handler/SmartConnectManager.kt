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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.GlobalScope
import kotlin.random.Random
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

    // Ссылка-матрёшка: сначала загружаем этот файл, в нём — реальный URL подписки
    const val WHITELIST_URL = "https://raw.githubusercontent.com/NullCoreDeveloper/v2whitelist/master/whitelist.txt"
    // Fallback если whitelist.txt недоступен
    const val FALLBACK_SUBSCRIPTION_URL = "https://raw.githubusercontent.com/zieng2/wl/main/vless_lite.txt"

    const val SUBSCRIPTION_ID = "v2whitelist_hardcoded_sub"
    const val SUBSCRIPTION_REMARKS = "v2Whitelist Official"
    const val UPDATE_INTERVAL_MS = 60 * 60 * 1000L // 1 hour

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
     * Загружает whitelist.txt и извлекает реальный URL подписки.
     * Если не удалось — возвращает fallback URL.
     * @param socksPort SOCKS5 порт (>0 = использовать VPN прокси).
     */
    private fun resolveSubscriptionUrl(socksPort: Int = 0): String {
        return try {
            val url = URL(WHITELIST_URL)
            val connection = if (socksPort > 0) {
                val httpPort = socksPort + 1
                url.openConnection(
                    Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))
                ) as HttpURLConnection
            } else {
                url.openConnection() as HttpURLConnection
            }
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "v2Whitelist/1.0")

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val body = connection.inputStream.bufferedReader().readText().trim()
                connection.disconnect()

                val resolvedUrl = body.lines()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("http") }

                if (!resolvedUrl.isNullOrEmpty()) {
                    Log.i(AppConfig.TAG, "Resolved subscription URL from whitelist: $resolvedUrl")
                    resolvedUrl
                } else {
                    Log.w(AppConfig.TAG, "Whitelist file is empty or has no valid URL, using fallback")
                    FALLBACK_SUBSCRIPTION_URL
                }
            } else {
                connection.disconnect()
                Log.w(AppConfig.TAG, "Failed to fetch whitelist (HTTP $responseCode), using fallback")
                FALLBACK_SUBSCRIPTION_URL
            }
        } catch (e: Exception) {
            Log.w(AppConfig.TAG, "Failed to resolve subscription URL: ${e.message}, using fallback")
            FALLBACK_SUBSCRIPTION_URL
        }
    }

    /**
     * Ensures the hardcoded subscription is present and updated.
     * Also sets up custom subscriptions.
     */
    suspend fun checkAndSetupSubscription(context: Context) = withContext(Dispatchers.IO) {
        val useBuiltin = MmkvManager.decodeSettingsBool(AppConfig.PREF_USE_BUILTIN_SUB, true)
        
        // Надежная проверка работы прокси (независимо от процесса VPN)
        val candidateSocksPort = com.kiktor.v2whitelist.handler.SettingsManager.getSocksPort()
        val socksPort = if (isProxyRunning(candidateSocksPort)) {
            candidateSocksPort
        } else {
            0
        }

        if (useBuiltin) {
            val subscriptions = MmkvManager.decodeSubscriptions()
            val existingSub = subscriptions.find { it.guid == SUBSCRIPTION_ID }

            // Разрешаем URL через матрёшку с учетом VPN
            val realUrl = resolveSubscriptionUrl(socksPort)

            if (existingSub == null) {
                Log.d(AppConfig.TAG, "Adding hardcoded subscription")
                val subItem = SubscriptionItem().apply {
                    remarks = SUBSCRIPTION_REMARKS
                    url = realUrl
                    enabled = true
                }
                MmkvManager.encodeSubscription(SUBSCRIPTION_ID, subItem)
                sendStatus(context, context.getString(R.string.status_updating_subscription))
                AngConfigManager.updateConfigViaSub(SubscriptionCache(SUBSCRIPTION_ID, subItem))
            } else {
                // Обновляем URL на случай если он изменился в whitelist.txt
                val subItem = existingSub.subscription
                if (subItem.url != realUrl) {
                    Log.d(AppConfig.TAG, "Subscription URL changed, updating: $realUrl")
                    subItem.url = realUrl
                    MmkvManager.encodeSubscription(SUBSCRIPTION_ID, subItem)
                }
            }
        } else {
            // Если выключено — проверяем нет ли "остатков" и вычищаем
            val subscriptions = MmkvManager.decodeSubscriptions()
            if (subscriptions.any { it.guid == SUBSCRIPTION_ID }) {
                Log.d(AppConfig.TAG, "Built-in subscription is disabled, removing cache")
                MmkvManager.removeSubscription(SUBSCRIPTION_ID)
                MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_RELOAD_SERVER_LIST, "")
            }
        }

        // Обработка кастомных подписок
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
                }
                MmkvManager.encodeSubscription(subId, subItem)
                AngConfigManager.updateConfigViaSub(SubscriptionCache(subId, subItem))
            } else {
                val subItem = existing.subscription
                if (subItem.url != sub.url || subItem.filter != sub.filter) {
                    subItem.url = sub.url
                    subItem.remarks = sub.name
                    subItem.filter = sub.filter
                    MmkvManager.encodeSubscription(subId, subItem)
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
        val enabled: Boolean = true
    )

    private fun sendStatus(context: Context, status: String) {
        MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE, status)
    }

    /**
     * Force updates all active subscriptions.
     * Сбрасывает кэш последнего сервера — после обновления старый GUID может не существовать.
     */
    suspend fun updateSubscription(context: Context, isStartup: Boolean = false) = withContext(Dispatchers.IO) {
        // Сбрасываем кэш: после обновления список серверов изменится
        MmkvManager.clearLastConnectedServer()
        Log.i(AppConfig.TAG, "updateSubscription: кэш последнего сервера сброшен")

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
        
        Log.i(AppConfig.TAG, "updateSubscription: VPN=$vpnStarted, socksPort=$socksPort")

        val useBuiltin = MmkvManager.decodeSettingsBool(AppConfig.PREF_USE_BUILTIN_SUB, true)

        if (useBuiltin) {
            val subscriptions = MmkvManager.decodeSubscriptions()
            val existingSub = subscriptions.find { it.guid == SUBSCRIPTION_ID }
            if (existingSub != null) {
                // Обновляем URL через VPN-прокси если он активен
                val realUrl = resolveSubscriptionUrl(socksPort)
                val subItem = existingSub.subscription
                if (subItem.url != realUrl) {
                    Log.d(AppConfig.TAG, "updateSubscription: URL изменился, обновляем")
                    subItem.url = realUrl
                    MmkvManager.encodeSubscription(SUBSCRIPTION_ID, subItem)
                }
                Log.d(AppConfig.TAG, "Manually updating builtin subscription via socksPort=$socksPort")
                sendStatus(context, if (socksPort > 0)
                    context.getString(R.string.status_updating_via_vpn)
                else
                    context.getString(R.string.status_updating_subscription)
                )
                AngConfigManager.updateConfigViaSub(existingSub, socksPort)
            } else {
                checkAndSetupSubscription(context)
            }
        }

        // Обновляем кастомные подписки
        val customSubs = loadCustomSubs()
        for (sub in customSubs.filter { it.enabled }) {
            val subId = "custom_sub_${sub.id}"
            val subscriptions = MmkvManager.decodeSubscriptions()
            val existing = subscriptions.find { it.guid == subId }
            if (existing != null) {
                Log.d(AppConfig.TAG, "Manually updating custom subscription: ${sub.name}")
                AngConfigManager.updateConfigViaSub(existing, socksPort)
            } else {
                // Создаём если нет
                val subItem = SubscriptionItem().apply {
                    remarks = sub.name
                    url = sub.url
                    enabled = true
                }
                MmkvManager.encodeSubscription(subId, subItem)
                AngConfigManager.updateConfigViaSub(SubscriptionCache(subId, subItem), socksPort)
            }
        }
    }

    /**
     * Фильтрует серверы: убирает не поддерживаемые и применяет фильтр локаций из настроек.
     */
    private fun filterServers(allServers: List<String>, excludeGuid: String? = null): List<Pair<String, ProfileItem>> {
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
                guid to profile
            } else null
        }.filter { it.second.configType != com.kiktor.v2whitelist.enums.EConfigType.POLICYGROUP }
            .filter {
                val remarks = it.second.remarks.lowercase()
                // Фильтр российских хостеров (всегда активен)
                !remarks.contains("timeweb") &&
                !remarks.contains("selectel") &&
                !remarks.contains("yandex") &&
                !remarks.contains("aeza") &&
                !remarks.contains("cloud.ru") &&
                !remarks.contains("vk")
            }
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
                if (tag == null) {
                    tag = com.kiktor.v2whitelist.ui.LocationFilterActivity.extractFirstFlagEmoji(it.second.remarks)
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

        val resultsList = mutableListOf<Triple<String, ProfileItem, Long>>()

        withTimeoutOrNull(totalTimeoutMs) {
            coroutineScope {
                val jobs = servers.map { (guid, profile) ->
                    async {
                        testSemaphore.withPermit {
                            if (resultsList.any { it.third < 500 }) return@withPermit null

                            val randomUrl = testUrls[Random.nextInt(testUrls.size)]
                            val config = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid)
                            val delay = if (config.status) {
                                withTimeoutOrNull(perServerTimeoutMs) {
                                    V2RayNativeManager.measureOutboundDelay(config.content, randomUrl)
                                } ?: -1L
                            } else -1L

                            val finalDelay = if (delay <= 0) Long.MAX_VALUE else delay
                            val result = Triple(guid, profile, finalDelay)
                            if (finalDelay < 500) {
                                synchronized(resultsList) { resultsList.add(result) }
                                this@coroutineScope.coroutineContext[Job]?.cancelChildren()
                            }
                            result
                        }
                    }
                }
                resultsList.addAll(jobs.awaitAll().filterNotNull())
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
     * Logic for "Smart Connect" - filter, sort by RealPing, and connect to best.
     *
     * Если с момента последнего включения VPN прошло менее [AppConfig.LAST_SERVER_CACHE_TTL_MS],
     * сразу переиспользует последний сервер без тестирования (быстрый путь).
     * Иначе — полный SmartConnect с тестированием.
     */
    suspend fun smartConnect(context: Context) = withContext(Dispatchers.IO) {

        // ── Быстрый путь: кэш последнего сервера ──────────────────────────────
        val cachedGuid = MmkvManager.getValidLastServer()
        if (cachedGuid != null) {
            val cachedProfile = MmkvManager.decodeServerConfig(cachedGuid)
            if (cachedProfile != null) {
                Log.i(AppConfig.TAG, "SmartConnect: используем кэшированный сервер → ${cachedProfile.remarks}")
                sendStatus(context, context.getString(R.string.status_using_cached_server, cachedProfile.remarks))
                MmkvManager.setSelectServer(cachedGuid)
                // Обновляем timestamp — часовой таймер сбрасывается с нового включения
                MmkvManager.saveLastConnectedServer(cachedGuid)

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

                // Логика 'выживания': если интернет глушат и подписка старая, обновляем её через VPN сразу после коннекта
                val status = checkInternetStatus()
                if (status == 1) { // JAMMED
                    coroutineScope {
                        launch(Dispatchers.IO) {
                            delay(5000) // Ждем пока VPN разгонится
                            Log.i(AppConfig.TAG, "Survival logic: Jamming detected, triggering background update via VPN")
                            updateSubscription(context)
                        }
                    }
                }
                return@withContext
            }
        }
        // ── Полный SmartConnect ────────────────────────────────────────────────

        // ── Полный SmartConnect ────────────────────────────────────────────────
        
        // Проверяем состояние интернета
        when (checkInternetStatus()) {
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
            return@withContext
        }

        val chunkedServers = filteredServers.chunked(20)
        var best: Triple<String, ProfileItem, Long>? = null
        val profileCheckEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROFILE_CHECK_ENABLED, true)

        for ((index, chunk) in chunkedServers.withIndex()) {
            Log.i(AppConfig.TAG, "Starting Smart Connect for chunk ${index + 1}/${chunkedServers.size} (${chunk.size} servers)")
            sendStatus(context, context.getString(R.string.status_testing_servers))

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
            Log.i(AppConfig.TAG, "Smart Connect: Selected ${best.second.remarks} (${best.third}ms)")
            sendStatus(context, context.getString(R.string.status_connecting_to, best.second.remarks))
            MmkvManager.setSelectServer(best.first)

            // Сохраняем сервер в кэш для быстрого повторного подключения
            MmkvManager.saveLastConnectedServer(best.first)
            Log.i(AppConfig.TAG, "SmartConnect: сервер ${best.second.remarks} сохранён в кэш")

            // Если VPN уже запущен — переключаем ядро, а не пытаемся стартовать заново
            val isRunning = V2RayServiceManager.isRunning()
            Log.i(AppConfig.TAG, "smartConnect: V2RayServiceManager.isRunning()=$isRunning")
            if (isRunning) {
                Log.i(AppConfig.TAG, "smartConnect: VPN is running, sending SWITCH_SERVER message")
                MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_SWITCH_SERVER, "")
            } else {
                Log.i(AppConfig.TAG, "smartConnect: VPN is not running, calling startV2Ray()")
                withContext(Dispatchers.Main) {
                    if (context is com.kiktor.v2whitelist.ui.MainActivity) {
                        context.startV2Ray()
                    } else {
                        V2RayServiceManager.startVService(context)
                    }
                }
            }
            
            // Фоновое авто-обновление подписки после установки соединения
            val isAutoUpdateEnabled = MmkvManager.decodeSettingsBool(AppConfig.SUBSCRIPTION_AUTO_UPDATE, true)
            if (isAutoUpdateEnabled) {
                val existingSub = MmkvManager.decodeSubscriptions().find { it.guid == SUBSCRIPTION_ID }
                val lastUpdated = existingSub?.subscription?.lastUpdated ?: 0L
                if (System.currentTimeMillis() - lastUpdated > UPDATE_INTERVAL_MS) {
                    Log.i(AppConfig.TAG, "smartConnect: triggering background subscription update")
                    GlobalScope.launch(Dispatchers.IO) {
                        updateSubscription(context, isStartup = true)
                    }
                }
            }

        } else {
            Log.e(AppConfig.TAG, "Critical: No servers available to connect")
            sendStatus(context, context.getString(R.string.status_no_servers))
        }
    }

    /**
     * Switches to the next best server.
     */
    suspend fun switchServer(context: Context) = withContext(Dispatchers.IO) {
        val currentGuid = MmkvManager.getSelectServer()
        val allServers = MmkvManager.decodeServerList()
        val filteredServers = filterServers(allServers, excludeGuid = currentGuid).shuffled()

        if (filteredServers.isEmpty()) {
            return@withContext
        }

        sendStatus(context, context.getString(R.string.status_switching_server))

        val chunkedServers = filteredServers.chunked(20)
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
                break
            }
            Log.w(AppConfig.TAG, "No working server found in chunk ${index + 1}, moving to next chunk...")
        }

        if (nextBest == null && filteredServers.isNotEmpty()) {
            nextBest = Triple(filteredServers[Random.nextInt(filteredServers.size)].first, filteredServers[0].second, Long.MAX_VALUE)
        }

        if (nextBest != null) {
            Log.i(AppConfig.TAG, "switchServer: Switching to ${nextBest.second.remarks}")
            sendStatus(context, context.getString(R.string.status_connecting_to, nextBest.second.remarks))
            MmkvManager.setSelectServer(nextBest.first)

            // Обновляем кэш — при ручном переключении запоминаем новый сервер
            MmkvManager.saveLastConnectedServer(nextBest.first)
            Log.i(AppConfig.TAG, "switchServer: кэш обновлён → ${nextBest.second.remarks}")

            val isRunning = V2RayServiceManager.isRunning()
            Log.i(AppConfig.TAG, "switchServer: V2RayServiceManager.isRunning()=$isRunning")
            if (isRunning) {
                Log.i(AppConfig.TAG, "switchServer: VPN is running, sending SWITCH_SERVER message")
                MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_SWITCH_SERVER, "")
            } else {
                Log.i(AppConfig.TAG, "switchServer: VPN is not running, calling startV2Ray()")
                withContext(Dispatchers.Main) {
                    if (context is com.kiktor.v2whitelist.ui.MainActivity) {
                        context.startV2Ray()
                    } else {
                        V2RayServiceManager.startVService(context)
                    }
                }
            }
        }
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
}
