package com.kiktor.v2whitelist.util

/**
 * Санитизатор строк логов Android / Xray: маскирует прокси-URI с учётными данными,
 * токены, пароли, ключи и UUID до показа или копирования в буфер обмена.
 */
object LogSanitizer {

    private val proxyUriRegex = Regex(
        """\b(?:vless|vmess|trojan|ssr?|hysteria2|hy2|tuic|socks5?|https?)://[^\s"'<>]*@[^\s"'<>]+""",
        RegexOption.IGNORE_CASE
    )
    private val headerSecretRegex = Regex(
        """\b(Authorization|X-V2W-Token|Proxy-Authorization)(\s*:\s*)(?:Bearer\s+)?([^\s,;'"}\]]+)""",
        RegexOption.IGNORE_CASE
    )
    private val paramSecretRegex = Regex(
        """\b(password|passwd|pass|token|secret|pbk|sid|privatekey|presharedkey)(\s*[:=]\s*)([^\s,;'"&}\]]+)""",
        RegexOption.IGNORE_CASE
    )
    private val uuidRegex = Regex(
        """\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""",
        RegexOption.IGNORE_CASE
    )

    fun sanitizeLine(line: String): String {
        if (line.isEmpty()) return line
        var out = proxyUriRegex.replace(line, "<redacted-uri>")
        out = headerSecretRegex.replace(out) { mr -> "${mr.groupValues[1]}${mr.groupValues[2]}<redacted>" }
        out = paramSecretRegex.replace(out) { mr -> "${mr.groupValues[1]}${mr.groupValues[2]}<redacted>" }
        out = uuidRegex.replace(out, "<redacted-uuid>")
        return out
    }
}
