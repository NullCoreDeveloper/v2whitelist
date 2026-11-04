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

private val CLAUDE_BLOCKED_REGIONS = setOf("AF", "BY", "CN", "CU", "HK", "IR", "KP", "MO", "RU", "SY")
private val CHATGPT_BLOCKED_REGIONS = setOf("AF", "BY", "CN", "CU", "HK", "IR", "KP", "MO", "RU", "SY")
private val GEMINI_BLOCKED_REGIONS = setOf("CHN", "RUS", "BLR", "CUB", "IRN", "PRK", "SYR", "HKG", "MAC")
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

private fun createProxy(socksPort: Int?, httpPort: Int?): Proxy {
    return when {
        httpPort != null && httpPort > 0 -> Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))
        socksPort != null && socksPort > 0 -> Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        else -> Proxy.NO_PROXY
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
    val start = currentTimeMillis()
    var conn: HttpURLConnection? = null
    return try {
        val url = URI.create("https://gemini.google.com").toURL()
        conn = (url.openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = timeoutMs.toInt()
            readTimeout = timeoutMs.toInt()
            instanceFollowRedirects = false
            useCaches = false
            requestMethod = "GET"
            setRequestProperty("Connection", "close")
            setRequestProperty("User-Agent", BROWSER_UA)
            setRequestProperty("Accept", "*/*")
        }
        conn.connect()
        var code = conn.responseCode
        val loc = conn.getHeaderField("Location")

        // Проверяем заглушку Google Captcha / Sorry / Rate Limit
        if (loc?.contains("/sorry/") == true || code == 429) {
            return ServiceCheckResult(
                service = service,
                success = false,
                latencyMs = -1L,
                httpCode = code,
                errorMessage = "Google Bot-Protection (Captcha)"
            )
        }

        // Если 301/302 редирект внутри gemini.google.com
        if ((code == 301 || code == 302) && loc != null && loc.contains("gemini.google.com")) {
            conn.disconnect()
            conn = (URI.create(loc).toURL().openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = timeoutMs.toInt()
                readTimeout = timeoutMs.toInt()
                instanceFollowRedirects = false
                useCaches = false
                requestMethod = "GET"
                setRequestProperty("Connection", "close")
                setRequestProperty("User-Agent", BROWSER_UA)
                setRequestProperty("Accept", "*/*")
            }
            conn.connect()
            code = conn.responseCode
            val newLoc = conn.getHeaderField("Location")
            if (newLoc?.contains("/sorry/") == true || code == 429) {
                return ServiceCheckResult(
                    service = service,
                    success = false,
                    latencyMs = -1L,
                    httpCode = code,
                    errorMessage = "Google Bot-Protection (Captcha)"
                )
            }
        }

        if (code in 200..399) {
            val stream = try { conn.inputStream } catch (_: Exception) { null }
            var foundRegion: String? = null
            var unsupportedText = false

            if (stream != null) {
                try {
                    val marker = ",2,1,200,\""
                    val buf = ByteArray(8192)
                    val sb = StringBuilder()
                    var readTotal = 0
                    val maxScan = 1_000_000 // до 1 МБ
                    while (readTotal < maxScan) {
                        val r = stream.read(buf)
                        if (r <= 0) break
                        val chunk = String(buf, 0, r, Charsets.UTF_8)
                        sb.append(chunk)
                        val idx = sb.indexOf(marker)
                        if (idx != -1 && sb.length >= idx + marker.length + 3) {
                            foundRegion = sb.substring(idx + marker.length, idx + marker.length + 3).uppercase()
                            break
                        }
                        val lowerChunk = chunk.lowercase()
                        if (lowerChunk.contains("not supported in your country") ||
                            lowerChunk.contains("is not available in your country")) {
                            unsupportedText = true
                            break
                        }
                        if (sb.length > 32768) {
                            sb.delete(0, 16384)
                        }
                        readTotal += r
                    }
                } catch (_: Exception) {
                    // Таймаут дочитывания длинного тела на медленном узле — не отменяет факт ответа сервера
                }
            }

            val elapsed = currentTimeMillis() - start
            when {
                unsupportedText -> {
                    ServiceCheckResult(
                        service = service,
                        success = false,
                        latencyMs = -1L,
                        httpCode = code,
                        errorMessage = "Регион не поддерживается"
                    )
                }
                foundRegion != null && foundRegion in GEMINI_BLOCKED_REGIONS -> {
                    ServiceCheckResult(
                        service = service,
                        success = false,
                        latencyMs = -1L,
                        httpCode = code,
                        errorMessage = "Регион $foundRegion не поддерживается"
                    )
                }
                else -> {
                    ServiceCheckResult(
                        service = service,
                        success = true,
                        latencyMs = elapsed,
                        httpCode = code
                    )
                }
            }
        } else {
            ServiceCheckResult(
                service = service,
                success = false,
                latencyMs = -1L,
                httpCode = code,
                errorMessage = "HTTP $code"
            )
        }
    } catch (e: Exception) {
        val elapsed = currentTimeMillis() - start
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
    // Если ошибка сетевая/таймаут — не тратим время на повторный таймаут
    if (probe.statusCode <= 0) {
        return ServiceCheckResult(
            service = service,
            success = false,
            latencyMs = -1L,
            httpCode = -1,
            errorMessage = probe.errorMessage ?: "Read timed out"
        )
    }
    val fallback = executeProbe(service.url, proxy, timeoutMs)
    val success = (fallback.statusCode in 200..399) || (fallback.statusCode in listOf(401, 403, 404, 429))
    return ServiceCheckResult(
        service = service,
        success = success,
        latencyMs = if (success) fallback.latencyMs else -1L,
        httpCode = fallback.statusCode,
        errorMessage = if (!success) (fallback.errorMessage ?: "HTTP ${fallback.statusCode}") else null
    )
}

