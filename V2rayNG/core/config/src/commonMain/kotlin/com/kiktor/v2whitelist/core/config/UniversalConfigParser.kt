package com.kiktor.v2whitelist.core.config

import com.kiktor.v2whitelist.core.config.parser.HttpParser
import com.kiktor.v2whitelist.core.config.parser.Hysteria2Parser
import com.kiktor.v2whitelist.core.config.parser.ShadowsocksParser
import com.kiktor.v2whitelist.core.config.parser.SocksParser
import com.kiktor.v2whitelist.core.config.parser.TrojanParser
import com.kiktor.v2whitelist.core.config.parser.VlessParser
import com.kiktor.v2whitelist.core.config.parser.VmessParser
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem

object UniversalConfigParser {

    /**
     * Parses a raw configuration URI string into a ProfileItem object.
     * Supports: VLESS, VMess, Trojan, Shadowsocks, Hysteria 2, SOCKS, HTTP.
     * Pure Kotlin Multiplatform - works on Android, Desktop, and iOS.
     */
    fun parse(rawUri: String?, allowInsecure: Boolean = false): ProfileItem? {
        val str = rawUri?.trim() ?: return null
        if (str.isEmpty()) return null

        return when {
            str.startsWith("vless://", ignoreCase = true) -> VlessParser.parse(str, allowInsecure)
            str.startsWith("vmess://", ignoreCase = true) -> VmessParser.parse(str, allowInsecure)
            str.startsWith("trojan://", ignoreCase = true) -> TrojanParser.parse(str, allowInsecure)
            str.startsWith("ss://", ignoreCase = true) -> ShadowsocksParser.parse(str)
            str.startsWith("hysteria2://", ignoreCase = true) || str.startsWith("hy2://", ignoreCase = true) ->
                Hysteria2Parser.parse(str, allowInsecure)
            str.startsWith("socks://", ignoreCase = true) || str.startsWith("socks5://", ignoreCase = true) ->
                SocksParser.parse(str)
            str.startsWith("http://", ignoreCase = true) -> HttpParser.parse(str)
            else -> null
        }
    }

    /**
     * Converts a ProfileItem back to its standard URI representation.
     */
    fun toUri(config: ProfileItem): String? {
        return when (config.configType) {
            EConfigType.VLESS -> VlessParser.toUri(config)
            EConfigType.VMESS -> VmessParser.toUri(config)
            EConfigType.TROJAN -> TrojanParser.toUri(config)
            EConfigType.SHADOWSOCKS -> ShadowsocksParser.toUri(config)
            EConfigType.HYSTERIA2 -> Hysteria2Parser.toUri(config)
            EConfigType.SOCKS -> SocksParser.toUri(config)
            EConfigType.HTTP -> HttpParser.toUri(config)
            else -> null
        }
    }
}
