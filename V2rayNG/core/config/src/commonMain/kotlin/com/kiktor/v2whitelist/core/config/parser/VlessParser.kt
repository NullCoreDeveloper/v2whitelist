package com.kiktor.v2whitelist.core.config.parser

import com.kiktor.v2whitelist.core.config.util.UriParser
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem

object VlessParser {

    fun parse(str: String, allowInsecureDefault: Boolean = false): ProfileItem? {
        val uri = UriParser.parse(str) ?: return null
        if (uri.scheme != "vless") return null
        if (uri.host.isEmpty() || uri.port <= 0 || uri.userInfo.isNullOrEmpty()) return null

        val config = ProfileItem.create(EConfigType.VLESS)
        config.remarks = uri.fragment ?: "none"
        config.server = uri.host
        config.serverPort = uri.port.toString()
        config.password = uri.userInfo
        config.method = uri.queryParams["encryption"] ?: "none"

        CommonParams.populateFromQueryParams(config, uri.queryParams, allowInsecureDefault)

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
        config.method?.let { query.add("encryption=$it") }
        config.flow?.let { query.add("flow=$it") }
        config.security?.let { query.add("security=$it") }
        config.sni?.let { query.add("sni=$it") }
        config.fingerPrint?.let { query.add("fp=$it") }
        config.alpn?.let { query.add("alpn=$it") }
        config.publicKey?.let { query.add("pbk=$it") }
        config.shortId?.let { query.add("sid=$it") }
        config.spiderX?.let { query.add("spx=${UriParser.encodeUrlComponent(it)}") }
        if (config.insecure == true) query.add("insecure=1")

        val queryStr = if (query.isNotEmpty()) "?" + query.joinToString("&") else ""
        val fragmentStr = if (config.remarks.isNotEmpty()) "#" + UriParser.encodeUrlComponent(config.remarks) else ""

        return "vless://$userInfo@$hostPart:${config.serverPort}$queryStr$fragmentStr"
    }
}
