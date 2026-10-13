package com.kiktor.v2whitelist.core.domain.ping

actual suspend fun rawTcpPing(host: String, port: Int, timeoutMs: Long): Long {
    // Stub for iOS native sockets (will be wired via POSIX / Network.framework)
    return -1L
}
