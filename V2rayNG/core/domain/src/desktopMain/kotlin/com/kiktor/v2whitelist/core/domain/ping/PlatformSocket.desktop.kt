package com.kiktor.v2whitelist.core.domain.ping

import com.kiktor.v2whitelist.core.model.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

private val YANDEX_VERIFIED_IPS = listOf(
    "77.88.55.242",
    "77.88.44.242",
    "5.255.255.242"
)

actual suspend fun rawTcpPing(host: String, port: Int, timeoutMs: Long): Long = withContext(Dispatchers.IO) {
    if (host.isBlank() || port <= 0 || port > 65535) return@withContext -1L
    val start = currentTimeMillis()
    try {
        Socket(Proxy.NO_PROXY).use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs.toInt())
            currentTimeMillis() - start
        }
    } catch (_: Exception) {
        // Fallback для ya.ru при мертвом/заблокированном DoT/Private DNS на белых списках
        if (host.equals("ya.ru", ignoreCase = true) || host.equals("yandex.ru", ignoreCase = true)) {
            for (ip in YANDEX_VERIFIED_IPS) {
                try {
                    Socket(Proxy.NO_PROXY).use { socket ->
                        socket.tcpNoDelay = true
                        socket.connect(InetSocketAddress(ip, port), timeoutMs.toInt())
                        return@withContext currentTimeMillis() - start
                    }
                } catch (_: Exception) {}
            }
        }
        -1L
    }
}
