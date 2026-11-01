package com.kiktor.v2whitelist.core.domain.servicecheck

import com.kiktor.v2whitelist.core.model.currentTimeMillis
import com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckItem
import com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.http
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun rawServiceHttpCheck(
    service: ServiceCheckItem,
    socksPort: Int?,
    httpPort: Int?,
    timeoutMs: Long
): ServiceCheckResult = withContext(Dispatchers.IO) {
    val start = currentTimeMillis()
    val client = HttpClient(CIO) {
        engine {
            when {
                httpPort != null && httpPort > 0 -> {
                    proxy = ProxyBuilder.http("http://127.0.0.1:$httpPort")
                }
                socksPort != null && socksPort > 0 -> {
                    proxy = ProxyBuilder.socks("127.0.0.1", socksPort)
                }
                else -> {
                    proxy = null
                }
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = timeoutMs
            socketTimeoutMillis = timeoutMs
        }
        followRedirects = false
    }

    try {
        val response = client.get(service.url) {
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            header("Accept", "*/*")
            header("Connection", "close")
        }
        val code = response.status.value
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
    } finally {
        client.close()
    }
}
