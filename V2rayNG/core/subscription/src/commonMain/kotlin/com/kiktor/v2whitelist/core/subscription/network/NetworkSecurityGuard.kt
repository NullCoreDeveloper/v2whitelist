package com.kiktor.v2whitelist.core.subscription.network

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.parseUrl
import io.ktor.http.takeFrom

/**
 * Кроссплатформенный фильтр сетевой безопасности для исходящих HTTP/HTTPS-запросов
 * (защита от SSRF, редиректов на локальные/служебные сети и HTTPS downgrade).
 */
object NetworkSecurityGuard {
    const val MAX_URL_LENGTH = 4096
    const val MAX_REDIRECTS = 5
    const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024

    private val IPV4_REGEX = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /**
     * Проверяет, относится ли хост к loopback, link-local, multicast или немаршрутизируемым адресам.
     */
    fun isBlockedHost(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]").trimEnd('.')
        if (h.isEmpty() || h == "localhost" || h.endsWith(".localhost")) {
            return true
        }

        val ipv4Match = IPV4_REGEX.matchEntire(h)
        if (ipv4Match != null) {
            val octets = ipv4Match.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
            if (octets.size != 4 || octets.any { it !in 0..255 }) return true
            return isBlockedIpv4Octets(octets[0], octets[1], octets[2], octets[3])
        }

        if (':' in h) {
            val withoutZone = h.substringBefore('%')
            if (withoutZone == "::1" || withoutZone == "::" || withoutZone == "0:0:0:0:0:0:0:1" || withoutZone == "0:0:0:0:0:0:0:0") {
                return true
            }
            if (withoutZone.startsWith("fe80:") || withoutZone.startsWith("fe9") ||
                withoutZone.startsWith("fea") || withoutZone.startsWith("feb")
            ) {
                return true
            }
            if (withoutZone.startsWith("ff")) {
                return true
            }
            if (withoutZone.startsWith("::ffff:")) {
                val mapped = withoutZone.removePrefix("::ffff:")
                val mappedIpv4 = IPV4_REGEX.matchEntire(mapped)
                if (mappedIpv4 != null) {
                    val octets = mappedIpv4.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
                    if (octets.size != 4 || octets.any { it !in 0..255 }) return true
                    return isBlockedIpv4Octets(octets[0], octets[1], octets[2], octets[3])
                }
                if (mapped.startsWith("7f") || mapped.startsWith("0:") || mapped.startsWith("a9fe:")) {
                    return true
                }
            }
        }

        return false
    }

    private fun isBlockedIpv4Octets(a: Int, b: Int, c: Int, d: Int): Boolean {
        if (a == 127) return true // 127.0.0.0/8 loopback
        if (a == 0) return true // 0.0.0.0/8 current network
        if (a == 169 && b == 254) return true // 169.254.0.0/16 link-local & cloud metadata
        if (a in 224..239) return true // 224.0.0.0/4 multicast
        if (a == 255 && b == 255 && c == 255 && d == 255) return true // broadcast
        return false
    }

    /**
     * Валидирует внешний URL для HTTP/HTTPS-запросов.
     */
    fun validateExternalUrl(rawUrl: String?, allowHttp: Boolean = true): Url? {
        val text = rawUrl?.trim() ?: return null
        if (text.isEmpty() || text.length > MAX_URL_LENGTH) return null
        if (text.any { it == '\n' || it == '\r' || it.isISOControl() }) return null

        val parsed = parseUrl(text) ?: return null
        val scheme = parsed.protocol.name.lowercase()
        val allowed = if (allowHttp) scheme == "https" || scheme == "http" else scheme == "https"
        if (!allowed) return null
        if (parsed.host.isBlank() || isBlockedHost(parsed.host)) return null
        return parsed
    }

    /**
     * Проверяет допустимость перенаправления с [from] на [location].
     */
    fun validateRedirect(from: Url, location: String?, allowHttp: Boolean = true): Url {
        val rawLocation = location?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Redirect location is missing")
        if (rawLocation.length > MAX_URL_LENGTH || rawLocation.any { it == '\n' || it == '\r' || it.isISOControl() }) {
            throw IllegalArgumentException("Malformed redirect location")
        }

        val resolved = resolveUrl(from, rawLocation)
            ?: throw IllegalArgumentException("Unresolvable redirect location")

        val scheme = resolved.protocol.name.lowercase()
        val allowed = if (allowHttp) scheme == "https" || scheme == "http" else scheme == "https"
        if (!allowed) {
            throw IllegalArgumentException("Disallowed redirect protocol: $scheme")
        }
        if (from.protocol == URLProtocol.HTTPS && resolved.protocol != URLProtocol.HTTPS) {
            throw IllegalArgumentException("HTTPS downgrade redirect blocked")
        }
        if (resolved.host.isBlank() || isBlockedHost(resolved.host)) {
            throw IllegalArgumentException("Redirect to local or restricted host blocked")
        }
        if (resolved.toString().length > MAX_URL_LENGTH) {
            throw IllegalArgumentException("Redirect URL exceeds max length")
        }
        return resolved
    }

    private fun resolveUrl(base: Url, location: String): Url? {
        return runCatching {
            val direct = parseUrl(location)
            if (direct != null && direct.host.isNotBlank()) {
                direct
            } else {
                URLBuilder(base).takeFrom(location).build()
            }
        }.getOrNull()
    }

    fun isSameOrigin(a: Url, b: Url): Boolean {
        return a.protocol.name.equals(b.protocol.name, ignoreCase = true) &&
            a.host.equals(b.host, ignoreCase = true) &&
            a.port == b.port
    }

    /**
     * Строковая обёртка для модулей без прямой зависимости от Ktor Url.
     */
    fun validateExternalUrlString(rawUrl: String?, allowHttp: Boolean = true): String? {
        return validateExternalUrl(rawUrl, allowHttp)?.toString()
    }

    /**
     * Строковая обёртка проверки перенаправления для модулей без прямой зависимости от Ktor Url.
     */
    fun validateRedirectString(fromUrl: String, location: String?, allowHttp: Boolean = true): String {
        val base = validateExternalUrl(fromUrl, allowHttp)
            ?: throw IllegalArgumentException("Invalid base URL")
        return validateRedirect(base, location, allowHttp).toString()
    }

    /**
     * Возвращает пару (host, port?) для отображения превью внешней ссылки.
     */
    fun extractHostAndPort(rawUrl: String): Pair<String, Int?>? {
        val parsed = validateExternalUrl(rawUrl, allowHttp = true) ?: return null
        val customPort = if (parsed.specifiedPort == 0 || parsed.specifiedPort == parsed.protocol.defaultPort) {
            null
        } else {
            parsed.port
        }
        return parsed.host to customPort
    }
}
