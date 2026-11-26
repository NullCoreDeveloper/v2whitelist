package com.kiktor.v2whitelist.core.domain.security

/**
 * Политика аутентификации локального прокси-сервера.
 * Исключает передачу локальных учётных данных внешним хостам при вызовах java.net.Authenticator.
 */
object LoopbackProxyAuthPolicy {

    /**
     * Проверяет, допустимо ли передавать локальные учётные данные прокси для данного запроса.
     */
    fun shouldAuthenticate(
        protocol: String?,
        host: String?,
        siteIsLoopback: Boolean,
        port: Int,
        allowedPorts: Set<Int> = emptySet()
    ): Boolean {
        val proto = protocol?.trim()?.uppercase().orEmpty()
        if (!proto.startsWith("SOCKS") && !proto.startsWith("HTTP")) {
            return false
        }

        val normalizedHost = host?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]")?.trimEnd('.').orEmpty()
        val isLoopbackHost = normalizedHost == "127.0.0.1" ||
            normalizedHost == "::1" ||
            normalizedHost == "localhost" ||
            siteIsLoopback
        if (!isLoopbackHost) {
            return false
        }

        if (allowedPorts.isNotEmpty() && port !in allowedPorts) {
            return false
        }
        return true
    }
}
