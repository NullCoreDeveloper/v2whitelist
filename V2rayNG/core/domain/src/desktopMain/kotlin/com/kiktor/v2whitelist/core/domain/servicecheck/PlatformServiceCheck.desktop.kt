package com.kiktor.v2whitelist.core.domain.servicecheck

import com.kiktor.v2whitelist.core.model.currentTimeMillis
import com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckItem
import com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.zip.GZIPInputStream

private val CLAUDE_BLOCKED_REGIONS = setOf("AF", "BY", "CN", "CU", "HK", "IR", "KP", "MO", "RU", "SY")
private val CHATGPT_BLOCKED_REGIONS = setOf("AF", "BY", "CN", "CU", "HK", "IR", "KP", "MO", "RU", "SY")
private val GEMINI_BLOCKED_REGIONS = setOf("AFG", "CHN", "RUS", "BLR", "CUB", "IRN", "PRK", "SYR", "HKG", "MAC")
private val GEMINI_MARKER_REGEX = Regex(""",2,1,200,"([A-Za-z]{3})"""")
private val GEMINI_STATUS_REGEX = Regex(""",2,1,([0-9]+),""")
private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
private const val WHATSAPP_UA = "WhatsApp/2.24.1.6 A"

private val WHATSAPP_HEADERS = mapOf(
    "User-Agent" to WHATSAPP_UA,
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Sec-Fetch-Site" to "none",
    "Sec-Fetch-Mode" to "navigate",
    "Sec-Fetch-Dest" to "document"
)

private data class RawProbeResult(
    val statusCode: Int,
    val latencyMs: Long,
    val body: String = "",
    val location: String? = null,
    val errorMessage: String? = null
)

private fun createProxy(socksPort: Int?, httpPort: Int?, preferSocks: Boolean = false): Proxy {
    return if (preferSocks) {
        when {
            socksPort != null && socksPort > 0 -> Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            httpPort != null && httpPort > 0 -> Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))
            else -> Proxy.NO_PROXY
        }
    } else {
        when {
            httpPort != null && httpPort > 0 -> Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))
            socksPort != null && socksPort > 0 -> Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            else -> Proxy.NO_PROXY
        }
    }
}

