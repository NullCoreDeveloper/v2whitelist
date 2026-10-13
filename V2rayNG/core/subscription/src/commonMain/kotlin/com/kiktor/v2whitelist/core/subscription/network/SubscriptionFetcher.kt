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

object SubscriptionFetcher {

    const val DEFAULT_TIMEOUT_MS = 10_000L
    const val DEFAULT_USER_AGENT = "v2rayNG/1.10.x"

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
     * Fetches raw content from a single URL.
     */
    suspend fun fetchSingle(
        client: HttpClient,
        url: String,
        userAgent: String? = null
    ): Result<String> = runCatching {
        val response = client.get(url) {
            val ua = userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT
            header(HttpHeaders.UserAgent, ua)
        }
        if (response.status.isSuccess()) {
            response.bodyAsText()
        } else {
            error("HTTP error: ${response.status.value} ${response.status.description}")
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
    ): Result<String> = withContext(Dispatchers.Default) {
        if (urls.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No URLs provided"))
        }

        val client = createHttpClient(httpProxyHost, httpProxyPort, timeoutMs)
        try {
            for (url in urls) {
                val res = fetchSingle(client, url, userAgent)
                if (res.isSuccess && res.getOrNull()?.isNotBlank() == true) {
                    return@withContext res
                }
            }
            Result.failure(IllegalStateException("All mirrors failed (sequential)"))
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
    ): Result<String> = withContext(Dispatchers.Default) {
        if (urls.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No URLs provided"))
        }
        if (urls.size == 1) {
            return@withContext fetchSequential(urls, userAgent, httpProxyHost, httpProxyPort, timeoutMs)
        }

        val client = createHttpClient(httpProxyHost, httpProxyPort, timeoutMs)
        try {
            coroutineScope {
                val winnerDeferred = CompletableDeferred<String>()

                val jobs = urls.map { url ->
                    launch {
                        try {
                            val res = fetchSingle(client, url, userAgent)
                            val text = res.getOrNull()
                            if (!text.isNullOrBlank()) {
                                winnerDeferred.complete(text)
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

                val winningContent = try {
                    withTimeoutOrNull(timeoutMs) {
                        winnerDeferred.await()
                    }
                } catch (_: Exception) {
                    null
                } finally {
                    monitorJob.cancel()
                    jobs.forEach { it.cancel() }
                }

                if (!winningContent.isNullOrBlank()) {
                    Result.success(winningContent)
                } else {
                    // Фоллбэк: если параллельная гонка не вернула данных, пробуем последовательный опрос
                    fetchSequential(urls, userAgent, httpProxyHost, httpProxyPort, timeoutMs = 5_000L)
                }
            }
        } finally {
            client.close()
        }
    }
}
