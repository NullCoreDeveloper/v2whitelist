package com.kiktor.v2whitelist.core.config.parser

import com.kiktor.v2whitelist.core.model.ProfileItem

object CommonParams {

    fun populateFromQueryParams(config: ProfileItem, query: Map<String, String>, allowInsecureDefault: Boolean) {
        config.network = query["type"] ?: "tcp"
        config.headerType = query["headerType"]
        config.host = query["host"]
        config.path = query["path"]

        config.seed = query["seed"]
        config.quicSecurity = query["quicSecurity"]
        config.quicKey = query["key"]
        config.mode = query["mode"]
        config.serviceName = query["serviceName"]
        config.authority = query["authority"]
        config.xhttpMode = query["mode"]
        config.xhttpExtra = query["extra"]

        val sec = query["security"]?.lowercase()
        config.security = when (sec) {
            "tls", "reality" -> sec
            else -> null
        }

        val allowInsecureKeys = arrayOf("insecure", "allowInsecure", "allow_insecure")
        config.insecure = when {
            allowInsecureKeys.any { query[it] == "1" || query[it].equals("true", ignoreCase = true) } -> true
            allowInsecureKeys.any { query[it] == "0" || query[it].equals("false", ignoreCase = true) } -> false
            else -> allowInsecureDefault
        }

        config.sni = query["sni"]
        config.fingerPrint = query["fp"]
        config.alpn = query["alpn"]
        config.echConfigList = query["ech"]
        config.pinnedCA256 = query["pcs"]
        config.publicKey = query["pbk"]
        config.shortId = query["sid"]
        config.spiderX = query["spx"]
        config.mldsa65Verify = query["pqv"]
        config.flow = query["flow"]
    }
}
