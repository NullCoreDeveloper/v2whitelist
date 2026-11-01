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
    val probe = executeProbe("https://claude.ai/cdn-cgi/trace", proxy, timeoutMs, maxBodyBytes = 4096)
    if (probe.statusCode !in 200..399) {
        return ServiceCheckResult(
            service = service,
            success = false,
            latencyMs = -1L,
            httpCode = probe.statusCode,
            errorMessage = probe.errorMessage ?: "HTTP ${probe.statusCode}"
        )
    }
    val locMatch = Regex("""(?m)^loc=([A-Za-z]{2})$""").find(probe.body)
        ?: Regex("""loc=([A-Za-z]{2})""").find(probe.body)
    val loc = locMatch?.groupValues?.get(1)?.uppercase()
    if (loc == null) {
        return ServiceCheckResult(
            service = service,
            success = false,
            latencyMs = -1L,
            httpCode = probe.statusCode,
            errorMessage = "Не удалось определить регион"
        )
    }
    if (loc in CLAUDE_BLOCKED_REGIONS) {
        return ServiceCheckResult(
            service = service,
            success = false,
            latencyMs = -1L,
            httpCode = probe.statusCode,
            errorMessage = "Регион $loc не поддерживается"
        )
    }
    return ServiceCheckResult(
        service = service,
        success = true,
        latencyMs = probe.latencyMs,
        httpCode = probe.statusCode
    )
}

private fun checkChatGptSmart(
    service: ServiceCheckItem,
    proxy: Proxy,
    timeoutMs: Long
): ServiceCheckResult {
    val traceProbe = executeProbe("https://chatgpt.com/cdn-cgi/trace", proxy, timeoutMs, maxBodyBytes = 4096)
    val locMatch = Regex("""(?m)^loc=([A-Za-z]{2})$""").find(traceProbe.body)
        ?: Regex("""loc=([A-Za-z]{2})""").find(traceProbe.body)
    val loc = locMatch?.groupValues?.get(1)?.uppercase()
    if (loc != null && loc in CHATGPT_BLOCKED_REGIONS) {
        return ServiceCheckResult(
            service = service,
            success = false,
            latencyMs = -1L,
            httpCode = traceProbe.statusCode,
            errorMessage = "Регион $loc не поддерживается"
        )
    }

    // iOS API endpoint для детекта блокировки ISP/IP
    val iosProbe = executeProbe("https://ios.chat.openai.com/", proxy, timeoutMs, maxBodyBytes = 4096)
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

    // Compliance endpoint на unsupported_country
    val complianceProbe = executeProbe("https://api.openai.com/compliance/cookie_requirements", proxy, timeoutMs, maxBodyBytes = 4096)
    if (complianceProbe.body.lowercase().contains("unsupported_country")) {
        return ServiceCheckResult(
            service = service,
            success = false,
            latencyMs = -1L,
            httpCode = complianceProbe.statusCode,
            errorMessage = "Страна не поддерживается OpenAI"
        )
    }

    val isTraceOk = traceProbe.statusCode in 200..399 && loc != null
    val isIosOk = iosProbe.statusCode in 200..399 || iosBody.contains("request is not allowed")
    if (isTraceOk || isIosOk) {
        val latency = when {
            traceProbe.latencyMs > 0 && iosProbe.latencyMs > 0 -> minOf(traceProbe.latencyMs, iosProbe.latencyMs)
            traceProbe.latencyMs > 0 -> traceProbe.latencyMs
            else -> iosProbe.latencyMs.coerceAtLeast(1L)
        }
        return ServiceCheckResult(
            service = service,
            success = true,
            latencyMs = latency,
            httpCode = if (traceProbe.statusCode > 0) traceProbe.statusCode else iosProbe.statusCode
        )
    }

    return ServiceCheckResult(
        service = service,
        success = false,
        latencyMs = -1L,
        httpCode = traceProbe.statusCode,
        errorMessage = traceProbe.errorMessage ?: iosProbe.errorMessage ?: "Недоступен"
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

        // Проверяем заглушку Google Captcha / Sorry
        if (loc?.contains("/sorry/") == true) {
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
            if (newLoc?.contains("/sorry/") == true) {
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
                val marker = ",2,1,200,\""
                val buf = ByteArray(8192)
                val sb = StringBuilder()
                var readTotal = 0
                val maxScan = 1_500_000 // до 1.5 МБ
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
            else -> checkGeneric(service, proxy, timeoutMs)
        }
    } else {
        checkGeneric(service, proxy, timeoutMs)
    }
}
