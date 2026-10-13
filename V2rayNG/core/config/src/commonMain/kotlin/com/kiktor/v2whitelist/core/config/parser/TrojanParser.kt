package com.kiktor.v2whitelist.core.config.parser

import com.kiktor.v2whitelist.core.config.util.UriParser
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem

object TrojanParser {

    fun parse(str: String, allowInsecureDefault: Boolean = false): ProfileItem? {
        val uri = UriParser.parse(str) ?: return null
        if (uri.scheme != "trojan") return null
        if (uri.host.isEmpty() || uri.port <= 0 || uri.userInfo.isNullOrEmpty()) return null

        val config = ProfileItem.create(EConfigType.TROJAN)
        config.remarks = uri.fragment ?: "none"
        config.server = uri.host
        config.serverPort = uri.port.toString()
        config.password = uri.userInfo

        CommonParams.populateFromQueryParams(config, uri.queryParams, allowInsecureDefault)
        if (config.security == null) {
            config.security = "tls"
        }

        return config
    }

    fun toUri(config: ProfileItem): String {
        val hostPart = if (config.server.orEmpty().contains(':')) "[${config.server}]" else config.server.orEmpty()
        val userInfo = UriParser.encodeUrlComponent(config.password.orEmpty())
        val query = mutableListOf<String>()

        config.network?.let { query.add("type=$it") }
        config.headerType?.let { query.add("headerType=$it") }
        config.host?.let { query.add("host=${UriParser.encodeUrlComponent(it)}") }
        config.path?.let { query.add("path=${UriParser.encodeUrlComponent(it)}") }
        config.security?.let { query.add("security=$it") }
        config.sni?.let { query.add("sni=$it") }
        config.alpn?.let { query.add("alpn=$it") }
        if (config.insecure == true) query.add("insecure=1")

        val queryStr = if (query.isNotEmpty()) "?" + query.joinToString("&") else ""
        val fragmentStr = if (config.remarks.isNotEmpty()) "#" + UriParser.encodeUrlComponent(config.remarks) else ""

        return "trojan://$userInfo@$hostPart:${config.serverPort}$queryStr$fragmentStr"
    }
}
