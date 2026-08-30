package com.kiktor.v2whitelist.handler

import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import android.util.Log
import kotlinx.coroutines.*
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.AppConfig.HY2
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.enums.EConfigType
import com.kiktor.v2whitelist.dto.ProfileItem
import com.kiktor.v2whitelist.dto.SubscriptionCache
import com.kiktor.v2whitelist.dto.SubscriptionItem
import com.kiktor.v2whitelist.fmt.CustomFmt
import com.kiktor.v2whitelist.fmt.Hysteria2Fmt
import com.kiktor.v2whitelist.fmt.ShadowsocksFmt
import com.kiktor.v2whitelist.fmt.SocksFmt
import com.kiktor.v2whitelist.fmt.TrojanFmt
import com.kiktor.v2whitelist.fmt.VlessFmt
import com.kiktor.v2whitelist.fmt.VmessFmt
import com.kiktor.v2whitelist.fmt.WireguardFmt
import com.kiktor.v2whitelist.util.HttpUtil
import com.kiktor.v2whitelist.util.JsonUtil
import com.kiktor.v2whitelist.util.QRCodeDecoder
import com.kiktor.v2whitelist.util.Utils
import java.net.URI

object AngConfigManager {


    /**
     * Shares the configuration to the clipboard.
     *
     * @param context The context.
     * @param guid The GUID of the configuration.
     * @return The result code.
     */
    fun share2Clipboard(context: Context, guid: String): Int {
        try {
            val conf = shareConfig(guid)
            if (TextUtils.isEmpty(conf)) {
                return -1
            }

            Utils.setClipboard(context, conf)

        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to share config to clipboard", e)
            return -1
        }
        return 0
    }

    /**
     * Shares non-custom configurations to the clipboard.
     *
     * @param context The context.
     * @param serverList The list of server GUIDs.
     * @return The number of configurations shared.
     */
    fun shareNonCustomConfigsToClipboard(context: Context, serverList: List<String>): Int {
        try {
            val sb = StringBuilder()
            for (guid in serverList) {
                val url = shareConfig(guid)
                if (TextUtils.isEmpty(url)) {
                    continue
                }
                sb.append(url)
                sb.appendLine()
            }
            if (sb.count() > 0) {
                Utils.setClipboard(context, sb.toString())
            }
            return sb.lines().count() - 1
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to share non-custom configs to clipboard", e)
            return -1
        }
    }

    /**
     * Shares the configuration as a QR code.
     *
     * @param guid The GUID of the configuration.
     * @return The QR code bitmap.
     */
    fun share2QRCode(guid: String): Bitmap? {
        try {
            val conf = shareConfig(guid)
            if (TextUtils.isEmpty(conf)) {
                return null
            }
            return QRCodeDecoder.createQRCode(conf)

        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to share config as QR code", e)
            return null
        }
    }

