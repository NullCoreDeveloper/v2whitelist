package com.kiktor.v2whitelist.core.config.parser

import com.kiktor.v2whitelist.core.config.util.UriParser
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem

object HttpParser {

    fun parse(str: String): ProfileItem? {
        val uri = UriParser.parse(str) ?: return null
        if (uri.scheme != "http") return null
        if (uri.host.isEmpty() || uri.port <= 0) return null

        val config = ProfileItem.create(EConfigType.HTTP)
        config.remarks = uri.fragment ?: "none"
        config.server = uri.host
        config.serverPort = uri.port.toString()

        uri.userInfo?.let {
            if (it.contains(':')) {
                val parts = it.split(':', limit = 2)
                config.username = parts[0]
                config.password = parts[1]
            } else {
                config.username = it
            }
        }

        return config
    }

    fun toUri(config: ProfileItem): String {
        val hostPart = if (config.server.orEmpty().contains(':')) "[${config.server}]" else config.server.orEmpty()
        val userInfo = if (!config.username.isNullOrEmpty()) {
            val pass = if (!config.password.isNullOrEmpty()) ":${config.password}" else ""
            UriParser.encodeUrlComponent("${config.username}$pass") + "@"
        } else ""
        val fragmentStr = if (config.remarks.isNotEmpty()) "#" + UriParser.encodeUrlComponent(config.remarks) else ""

        return "http://$userInfo$hostPart:${config.serverPort}$fragmentStr"
    }
}
