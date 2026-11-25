package com.kiktor.v2whitelist.core.config.xray

enum class RouteVerdict {
    DIRECT,
    PROXY
}

data class DomainRouteTestResult(
    val normalizedHost: String,
    val verdict: RouteVerdict,
    val matchedRule: String,
    val note: String? = null
)

/**
 * Симулирует порядок правил маршрутизации из [XrayConfigGenerator], чтобы пользователь
 * мог заранее проверить, куда пойдёт домен/IP (Direct или Proxy) и какое правило сработает.
 */
object DomainRouteTester {

    private val BYPASS_RU_EXPLICIT_DOMAINS = listOf(
        "gosuslugi.ru",
        "sber.ru",
        "sberbank.ru",
        "tbank.ru",
        "tinkoff.ru",
        "ozon.ru",
        "wildberries.ru",
        "wb.ru",
        "avito.ru",
        "yandex.ru",
        "ya.ru",
        "vk.com",
        "mail.ru"
    )

    private val BYPASS_RU_TLDS = listOf(".ru", ".su", ".xn--p1ai", ".рф")

    fun test(
        input: String,
        options: XrayGeneratorOptions,
        resolvedIps: List<String> = emptyList()
    ): DomainRouteTestResult {
        val host = normalizeInput(input)
        if (host.isBlank()) {
            return DomainRouteTestResult(
                normalizedHost = "",
                verdict = RouteVerdict.PROXY,
                matchedRule = "Пустой запрос"
            )
        }

        val candidatesIps = buildList {
            if (isIpAddress(host)) add(host)
            resolvedIps.forEach { ip ->
                val trimmed = ip.trim()
                if (trimmed.isNotEmpty() && !contains(trimmed)) add(trimmed)
            }
        }

        // 1. Bypass RU (первое правило в XrayConfigGenerator)
        if (options.bypassRussianServices && !isIpAddress(host)) {
            val matchedExplicit = BYPASS_RU_EXPLICIT_DOMAINS.firstOrNull { matchesDomainSuffix(host, it) }
            if (matchedExplicit != null) {
                return DomainRouteTestResult(
                    normalizedHost = host,
                    verdict = RouteVerdict.DIRECT,
                    matchedRule = "Bypass RU (domain:$matchedExplicit)"
                )
            }
            val matchedTld = BYPASS_RU_TLDS.firstOrNull { host.endsWith(it) && host.length > it.length }
            if (matchedTld != null) {
                return DomainRouteTestResult(
                    normalizedHost = host,
                    verdict = RouteVerdict.DIRECT,
                    matchedRule = "Bypass RU (geosite:category-ru / $matchedTld)"
                )
            }
        }

        // 2. Private IPs (geoip:private)
        if (options.bypassPrivateIps) {
            val privateHit = candidatesIps.firstOrNull { isPrivateOrLocalIp(it) }
            if (privateHit != null) {
                return DomainRouteTestResult(
                    normalizedHost = host,
                    verdict = RouteVerdict.DIRECT,
                    matchedRule = "Private IP / LAN (geoip:private: $privateHit)"
                )
            }
        }

        // 3. Custom Proxy Domains / IPs (приоритет над whitelist и custom direct)
        val matchedProxy = findMatchingUserRule(host, candidatesIps, options.customProxyDomains)
        if (matchedProxy != null) {
            return DomainRouteTestResult(
                normalizedHost = host,
                verdict = RouteVerdict.PROXY,
                matchedRule = "Custom Proxy ($matchedProxy)"
            )
        }

        // 4. Режим белого списка (всё, что не попало в customProxyDomains — Direct)
        if (options.domainWhitelistMode) {
            return DomainRouteTestResult(
                normalizedHost = host,
                verdict = RouteVerdict.DIRECT,
                matchedRule = "Whitelist Mode (всё вне списка Proxy → Direct)"
            )
        }

        // 5. Custom Direct Domains / IPs
        val matchedDirect = findMatchingUserRule(host, candidatesIps, options.customDirectDomains)
        if (matchedDirect != null) {
            return DomainRouteTestResult(
                normalizedHost = host,
                verdict = RouteVerdict.DIRECT,
                matchedRule = "Custom Direct ($matchedDirect)"
            )
        }

        // 6. Правило по умолчанию -> Proxy
        val geoNote = if (options.bypassRussianServices) {
            "По статическим правилам — Proxy. Если домен есть в geosite:category-ru или его IP в geoip:ru, Xray направит его в Direct."
        } else null

        return DomainRouteTestResult(
            normalizedHost = host,
            verdict = RouteVerdict.PROXY,
            matchedRule = "Default Route (proxy)",
            note = geoNote
        )
    }

