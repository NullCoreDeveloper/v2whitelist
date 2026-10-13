package com.kiktor.v2whitelist.core.config.parser

import com.kiktor.v2whitelist.core.config.util.Base64Util
import com.kiktor.v2whitelist.core.config.util.UriParser
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem
import kotlinx.serialization.json.Json

object VmessParser {

    private val jsonHelper = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    fun parse(str: String, allowInsecureDefault: Boolean = false): ProfileItem? {
        val clean = str.trim()
        if (!clean.startsWith("vmess://", ignoreCase = true)) return null

        if (clean.contains('?') && clean.contains('&')) {
            return parseQueryFormat(clean, allowInsecureDefault)
        }
        return parseBase64JsonFormat(clean, allowInsecureDefault)
    }

    fun parseBase64JsonFormat(str: String, allowInsecureDefault: Boolean): ProfileItem? {
        val raw = str.substring(8) // remove vmess://
        val base64 = raw.substringBefore('#')
        val fragment = if (raw.contains('#')) UriParser.decodeUrlComponent(raw.substringAfter('#')) else ""

        val decodedJson = Base64Util.decode(base64)
        if (decodedJson.isBlank()) return null

        val vmessData = try {
            jsonHelper.decodeFromString<VmessJson>(decodedJson)
        } catch (_: Exception) {
            return null
        }

        if (vmessData.add.isNullOrBlank() || vmessData.port.isNullOrBlank() || vmessData.id.isNullOrBlank()) {
            return null
        }

        val config = ProfileItem.create(EConfigType.VMESS)
        config.remarks = if (!vmessData.ps.isNullOrBlank()) vmessData.ps else fragment.ifEmpty { "none" }
        config.server = vmessData.add
        config.serverPort = vmessData.port
        config.password = vmessData.id
        config.method = if (vmessData.scy.isNullOrBlank()) "auto" else vmessData.scy

        config.network = if (vmessData.net.isNullOrBlank()) "tcp" else vmessData.net
        config.headerType = vmessData.type
        config.host = vmessData.host
        config.path = vmessData.path

        when (config.network?.lowercase()) {
            "kcp" -> config.seed = vmessData.path
            "grpc" -> {
                config.mode = vmessData.type
                config.serviceName = vmessData.path
            }
        }

        if (vmessData.tls.equals("tls", ignoreCase = true)) {
            config.security = "tls"
        }
        config.sni = vmessData.sni
        config.alpn = vmessData.alpn
        config.fingerPrint = vmessData.fp
        config.insecure = allowInsecureDefault

        return config
    }

    private fun parseQueryFormat(str: String, allowInsecureDefault: Boolean): ProfileItem? {
        val uri = UriParser.parse(str) ?: return null
        if (uri.host.isEmpty() || uri.port <= 0 || uri.userInfo.isNullOrEmpty()) return null

        val config = ProfileItem.create(EConfigType.VMESS)
        config.remarks = uri.fragment ?: "none"
        config.server = uri.host
        config.serverPort = uri.port.toString()
        config.password = uri.userInfo
        config.method = uri.queryParams["encryption"] ?: "auto"

        CommonParams.populateFromQueryParams(config, uri.queryParams, allowInsecureDefault)

        return config
    }

    fun toUri(config: ProfileItem): String {
        val vmessData = VmessJson(
            v = "2",
            ps = config.remarks,
            add = config.server.orEmpty(),
            port = config.serverPort.orEmpty(),
            id = config.password.orEmpty(),
            aid = "0",
            scy = config.method ?: "auto",
            net = config.network ?: "tcp",
            type = config.headerType ?: "none",
            host = config.host.orEmpty(),
            path = config.path.orEmpty(),
            tls = if (config.security == "tls") "tls" else "",
            sni = config.sni.orEmpty(),
            alpn = config.alpn.orEmpty(),
            fp = config.fingerPrint.orEmpty()
        )
        val jsonString = jsonHelper.encodeToString(VmessJson.serializer(), vmessData)
        val base64 = Base64Util.encode(jsonString).trimEnd('=')
        return "vmess://$base64"
    }
}