private fun checkTelegramSmart(
    service: ServiceCheckItem,
    proxy: Proxy,
    timeoutMs: Long
): ServiceCheckResult {
    val probe = executeProbe(service.url, proxy, timeoutMs)
    if (probe.statusCode in 200..399) {
        return ServiceCheckResult(
            service = service,
            success = true,
            latencyMs = probe.latencyMs,
            httpCode = probe.statusCode
        )
    }
    // Если соединение оборвалось или упало по таймауту — возвращаем ошибку сразу
    if (probe.statusCode <= 0) {
        return ServiceCheckResult(
            service = service,
            success = false,
            latencyMs = -1L,
            httpCode = -1,
            errorMessage = probe.errorMessage ?: "Read timed out"
        )
    }
    val fallback = executeProbe("https://telegram.org", proxy, timeoutMs)
    val success = fallback.statusCode in 200..399
    return ServiceCheckResult(
        service = service,
        success = success,
        latencyMs = if (success) fallback.latencyMs else -1L,
        httpCode = fallback.statusCode,
        errorMessage = if (!success) (fallback.errorMessage ?: "HTTP ${fallback.statusCode}") else null
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
    if (probe.statusCode in 200..399) {
        return ServiceCheckResult(
            service = service,
            success = true,
            latencyMs = probe.latencyMs,
            httpCode = probe.statusCode
        )
    }
    if (probe.statusCode <= 0) {
        return ServiceCheckResult(
            service = service,
            success = false,
            latencyMs = -1L,
            httpCode = -1,
            errorMessage = probe.errorMessage ?: "Read timed out"
        )
    }
    val success = probe.statusCode in 200..399 || probe.statusCode == 403
    return ServiceCheckResult(
        service = service,
        success = success,
        latencyMs = if (success) probe.latencyMs else -1L,
        httpCode = probe.statusCode,
        errorMessage = if (!success) (probe.errorMessage ?: "HTTP ${probe.statusCode}") else null
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
    timeoutMs: Long
): ServiceCheckResult = withContext(Dispatchers.IO) {
    val proxy = createProxy(socksPort, httpPort)
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
