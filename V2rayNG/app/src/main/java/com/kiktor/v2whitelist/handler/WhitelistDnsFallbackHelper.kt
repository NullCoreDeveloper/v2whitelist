package com.kiktor.v2whitelist.handler

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.kiktor.v2whitelist.AngApplication
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Хелпер для обхода сбоев проверки белого списка при заблокированном / мертвом DoT / Private DNS / DoH.
 * 
 * Проблема:
 * При включенном Private DNS в Android система в строгом режиме принудительно направляет все DNS-запросы
 * через DoT (порт 853). На мобильных "белых списках" операторы полностью блокируют порт 853 и сторонние DoH-серверы,
 * из-за чего системный резолвер выбрасывает UnknownHostException для ya.ru, и арбитраж сети выдает ложный NO_INTERNET.
 * 
 * Решение (гибрид двух вариантов):
 * 1. Вариант 1 (Operator UDP 53): Прямой запрос к локальным DNS-серверам оператора (linkProperties.dnsServers)
 *    по стандартному UDP порту 53 в обход системного Private DNS.
 * 2. Вариант 2 (Hardcoded Verified IPs): Официальные проверенные IPv4-адреса Яндекса (AS13238 YANDEX LLC),
 *    подключение по HTTPS:443 с TLS SNI "ya.ru" и валидацией сертификата.
 */
object WhitelistDnsFallbackHelper {

    /**
     * Официальные проверенные IPv4-адреса Яндекса (ya.ru), принадлежащие AS13238 YANDEX LLC.
     */
    val YANDEX_VERIFIED_IPS = listOf(
        "77.88.55.242",
        "77.88.44.242",
        "5.255.255.242"
    )

    /**
     * Извлекает хост из URL или строки хоста (например, "https://ya.ru:443/test" -> "ya.ru").
     */
    fun extractHost(urlOrHost: String): String {
        var host = urlOrHost.trim()
        if (host.startsWith("https://", ignoreCase = true)) {
            host = host.substring(8)
        } else if (host.startsWith("http://", ignoreCase = true)) {
            host = host.substring(7)
        }
        val slashIdx = host.indexOf('/')
        if (slashIdx != -1) {
            host = host.substring(0, slashIdx)
        }
        val colonIdx = host.indexOf(':')
        if (colonIdx != -1) {
            host = host.substring(0, colonIdx)
        }
        return host
    }

    /**
     * Формирует стандартный UDP DNS-запрос (RFC 1035) типа A для указанного домена.
     */
    private fun buildDnsQuery(domain: String): ByteArray {
        val clean = domain.trim().trimEnd('.')
        val labels = clean.split('.')
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)

