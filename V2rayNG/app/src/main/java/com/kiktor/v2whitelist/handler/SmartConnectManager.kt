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
import kotlin.random.Random
import java.net.HttpURLConnection
import java.net.URL

object SmartConnectManager {
    private val testSemaphore = Semaphore(48)

    // Ссылка-матрёшка: сначала загружаем этот файл, в нём — реальный URL подписки
    const val WHITELIST_URL = "https://raw.githubusercontent.com/kiktor12358/v2whitelist/master/whitelist.txt"
    // Fallback если whitelist.txt недоступен
    const val FALLBACK_SUBSCRIPTION_URL = "https://raw.githubusercontent.com/zieng2/wl/main/vless_lite.txt"

    const val SUBSCRIPTION_ID = "v2whitelist_hardcoded_sub"
    const val SUBSCRIPTION_REMARKS = "v2Whitelist Official"
    const val UPDATE_INTERVAL_MS = 60 * 60 * 1000L // 1 hour

    /**
     * Загружает whitelist.txt и извлекает реальный URL подписки.
     * Если не удалось — возвращает fallback URL.
     */
    private fun resolveSubscriptionUrl(): String {
        return try {
            val connection = URL(WHITELIST_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "v2Whitelist/1.0")

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val body = connection.inputStream.bufferedReader().readText().trim()
                connection.disconnect()

                // Берём первую непустую строку как URL подписки
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
     */
    suspend fun checkAndSetupSubscription(context: Context) = withContext(Dispatchers.IO) {
        val subscriptions = MmkvManager.decodeSubscriptions()
        val existingSub = subscriptions.find { it.guid == SUBSCRIPTION_ID }

        // Разрешаем URL через матрёшку
        val realUrl = resolveSubscriptionUrl()

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

            val lastUpdated = subItem.lastUpdated
            if (System.currentTimeMillis() - lastUpdated > UPDATE_INTERVAL_MS) {
                Log.d(AppConfig.TAG, "Updating hardcoded subscription (time passed)")
                sendStatus(context, context.getString(R.string.status_updating_subscription))
                AngConfigManager.updateConfigViaSub(existingSub)
            }
        }
    }

    private fun sendStatus(context: Context, status: String) {
        MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE, status)
    }

    /**
     * Force updates the subscription.
     */
    suspend fun updateSubscription(context: Context) = withContext(Dispatchers.IO) {
        val subscriptions = MmkvManager.decodeSubscriptions()
        val existingSub = subscriptions.find { it.guid == SUBSCRIPTION_ID }
        if (existingSub != null) {
            // Обновляем URL перед загрузкой
            val realUrl = resolveSubscriptionUrl()
            val subItem = existingSub.subscription
            if (subItem.url != realUrl) {
                subItem.url = realUrl
                MmkvManager.encodeSubscription(SUBSCRIPTION_ID, subItem)
            }
            Log.d(AppConfig.TAG, "Manually updating subscription")
            AngConfigManager.updateConfigViaSub(existingSub)
        } else {
            checkAndSetupSubscription(context)
        }
    }

    /**
     * Фильтрует серверы: убирает российские и не поддерживаемые.
     */
    private fun filterServers(allServers: List<String>, excludeGuid: String? = null): List<Pair<String, ProfileItem>> {
        return allServers.mapNotNull { guid ->
            val profile = MmkvManager.decodeServerConfig(guid)
            if (profile?.subscriptionId == SUBSCRIPTION_ID && (excludeGuid == null || guid != excludeGuid)) {
                guid to profile
            } else null
        }.filter { it.second.configType != EConfigType.POLICYGROUP }
            .filter {
                val remarks = it.second.remarks.lowercase()
                !remarks.contains("timeweb") &&
                !remarks.contains("selectel") &&
                !remarks.contains("yandex") &&
                !remarks.contains("aeza") &&
                !remarks.contains("cloud.ru") &&
                !remarks.contains("vk") &&
                !remarks.contains("🇷🇺")
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
                            val config = V2rayConfigManager.getV2rayConfig(context, guid)
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
     * Logic for "Smart Connect" - filter, sort by RealPing, and connect to best.
     */
    suspend fun smartConnect(context: Context) = withContext(Dispatchers.IO) {
        checkAndSetupSubscription(context)
        val allServers = MmkvManager.decodeServerList()
        val servers = filterServers(allServers).shuffled().take(20)

        if (servers.isEmpty()) {
            Log.e(AppConfig.TAG, "No servers found in hardcoded subscription")
            sendStatus(context, context.getString(R.string.status_no_servers))
            return@withContext
        }

        Log.i(AppConfig.TAG, "Starting Smart Connect for ${servers.size} servers (6s limit)")
        sendStatus(context, context.getString(R.string.status_testing_servers))

        val results = testServers(context, servers)
        var best = results.firstOrNull { it.third < Long.MAX_VALUE }

        // Fallback: if no server found in time, just pick the first one from list
        if (best == null && servers.isNotEmpty()) {
            Log.w(AppConfig.TAG, "No servers found within timeout, picking first available")
            best = Triple(servers[0].first, servers[0].second, Long.MAX_VALUE)
        }

        if (best != null) {
            Log.i(AppConfig.TAG, "Smart Connect: Selected ${best.second.remarks} (${best.third}ms)")
            sendStatus(context, context.getString(R.string.status_connecting_to, best.second.remarks))
            MmkvManager.setSelectServer(best.first)

            // Если VPN уже запущен — переключаем ядро, а не пытаемся стартовать заново
            if (V2RayServiceManager.isRunning()) {
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
        val servers = filterServers(allServers, excludeGuid = currentGuid).shuffled().take(20)

        if (servers.isEmpty()) {
            return@withContext
        }

        Log.i(AppConfig.TAG, "Switching server: testing ${servers.size} alternatives (6s limit)")
        sendStatus(context, context.getString(R.string.status_switching_server))
        sendStatus(context, context.getString(R.string.status_testing_servers))

        val results = testServers(context, servers)

        var nextBest = results.firstOrNull { it.third < Long.MAX_VALUE }
        if (nextBest == null && servers.isNotEmpty()) {
            nextBest = Triple(servers[Random.nextInt(servers.size)].first, servers[0].second, Long.MAX_VALUE)
        }

        if (nextBest != null) {
            Log.i(AppConfig.TAG, "Switching to next best server: ${nextBest.second.remarks}")
            sendStatus(context, context.getString(R.string.status_connecting_to, nextBest.second.remarks))
            MmkvManager.setSelectServer(nextBest.first)

            if (V2RayServiceManager.isRunning()) {
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
        }
    }
}
