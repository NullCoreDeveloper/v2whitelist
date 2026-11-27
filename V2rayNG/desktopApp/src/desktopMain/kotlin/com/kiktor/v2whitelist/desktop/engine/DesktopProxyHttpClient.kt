package com.kiktor.v2whitelist.desktop.engine

import com.kiktor.v2whitelist.core.domain.security.LoopbackProxyAuthenticator
import com.kiktor.v2whitelist.core.subscription.network.NetworkSecurityGuard
import com.kiktor.v2whitelist.core.subscription.network.SubscriptionFetchResult
import com.kiktor.v2whitelist.core.subscription.network.SubscriptionFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * HTTP-клиент для Desktop поверх локального SOCKS5-прокси с поддержкой LoopbackProxyAuthenticator.
 * Используется при включённой авторизации локального прокси, так как Ktor CIO ProxyBuilder.http
 * не отправляет Proxy-Authorization при HTTPS CONNECT-туннелировании.
 */
object DesktopProxyHttpClient {

    private fun readStreamLimited(
        input: InputStream,
        maxBytes: Long = NetworkSecurityGuard.MAX_RESPONSE_BYTES
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw IOException("Response exceeds size limit ($maxBytes bytes)")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun ensureAuthenticator(socksPort: Int, proxyUser: String?, proxyPass: String?) {
        if (!proxyUser.isNullOrBlank() && !proxyPass.isNullOrBlank()) {
            LoopbackProxyAuthenticator.allowPort(socksPort)
            LoopbackProxyAuthenticator.install(
                userProvider = { proxyUser },
                passProvider = { proxyPass },
                allowedPortsProvider = { setOf(socksPort) }
            )
        }
    }

    private fun executeRequest(
        url: String,
        socksPort: Int,
        proxyUser: String?,
        proxyPass: String?,
        timeoutMs: Long,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Long = NetworkSecurityGuard.MAX_RESPONSE_BYTES
    ): Triple<Int, String, Map<String, String>> {
        if (socksPort <= 0) throw IllegalArgumentException("Invalid socksPort: $socksPort")
        ensureAuthenticator(socksPort, proxyUser, proxyPass)

        var currentUrl = NetworkSecurityGuard.validateExternalUrl(url, allowHttp = true)
            ?: error("Blocked or invalid external URL")
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val timeoutInt = timeoutMs.coerceIn(1000L, 60_000L).toInt()

        repeat(NetworkSecurityGuard.MAX_REDIRECTS + 1) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URI.create(currentUrl.toString()).toURL().openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = timeoutInt
                    readTimeout = timeoutInt
                    instanceFollowRedirects = false
                    useCaches = false
                    requestMethod = "GET"
                    setRequestProperty("Connection", "close")
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                val code = conn.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location")
                    currentUrl = NetworkSecurityGuard.validateRedirect(currentUrl, location, allowHttp = true)
                    return@repeat
                }
                val contentLength = conn.contentLengthLong
                if (contentLength > maxBytes) {
                    throw IOException("Response exceeds size limit")
                }
                val stream = if (code in 200..399) conn.inputStream else (conn.errorStream ?: conn.inputStream)
                val body = stream?.use { readStreamLimited(it, maxBytes).decodeToString() }.orEmpty()
                val userInfoHeader = conn.getHeaderField("subscription-userinfo")
                    ?: conn.getHeaderField("Subscription-Userinfo")
                val respHeaders = if (userInfoHeader != null) mapOf("subscription-userinfo" to userInfoHeader) else emptyMap()
                return Triple(code, body, respHeaders)
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                }
            }
        }
        error("Too many redirects")
    }

    suspend fun getText(
        url: String,
        socksPort: Int,
        proxyUser: String?,
        proxyPass: String?,
        timeoutMs: Long = 6_000L,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Long = NetworkSecurityGuard.MAX_RESPONSE_BYTES
    ): Result<Pair<Int, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val (code, body, _) = executeRequest(
                url = url,
                socksPort = socksPort,
                proxyUser = proxyUser,
                proxyPass = proxyPass,
                timeoutMs = timeoutMs,
                headers = headers,
                maxBytes = maxBytes
            )
            code to body
        }
    }

    suspend fun testDelay(
        socksPort: Int,
        proxyUser: String?,
        proxyPass: String?,
        testUrl: String,
        timeoutMs: Long = 4500L
    ): Long = withContext(Dispatchers.IO) {
        if (socksPort <= 0) return@withContext -1L
        val start = System.currentTimeMillis()
        val res = getText(
            url = testUrl,
            socksPort = socksPort,
            proxyUser = proxyUser,
            proxyPass = proxyPass,
            timeoutMs = timeoutMs,
            maxBytes = 128L * 1024L
        )
        val pair = res.getOrNull() ?: return@withContext -1L
        if (pair.first in 200..399) System.currentTimeMillis() - start else -1L
    }

    suspend fun fetchSingleWithMeta(
        url: String,
        userAgent: String? = null,
        socksPort: Int,
        proxyUser: String?,
        proxyPass: String?,
        timeoutMs: Long = SubscriptionFetcher.DEFAULT_TIMEOUT_MS
    ): Result<SubscriptionFetchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val ua = userAgent?.takeIf { it.isNotBlank() } ?: SubscriptionFetcher.DEFAULT_USER_AGENT
            val (code, body, headers) = executeRequest(
                url = url,
                socksPort = socksPort,
                proxyUser = proxyUser,
                proxyPass = proxyPass,
                timeoutMs = timeoutMs,
                headers = mapOf("User-Agent" to ua)
            )
            if (code !in 200..299) {
                error("HTTP error: $code")
            }
            SubscriptionFetchResult(
                content = body,
                userInfo = SubscriptionFetcher.parseSubscriptionUserInfo(headers["subscription-userinfo"])
            )
        }
    }

    suspend fun fetchParallelWithMeta(
        urls: List<String>,
        userAgent: String? = null,
        socksPort: Int,
        proxyUser: String?,
        proxyPass: String?,
        timeoutMs: Long = SubscriptionFetcher.DEFAULT_TIMEOUT_MS
    ): Result<SubscriptionFetchResult> = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No URLs provided"))
        }
        if (urls.size == 1) {
            return@withContext fetchSingleWithMeta(
                url = urls.first(),
                userAgent = userAgent,
                socksPort = socksPort,
                proxyUser = proxyUser,
                proxyPass = proxyPass,
                timeoutMs = timeoutMs
            )
        }

        coroutineScope {
            val winnerDeferred = CompletableDeferred<SubscriptionFetchResult>()
            val jobs = urls.map { url ->
                launch {
                    try {
                        val res = fetchSingleWithMeta(
                            url = url,
                            userAgent = userAgent,
                            socksPort = socksPort,
                            proxyUser = proxyUser,
                            proxyPass = proxyPass,
                            timeoutMs = timeoutMs
                        )
                        val value = res.getOrNull()
                        if (value != null && value.content.isNotBlank()) {
                            winnerDeferred.complete(value)
                        }
                    } catch (_: CancellationException) {
                    } catch (_: Exception) {
                    }
                }
            }

            val monitorJob = launch {
                jobs.joinAll()
                if (!winnerDeferred.isCompleted) {
                    winnerDeferred.completeExceptionally(IllegalStateException("All mirrors failed (parallel SOCKS5)"))
                }
            }

            val winningResult = try {
                withTimeoutOrNull(timeoutMs) {
                    winnerDeferred.await()
                }
            } catch (_: Exception) {
                null
            } finally {
                monitorJob.cancel()
                jobs.forEach { it.cancel() }
            }

            if (winningResult != null && winningResult.content.isNotBlank()) {
                Result.success(winningResult)
            } else {
                for (url in urls) {
                    val res = fetchSingleWithMeta(
                        url = url,
                        userAgent = userAgent,
                        socksPort = socksPort,
                        proxyUser = proxyUser,
                        proxyPass = proxyPass,
                        timeoutMs = 5_000L
                    )
                    val value = res.getOrNull()
                    if (res.isSuccess && value != null && value.content.isNotBlank()) {
                        return@coroutineScope Result.success(value)
                    }
                }
                Result.failure(IllegalStateException("All mirrors failed (SOCKS5)"))
            }
        }
    }
}
