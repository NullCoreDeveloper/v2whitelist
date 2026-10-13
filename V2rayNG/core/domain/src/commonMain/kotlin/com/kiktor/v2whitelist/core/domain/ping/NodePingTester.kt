package com.kiktor.v2whitelist.core.domain.ping

import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.currentTimeMillis
import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.http
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Кроссплатформенный тестер задержки нод (быстрый параллельный TCP-пинг и замер HTTP 204 задержки).
 */
object NodePingTester {

    /**
     * Выполняет TCP-пинг одной ноды.
     * Возвращает задержку в миллисекундах или -1L при ошибке/таймауте.
     */
    suspend fun testSingleTcp(profile: ProfileItem, timeoutMs: Long = 1200L): Long {
        val host = profile.server?.trim().orEmpty()
        val port = profile.serverPort?.trim()?.toIntOrNull() ?: return -1L
        if (host.isEmpty() || port <= 0) return -1L

        return rawTcpPing(host, port, timeoutMs)
    }

    /**
     * Параллельно тестирует список серверов через быстрый TCP-пинг.
     * Возвращает только ответившие серверы (delay > 0), отсортированные по возрастанию задержки (быстрые первые).
     */
    suspend fun testBatchTcp(
        servers: List<Pair<String, ProfileItem>>,
        timeoutMs: Long = 1200L,
        concurrency: Int = 32
    ): List<Triple<String, ProfileItem, Long>> = coroutineScope {
        if (servers.isEmpty()) return@coroutineScope emptyList()

        val semaphore = Semaphore(concurrency)
        val results = servers.map { (guid, profile) ->
            async(Dispatchers.Default) {
                semaphore.withPermit {
                    // Для UDP-протоколов TCP-пинг не применим (помечаем как условно доступные с 0ms для последующего HTTP 204)
                    val isUdpOnly = profile.configType == EConfigType.HYSTERIA2 ||
                            profile.configType == EConfigType.WIREGUARD
                    val delay = if (isUdpOnly) 0L else testSingleTcp(profile, timeoutMs)
                    if (delay >= 0L) Triple(guid, profile, delay) else null
                }
            }
        }.awaitAll().filterNotNull()

        // Сортируем: сначала реальные TCP замеры по возрастанию, затем UDP-узлы
        results.sortedWith(compareBy({ if (it.third == 0L) Long.MAX_VALUE else it.third }))
    }

    /**
     * Измеряет реальную HTTP задержку (Round-Trip Time) через запущенный локальный прокси (например, порт 10809).
     * @param proxyPort Локальный порт HTTP-прокси ядра Xray.
     * @param testUrl URL для проверки (по умолчанию generate_204).
     * @return Задержка в мс или -1L при ошибке.
     */
    suspend fun testHttpProxyDelay(
        proxyPort: Int,
        proxyHost: String = "127.0.0.1",
        testUrl: String = "https://www.google.com/generate_204",
        timeoutMs: Long = 3000L
    ): Long = withContext(Dispatchers.Default) {
        if (proxyPort <= 0) return@withContext -1L

        val client = HttpClient(CIO) {
            engine {
                proxy = ProxyBuilder.http("http://$proxyHost:$proxyPort")
            }
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = timeoutMs
                socketTimeoutMillis = timeoutMs
            }
        }

        try {
            val start = currentTimeMillis()
            val response = client.get(testUrl)
            if (response.status.isSuccess() || response.status.value in 200..399) {
                currentTimeMillis() - start
            } else {
                -1L
            }
        } catch (_: Exception) {
            -1L
        } finally {
            client.close()
        }
    }
}
