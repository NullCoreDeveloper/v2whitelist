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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

object YandexTranslateUpdater {

    private const val YANDEX_TRANSLATE_BASE_URL = "https://translate.yandex.ru/translate?url="
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
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
     * Checks if the response is a Yandex SmartCaptcha challenge or bot blocking page.
     */
    fun isCaptchaResponse(response: String): Boolean {
        val lower = response.lowercase()
        return lower.contains("smartcaptcha") ||
                lower.contains("captcha.yandex") ||
                lower.contains("/captcha") ||
                lower.contains("showcaptcha") ||
                (lower.contains("<title>") && lower.contains("captcha"))
    }

    data class FetchResult(
        val body: String?,
        val isCaptcha: Boolean,
        val captchaUrl: String? = null
    )

    /**
     * Performs direct HTTP GET request to Yandex Translate with cookie persistence and captcha detection.
     */
    fun fetchHttp(urlStr: String, timeoutMs: Int = 7000): FetchResult {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                setRequestProperty("Accept-Encoding", "gzip, deflate")
                setRequestProperty("Sec-Ch-Ua", "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"")
                setRequestProperty("Sec-Ch-Ua-Mobile", "?1")
                setRequestProperty("Sec-Ch-Ua-Platform", "\"Android\"")
                setRequestProperty("Sec-Fetch-Dest", "document")
                setRequestProperty("Sec-Fetch-Mode", "navigate")
                setRequestProperty("Sec-Fetch-Site", "none")
                setRequestProperty("Sec-Fetch-User", "?1")
                setRequestProperty("Upgrade-Insecure-Requests", "1")

                val savedCookies = MmkvManager.decodeSettingsString(AppConfig.PREF_YANDEX_COOKIES, "").orEmpty()
                if (savedCookies.isNotBlank()) {
                    setRequestProperty("Cookie", savedCookies)
                }
            }

            val finalUrl = connection.url.toString()
            val responseCode = connection.responseCode

            // Сохраняем новые Set-Cookie
            val setCookieHeaders = connection.headerFields["Set-Cookie"]
            if (!setCookieHeaders.isNullOrEmpty()) {
                val newCookies = setCookieHeaders.joinToString("; ") { it.substringBefore(';') }
                YandexCaptchaSolver.saveCookies(newCookies)
            }

            if (finalUrl.contains("showcaptcha") || finalUrl.contains("captcha.yandex")) {
                Log.w(AppConfig.TAG, "YandexTranslateUpdater: redirected to captcha URL: $finalUrl")
                GeekModeLogger.log("YandexTranslate", "⚠️ Редирект на капчу: $finalUrl")
                return FetchResult(body = null, isCaptcha = true, captchaUrl = finalUrl)
            }

            fun readStream(stream: java.io.InputStream?): String? {
                if (stream == null) return null
                val isGzip = "gzip".equals(connection.contentEncoding, ignoreCase = true)
                val inStream = if (isGzip) java.util.zip.GZIPInputStream(stream) else stream
                return inStream.bufferedReader().use { it.readText() }
            }

            if (responseCode in 200..299) {
                val body = readStream(connection.inputStream).orEmpty()
                if (isCaptchaResponse(body)) {
                    Log.w(AppConfig.TAG, "YandexTranslateUpdater: response body contains captcha challenge")
                    GeekModeLogger.log("YandexTranslate", "⚠️ Ответ содержит экран SmartCaptcha ($responseCode)")
                    return FetchResult(body = body, isCaptcha = true, captchaUrl = finalUrl)
                }
                FetchResult(body = body, isCaptcha = false)
            } else if (responseCode == 403) {
                val errBody = try {
                    readStream(connection.errorStream)
                } catch (e: Exception) { null }
                val isCaptcha = errBody?.let { isCaptchaResponse(it) } ?: true
                Log.w(AppConfig.TAG, "YandexTranslateUpdater: HTTP 403 (isCaptcha=$isCaptcha)")
                GeekModeLogger.log("YandexTranslate", "⚠️ HTTP 403 (капча/блок: $isCaptcha)")
                FetchResult(body = errBody, isCaptcha = isCaptcha, captchaUrl = finalUrl)
            } else {
                Log.w(AppConfig.TAG, "YandexTranslateUpdater: HTTP $responseCode for $urlStr")
                GeekModeLogger.log("YandexTranslate", "HTTP $responseCode для $urlStr")
                FetchResult(body = null, isCaptcha = false)
            }
        } catch (e: Exception) {
            Log.w(AppConfig.TAG, "YandexTranslateUpdater: connection failed for $urlStr: ${e.message}")
            GeekModeLogger.log("YandexTranslate", "Ошибка подключения к $urlStr: ${e.message}")
            FetchResult(body = null, isCaptcha = false)
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

    private suspend fun processTarget(
        target: SubUpdateTarget,
        context: Context,
        isRetryPass: Boolean = false,
        subIndex: Int = 1,
        totalSubs: Int = 1,
        sequential: Boolean = false
    ): Pair<Boolean, Int> {
        var success = false
        var importedCount = 0
        var hadCaptcha = false

        val prefix = if (isRetryPass) "Повтор [$subIndex/$totalSubs]" else "[$subIndex/$totalSubs]"
        GeekModeLogger.log("YandexTranslate", "► $prefix '${target.remarks}' (${target.urls.size} зеркал${if (isRetryPass) ", повторный проход" else ""})")
        MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE, "$prefix: ${target.remarks}")

        for ((index, mirror) in target.urls.withIndex()) {
            try {
                val yandexUrl = buildYandexTranslateUrl(mirror)
                Log.d(AppConfig.TAG, "YandexTranslateUpdater: [${if (isRetryPass) "RETRY" else "PASS1"}] querying mirror for '${target.remarks}': $mirror")
                GeekModeLogger.log("YandexTranslate", "  → $prefix Зеркало ${index + 1}/${target.urls.size}: $mirror")
                MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE, "$prefix ${target.remarks} (${index + 1}/${target.urls.size})")

                var result = fetchHttp(yandexUrl, timeoutMs = 7000)

                if (result.isCaptcha) {
                    Log.w(AppConfig.TAG, "YandexTranslateUpdater: SmartCaptcha detected for '${target.remarks}', invoking solver...")
                    GeekModeLogger.log("YandexTranslate", "  ⚠️ $prefix Обнаружена SmartCaptcha! Запуск решения...")
                    MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE, "$prefix: проверка капчи…")
                    hadCaptcha = true
                    val solved = YandexCaptchaSolver.solve(context, result.captchaUrl ?: yandexUrl, allowInteractive = !sequential)
                    if (solved) {
                        Log.i(AppConfig.TAG, "YandexTranslateUpdater: Captcha solved! Retrying mirror $mirror...")
                        GeekModeLogger.log("YandexTranslate", "  ✅ $prefix Капча пройдена! Повторный запрос...")
                        result = fetchHttp(yandexUrl, timeoutMs = 7000)
                    } else {
                        Log.w(AppConfig.TAG, "YandexTranslateUpdater: Captcha was not solved, skipping mirror")
                        GeekModeLogger.log("YandexTranslate", "  ❌ $prefix Капча не решена, пропуск зеркала")
                        continue
                    }
                }

                if (!result.isCaptcha && !result.body.isNullOrBlank()) {
                    val extracted = extractSubscriptionContent(result.body)
                    if (extracted.isNotBlank()) {
                        val (count, _) = AngConfigManager.importBatchConfig(extracted, target.subGuid, append = false)
                        if (count > 0) {
                            Log.i(AppConfig.TAG, "YandexTranslateUpdater: successfully imported $count configs for '${target.remarks}' via $mirror")
                            GeekModeLogger.log("YandexTranslate", "  ✅ $prefix Успешно импортировано: $count серверов")
                            MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE, "$prefix ${target.remarks}: +$count")
                            target.subItem.lastUpdated = System.currentTimeMillis()
                            target.subItem.lastUpdateFailed = false
                            MmkvManager.encodeSubscription(target.subGuid, target.subItem)
                            success = true
                            importedCount = count
                            break // Успех: прерываем перебор зеркал для этой подписки
                        } else {
                            Log.w(AppConfig.TAG, "YandexTranslateUpdater: parsed 0 configs from $mirror")
                            GeekModeLogger.log("YandexTranslate", "  ⚠️ $prefix 0 серверов распознано из ответа")
                        }
                    } else {
                        Log.w(AppConfig.TAG, "YandexTranslateUpdater: extracted content is blank for $mirror")
                        GeekModeLogger.log("YandexTranslate", "  ⚠️ $prefix Текст подписки пуст после декодирования")
                    }
                }
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "YandexTranslateUpdater: mirror $mirror failed: ${e.message}")
                GeekModeLogger.log("YandexTranslate", "  ❌ $prefix Ошибка зеркала: ${e.message}")
            }
            delay(if (sequential) 300L else 150L)
        }

        if (!success && !isRetryPass && !hadCaptcha) {
            Log.w(AppConfig.TAG, "YandexTranslateUpdater: all mirrors failed for '${target.remarks}' on initial pass")
            GeekModeLogger.log("YandexTranslate", "  ⚠️ $prefix Все зеркала не ответили на первом проходе")
        }

        return Pair(success, importedCount)
    }

    /**
     * Updates all active subscriptions via Yandex Translate proxy.
     * In interactive mode runs concurrently (2 workers); in sequential mode runs 1 worker at a time.
     * Includes automatic SmartCaptcha resolution and retry pass for failed subscriptions.
     *
     * @param context Application or Activity context.
     * @param isDebug True if triggered manually for debugging.
     * @param sequential If true, process strictly one subscription at a time and avoid interactive UI popups.
     * @return Total count of successfully imported configurations.
     */
    suspend fun updateAllViaYandex(
        context: Context,
        isDebug: Boolean = false,
        sequential: Boolean = false
    ): Int = withContext(Dispatchers.IO) {
        val modeStr = if (sequential) "последовательный режим, 1 поток" else "2 потока"
        Log.i(AppConfig.TAG, "YandexTranslateUpdater: starting update (isDebug=$isDebug, sequential=$sequential)")

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
            GeekModeLogger.log("YandexTranslate", "⚠️ Нет включенных подписок для обновления")
            return@withContext 0
        }

        Log.i(AppConfig.TAG, "YandexTranslateUpdater: processing ${targets.size} subscriptions ($modeStr)")
        GeekModeLogger.log("YandexTranslate", "🚀 Запуск обновления подписок через Яндекс (${targets.size} подписок, $modeStr)")
        MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE, "Обновление через Яндекс (${targets.size} подписок)...")

        var totalConfigs = 0
        val concurrency = if (sequential) 1 else 2
        val semaphore = Semaphore(concurrency)
        val failedTargets = mutableListOf<SubUpdateTarget>()

        // 3. Первый проход
        coroutineScope {
            val tasks = targets.mapIndexed { idx, target ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val (success, count) = processTarget(
                            target = target,
                            context = context,
                            isRetryPass = false,
                            subIndex = idx + 1,
                            totalSubs = targets.size,
                            sequential = sequential
                        )
                        if (!success) {
                            synchronized(failedTargets) {
                                failedTargets.add(target)
                            }
                        }
                        count
                    }
                }
            }

            val results = tasks.awaitAll()
            totalConfigs = results.sum()
        }

        // 4. Повторный проход для подписок, у которых не удалось получить серверы (retry pass)
        if (failedTargets.isNotEmpty()) {
            Log.i(AppConfig.TAG, "YandexTranslateUpdater: ${failedTargets.size} subscriptions failed, starting retry pass after delay...")
            GeekModeLogger.log("YandexTranslate", "🔄 Запуск повторного прохода для ${failedTargets.size} подписок...")
            MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE, "Повторный опрос ${failedTargets.size} подписок...")
            delay(if (sequential) 2000L else 1500L)

            val stillFailed = mutableListOf<SubUpdateTarget>()
            coroutineScope {
                val retryTasks = failedTargets.mapIndexed { rIdx, target ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val (success, count) = processTarget(
                                target = target,
                                context = context,
                                isRetryPass = true,
                                subIndex = rIdx + 1,
                                totalSubs = failedTargets.size,
                                sequential = sequential
                            )
                            if (!success) {
                                synchronized(stillFailed) {
                                    stillFailed.add(target)
                                }
                            }
                            count
                        }
                    }
                }
                val retryResults = retryTasks.awaitAll()
                totalConfigs += retryResults.sum()
            }

            for (target in stillFailed) {
                Log.e(AppConfig.TAG, "YandexTranslateUpdater: all mirrors failed after retry for '${target.remarks}'")
                GeekModeLogger.log("YandexTranslate", "❌ [${target.remarks}] Не удалось обновить ни с одного зеркала")
                target.subItem.lastUpdateFailed = true
                MmkvManager.encodeSubscription(target.subGuid, target.subItem)
            }
        }

        // 5. Ремаппинг кэшей
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

        // 6. Подавление алертов при ограниченном доступе
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

        // 7. Оповещение UI об обновлении списка серверов
        MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_RELOAD_SERVER_LIST, "")
        Log.i(AppConfig.TAG, "YandexTranslateUpdater: update completed, total configs imported: $totalConfigs")
        GeekModeLogger.log("YandexTranslate", "🏁 Обновление завершено! Всего импортировано конфигураций: $totalConfigs")
        MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE, "Обновлено (+$totalConfigs серверов)")

        totalConfigs
    }
}