    /**
     * Shares the full content of the configuration to the clipboard.
     *
     * @param context The context.
     * @param guid The GUID of the configuration.
     * @return The result code.
     */
    fun shareFullContent2Clipboard(context: Context, guid: String?): Int {
        try {
            if (guid == null) return -1
            val result = V2rayConfigManager.getV2rayConfig(context, guid)
            if (result.status) {
                Utils.setClipboard(context, result.content)
            } else {
                return -1
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to share full content to clipboard", e)
            return -1
        }
        return 0
    }

    /**
     * Shares the configuration.
     *
     * @param guid The GUID of the configuration.
     * @return The configuration string.
     */
    fun shareConfig(guid: String): String {
        try {
            val config = MmkvManager.decodeServerConfig(guid) ?: return ""

            return config.configType.protocolScheme + when (config.configType) {
                EConfigType.VMESS -> VmessFmt.toUri(config)
                EConfigType.CUSTOM -> ""
                EConfigType.SHADOWSOCKS -> ShadowsocksFmt.toUri(config)
                EConfigType.SOCKS -> SocksFmt.toUri(config)
                EConfigType.HTTP -> ""
                EConfigType.VLESS -> VlessFmt.toUri(config)
                EConfigType.TROJAN -> TrojanFmt.toUri(config)
                EConfigType.WIREGUARD -> WireguardFmt.toUri(config)
                EConfigType.HYSTERIA2 -> Hysteria2Fmt.toUri(config)
                EConfigType.POLICYGROUP -> ""
                else -> ""
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to share config for GUID: $guid", e)
            return ""
        }
    }

    /**
     * Imports a batch of configurations.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return A pair containing the number of configurations and subscriptions imported.
     */
    fun importBatchConfig(server: String?, subid: String, append: Boolean): Pair<Int, Int> {
        var count = parseBatchConfig(Utils.decode(server), subid, append)
        if (count <= 0) {
            count = parseBatchConfig(server, subid, append)
        }
        if (count <= 0) {
            count = parseCustomConfigServer(server, subid)
        }

        var countSub = parseBatchSubscription(server)
        if (countSub <= 0) {
            countSub = parseBatchSubscription(Utils.decode(server))
        }
        if (countSub > 0) {
            updateConfigViaSubAll()
        }

        return count to countSub
    }

    /**
     * Parses a batch of subscriptions.
     *
     * @param servers The servers string.
     * @return The number of subscriptions parsed.
     */
    private fun parseBatchSubscription(servers: String?): Int {
        try {
            if (servers == null) {
                return 0
            }

            var count = 0
            servers.lines()
                .distinct()
                .forEach { str ->
                    if (Utils.isValidSubUrl(str)) {
                        count += importUrlAsSubscription(str)
                    }
                }
            return count
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to parse batch subscription", e)
        }
        return 0
    }

    /**
     * Parses a batch of configurations.
     *
     * @param servers The servers string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private fun parseBatchConfig(servers: String?, subid: String, append: Boolean): Int {
        try {
            if (servers == null) {
                return 0
            }

            val subItem = MmkvManager.decodeSubscription(subid)
            val lines = servers.lines().distinct().reversed()
            val newConfigs = mutableListOf<ProfileItem>()

            for (line in lines) {
                val config = try {
                    identifyConfigType(line) ?: continue
                } catch (e: Exception) {
                    continue
                }
                
                // Применяем фильтр подписки если есть
                if (subItem?.filter != null && subItem.filter?.isNotEmpty() == true && config.remarks.isNotEmpty()) {
                    val matched = Regex(pattern = subItem.filter ?: "")
                        .containsMatchIn(input = config.remarks)
                    if (!matched) continue
                }
                newConfigs.add(config)
            }

            // Если не удалось распарсить ни одного конфига — не трогаем старые!
            if (newConfigs.isEmpty() && !append) {
                Log.w(AppConfig.TAG, "No valid configs found in subscription update for $subid, keeping old configs.")
                return 0
            }

            val removedSelectedServer =
                if (!TextUtils.isEmpty(subid) && !append) {
                    MmkvManager.decodeServerConfig(
                        MmkvManager.getSelectServer().orEmpty()
                    )?.let {
                        if (it.subscriptionId == subid) {
                            return@let it
                        }
                        return@let null
                    }
                } else {
                    null
                }

            if (!append) {
                MmkvManager.removeServerViaSubid(subid)
            }

            var count = 0
            val processedConfigs = mutableListOf<ProfileItem>()
            for (config in newConfigs) {
                config.subscriptionId = subid
                config.description = generateDescription(config)
                processedConfigs.add(config)
            }
            
            val guids = MmkvManager.encodeServerConfigs(processedConfigs)
            for (i in processedConfigs.indices) {
                val guid = guids[i]
                val config = processedConfigs[i]
                // Восстановление выбора сервера
                if (removedSelectedServer != null &&
                    config.server == removedSelectedServer.server &&
                    config.serverPort == removedSelectedServer.serverPort &&
                    config.remarks == removedSelectedServer.remarks
                ) {
                    MmkvManager.setSelectServer(guid)
                }
                count++
            }
            return count
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to parse batch config", e)
        }
        return 0
    }

    fun identifyConfigType(str: String?): ProfileItem? {
        if (str == null || TextUtils.isEmpty(str)) return null
        return if (str.startsWith(EConfigType.VMESS.protocolScheme)) {
            VmessFmt.parse(str)
        } else if (str.startsWith(EConfigType.SHADOWSOCKS.protocolScheme)) {
            ShadowsocksFmt.parse(str)
        } else if (str.startsWith(EConfigType.SOCKS.protocolScheme)) {
            SocksFmt.parse(str)
        } else if (str.startsWith(EConfigType.TROJAN.protocolScheme)) {
            TrojanFmt.parse(str)
        } else if (str.startsWith(EConfigType.VLESS.protocolScheme)) {
            VlessFmt.parse(str)
        } else if (str.startsWith(EConfigType.WIREGUARD.protocolScheme)) {
            WireguardFmt.parse(str)
        } else if (str.startsWith(EConfigType.HYSTERIA2.protocolScheme) || str.startsWith(HY2)) {
            Hysteria2Fmt.parse(str)
        } else {
            null
        }
    }

    /**
     * Parses a custom configuration server.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @return The number of configurations parsed.
     */
    private fun parseCustomConfigServer(server: String?, subid: String): Int {
        if (server == null) {
            return 0
        }
        if (server.contains("inbounds")
            && server.contains("outbounds")
            && server.contains("routing")
        ) {
            try {
                val serverList: Array<Any> =
                    JsonUtil.fromJson(server, Array<Any>::class.java) ?: arrayOf()

                if (serverList.isNotEmpty()) {
                    var count = 0
                    for (srv in serverList.reversed()) {
                        val config = CustomFmt.parse(JsonUtil.toJson(srv)) ?: continue
                        config.subscriptionId = subid
                        config.description = generateDescription(config)
                        val key = MmkvManager.encodeServerConfig("", config)
                        MmkvManager.encodeServerRaw(key, JsonUtil.toJsonPretty(srv) ?: "")
                        count += 1
                    }
                    return count
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to parse custom config server JSON array", e)
            }

            try {
                // For compatibility
                val config = CustomFmt.parse(server) ?: return 0
                config.subscriptionId = subid
                config.description = generateDescription(config)
                val key = MmkvManager.encodeServerConfig("", config)
                MmkvManager.encodeServerRaw(key, server)
                return 1
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to parse custom config server as single config", e)
            }
            return 0
        } else if (server.startsWith("[Interface]") && server.contains("[Peer]")) {
            try {
                val config = WireguardFmt.parseWireguardConfFile(server) ?: return R.string.toast_incorrect_protocol
                config.description = generateDescription(config)
                val key = MmkvManager.encodeServerConfig("", config)
                MmkvManager.encodeServerRaw(key, server)
                return 1
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to parse WireGuard config file", e)
            }
            return 0
        } else {
            return 0
        }
    }

    /**
     * Parses the configuration from a QR code or string.
     *
     * @param str The configuration string.
     * @param subid The subscription ID.
     * @param subItem The subscription item.
     * @param removedSelectedServer The removed selected server.
     * @return The result code.
     */
    private fun parseConfig(
        str: String?,
        subid: String,
        subItem: SubscriptionItem?,
        removedSelectedServer: ProfileItem?
    ): Int {
        try {
            val config = identifyConfigType(str) ?: return R.string.toast_incorrect_protocol

            //filter
            if (subItem?.filter != null && subItem.filter?.isNotEmpty() == true && config.remarks.isNotEmpty()) {
                val matched = Regex(pattern = subItem.filter ?: "")
                    .containsMatchIn(input = config.remarks)
                if (!matched) return -1
            }

            config.subscriptionId = subid
            config.description = generateDescription(config)
            val guid = MmkvManager.encodeServerConfig("", config)
            if (removedSelectedServer != null &&
                config.server == removedSelectedServer.server &&
                config.serverPort == removedSelectedServer.serverPort &&
                config.remarks == removedSelectedServer.remarks
            ) {
                MmkvManager.setSelectServer(guid)
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to parse config", e)
            return -1
        }
        return 0
    }

    /**
     * Updates the configuration via all subscriptions.
     *
     * @return The number of configurations updated.
     */
    fun updateConfigViaSubAll(): Int {
        var count = 0
        try {
            MmkvManager.decodeSubscriptions().forEach {
                count += updateConfigViaSub(it)
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to update config via all subscriptions", e)
            return 0
        }
        return count
    }

    /**
     * Updates the configuration via a subscription.
     *
     * @param it The subscription item.
     * @param socksPort SOCKS5 порт для загрузки через VPN (0 = не использовать).
     * @param sequential Если true — последовательный перебор зеркал (для фонового воркера).
     *                   Если false — параллельная гонка зеркал (для UI, когда пользователь ждёт).
     * @return The number of configurations updated.
     */
    fun updateConfigViaSub(it: SubscriptionCache, socksPort: Int = 0, sequential: Boolean = false): Int {
        try {
            if (TextUtils.isEmpty(it.guid) || TextUtils.isEmpty(it.subscription.remarks) || TextUtils.isEmpty(it.subscription.url)) {
                return 0
            }
            if (!it.subscription.enabled) {
                return 0
            }
            
            val urls = it.subscription.url.split("|").map { url -> url.trim() }.filter { url -> url.isNotEmpty() }
            if (urls.isEmpty()) return 0
            
            Log.i(AppConfig.TAG, "Updating sub '${it.subscription.remarks}' via ${urls.size} mirrors (sequential=$sequential)")
            val userAgent = it.subscription.userAgent
            val httpPort = if (socksPort > 0) SettingsManager.getHttpPort() else 0
            var configText = ""

            if (sequential) {
                // ── Последовательный режим (фоновый воркер) ──────────────────────────────
                // Перебираем зеркала по одному: нет GlobalScope, нет Channel, нет лишних потоков.
                // Первое успешное зеркало — останавливаемся. Дёшево по RAM и CPU.
                kotlinx.coroutines.runBlocking {
                    for (singleUrl in urls) {
                        try {
                            val urlFixed = HttpUtil.toIdnUrl(singleUrl)
                            if (!Utils.isValidUrl(urlFixed)) {
                                if (!it.subscription.allowInsecureUrl && !Utils.isValidSubUrl(urlFixed)) continue
                            }
                            var result = ""
                            // 1. HTTP Proxy
                            if (httpPort > 0) {
                                try { result = HttpUtil.getUrlContentWithUserAgent(urlFixed, userAgent, 8000, httpPort) } catch (_: Exception) {}
                            }
                            // 2. SOCKS5 Proxy
                            if (result.isEmpty() && socksPort > 0) {
                                try { result = HttpUtil.getUrlContentViaSocks(urlFixed, userAgent, 6000, socksPort) } catch (_: Exception) {}
                            }
                            // 3. Direct
                            if (result.isEmpty()) {
                                try { result = HttpUtil.getUrlContentWithUserAgent(urlFixed, userAgent, 6000) } catch (_: Exception) {}
                            }
                            if (result.isNotEmpty()) {
                                Log.i(AppConfig.TAG, "Sequential mirror succeeded: $singleUrl")
                                configText = result
                                return@runBlocking // нашли — выходим сразу
                            }
                            Log.d(AppConfig.TAG, "Sequential mirror failed: $singleUrl")
                        } catch (e: Exception) {
                            Log.d(AppConfig.TAG, "Sequential mirror error: $singleUrl — ${e.message}")
                        }
                    }
                }
            } else {
                // ── Параллельная гонка (UI, ручное обновление) ───────────────────────────
                // Все зеркала стартуют одновременно, побеждает первое успешное.
                // Быстро, но ресурсоёмко — не для фонового воркера.
                kotlinx.coroutines.runBlocking {
                    val channel = kotlinx.coroutines.channels.Channel<String>(urls.size)
                    var activeJobs = urls.size
                    val lock = Any()
                    
                    val jobs = urls.map { singleUrl ->
                        @OptIn(DelicateCoroutinesApi::class)
                        GlobalScope.launch(Dispatchers.IO) {
                            var result = ""
                            try {
                                val urlFixed = HttpUtil.toIdnUrl(singleUrl)
                                if (!Utils.isValidUrl(urlFixed)) {
                                    if (!it.subscription.allowInsecureUrl && !Utils.isValidSubUrl(urlFixed)) return@launch
                                }
                                // 1. HTTP Proxy
                                if (httpPort > 0) {
                                    try { result = HttpUtil.getUrlContentWithUserAgent(urlFixed, userAgent, 6000, httpPort) } catch (_: Exception) {}
                                }
                                // 2. SOCKS5 Proxy
                                if (result.isEmpty() && socksPort > 0) {
                                    try { result = HttpUtil.getUrlContentViaSocks(urlFixed, userAgent, 4000, socksPort) } catch (_: Exception) {}
                                }
                                // 3. Direct
                                if (result.isEmpty()) {
                                    try { result = HttpUtil.getUrlContentWithUserAgent(urlFixed, userAgent, 4000) } catch (_: Exception) {}
                                }
                                if (result.isNotEmpty()) {
                                    Log.i(AppConfig.TAG, "Mirror WON the race: $singleUrl")
                                    channel.trySend(result)
                                }
                            } catch (e: Exception) {
                                Log.d(AppConfig.TAG, "Mirror failed: $singleUrl")
                            } finally {
                                synchronized(lock) {
                                    activeJobs--
                                    // Когда все зеркала закончили — сигнализируем пустой строкой
                                    if (activeJobs == 0) channel.trySend("")
                                }
                            }
                        }
                    }
                    
                    // Таймаут 30с на подписку: защита от TCP-зависания зеркал без ответа.
                    // Без него channel.receive() мог висеть вечно если один GlobalScope.launch завис
                    // (TCP handshake без ACK) — Android убивал WorkManager воркер по таймауту → FAILED.
                    configText = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                        channel.receive()
                    } ?: ""
                    
                    // Отменяем все оставшиеся загрузки зеркал
                    jobs.forEach { job -> job.cancel() }
                }
            }

            if (configText.isEmpty()) {
                Log.w(AppConfig.TAG, "Update subscription: all mirrors failed for ${it.subscription.remarks}")
                return 0
            }
            
            val count = parseConfigViaSub(configText, it.guid, false)
            if (count > 0) {
                it.subscription.lastUpdated = System.currentTimeMillis()
                MmkvManager.encodeSubscription(it.guid, it.subscription)
                MmkvManager.removeDuplicateServer() // Авто-очистка дубликатов
                Log.i(AppConfig.TAG, "Subscription updated: ${it.subscription.remarks}, $count configs (duplicates cleaned)")
            }
            return count
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to update config via subscription", e)
            return 0
        }
    }

    /**
     * Parses the configuration via a subscription.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private fun parseConfigViaSub(server: String?, subid: String, append: Boolean): Int {
        val cleanServer = server?.lines()?.filter { !it.trimStart().startsWith("#") }?.joinToString("\n")
        var count = parseBatchConfig(Utils.decode(cleanServer), subid, append)
        if (count <= 0) {
            count = parseBatchConfig(cleanServer, subid, append)
        }
        if (count <= 0) {
            count = parseCustomConfigServer(cleanServer, subid)
        }
        return count
    }

    /**
     * Imports a URL as a subscription.
     *
     * @param url The URL.
     * @return The number of subscriptions imported.
     */
    private fun importUrlAsSubscription(url: String): Int {
        val subscriptions = MmkvManager.decodeSubscriptions()
        for (it in subscriptions) {
            if (it.subscription.url == url) {
                return 0
            }
        }
        val uri = URI(Utils.fixIllegalUrl(url))
        val subItem = SubscriptionItem()
        subItem.remarks = uri.fragment ?: "import sub"
        subItem.url = url
        MmkvManager.encodeSubscription("", subItem)
        return 1
    }

    /** Generates a description for the profile.
     *
     * @param profile The profile item.
     * @return The generated description.
     */
    fun generateDescription(profile: ProfileItem): String {
        // Hide xxx:xxx:***/xxx.xxx.xxx.***
        val server = profile.server
        val port = profile.serverPort
        if (server.isNullOrBlank() && port.isNullOrBlank()) return ""

        val addrPart = server?.let {
            if (it.contains(":"))
                it.split(":").take(2).joinToString(":", postfix = ":***")
            else
                it.split('.').dropLast(1).joinToString(".", postfix = ".***")
        } ?: ""

        return "$addrPart : ${port ?: ""}"
    }
}
