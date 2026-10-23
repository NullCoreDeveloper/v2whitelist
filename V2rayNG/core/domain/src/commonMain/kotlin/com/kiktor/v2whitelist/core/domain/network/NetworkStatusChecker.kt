package com.kiktor.v2whitelist.core.domain.network

import com.kiktor.v2whitelist.core.model.currentTimeMillis
import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Кроссплатформенный анализатор состояния сети и детектора блокировок/белых списков (БС).
 */
object NetworkStatusChecker {

    const val STATUS_FULL_INTERNET = 0   // Полноценный интернет (Google и Яндекс доступны)
    const val STATUS_WHITELIST_ONLY = 1  // Белые списки / глушилки (ya.ru доступен, google.com нет)
    const val STATUS_NO_INTERNET = 2     // Нет сети (ничего не доступно)

    private var lastFailureReportCheckTime = 0L
    private var lastFailureReportCheckResult = false

    fun createCheckClient(timeoutMs: Long = 2000L): HttpClient {
        return HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = timeoutMs
                socketTimeoutMillis = timeoutMs
            }
        }
    }

    /**
     * Проверяет доступность заданного URL (код 200..399).
     */
    suspend fun checkHttpAccess(
        client: HttpClient,
        urlString: String,
        userAgent: String = "v2whitelist/kmp"
    ): Boolean = runCatching {
        val fixedUrl = if (!urlString.startsWith("http://", ignoreCase = true) &&
            !urlString.startsWith("https://", ignoreCase = true)
        ) {
            "https://$urlString"
        } else {
            urlString
        }
        val response = client.get(fixedUrl) {
            header(HttpHeaders.UserAgent, userAgent)
        }
        response.status.isSuccess() || response.status.value in 300..399
    }.getOrElse {
        // Резервная проверка для ya.ru при заблокированном DNS/DoT/DoH
        if (urlString.contains("ya.ru", ignoreCase = true) || urlString.contains("yandex.ru", ignoreCase = true)) {
            val verifiedIps = listOf("77.88.55.242", "77.88.44.242", "5.255.255.242")
            for (ip in verifiedIps) {
                if (com.kiktor.v2whitelist.core.domain.ping.rawTcpPing(ip, 443, 1200L) >= 0) {
                    return@getOrElse true
                }
            }
        }
        false
    }

    /**
     * Быстрая параллельная проверка состояния интернета через TCP-пинг порта 443:
     * @return 0 (STATUS_FULL_INTERNET), 1 (STATUS_WHITELIST_ONLY), 2 (STATUS_NO_INTERNET)
     */
    suspend fun checkTcpInternetStatus(
        foreignHost: String = "google.com",
        domesticHost: String = "ya.ru",
        timeoutMs: Long = 800L
    ): Int = coroutineScope {
        val googleDeferred = async {
            com.kiktor.v2whitelist.core.domain.ping.rawTcpPing(foreignHost, 443, timeoutMs) >= 0
        }
        val yandexDeferred = async {
            com.kiktor.v2whitelist.core.domain.ping.rawTcpPing(domesticHost, 443, timeoutMs) >= 0
        }

        val googleOk = googleDeferred.await()
        val yandexOk = yandexDeferred.await()

        when {
            googleOk && yandexOk -> STATUS_FULL_INTERNET
            !googleOk && yandexOk -> STATUS_WHITELIST_ONLY
            else -> STATUS_NO_INTERNET
        }
    }

    /**
     * Определяет состояние интернета через HTTP:
     * @return 0 (OK), 1 (WHITELIST_ONLY), 2 (NO_INTERNET)
     */
    suspend fun checkInternetStatus(
        foreignUrl: String = "google.com",
        domesticUrl: String = "ya.ru",
        timeoutMs: Long = 2000L
    ): Int = withContext(Dispatchers.Default) {
        val client = createCheckClient(timeoutMs)
        try {
            val foreignOk = checkHttpAccess(client, foreignUrl)
            val domesticOk = checkHttpAccess(client, domesticUrl)

            when {
                foreignOk && domesticOk -> STATUS_FULL_INTERNET
                !foreignOk && domesticOk -> STATUS_WHITELIST_ONLY
                else -> STATUS_NO_INTERNET
            }
        } finally {
            client.close()
        }
    }

    /**
     * Проверяет, следует ли показывать пользователю ошибку обновления подписки.
     * Если зарубежные узлы заблокированы оператором (режим БС), ошибка подписки не регистрируется.
     */
    suspend fun shouldReportSubscriptionFailure(
        foreignUrl: String = "google.com",
        domesticUrl: String = "ya.ru",
        forceRefresh: Boolean = false,
        timeoutMs: Long = 2000L
    ): Boolean = withContext(Dispatchers.Default) {
        val now = currentTimeMillis()
        if (!forceRefresh && (now - lastFailureReportCheckTime < 10_000L)) {
            return@withContext lastFailureReportCheckResult
        }

        val client = createCheckClient(timeoutMs)
        try {
            val domesticOk = checkHttpAccess(client, domesticUrl)
            val result = if (!domesticOk) {
                false
            } else {
                checkHttpAccess(client, foreignUrl)
            }

            lastFailureReportCheckTime = now
            lastFailureReportCheckResult = result
            GeekModeLogger.log("Network", "shouldReportSubscriptionFailure: domesticOk=$domesticOk, bothOk=$result")
            result
        } finally {
            client.close()
        }
    }
}
