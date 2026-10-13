package com.kiktor.v2whitelist.core.config.parser

import com.kiktor.v2whitelist.core.config.util.UriParser
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem

object Hysteria2Parser {

    fun parse(str: String, allowInsecureDefault: Boolean = false): ProfileItem? {
        val uri = UriParser.parse(str) ?: return null
        if (uri.scheme != "hysteria2" && uri.scheme != "hy2") return null
        if (uri.host.isEmpty() || uri.userInfo.isNullOrEmpty()) return null

        val config = ProfileItem.create(EConfigType.HYSTERIA2)
        config.remarks = uri.fragment ?: "none"
        config.server = uri.host
        config.serverPort = if (uri.port > 0) uri.port.toString() else "443"
        config.password = uri.userInfo
        config.security = "tls"
        config.network = "hysteria"

        CommonParams.populateFromQueryParams(config, uri.queryParams, allowInsecureDefault)

        config.obfsPassword = uri.queryParams["obfs-password"]
        config.portHopping = uri.queryParams["mport"]
        if (!config.portHopping.isNullOrEmpty()) {
            config.portHoppingInterval = uri.queryParams["mportHopInt"]
        }
        config.pinnedCA256 = uri.queryParams["pinSHA256"]

        return config
    }

    fun toUri(config: ProfileItem): String {
        val hostPart = if (config.server.orEmpty().contains(':')) "[${config.server}]" else config.server.orEmpty()
        val userInfo = UriParser.encodeUrlComponent(config.password.orEmpty())
        val query = mutableListOf<String>()

        query.add("security=tls")
        config.sni?.let { query.add("sni=${UriParser.encodeUrlComponent(it)}") }
        config.alpn?.let { query.add("alpn=${UriParser.encodeUrlComponent(it)}") }
        if (config.insecure == true) query.add("insecure=1")

        config.obfsPassword?.let {
            query.add("obfs=salamander")
            query.add("obfs-password=${UriParser.encodeUrlComponent(it)}")
        }
        config.portHopping?.let { query.add("mport=${UriParser.encodeUrlComponent(it)}") }
        config.portHoppingInterval?.let { query.add("mportHopInt=${UriParser.encodeUrlComponent(it)}") }
        config.pinnedCA256?.let { query.add("pinSHA256=${UriParser.encodeUrlComponent(it)}") }

        val queryStr = if (query.isNotEmpty()) "?" + query.joinToString("&") else ""
        val fragmentStr = if (config.remarks.isNotEmpty()) "#" + UriParser.encodeUrlComponent(config.remarks) else ""

        return "hysteria2://$userInfo@$hostPart:${config.serverPort}$queryStr$fragmentStr"
    }
}
