package com.kiktor.v2whitelist.core.domain.servicecheck

import com.kiktor.v2whitelist.core.model.currentTimeMillis
import com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckItem
import com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

actual suspend fun rawServiceHttpCheck(
    service: ServiceCheckItem,
    socksPort: Int?,
    httpPort: Int?,
    timeoutMs: Long
): ServiceCheckResult = withContext(Dispatchers.IO) {
    val start = currentTimeMillis()
    try {
        val proxySelector = when {
            httpPort != null && httpPort > 0 -> {
                ProxySelector.of(InetSocketAddress("127.0.0.1", httpPort))
            }
            socksPort != null && socksPort > 0 -> {
                object : ProxySelector() {
                    override fun select(uri: URI?): List<Proxy> =
                        listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))

                    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
                }
            }
            else -> HttpClient.Builder.NO_PROXY
        }

        val client = HttpClient.newBuilder()
            .proxy(proxySelector)
            .connectTimeout(Duration.ofMillis(timeoutMs))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

        val request = HttpRequest.newBuilder()
            .uri(URI.create(service.url))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "*/*")
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        val code = response.statusCode()
        val elapsed = currentTimeMillis() - start
        // Сервис считается доступным, если он вернул ответ на прикладном уровне (2xx, 3xx или клиенсткие 401/403/404/429)
        // Исключаем 451 (Unavailable For Legal Reasons / цензурные заглушки) и 5xx
        val success = (code in 200..399) || (code in listOf(401, 403, 404, 429))
        ServiceCheckResult(
            service = service,
            success = success,
            latencyMs = if (success) elapsed else -1L,
            httpCode = code,
            errorMessage = if (!success) "HTTP $code" else null
        )
    } catch (e: Exception) {
        val elapsed = currentTimeMillis() - start
        ServiceCheckResult(
            service = service,
            success = false,
            latencyMs = -1L,
            httpCode = -1,
            errorMessage = e.message ?: e::class.simpleName
        )
    }
}