    fun normalizeInput(raw: String): String {
        var s = raw.trim()
        val schemeIdx = s.indexOf("://")
        if (schemeIdx >= 0) {
            s = s.substring(schemeIdx + 3)
        }
        // userinfo@host
        val atIdx = s.indexOf('@')
        if (atIdx >= 0 && atIdx < s.indexOf('/').let { if (it < 0) s.length else it }) {
            s = s.substring(atIdx + 1)
        }
        // path / query / fragment
        val cutIdx = s.indexOfAny(charArrayOf('/', '?', '#'))
        if (cutIdx >= 0) {
            s = s.substring(0, cutIdx)
        }
        // [IPv6]:port или [IPv6]
        if (s.startsWith("[")) {
            val endBracket = s.indexOf(']')
            if (endBracket > 0) {
                s = s.substring(1, endBracket)
            }
        } else {
            // host:port (только если ровно одно двоеточие, чтобы не ломать голый IPv6)
            val firstColon = s.indexOf(':')
            val lastColon = s.lastIndexOf(':')
            if (firstColon > 0 && firstColon == lastColon) {
                s = s.substring(0, firstColon)
            }
        }
        return s.trimEnd('.').lowercase()
    }

    private fun findMatchingUserRule(
        host: String,
        ips: List<String>,
        rules: List<String>
    ): String? {
        for (rawRule in rules) {
            val rule = rawRule.trim()
            if (rule.isEmpty()) continue
            if (matchesSingleRule(host, ips, rule)) {
                return rule
            }
        }
        return null
    }

    private fun matchesSingleRule(host: String, ips: List<String>, rule: String): Boolean {
        val lower = rule.lowercase()
        return when {
            lower.startsWith("geosite:") || lower.startsWith("geoip:") -> false
            lower.startsWith("full:") -> host == lower.removePrefix("full:")
            lower.startsWith("domain:") -> matchesDomainSuffix(host, lower.removePrefix("domain:"))
            lower.startsWith("keyword:") -> host.contains(lower.removePrefix("keyword:"))
            lower.startsWith("regexp:") -> runCatching {
                Regex(rule.substring("regexp:".length)).containsMatchIn(host)
            }.getOrDefault(false)
            isIpv4Cidr(lower) -> ips.any { matchesIpv4Cidr(it, lower) }
            isIpAddress(lower) -> ips.any { it.equals(lower, ignoreCase = true) }
            else -> matchesDomainSuffix(host, lower)
        }
    }

    private fun matchesDomainSuffix(host: String, domain: String): Boolean {
        val cleanDomain = domain.trimEnd('.').lowercase()
        if (cleanDomain.isEmpty()) return false
        return host == cleanDomain || host.endsWith(".$cleanDomain")
    }

    private fun isIpAddress(value: String): Boolean {
        return parseIpv4(value) != null || (value.contains(':') && !value.startsWith("domain:"))
    }

    private fun isIpv4Cidr(value: String): Boolean {
        val slash = value.indexOf('/')
        if (slash <= 0) return false
        val ipPart = value.substring(0, slash)
        val prefix = value.substring(slash + 1).toIntOrNull() ?: return false
        return parseIpv4(ipPart) != null && prefix in 0..32
    }

    private fun matchesIpv4Cidr(ip: String, cidr: String): Boolean {
        val ipVal = parseIpv4(ip) ?: return false
        val slash = cidr.indexOf('/')
        val netVal = parseIpv4(cidr.substring(0, slash)) ?: return false
        val prefix = cidr.substring(slash + 1).toIntOrNull() ?: return false
        if (prefix == 0) return true
        val mask = (-1L shl (32 - prefix)) and 0xFFFF_FFFFL
        return (ipVal and mask) == (netVal and mask)
    }

    private fun isPrivateOrLocalIp(ip: String): Boolean {
        val v4 = parseIpv4(ip)
        if (v4 != null) {
            val b0 = (v4 ushr 24) and 0xFF
            val b1 = (v4 ushr 16) and 0xFF
            return b0 == 10L || // 10.0.0.0/8
                b0 == 127L || // 127.0.0.0/8
                (b0 == 172L && b1 in 16L..31L) || // 172.16.0.0/12
                (b0 == 192L && b1 == 168L) || // 192.168.0.0/16
                (b0 == 169L && b1 == 254L) // 169.254.0.0/16
        }
        val lower = ip.lowercase()
        return lower == "::1" || lower.startsWith("fe80:") || lower.startsWith("fc") || lower.startsWith("fd")
    }

    private fun parseIpv4(ip: String): Long? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        var res = 0L
        for (p in parts) {
            val octet = p.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            res = (res shl 8) or octet.toLong()
        }
        return res
    }
}
