package com.kiktor.v2whitelist.core.subscription.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.http
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class SubscriptionUserInfo(
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val expireTimestampSec: Long = 0L
)

data class SubscriptionFetchResult(
    val content: String,
    val userInfo: SubscriptionUserInfo? = null
)

object SubscriptionFetcher {

    const val DEFAULT_TIMEOUT_MS = 10_000L
    const val DEFAULT_USER_AGENT = "v2rayNG/1.10.x"

    /**
     * Parses standard `subscription-userinfo` header:
     * e.g. `upload=12345; download=67890; total=10737418240; expire=1700000000`
     */
    fun parseSubscriptionUserInfo(headerValue: String?): SubscriptionUserInfo? {
        if (headerValue.isNullOrBlank()) return null
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L
        var matchedAny = false

        for (part in headerValue.split(";", ",")) {
            val eqIdx = part.indexOf('=')
            if (eqIdx <= 0) continue
            val key = part.substring(0, eqIdx).trim().lowercase()
            val rawVal = part.substring(eqIdx + 1).trim()
            val num = rawVal.toLongOrNull() ?: rawVal.toDoubleOrNull()?.toLong() ?: continue
            when (key) {
                "upload" -> { upload = num.coerceAtLeast(0L); matchedAny = true }
                "download" -> { download = num.coerceAtLeast(0L); matchedAny = true }
                "total" -> { total = num.coerceAtLeast(0L); matchedAny = true }
                "expire" -> { expire = num.coerceAtLeast(0L); matchedAny = true }
            }
        }
        return if (matchedAny) SubscriptionUserInfo(upload, download, total, expire) else null
    }

    /**
     * Splits mirror URLs separated by "|" and removes invalid/empty parts.
     */
    fun parseMirrors(rawUrl: String): List<String> {
        return rawUrl.split("|")
            .map { it.trim() }
            .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
    }

    /**
     * Creates a configured Ktor HttpClient with optional HTTP proxy and timeout.
     */
    fun createHttpClient(
        httpProxyHost: String = "127.0.0.1",
        httpProxyPort: Int = 0,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): HttpClient {
        return HttpClient(CIO) {
            engine {
                if (httpProxyPort > 0) {
                    proxy = ProxyBuilder.http("http://$httpProxyHost:$httpProxyPort")
                }
            }
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = timeoutMs
                socketTimeoutMillis = timeoutMs
            }
        }
    }

    /**
     * Fetches raw content and metadata (such as subscription-userinfo) from a single URL.
     */
    suspend fun fetchSingleWithMeta(
        client: HttpClient,
        url: String,
        userAgent: String? = null
    ): Result<SubscriptionFetchResult> = runCatching {
        val response = client.get(url) {
            val ua = userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT
            header(HttpHeaders.UserAgent, ua)
        }
        if (response.status.isSuccess()) {
            val userInfoHeader = response.headers["subscription-userinfo"]
                ?: response.headers["Subscription-Userinfo"]
            SubscriptionFetchResult(
                content = response.bodyAsText(),
                userInfo = parseSubscriptionUserInfo(userInfoHeader)
            )
        } else {
            error("HTTP error: ${response.status.value} ${response.status.description}")
        }
    }

    /**
     * Fetches raw content from a single URL.
     */
    suspend fun fetchSingle(
        client: HttpClient,
        url: String,
        userAgent: String? = null
    ): Result<String> = fetchSingleWithMeta(client, url, userAgent).map { it.content }

    /**
     * Sequential mode with metadata.
     */
    suspend fun fetchSequentialWithMeta(
        urls: List<String>,
        userAgent: String? = null,
        httpProxyHost: String = "127.0.0.1",
        httpProxyPort: Int = 0,
        timeoutMs: Long = 6_000L
    ): Result<SubscriptionFetchResult> = withContext(Dispatchers.Default) {
        if (urls.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No URLs provided"))
        }

        val client = createHttpClient(httpProxyHost, httpProxyPort, timeoutMs)
        try {
            for (url in urls) {
                val res = fetchSingleWithMeta(client, url, userAgent)
                val value = res.getOrNull()
                if (res.isSuccess && value != null && value.content.isNotBlank()) {
                    return@withContext Result.success(value)
                }
            }
            Result.failure(IllegalStateException("All mirrors failed (sequential)"))
        } finally {
            client.close()
        }
    }

    /**
     * Sequential mode (ideal for background worker):
     * Tests mirrors one by one until the first successful non-empty response.
     * Economical on memory, network, and battery.
     */
    suspend fun fetchSequential(
        urls: List<String>,
        userAgent: String? = null,
        httpProxyHost: String = "127.0.0.1",
        httpProxyPort: Int = 0,
        timeoutMs: Long = 6_000L
    ): Result<String> = fetchSequentialWithMeta(urls, userAgent, httpProxyHost, httpProxyPort, timeoutMs).map { it.content }

    /**
     * Parallel race mode with metadata.
     */
    suspend fun fetchParallelWithMeta(
        urls: List<String>,
        userAgent: String? = null,
        httpProxyHost: String = "127.0.0.1",
        httpProxyPort: Int = 0,
        timeoutMs: Long = 10_000L
    ): Result<SubscriptionFetchResult> = withContext(Dispatchers.Default) {
        if (urls.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No URLs provided"))
        }
        if (urls.size == 1) {
            return@withContext fetchSequentialWithMeta(urls, userAgent, httpProxyHost, httpProxyPort, timeoutMs)
        }

        val client = createHttpClient(httpProxyHost, httpProxyPort, timeoutMs)
        try {
            coroutineScope {
                val winnerDeferred = CompletableDeferred<SubscriptionFetchResult>()

                val jobs = urls.map { url ->
                    launch {
                        try {
                            val res = fetchSingleWithMeta(client, url, userAgent)
                            val value = res.getOrNull()
                            if (value != null && value.content.isNotBlank()) {
                                winnerDeferred.complete(value)
                            }
                        } catch (_: CancellationException) {
                            // Cancelled by winner
                        } catch (_: Exception) {
                            // Ignored mirror error
                        }
                    }
                }

                val monitorJob = launch {
                    jobs.joinAll()
                    if (!winnerDeferred.isCompleted) {
                        winnerDeferred.completeExceptionally(IllegalStateException("All mirrors failed (parallel race)"))
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
                    fetchSequentialWithMeta(urls, userAgent, httpProxyHost, httpProxyPort, timeoutMs = 5_000L)
                }
            }
        } finally {
            client.close()
        }
    }

    /**
     * Parallel race mode (ideal for manual UI refresh):
     * All mirrors start concurrently, the first successful mirror wins and remaining ones are cancelled.
     */
    suspend fun fetchParallel(
        urls: List<String>,
        userAgent: String? = null,
        httpProxyHost: String = "127.0.0.1",
        httpProxyPort: Int = 0,
        timeoutMs: Long = 10_000L
    ): Result<String> = fetchParallelWithMeta(urls, userAgent, httpProxyHost, httpProxyPort, timeoutMs).map { it.content }
}
