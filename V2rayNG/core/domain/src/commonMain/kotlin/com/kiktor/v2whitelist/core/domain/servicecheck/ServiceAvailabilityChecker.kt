package com.kiktor.v2whitelist.core.domain.servicecheck

import com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckItem
import com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Платформо-зависимый HTTP/HTTPS запрос для проверки доступности конкретного сервиса.
 */
expect suspend fun rawServiceHttpCheck(
    service: ServiceCheckItem,
    socksPort: Int? = null,
    httpPort: Int? = null,
    timeoutMs: Long = 4000L,
    socksUser: String? = null,
    socksPass: String? = null
): ServiceCheckResult

/**
 * Менеджер быстрой параллельной проверки доступности сервисов (Google, YouTube, Telegram, AI и пользовательских).
 */
object ServiceAvailabilityChecker {

    suspend fun checkSingle(
        service: ServiceCheckItem,
        socksPort: Int? = null,
        httpPort: Int? = null,
        timeoutMs: Long = 4000L,
        socksUser: String? = null,
        socksPass: String? = null
    ): ServiceCheckResult {
        return rawServiceHttpCheck(service, socksPort, httpPort, timeoutMs, socksUser, socksPass)
    }

    /**
     * Параллельно проверяет все включенные сервисы из списка [services].
     * Запросы выполняются одновременно, за счет чего общее время проверки не превышает таймаута самого медленного узла.
     */
    suspend fun checkServicesParallel(
        services: List<ServiceCheckItem>,
        socksPort: Int? = null,
        httpPort: Int? = null,
        timeoutMs: Long = 4000L,
        socksUser: String? = null,
        socksPass: String? = null
    ): List<ServiceCheckResult> = coroutineScope {
        val enabled = services.filter { it.enabled }
        if (enabled.isEmpty()) return@coroutineScope emptyList()

        enabled.map { service ->
            async {
                rawServiceHttpCheck(service, socksPort, httpPort, timeoutMs, socksUser, socksPass)
            }
        }.awaitAll()
    }

    /**
     * Проверяет, доступны ли ВСЕ включенные сервисы из списка.
     * Если хотя бы один сервис недоступен, возвращает false.
     */
    suspend fun areAllServicesAvailable(
        services: List<ServiceCheckItem>,
        socksPort: Int? = null,
        httpPort: Int? = null,
        timeoutMs: Long = 4000L,
        socksUser: String? = null,
        socksPass: String? = null
    ): Boolean {
        val results = checkServicesParallel(
            services = services,
            socksPort = socksPort,
            httpPort = httpPort,
            timeoutMs = timeoutMs,
            socksUser = socksUser,
            socksPass = socksPass
        )
        return results.isNotEmpty() && results.all { it.success }
    }
}

