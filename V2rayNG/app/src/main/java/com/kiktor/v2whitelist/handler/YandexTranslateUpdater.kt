package com.kiktor.v2whitelist.handler

import android.content.Context
import android.util.Log
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.dto.SubscriptionItem
import com.kiktor.v2whitelist.util.MessageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

object YandexTranslateUpdater {

    private const val YANDEX_TRANSLATE_BASE_URL = "https://translate.yandex.ru/translate?url="
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private val PRE_TAG_PATTERN = Pattern.compile("<pre[^>]*>(.*?)</pre>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)

    /**
     * Builds URL for fetching content through Yandex Translate proxy.
     */
    fun buildYandexTranslateUrl(targetUrl: String): String {
        val encoded = URLEncoder.encode(targetUrl, "UTF-8")
        return "$YANDEX_TRANSLATE_BASE_URL$encoded"
    }

    /**
     * Unescapes HTML entities commonly returned by Yandex Translate in pre-formatted text.
     */
    fun unescapeHtml(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&#x2F;", "/")
            .replace("&#47;", "/")
    }

    /**
     * Extracts raw subscription text from Yandex Translate response (handles both <pre> HTML and plain text).
     */
    fun extractSubscriptionContent(response: String): String {
        val matcher = PRE_TAG_PATTERN.matcher(response)
        if (matcher.find()) {
            val rawPre = matcher.group(1).orEmpty()
            return unescapeHtml(rawPre).trim()
        }

        // If response is not an HTML document, return as plain text
        if (!response.contains("<html", ignoreCase = true) && !response.contains("<body", ignoreCase = true)) {
            return unescapeHtml(response).trim()
        }

        // If it contains HTML tags without <pre>, strip tags preserving line breaks
        val withNewlines = response
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n")
            .replace(Regex("(?i)<p[^>]*>"), "")
        val stripped = withNewlines.replace(Regex("<[^>]+>"), "")
        return unescapeHtml(stripped).trim()
    }

    /**
     * Performs direct HTTP GET request to Yandex Translate.
     */
    fun fetchHttp(urlStr: String, timeoutMs: Int = 12000): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.7")
                setRequestProperty("Accept-Language", "ru,en;q=0.9")
            }
            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.w(AppConfig.TAG, "YandexTranslateUpdater: HTTP $responseCode for $urlStr")
                null
            }
        } catch (e: Exception) {
            Log.w(AppConfig.TAG, "YandexTranslateUpdater: connection failed for $urlStr: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private data class SubUpdateTarget(
        val subGuid: String,
        val remarks: String,
        val urls: List<String>,
        val subItem: SubscriptionItem
    )

    /**
     * Updates all active subscriptions via Yandex Translate proxy.
     * All subscriptions run in parallel (concurrently).
     * For each subscription, mirrors are queried sequentially, stopping on the first successful mirror.
     *
     * @param context Application context.
     * @param isDebug True if triggered manually for debugging.
     * @return Total count of successfully imported configurations.
     */
    suspend fun updateAllViaYandex(context: Context, isDebug: Boolean = false): Int = withContext(Dispatchers.IO) {
        Log.i(AppConfig.TAG, "YandexTranslateUpdater: starting update (isDebug=$isDebug)")

        // 1. Снимок кэшей серверов для последующего ремаппинга
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

        // 2. Инициализация и сбор целевых подписок
        SubscriptionHelper.checkAndSetupSubscription(context)

        val targets = mutableListOf<SubUpdateTarget>()

        // Кастомные подписки
        val customSubs = SubscriptionHelper.loadCustomSubs()
        val allSubsInStorage = MmkvManager.decodeSubscriptions()
        for (sub in customSubs.filter { it.enabled }) {
            val subGuid = "custom_sub_${sub.id}"
            val existing = allSubsInStorage.find { it.guid == subGuid }
            val item = existing?.subscription ?: SubscriptionItem().apply {
                remarks = sub.name
                url = sub.url
                filter = sub.filter
                sharePercent = sub.sharePercent
                enabled = true
            }
            item.enabled = true
            item.filter = sub.filter
            item.sharePercent = sub.sharePercent
            MmkvManager.encodeSubscription(subGuid, item)

            val mirrorUrls = sub.url.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            if (mirrorUrls.isNotEmpty()) {
                targets.add(SubUpdateTarget(subGuid, sub.name, mirrorUrls, item))
            }
        }

        // Обычные подписки
        val regularSubs = allSubsInStorage.filter { !it.guid.startsWith("custom_sub_") && it.subscription.enabled }
        for (sub in regularSubs) {
            val mirrorUrls = sub.subscription.url.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            if (mirrorUrls.isNotEmpty()) {
                targets.add(SubUpdateTarget(sub.guid, sub.subscription.remarks, mirrorUrls, sub.subscription))
            }
        }

        if (targets.isEmpty()) {
            Log.w(AppConfig.TAG, "YandexTranslateUpdater: no enabled subscriptions found")
            return@withContext 0
        }

        Log.i(AppConfig.TAG, "YandexTranslateUpdater: processing ${targets.size} subscriptions in parallel")

        // 3. Параллельное обновление подписок; для каждой подписки — последовательный перебор зеркал
        var totalConfigs = 0
        coroutineScope {
            val tasks = targets.map { target ->
                async(Dispatchers.IO) {
                    var success = false
                    var importedCount = 0

                    for (mirror in target.urls) {
                        try {
                            val yandexUrl = buildYandexTranslateUrl(mirror)
                            Log.d(AppConfig.TAG, "YandexTranslateUpdater: querying mirror for '${target.remarks}': $mirror")
                            val responseBody = fetchHttp(yandexUrl) ?: continue
                            val extracted = extractSubscriptionContent(responseBody)
                            if (extracted.isBlank()) {
                                Log.w(AppConfig.TAG, "YandexTranslateUpdater: extracted content is blank for $mirror")
                                continue
                            }

                            val (count, _) = AngConfigManager.importBatchConfig(extracted, target.subGuid, append = false)
                            if (count > 0) {
                                Log.i(AppConfig.TAG, "YandexTranslateUpdater: imported $count configs for '${target.remarks}' via $mirror")
                                target.subItem.lastUpdated = System.currentTimeMillis()
                                target.subItem.lastUpdateFailed = false
                                MmkvManager.encodeSubscription(target.subGuid, target.subItem)
                                success = true
                                importedCount = count
                                break // Прерываем цикл зеркал: первое же успешное зеркало завершает обработку этой подписки!
                            } else {
                                Log.w(AppConfig.TAG, "YandexTranslateUpdater: parsed 0 configs from $mirror")
                            }
                        } catch (e: Exception) {
                            Log.w(AppConfig.TAG, "YandexTranslateUpdater: mirror $mirror failed: ${e.message}")
                        }
                    }

                    if (!success) {
                        Log.e(AppConfig.TAG, "YandexTranslateUpdater: all mirrors failed for '${target.remarks}'")
                        target.subItem.lastUpdateFailed = true
                        MmkvManager.encodeSubscription(target.subGuid, target.subItem)
                    }

                    importedCount
                }
            }

            val results = tasks.awaitAll()
            totalConfigs = results.sum()
        }

        // 4. Ремаппинг кэшей
        if (lastServerIdentity != null || vipIdentities.isNotEmpty()) {
            val updatedServers = MmkvManager.decodeServerList()
            val identityIndex = mutableMapOf<String, String>()
            for (guid in updatedServers) {
                val profile = MmkvManager.decodeServerConfig(guid) ?: continue
                val key = "${profile.server}|${profile.serverPort}|${profile.remarks}"
                if (!identityIndex.containsKey(key)) {
                    identityIndex[key] = guid
                }
            }

            if (lastServerIdentity != null) {
                val key = "${lastServerIdentity.server}|${lastServerIdentity.port}|${lastServerIdentity.remarks}"
                val newGuid = identityIndex[key]
                if (newGuid != null) {
                    MmkvManager.remapLastConnectedServer(newGuid)
                } else {
                    MmkvManager.clearLastConnectedServer()
                }
            }

            if (vipIdentities.isNotEmpty()) {
                val remapped = vipIdentities.mapNotNull { id ->
                    val key = "${id.server}|${id.port}|${id.remarks}"
                    identityIndex[key]
                }
                if (remapped.isNotEmpty()) {
                    MmkvManager.replaceVipCache(remapped)
                } else {
                    MmkvManager.clearVipCache()
                }
            }
        }

        // 5. Подавление алертов при ограниченном доступе
        val allSubs = MmkvManager.decodeSubscriptions()
        val failedSubs = allSubs.filter { it.subscription.enabled && it.subscription.lastUpdateFailed }
        if (failedSubs.isNotEmpty()) {
            val shouldReport = NetworkManager.shouldReportSubscriptionFailure()
            if (!shouldReport) {
                for (sub in failedSubs) {
                    sub.subscription.lastUpdateFailed = false
                    MmkvManager.encodeSubscription(sub.guid, sub.subscription)
                }
            }
        }

        // 6. Оповещение UI об обновлении списка серверов
        MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_RELOAD_SERVER_LIST, "")
        Log.i(AppConfig.TAG, "YandexTranslateUpdater: update completed, total configs imported: $totalConfigs")

        totalConfigs
    }
}
