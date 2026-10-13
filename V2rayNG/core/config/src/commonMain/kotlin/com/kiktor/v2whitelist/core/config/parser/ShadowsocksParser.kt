package com.kiktor.v2whitelist.core.config.parser

import com.kiktor.v2whitelist.core.config.util.Base64Util
import com.kiktor.v2whitelist.core.config.util.UriParser
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem

object ShadowsocksParser {

    fun parse(str: String): ProfileItem? {
        return parseSip002(str) ?: parseLegacy(str)
    }

    fun parseSip002(str: String): ProfileItem? {
        val uri = UriParser.parse(str) ?: return null
        if (uri.scheme != "ss") return null
        if (uri.host.isEmpty() || uri.port <= 0 || uri.userInfo.isNullOrEmpty()) return null

        val config = ProfileItem.create(EConfigType.SHADOWSOCKS)
        config.remarks = uri.fragment ?: "none"
        config.server = uri.host
        config.serverPort = uri.port.toString()

        val decodedUser = if (uri.userInfo.contains(':')) {
            uri.userInfo
        } else {
            Base64Util.decode(uri.userInfo)
        }

        val parts = decodedUser.split(':', limit = 2)
        if (parts.size != 2) return null
        config.method = parts[0].lowercase()
        config.password = parts[1]

        val plugin = uri.queryParams["plugin"]
        if (!plugin.isNullOrEmpty() && plugin.contains("obfs=http")) {
            for (pair in plugin.split(';')) {
                val kv = pair.split('=', limit = 2)
                if (kv.size == 2) {
                    when (kv[0].trim()) {
                        "obfs-host" -> config.host = kv[1].trim()
                        "path" -> config.path = kv[1].trim()
                    }
                }
            }
            config.network = "tcp"
            config.headerType = "http"
        }

        return config
    }

    fun parseLegacy(str: String): ProfileItem? {
        val clean = str.trim()
        if (!clean.startsWith("ss://", ignoreCase = true)) return null
        var body = clean.substring(5)

        val remarks = if (body.contains('#')) {
            val frag = body.substringAfter('#')
            body = body.substringBefore('#')
            UriParser.decodeUrlComponent(frag).ifEmpty { "none" }
        } else {
            "none"
        }

        val decoded = if (body.contains('@')) {
            val userPart = body.substringBeforeLast('@')
            val hostPart = body.substringAfterLast('@')
            Base64Util.decode(userPart) + "@" + hostPart
        } else {
            Base64Util.decode(body)
        }

        val regex = "^(.+?):(.*)@(.+?):(\\d+?)/?$".toRegex()
        val match = regex.matchEntire(decoded) ?: return null

        val config = ProfileItem.create(EConfigType.SHADOWSOCKS)
        config.remarks = remarks
        config.method = match.groupValues[1].lowercase()
        config.password = match.groupValues[2]
        config.server = match.groupValues[3].removeSurrounding("[", "]")
        config.serverPort = match.groupValues[4]

        return config
    }

    fun toUri(config: ProfileItem): String {
        val userAuth = "${config.method}:${config.password}"
        val encodedUser = Base64Util.encode(userAuth).trimEnd('=')
        val hostPart = if (config.server.orEmpty().contains(':')) "[${config.server}]" else config.server.orEmpty()
        val fragmentStr = if (config.remarks.isNotEmpty()) "#" + UriParser.encodeUrlComponent(config.remarks) else ""

        return "ss://$encodedUser@$hostPart:${config.serverPort}$fragmentStr"
    }
}
