package com.kiktor.v2whitelist.core.domain.ping

/**
 * Кроссплатформенный замер задержки TCP-соединения (в миллисекундах) или -1L при ошибке/таймауте.
 */
expect suspend fun rawTcpPing(host: String, port: Int, timeoutMs: Long = 1200L): Long
