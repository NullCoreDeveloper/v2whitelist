package com.kiktor.v2whitelist.core.domain.ping

import com.kiktor.v2whitelist.core.model.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

actual suspend fun rawTcpPing(host: String, port: Int, timeoutMs: Long): Long = withContext(Dispatchers.IO) {
    if (host.isBlank() || port <= 0 || port > 65535) return@withContext -1L
    val start = currentTimeMillis()
    try {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs.toInt())
            currentTimeMillis() - start
        }
    } catch (_: Exception) {
        -1L
    }
}