private fun executeProbe(
    urlStr: String,
    proxy: Proxy,
    timeoutMs: Long,
    maxBodyBytes: Int = 0,
    headers: Map<String, String> = emptyMap()
): RawProbeResult {
    val start = currentTimeMillis()
    var conn: HttpURLConnection? = null
    return try {
        val url = URI.create(urlStr).toURL()
        conn = (url.openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = timeoutMs.toInt()
            readTimeout = timeoutMs.toInt()
            instanceFollowRedirects = false
            useCaches = false
            requestMethod = "GET"
            setRequestProperty("Connection", "close")
            setRequestProperty("User-Agent", BROWSER_UA)
            setRequestProperty("Accept", "*/*")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.connect()
        val code = conn.responseCode
        val elapsed = currentTimeMillis() - start
        val location = conn.getHeaderField("Location")

        val body = if (maxBodyBytes > 0) {
            val stream = if (code in 200..299) {
                try { conn.inputStream } catch (_: Exception) { null }
            } else {
                try { conn.errorStream } catch (_: Exception) { null }
            }
            if (stream != null) {
                val buf = ByteArray(minOf(maxBodyBytes, 8192))
                val sb = StringBuilder()
                var readTotal = 0
                while (readTotal < maxBodyBytes) {
                    val toRead = minOf(buf.size, maxBodyBytes - readTotal)
                    val r = stream.read(buf, 0, toRead)
                    if (r <= 0) break
                    sb.append(String(buf, 0, r, Charsets.UTF_8))
                    readTotal += r
                }
                sb.toString()
            } else ""
        } else ""

        RawProbeResult(
            statusCode = code,
            latencyMs = elapsed,
            body = body,
            location = location
        )
    } catch (e: Exception) {
        val elapsed = currentTimeMillis() - start
        RawProbeResult(
            statusCode = -1,
            latencyMs = elapsed,
            errorMessage = e.message ?: e::class.simpleName
        )
    } finally {
        conn?.disconnect()
    }
}

private fun checkClaudeSmart(
    service: ServiceCheckItem,
    proxy: Proxy,
    timeoutMs: Long
): ServiceCheckResult {
    // 1. Cloudflare trace (публичный CDN эндпоинт Cloudflare без капчи и Turnstile-челленджей)
    val cfTrace = executeProbe("https://www.cloudflare.com/cdn-cgi/trace", proxy, timeoutMs, maxBodyBytes = 4096)
    val locMatch = Regex("""(?m)^loc=([A-Za-z]{2})$""").find(cfTrace.body)
        ?: Regex("""loc=([A-Za-z]{2})""").find(cfTrace.body)
    val loc = locMatch?.groupValues?.get(1)?.uppercase()

    if (loc != null && loc in CLAUDE_BLOCKED_REGIONS) {
        return ServiceCheckResult(
            service = service,
            success = false,
            latencyMs = -1L,
            httpCode = 403,
            errorMessage = "Регион $loc не поддерживается"
        )
    }

    // 2. API Anthropic: возвращает 401 Unauthorized (x-api-key required) если IP разрешен,
    // либо 403 / permission_error если IP заблокирован/не поддерживается.
    val apiProbe = executeProbe("https://api.anthropic.com/v1/models", proxy, timeoutMs, maxBodyBytes = 1024)
    val apiBody = apiProbe.body.lowercase()
    if (apiBody.contains("not available in your region") || apiBody.contains("permission_error")) {
        return ServiceCheckResult(
            service = service,
            success = false,
            latencyMs = -1L,
            httpCode = apiProbe.statusCode,
            errorMessage = "Anthropic: регион не поддерживается"
        )
    }

    // Если API Anthropic ответил 401 или 200..399 — сервис доступен
    if (apiProbe.statusCode == 401 || apiProbe.statusCode in 200..399) {
        return ServiceCheckResult(
            service = service,
            success = true,
            latencyMs = apiProbe.latencyMs,
            httpCode = apiProbe.statusCode
        )
    }

    // Если сетевая ошибка на api.anthropic.com и на cfTrace
    if (apiProbe.statusCode <= 0 && cfTrace.statusCode <= 0) {
        return ServiceCheckResult(
            service = service,
            success = false,
            latencyMs = -1L,
            httpCode = -1,
            errorMessage = apiProbe.errorMessage ?: cfTrace.errorMessage ?: "Read timed out"
        )
    }

    // 3. Fallback: веб-проверка claude.ai
    val webProbe = executeProbe("https://claude.ai", proxy, timeoutMs, maxBodyBytes = 512)
    val isWebResponding = webProbe.statusCode in 200..399 || (webProbe.statusCode == 403 && loc != null && loc !in CLAUDE_BLOCKED_REGIONS)
    if (isWebResponding) {
        return ServiceCheckResult(
            service = service,
            success = true,
            latencyMs = if (webProbe.latencyMs > 0) webProbe.latencyMs else apiProbe.latencyMs,
            httpCode = webProbe.statusCode
        )
    }

    return ServiceCheckResult(
        service = service,
        success = false,
        latencyMs = -1L,
        httpCode = if (apiProbe.statusCode > 0) apiProbe.statusCode else webProbe.statusCode,
        errorMessage = apiProbe.errorMessage ?: webProbe.errorMessage ?: "Недоступен"
    )
}

private fun checkChatGptSmart(
    service: ServiceCheckItem,
    proxy: Proxy,
    timeoutMs: Long
): ServiceCheckResult {
    // 1. Cloudflare trace для точного определения региона выхода без бот-челленджей
    val cfTrace = executeProbe("https://www.cloudflare.com/cdn-cgi/trace", proxy, timeoutMs, maxBodyBytes = 4096)
    val locMatch = Regex("""(?m)^loc=([A-Za-z]{2})$""").find(cfTrace.body)
        ?: Regex("""loc=([A-Za-z]{2})""").find(cfTrace.body)
    val loc = locMatch?.groupValues?.get(1)?.uppercase()

    if (loc != null && loc in CHATGPT_BLOCKED_REGIONS) {
        return ServiceCheckResult(
            service = service,
            success = false,
            latencyMs = -1L,
            httpCode = 403,
            errorMessage = "Регион $loc не поддерживается"
        )
    }

    // 2. iOS API endpoint для детекта блокировки ISP/IP (как в FlClash)
    val iosProbe = executeProbe("https://ios.chat.openai.com/", proxy, timeoutMs, maxBodyBytes = 2048)
    if (iosProbe.statusCode <= 0 && cfTrace.statusCode <= 0) {
        return ServiceCheckResult(
            service = service,
            success = false,
            latencyMs = -1L,
            httpCode = -1,
            errorMessage = iosProbe.errorMessage ?: cfTrace.errorMessage ?: "Read timed out"
        )
    }

    val iosBody = iosProbe.body.lowercase()
    when {
        iosBody.contains("disallowed isp") -> {
            return ServiceCheckResult(
                service = service,
                success = false,
                latencyMs = -1L,
                httpCode = iosProbe.statusCode,
                errorMessage = "Хостинг заблокирован (Disallowed ISP)"
            )
        }
        iosBody.contains("sorry, you have been blocked") -> {
            return ServiceCheckResult(
                service = service,
                success = false,
                latencyMs = -1L,
                httpCode = iosProbe.statusCode,
                errorMessage = "IP заблокирован OpenAI"
            )
        }
    }

    // 3. Compliance endpoint на unsupported_country
    if (iosProbe.statusCode > 0) {
        val complianceProbe = executeProbe("https://api.openai.com/compliance/cookie_requirements", proxy, timeoutMs, maxBodyBytes = 2048)
        if (complianceProbe.body.lowercase().contains("unsupported_country")) {
            return ServiceCheckResult(
                service = service,
                success = false,
                latencyMs = -1L,
                httpCode = complianceProbe.statusCode,
                errorMessage = "Страна не поддерживается OpenAI"
            )
        }
    }

    val isIosOk = iosProbe.statusCode in 200..399 || iosBody.contains("request is not allowed")
    if (isIosOk) {
        return ServiceCheckResult(
            service = service,
            success = true,
            latencyMs = if (iosProbe.latencyMs > 0) iosProbe.latencyMs else cfTrace.latencyMs,
            httpCode = if (iosProbe.statusCode > 0) iosProbe.statusCode else cfTrace.statusCode
        )
    }

    return ServiceCheckResult(
        service = service,
        success = false,
        latencyMs = -1L,
        httpCode = iosProbe.statusCode,
        errorMessage = iosProbe.errorMessage ?: "Недоступен"
    )
}

private fun checkGeminiSmart(
    service: ServiceCheckItem,
    proxy: Proxy,
    timeoutMs: Long
): ServiceCheckResult {
    return checkGeminiUrl(service, proxy, timeoutMs, "https://gemini.google.com/app")
}

internal fun checkGeminiUrl(
    service: ServiceCheckItem,
    proxy: Proxy,
    timeoutMs: Long,
    initialUrl: String = "https://gemini.google.com/app",
    maxHops: Int = 3
): ServiceCheckResult {
    val start = currentTimeMillis()
    var conn: HttpURLConnection? = null
    return try {
        var targetUrl = initialUrl
        var code = -1

        for (hop in 0 until maxHops) {
            conn?.disconnect()
            val url = URI.create(targetUrl).toURL()
            conn = (url.openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = timeoutMs.toInt()
                readTimeout = timeoutMs.toInt()
                instanceFollowRedirects = false
                useCaches = false
                requestMethod = "GET"
                setRequestProperty("Connection", "close")
                setRequestProperty("User-Agent", BROWSER_UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("Cookie", "SOCS=CAESHAgBEhJnd3NfMjAyMzA4MTAtMF9SQzIaAmVuIAEaBgiAo_CmBg")
            }
            conn.connect()
            code = conn.responseCode
            val loc = conn.getHeaderField("Location")?.trim()

            // Проверяем заглушку Google Captcha / Sorry / Rate Limit
            if (code == 429 || loc?.lowercase()?.contains("/sorry/") == true) {
                return ServiceCheckResult(
                    service = service,
                    success = false,
                    latencyMs = -1L,
                    httpCode = code,
                    errorMessage = "Google Bot-Protection (Captcha)"
                )
            }

            if (code in 300..399) {
                if (loc.isNullOrEmpty()) {
                    return ServiceCheckResult(
                        service = service,
                        success = false,
                        latencyMs = -1L,
                        httpCode = code,
                        errorMessage = "Пустой редирект HTTP $code"
                    )
                }
                val resolved = URI.create(targetUrl).resolve(loc)
                val host = resolved.host?.lowercase().orEmpty()
                if (host != "gemini.google.com" && !host.endsWith(".gemini.google.com") && host != URI.create(initialUrl).host?.lowercase()) {
                    return ServiceCheckResult(
                        service = service,
                        success = false,
                        latencyMs = -1L,
                        httpCode = code,
                        errorMessage = "Редирект на $host (недоступен)"
                    )
                }
                if (resolved.path?.lowercase()?.startsWith("/faq") == true) {
                    return ServiceCheckResult(
                        service = service,
                        success = false,
                        latencyMs = -1L,
                        httpCode = code,
                        errorMessage = "Регион не поддерживается (/faq)"
                    )
                }
                if (hop == maxHops - 1) {
                    return ServiceCheckResult(
                        service = service,
                        success = false,
                        latencyMs = -1L,
                        httpCode = code,
                        errorMessage = "Слишком много редиректов"
                    )
                }
                targetUrl = resolved.toString()
                continue
            }
            break
        }

        if (code !in 200..299) {
            return ServiceCheckResult(
                service = service,
                success = false,
                latencyMs = -1L,
                httpCode = code,
                errorMessage = "HTTP $code"
            )
        }

        val rawStream = conn?.inputStream ?: return ServiceCheckResult(
            service = service,
            success = false,
            latencyMs = -1L,
            httpCode = code,
            errorMessage = "Пустой ответ сервера"
        )
        val isGzip = conn.contentEncoding?.lowercase()?.contains("gzip") == true
        val stream: InputStream = if (isGzip) GZIPInputStream(rawStream) else rawStream

        var foundRegion: String? = null
        var non200Status: String? = null
        var unsupportedText = false

        stream.use { input ->
            val buf = ByteArray(8192)
            val sb = StringBuilder()
            var readTotal = 0
            val maxScan = 2_000_000
            while (readTotal < maxScan) {
                val r = input.read(buf)
                if (r <= 0) break
                val chunk = String(buf, 0, r, Charsets.UTF_8)
                sb.append(chunk)

                val window = if (sb.length > 32768) sb.substring(sb.length - 32768) else sb.toString()
                val lowerWindow = window.lowercase()
                if (lowerWindow.contains("not supported in your country") ||
                    lowerWindow.contains("isn't supported in your country") ||
                    lowerWindow.contains("is not available in your country") ||
                    lowerWindow.contains("isn't available in your country") ||
                    lowerWindow.contains("not available in your region") ||
                    lowerWindow.contains("isn't available in your region") ||
                    lowerWindow.contains("unusual traffic") ||
                    lowerWindow.contains("/sorry/")
                ) {
                    unsupportedText = true
                    break
                }

                val match = GEMINI_MARKER_REGEX.find(window)
                if (match != null) {
                    foundRegion = match.groupValues[1].uppercase()
                    break
                }
                if (non200Status == null) {
                    val statusMatch = GEMINI_STATUS_REGEX.find(window)
                    if (statusMatch != null && statusMatch.groupValues[1] != "200") {
                        non200Status = statusMatch.groupValues[1]
                    }
                }

                if (sb.length > 65536) {
                    sb.delete(0, sb.length - 32768)
                }
                readTotal += r
            }
        }

        val elapsed = currentTimeMillis() - start
        when {
            unsupportedText -> ServiceCheckResult(
                service = service,
                success = false,
                latencyMs = -1L,
                httpCode = code,
                errorMessage = "Регион или IP не поддерживается"
            )
            foundRegion == null -> ServiceCheckResult(
                service = service,
                success = false,
                latencyMs = -1L,
                httpCode = code,
                errorMessage = if (non200Status != null) {
                    "Gemini статус $non200Status"
                } else {
                    "Не удалось подтвердить регион Gemini"
                }
            )
            foundRegion in GEMINI_BLOCKED_REGIONS -> ServiceCheckResult(
                service = service,
                success = false,
                latencyMs = -1L,
                httpCode = code,
                errorMessage = "Регион $foundRegion не поддерживается"
            )
            else -> ServiceCheckResult(
                service = service,
                success = true,
                latencyMs = elapsed,
                httpCode = code
            )
        }
    } catch (e: Exception) {
        ServiceCheckResult(
            service = service,
            success = false,
            latencyMs = -1L,
            httpCode = -1,
            errorMessage = e.message ?: e::class.simpleName
        )
    } finally {
        conn?.disconnect()
    }
}

private fun checkDiscordSmart(
    service: ServiceCheckItem,
    proxy: Proxy,
    timeoutMs: Long
): ServiceCheckResult {
    val probe = executeProbe("https://discord.com/api/v9/auth/location-metadata", proxy, timeoutMs, maxBodyBytes = 1024)
    if (probe.statusCode in 200..399) {
        return ServiceCheckResult(
            service = service,
            success = true,
            latencyMs = probe.latencyMs,
            httpCode = probe.statusCode
        )
    }
    val fallback = executeProbe(service.url, proxy, timeoutMs)
    val success = (fallback.statusCode in 200..399) || (fallback.statusCode in listOf(401, 403, 404, 429))
    val bestCode = if (fallback.statusCode > 0) fallback.statusCode else probe.statusCode
    return ServiceCheckResult(
        service = service,
        success = success,
        latencyMs = if (success) fallback.latencyMs else -1L,
        httpCode = bestCode,
        errorMessage = if (!success) (fallback.errorMessage ?: probe.errorMessage ?: "HTTP $bestCode") else null
    )
}

private fun checkTelegramSmart(
    service: ServiceCheckItem,
    proxy: Proxy,
    timeoutMs: Long
): ServiceCheckResult {
    val candidates = mutableListOf<String>()
    if (service.url.isNotBlank() && service.url != "https://web.telegram.org") {
        candidates.add(service.url)
    }
    candidates.add("https://telegram.org")
    candidates.add("https://api.telegram.org")
    candidates.add("https://t.me")
    if (!candidates.contains(service.url) && service.url.isNotBlank()) {
        candidates.add(service.url)
    }

    val perProbeTimeout = minOf(timeoutMs, 3000L).coerceAtLeast(2000L)
    var lastProbe: RawProbeResult? = null

    for (url in candidates) {
        val probe = executeProbe(url, proxy, perProbeTimeout)
        lastProbe = probe
        val isSuccess = (probe.statusCode in 200..399) || probe.statusCode == 403 || probe.statusCode == 429
        if (isSuccess) {
            return ServiceCheckResult(
                service = service,
                success = true,
                latencyMs = probe.latencyMs,
                httpCode = probe.statusCode
            )
        }
    }

    val code = lastProbe?.statusCode ?: -1
    return ServiceCheckResult(
        service = service,
        success = false,
        latencyMs = -1L,
        httpCode = code,
        errorMessage = lastProbe?.errorMessage ?: "HTTP $code"
    )
}

private fun checkWhatsAppSmart(
    service: ServiceCheckItem,
    proxy: Proxy,
    timeoutMs: Long
): ServiceCheckResult {
    // 1. Проверяем основной URL с нативным WhatsApp User-Agent и W3C Fetch Metadata
    val probe = executeProbe(service.url, proxy, timeoutMs, headers = WHATSAPP_HEADERS)
    if (probe.statusCode in 200..399) {
        return ServiceCheckResult(
            service = service,
            success = true,
            latencyMs = probe.latencyMs,
            httpCode = probe.statusCode
        )
    }

    // 2. Официальный мобильный эндпоинт проверки связности WhatsApp
    val mobileProbe = executeProbe("https://v.whatsapp.net/v2/exist", proxy, timeoutMs, headers = WHATSAPP_HEADERS)
    if (mobileProbe.statusCode in 200..399) {
        return ServiceCheckResult(
            service = service,
            success = true,
            latencyMs = mobileProbe.latencyMs,
            httpCode = mobileProbe.statusCode
        )
    }

    // 3. Fallback на www.whatsapp.com
    val fallback = executeProbe("https://www.whatsapp.com", proxy, timeoutMs, headers = WHATSAPP_HEADERS)
    val success = fallback.statusCode in 200..399
    val bestCode = if (fallback.statusCode > 0) fallback.statusCode else if (mobileProbe.statusCode > 0) mobileProbe.statusCode else probe.statusCode
    return ServiceCheckResult(
        service = service,
        success = success,
        latencyMs = if (success) fallback.latencyMs else -1L,
        httpCode = bestCode,
        errorMessage = if (!success) (fallback.errorMessage ?: mobileProbe.errorMessage ?: probe.errorMessage ?: "HTTP $bestCode") else null
    )
}

private fun checkInstagramSmart(
    service: ServiceCheckItem,
    proxy: Proxy,
    timeoutMs: Long
): ServiceCheckResult {
    val probe = executeProbe(service.url, proxy, timeoutMs)
    if (probe.statusCode in 200..399 || probe.statusCode == 403) {
        return ServiceCheckResult(
            service = service,
            success = true,
            latencyMs = probe.latencyMs,
            httpCode = probe.statusCode
        )
    }
    val fallback = executeProbe("https://www.instagram.com", proxy, timeoutMs)
    val success = (fallback.statusCode in 200..399) || fallback.statusCode == 403
    val bestCode = if (fallback.statusCode > 0) fallback.statusCode else probe.statusCode
    return ServiceCheckResult(
        service = service,
        success = success,
        latencyMs = if (success) fallback.latencyMs else -1L,
        httpCode = bestCode,
        errorMessage = if (!success) (fallback.errorMessage ?: probe.errorMessage ?: "HTTP $bestCode") else null
    )
}

private fun checkGeneric(
    service: ServiceCheckItem,
    proxy: Proxy,
    timeoutMs: Long
): ServiceCheckResult {
    val probe = executeProbe(service.url, proxy, timeoutMs)
    val code = probe.statusCode
    val success = (code in 200..399) || (code in listOf(401, 403, 404, 429))
    return ServiceCheckResult(
        service = service,
        success = success,
        latencyMs = if (success) probe.latencyMs else -1L,
        httpCode = code,
        errorMessage = if (!success) (probe.errorMessage ?: "HTTP $code") else null
    )
}

actual suspend fun rawServiceHttpCheck(
    service: ServiceCheckItem,
    socksPort: Int?,
    httpPort: Int?,
    timeoutMs: Long,
    socksUser: String?,
    socksPass: String?
): ServiceCheckResult = withContext(Dispatchers.IO) {
    val hasAuth = !socksUser.isNullOrEmpty() && !socksPass.isNullOrEmpty()
    if (hasAuth) {
        val ports = setOfNotNull(socksPort, httpPort).filter { it > 0 }.toSet()
        com.kiktor.v2whitelist.core.domain.security.LoopbackProxyAuthenticator.install(
            userProvider = { socksUser },
            passProvider = { socksPass },
            allowedPortsProvider = { ports }
        )
    }
    val proxy = createProxy(socksPort, httpPort, preferSocks = hasAuth)
    if (!service.isCustom) {
        when (service.id.lowercase()) {
            "claude" -> checkClaudeSmart(service, proxy, timeoutMs)
            "chatgpt" -> checkChatGptSmart(service, proxy, timeoutMs)
            "gemini" -> checkGeminiSmart(service, proxy, timeoutMs)
            "discord" -> checkDiscordSmart(service, proxy, timeoutMs)
            "telegram" -> checkTelegramSmart(service, proxy, timeoutMs)
            "whatsapp" -> checkWhatsAppSmart(service, proxy, timeoutMs)
            "instagram" -> checkInstagramSmart(service, proxy, timeoutMs)
            else -> checkGeneric(service, proxy, timeoutMs)
        }
    } else {
        checkGeneric(service, proxy, timeoutMs)
    }
}