        val txId = kotlin.random.Random.nextInt(1, 0xFFFF)
        dos.writeShort(txId)
        dos.writeShort(0x0100) // Flags: Standard query, recursion desired
        dos.writeShort(1)      // QDCOUNT: 1 question
        dos.writeShort(0)      // ANCOUNT: 0
        dos.writeShort(0)      // NSCOUNT: 0
        dos.writeShort(0)      // ARCOUNT: 0

        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            dos.writeByte(bytes.size)
            dos.write(bytes)
        }
        dos.writeByte(0)       // Конец QNAME

        dos.writeShort(1)      // QTYPE: A (IPv4)
        dos.writeShort(1)      // QCLASS: IN

        dos.flush()
        return bos.toByteArray()
    }

    /**
     * Разбирает стандартный DNS-ответ (RFC 1035) и возвращает список найденных IPv4-адресов.
     */
    private fun parseDnsResponse(data: ByteArray, length: Int): List<String> {
        if (length < 12) return emptyList()
        val buffer = ByteBuffer.wrap(data, 0, length)
        buffer.short // txId
        val flags = buffer.short.toInt() and 0xFFFF
        val rcode = flags and 0x0F
        if (rcode != 0) return emptyList()

        val qdCount = buffer.short.toInt() and 0xFFFF
        val anCount = buffer.short.toInt() and 0xFFFF
        buffer.short // nsCount
        buffer.short // arCount

        // Пропускаем секцию Question
        for (i in 0 until qdCount) {
            while (buffer.hasRemaining()) {
                val len = buffer.get().toInt() and 0xFF
                if (len == 0) break
                if ((len and 0xC0) == 0xC0) {
                    if (buffer.hasRemaining()) buffer.get()
                    break
                }
                if (buffer.remaining() < len) return emptyList()
                buffer.position(buffer.position() + len)
            }
            if (buffer.remaining() < 4) return emptyList()
            buffer.short // QTYPE
            buffer.short // QCLASS
        }

        val ips = mutableListOf<String>()
        // Разбираем секцию Answer
        for (i in 0 until anCount) {
            if (!buffer.hasRemaining()) break
            while (buffer.hasRemaining()) {
                val b = buffer.get().toInt() and 0xFF
                if (b == 0) break
                if ((b and 0xC0) == 0xC0) {
                    if (buffer.hasRemaining()) buffer.get()
                    break
                }
                if (buffer.remaining() < b) break
                buffer.position(buffer.position() + b)
            }
            if (buffer.remaining() < 10) break
            val type = buffer.short.toInt() and 0xFFFF
            val clazz = buffer.short.toInt() and 0xFFFF
            buffer.int // TTL
            val rdLength = buffer.short.toInt() and 0xFFFF
            if (buffer.remaining() < rdLength) break

            if (type == 1 && clazz == 1 && rdLength == 4) {
                val b1 = buffer.get().toInt() and 0xFF
                val b2 = buffer.get().toInt() and 0xFF
                val b3 = buffer.get().toInt() and 0xFF
                val b4 = buffer.get().toInt() and 0xFF
                ips.add("$b1.$b2.$b3.$b4")
            } else {
                buffer.position(buffer.position() + rdLength)
            }
        }
        return ips
    }

    /**
     * Вариант 1: Прямой запрос к DNS-серверам оператора через порт 53 UDP.
     * Обходит системный DoT (Private DNS) в Android.
     */
    fun resolveViaOperatorDns(context: Context, domain: String, network: Network? = null, timeoutMs: Int = 1000): List<String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return emptyList()
        val targetNetwork = network ?: cm.activeNetwork ?: return emptyList()
        val linkProps = cm.getLinkProperties(targetNetwork) ?: return emptyList()
        val dnsServers = linkProps.dnsServers.filter { !it.isLoopbackAddress }
        if (dnsServers.isEmpty()) return emptyList()

        val query = buildDnsQuery(domain)
        for (dnsServer in dnsServers) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                targetNetwork.bindSocket(socket)
                socket.soTimeout = timeoutMs
                val packet = DatagramPacket(query, query.size, dnsServer, 53)
                socket.send(packet)

                val recvBuffer = ByteArray(1024)
                val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)
                socket.receive(recvPacket)

                val ips = parseDnsResponse(recvPacket.data, recvPacket.length)
                if (ips.isNotEmpty()) {
                    GeekModeLogger.log("DnsFallback", "Operator DNS ${dnsServer.hostAddress}:53 resolved $domain -> $ips")
                    return ips
                }
            } catch (_: Exception) {
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
        return emptyList()
    }

    /**
     * Гибридная проверка доступности узла белого списка для физической сети:
     * 1) Сначала пробуем резолв через операторские DNS (порт 53 UDP);
     * 2) Если это ya.ru / yandex.ru — добавляем проверенные IP Яндекса (AS13238);
     * 3) Подключаемся к IP:443 с TLS SNI хоста, проверяем валидность сертификата и делаем HTTP HEAD запрос.
     */
    fun checkPhysicalNetworkWhitelistFallback(
        context: Context,
        network: Network,
        urlOrHost: String,
        timeoutMs: Int = 1500
    ): Boolean {
        val host = extractHost(urlOrHost)
        val isYandex = host.equals("ya.ru", ignoreCase = true) || host.equals("yandex.ru", ignoreCase = true)

        val resolvedIps = resolveViaOperatorDns(context, host, network, timeoutMs = 800)
        val candidateIps = mutableListOf<String>()
        candidateIps.addAll(resolvedIps)
        if (isYandex) {
            for (vIp in YANDEX_VERIFIED_IPS) {
                if (!candidateIps.contains(vIp)) candidateIps.add(vIp)
            }
        }

        if (candidateIps.isEmpty()) return false

        for (ip in candidateIps) {
            var rawSocket: Socket? = null
            var sslSocket: SSLSocket? = null
            try {
                rawSocket = Socket()
                network.bindSocket(rawSocket)
                rawSocket.tcpNoDelay = true
                rawSocket.soTimeout = timeoutMs
                rawSocket.connect(InetSocketAddress(InetAddress.getByName(ip), 443), timeoutMs)

                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                sslSocket = sslFactory.createSocket(rawSocket, host, 443, true) as SSLSocket
                sslSocket.soTimeout = timeoutMs
                sslSocket.startHandshake()

                // Проверяем валидность сессии и сертификата для хоста
                val session = sslSocket.session
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)) {
                    continue
                }

                val out = sslSocket.outputStream
                out.write("HEAD / HTTP/1.1\r\nHost: $host\r\nUser-Agent: v2whitelist/probe\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()

                val reader = sslSocket.inputStream.bufferedReader(Charsets.US_ASCII)
                val statusLine = reader.readLine()
                if (statusLine != null && (statusLine.contains(" 200") || statusLine.contains(" 301") || statusLine.contains(" 302") || statusLine.contains(" 403"))) {
                    GeekModeLogger.log("DnsFallback", "Whitelist probe success via $ip for $host ($statusLine)")
                    return true
                }
            } catch (_: Exception) {
            } finally {
                try { sslSocket?.close() } catch (_: Exception) {}
                try { rawSocket?.close() } catch (_: Exception) {}
            }
        }
        return false
    }

    /**
     * Fallback для проверки HTTP-доступа без специфического Network объекта (для NetworkManager).
     */
    fun checkWhitelistHttpFallback(urlOrHost: String, timeoutMs: Int = 2000): Boolean {
        val host = extractHost(urlOrHost)
        val isYandex = host.equals("ya.ru", ignoreCase = true) || host.equals("yandex.ru", ignoreCase = true)

        val resolvedIps = try {
            resolveViaOperatorDns(AngApplication.application, host, timeoutMs = 800)
        } catch (_: Exception) {
            emptyList()
        }
        val candidateIps = mutableListOf<String>()
        candidateIps.addAll(resolvedIps)
        if (isYandex) {
            for (vIp in YANDEX_VERIFIED_IPS) {
                if (!candidateIps.contains(vIp)) candidateIps.add(vIp)
            }
        }

        if (candidateIps.isEmpty()) return false

        for (ip in candidateIps) {
            var rawSocket: Socket? = null
            var sslSocket: SSLSocket? = null
            try {
                rawSocket = Socket()
                rawSocket.tcpNoDelay = true
                rawSocket.soTimeout = timeoutMs
                rawSocket.connect(InetSocketAddress(InetAddress.getByName(ip), 443), timeoutMs)

                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                sslSocket = sslFactory.createSocket(rawSocket, host, 443, true) as SSLSocket
                sslSocket.soTimeout = timeoutMs
                sslSocket.startHandshake()

                val session = sslSocket.session
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)) {
                    continue
                }

                val out = sslSocket.outputStream
                out.write("HEAD / HTTP/1.1\r\nHost: $host\r\nUser-Agent: v2whitelist/probe\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()

                val reader = sslSocket.inputStream.bufferedReader(Charsets.US_ASCII)
                val statusLine = reader.readLine()
                if (statusLine != null && (statusLine.contains(" 200") || statusLine.contains(" 301") || statusLine.contains(" 302") || statusLine.contains(" 403"))) {
                    return true
                }
            } catch (_: Exception) {
            } finally {
                try { sslSocket?.close() } catch (_: Exception) {}
                try { rawSocket?.close() } catch (_: Exception) {}
            }
        }
        return false
    }
}
