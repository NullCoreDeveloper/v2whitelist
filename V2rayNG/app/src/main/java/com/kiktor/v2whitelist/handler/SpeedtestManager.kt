package com.kiktor.v2whitelist.handler
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.dto.IPAPIInfo
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.util.HttpUtil
import com.kiktor.v2whitelist.util.JsonUtil
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.net.UnknownHostException

object SpeedtestManager {

    private val tcpTestingSockets = ArrayList<Socket?>()

    /**
     * Measures the TCP connection time to a given URL and port.
     *
     * @param url The URL to connect to.
     * @param port The port to connect to.
     * @return The connection time in milliseconds, or -1 if the connection failed.
     */
    suspend fun tcping(url: String, port: Int): Long {
        var time = -1L
        for (k in 0 until 2) {
            val one = socketConnectTime(url, port)
            if (!currentCoroutineContext().isActive) {
                break
            }
            if (one != -1L && (time == -1L || one < time)) {
                time = one
            }
        }
        return time
    }

    /**
     * Measures the time taken to establish a TCP connection to a given URL and port.
     *
     * @param url The URL to connect to.
     * @param port The port to connect to.
     * @return The connection time in milliseconds, or -1 if the connection failed.
     */
    fun socketConnectTime(url: String, port: Int): Long {
        try {
            val socket = Socket()
            synchronized(this) {
                tcpTestingSockets.add(socket)
            }
            val start = System.currentTimeMillis()
            socket.connect(InetSocketAddress(url, port), 3000)
            val time = System.currentTimeMillis() - start
            synchronized(this) {
                tcpTestingSockets.remove(socket)
            }
            socket.close()
            return time
        } catch (e: UnknownHostException) {
            Log.e(AppConfig.TAG, "Unknown host: $url", e)
        } catch (e: IOException) {
            Log.e(AppConfig.TAG, "socketConnectTime IOException: $e")
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to establish socket connection to $url:$port", e)
        }
        return -1
    }

    /**
     * Closes all TCP sockets that are currently being tested.
     */
    fun closeAllTcpSockets() {
        synchronized(this) {
            tcpTestingSockets.forEach {
                it?.close()
            }
            tcpTestingSockets.clear()
        }
    }

    /**
     * Замеряет скорость скачивания через локальный SOCKS-прокси ядра на [socksPort].
     * Скачивает [bytes] байт с Cloudflare и возвращает скорость в Мбит/с,
     * или null если соединение не прошло / таймаут / ошибка.
     *
     * @param socksPort  локальный порт SOCKS5 поднятого ядра
     * @param bytes      объём загрузки в байтах (default: 2 МБ)
     * @param timeoutMs  таймаут в мс (default: 8 сек)
     */
    suspend fun measureSpeedThroughProxy(
        socksPort: Int,
        bytes: Long = 2_000_000L,
        timeoutMs: Int = 8_000
    ): Double? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        return@withContext try {
            val url = URL(AppConfig.SPEED_CHECK_URL + bytes)
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(AppConfig.LOOPBACK, socksPort))
            val conn = url.openConnection(proxy) as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"

            val job = launch {
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    Thread { try { conn.disconnect() } catch (e: Exception) {} }.start()
                }
            }

            val start = SystemClock.elapsedRealtime()
            var totalRead = 0L
            var stream: InputStream? = null
            try {
                conn.connect()
                stream = conn.inputStream
                val buf = ByteArray(8192)
                var n: Int
                while (stream.read(buf).also { n = it } != -1) {
                    if (!isActive) break
                    if (SystemClock.elapsedRealtime() - start > timeoutMs) break
                    totalRead += n
                }
            } catch (_: IOException) {
                // Таймаут чтения считаем нормой — данные уже прочли частично
            } finally {
                job.cancel()
                try { stream?.close() } catch (e: Exception) {}
                conn.disconnect()
            }

            val elapsedSec = (SystemClock.elapsedRealtime() - start) / 1000.0
            if (elapsedSec <= 0 || totalRead == 0L) return@withContext null

            val mbps = (totalRead.toDouble() / elapsedSec) * 8.0 / 1_000_000.0
            GeekModeLogger.log("SpeedTest", "Скачано $totalRead байт за %.2f сек → %.2f Мбит/с".format(elapsedSec, mbps))
            mbps
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(AppConfig.TAG, "measureSpeedThroughProxy error: ${e.message}")
            null
        }
    }

    /**
     * Tests the connection to a given URL and port.
     *
     * @param context The Context in which the test is running.
     * @param port The port to connect to.
     * @return A pair containing the elapsed time in milliseconds and the result message.
     */
    suspend fun testConnection(context: Context, port: Int, timeoutMs: Int = 15000): Pair<Long, String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        var result = ""
        var elapsed = -1L

        val conn = HttpUtil.createProxyConnection(SettingsManager.getDelayTestUrl(), port, timeoutMs, timeoutMs) ?: return@withContext Pair(elapsed, "")
        
        val job = launch {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                Thread { try { conn.disconnect() } catch (e: Exception) {} }.start()
            }
        }

        try {
            val start = SystemClock.elapsedRealtime()
            if (!isActive) return@withContext Pair(-1L, "")

            val code = conn.responseCode
            elapsed = SystemClock.elapsedRealtime() - start

            if (!isActive) return@withContext Pair(-1L, "")

            result = when (code) {
                204 -> context.getString(R.string.connection_test_available, elapsed)
                200 -> {
                    if (conn.contentLengthLong == 0L) context.getString(R.string.connection_test_available, elapsed)
                    else throw IOException(context.getString(R.string.connection_test_error_status_code, code))
                }
                else -> throw IOException(context.getString(R.string.connection_test_error_status_code, code))
            }
        } catch (e: IOException) {
            Log.e(AppConfig.TAG, "Connection test IOException", e)
            result = context.getString(R.string.connection_test_error, e.message)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(AppConfig.TAG, "Connection test Exception", e)
            result = context.getString(R.string.connection_test_error, e.message)
        } finally {
            job.cancel()
            conn.disconnect()
        }

        return@withContext Pair(elapsed, result)
    }

    fun getRemoteIPInfo(): String? {
        val url = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
            .takeIf { !it.isNullOrBlank() } ?: AppConfig.IP_API_URL

        val httpPort = SettingsManager.getHttpPort()
        val content = HttpUtil.getUrlContent(url, 5000, httpPort) ?: return null
        val ipInfo = JsonUtil.fromJson(content, IPAPIInfo::class.java) ?: return null

        val ip = listOf(
            ipInfo.ip,
            ipInfo.clientIp,
            ipInfo.ip_addr,
            ipInfo.query
        ).firstOrNull { !it.isNullOrBlank() }

        val country = listOf(
            ipInfo.country_code,
            ipInfo.country,
            ipInfo.countryCode,
            ipInfo.location?.country_code
        ).firstOrNull { !it.isNullOrBlank() }

        return "(${country ?: "unknown"}) ${ip ?: "unknown"}"
    }
}
