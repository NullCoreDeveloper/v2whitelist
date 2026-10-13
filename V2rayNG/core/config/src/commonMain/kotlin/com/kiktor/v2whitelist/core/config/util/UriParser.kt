package com.kiktor.v2whitelist.core.config.util

data class ParsedUri(
    val scheme: String,
    val userInfo: String?,
    val host: String,
    val port: Int,
    val path: String?,
    val queryParams: Map<String, String>,
    val fragment: String?
)

object UriParser {

    fun decodeUrlComponent(s: String): String {
        if (!s.contains('%') && !s.contains('+')) return s
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when (c) {
                '+' -> {
                    bytes.add(' '.code.toByte())
                    i++
                }
                '%' -> {
                    if (i + 2 < s.length) {
                        val hex = s.substring(i + 1, i + 3)
                        val b = hex.toIntOrNull(16)
                        if (b != null) {
                            bytes.add(b.toByte())
                            i += 3
                            continue
                        }
                    }
                    bytes.add('%'.code.toByte())
                    i++
                }
                else -> {
                    val encoded = c.toString().encodeToByteArray()
                    for (b in encoded) bytes.add(b)
                    i++
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    fun encodeUrlComponent(s: String): String {
        val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
        val sb = StringBuilder()
        for (b in s.encodeToByteArray()) {
            val c = b.toInt().toChar()
            if (c in allowed) {
                sb.append(c)
            } else {
                val hex = (b.toInt() and 0xFF).toString(16).uppercase()
                sb.append('%')
                if (hex.length == 1) sb.append('0')
                sb.append(hex)
            }
        }
        return sb.toString()
    }

    fun parse(raw: String): ParsedUri? {
        val trimmed = raw.trim()
        val schemeIdx = trimmed.indexOf("://")
        if (schemeIdx <= 0) return null
        val scheme = trimmed.substring(0, schemeIdx).lowercase()
        var remainder = trimmed.substring(schemeIdx + 3)

        // Fragment (#remarks)
        val fragment = if (remainder.contains('#')) {
            val frag = remainder.substringAfter('#')
            remainder = remainder.substringBefore('#')
            decodeUrlComponent(frag).ifEmpty { null }
        } else null

        // Query parameters (?key=value&...)
        val queryParams = mutableMapOf<String, String>()
        if (remainder.contains('?')) {
            val queryStr = remainder.substringAfter('?')
            remainder = remainder.substringBefore('?')
            for (param in queryStr.split('&')) {
                if (param.isEmpty()) continue
                val eqIdx = param.indexOf('=')
                if (eqIdx > 0) {
                    val k = param.substring(0, eqIdx)
                    val v = decodeUrlComponent(param.substring(eqIdx + 1))
                    queryParams[k] = v
                } else {
                    queryParams[param] = ""
                }
            }
        }

        // Path
        val path = if (remainder.contains('/')) {
            val p = remainder.substringAfter('/')
            remainder = remainder.substringBefore('/')
            "/$p"
        } else null

        // UserInfo (@)
        val userInfo = if (remainder.contains('@')) {
            val u = remainder.substringBeforeLast('@')
            remainder = remainder.substringAfterLast('@')
            decodeUrlComponent(u)
        } else null

        // Host and Port
        val host: String
        val port: Int
        if (remainder.startsWith('[')) {
            val closeBracket = remainder.indexOf(']')
            if (closeBracket <= 0) return null
            host = remainder.substring(1, closeBracket)
            val afterBracket = remainder.substring(closeBracket + 1)
            port = if (afterBracket.startsWith(':')) {
                afterBracket.substring(1).toIntOrNull() ?: 0
            } else 0
        } else {
            if (remainder.contains(':')) {
                host = remainder.substringBeforeLast(':')
                port = remainder.substringAfterLast(':').toIntOrNull() ?: 0
            } else {
                host = remainder
                port = 0
            }
        }

        return ParsedUri(
            scheme = scheme,
            userInfo = userInfo,
            host = host,
            port = port,
            path = path,
            queryParams = queryParams,
            fragment = fragment
        )
    }
}
