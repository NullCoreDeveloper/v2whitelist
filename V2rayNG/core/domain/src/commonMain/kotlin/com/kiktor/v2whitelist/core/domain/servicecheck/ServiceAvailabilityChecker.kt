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
    timeoutMs: Long = 4000L
): ServiceCheckResult

/**
 * Менеджер быстрой параллельной проверки доступности сервисов (Google, YouTube, Telegram, AI и пользовательских).
 */
object ServiceAvailabilityChecker {

    suspend fun checkSingle(
        service: ServiceCheckItem,
        socksPort: Int? = null,
        timeoutMs: Long = 4000L
    ): ServiceCheckResult {
        return rawServiceHttpCheck(service, socksPort, timeoutMs)
    }

    /**
     * Параллельно проверяет все включенные сервисы из списка [services].
     * Запросы выполняются одновременно, за счет чего общее время проверки не превышает таймаута самого медленного узла.
     */
    suspend fun checkServicesParallel(
        services: List<ServiceCheckItem>,
        socksPort: Int? = null,
        timeoutMs: Long = 4000L
    ): List<ServiceCheckResult> = coroutineScope {
        val enabled = services.filter { it.enabled }
        if (enabled.isEmpty()) return@coroutineScope emptyList()

        enabled.map { service ->
            async {
                rawServiceHttpCheck(service, socksPort, timeoutMs)
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
        timeoutMs: Long = 4000L
    ): Boolean {
        val results = checkServicesParallel(services, socksPort, timeoutMs)
        return results.isNotEmpty() && results.all { it.success }
    }
}

