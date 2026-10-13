package com.kiktor.v2whitelist.core.model

import kotlinx.serialization.Serializable

const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BLOCKED = "block"
const val LOOPBACK = "127.0.0.1"
const val PORT_SOCKS = "10808"

@Serializable
data class ProfileItem(
    val configVersion: Int = 4,
    val configType: EConfigType,
    var subscriptionId: String = "",
    var addedTime: Long = currentTimeMillis(),

    var remarks: String = "",
    var description: String? = null,
    var server: String? = null,
    var serverPort: String? = null,

    var password: String? = null,
    var method: String? = null,
    var flow: String? = null,
    var username: String? = null,

    var network: String? = null,
    var headerType: String? = null,
    var host: String? = null,
    var path: String? = null,
    var seed: String? = null,
    var quicSecurity: String? = null,
    var quicKey: String? = null,
    var mode: String? = null,
    var serviceName: String? = null,
    var authority: String? = null,
    var xhttpMode: String? = null,
    var xhttpExtra: String? = null,

    var security: String? = null,
    var sni: String? = null,
    var alpn: String? = null,
    var fingerPrint: String? = null,
    var insecure: Boolean? = null,
    var echConfigList: String? = null,
    var echForceQuery: String? = null,
    var pinnedCA256: String? = null,

    var publicKey: String? = null,
    var shortId: String? = null,
    var spiderX: String? = null,
    var mldsa65Verify: String? = null,

    var secretKey: String? = null,
    var preSharedKey: String? = null,
    var localAddress: String? = null,
    var reserved: String? = null,
    var mtu: Int? = null,
    // Мусорные UDP-пакеты перед WG-хендшейком (обход DPI). Только для WireGuard/WARP.
    var junkCount: Int? = null,
    var junkMin: Int? = null,
    var junkMax: Int? = null,

    var obfsPassword: String? = null,
    var portHopping: String? = null,
    var portHoppingInterval: String? = null,
    var pinSHA256: String? = null,
    var bandwidthDown: String? = null,
    var bandwidthUp: String? = null,

    var policyGroupType: String? = null,
    var policyGroupSubscriptionId: String? = null,
    var policyGroupFilter: String? = null,
) {
    companion object {
        fun create(configType: EConfigType): ProfileItem {
            return ProfileItem(configType = configType)
        }
    }

    fun getAllOutboundTags(): MutableList<String> {
        return mutableListOf(TAG_PROXY, TAG_DIRECT, TAG_BLOCKED)
    }

    fun isSecure(): Boolean {
        if (insecure == true) return false

        val sec = security?.lowercase() ?: ""
        val hasTls = sec == "tls" || sec == "reality" || sec == "xtls"

        return when (configType) {
            EConfigType.VLESS, EConfigType.VMESS -> hasTls
            EConfigType.SHADOWSOCKS -> true
            EConfigType.TROJAN, EConfigType.HYSTERIA2, EConfigType.WIREGUARD -> true
            else -> true
        }
    }

    fun getServerAddressAndPort(): String {
        if (server.isNullOrEmpty() && configType == EConfigType.CUSTOM) {
            return "$LOOPBACK:$PORT_SOCKS"
        }
        val s = server.orEmpty()
        val formattedServer = if (s.contains(':') && !s.startsWith('[') && !s.endsWith(']')) {
            "[$s]"
        } else {
            s
        }
        return "$formattedServer:$serverPort"
    }

    override fun equals(other: Any?): Boolean {
        if (other == null) return false
        val obj = other as? ProfileItem ?: return false

        return (this.server == obj.server
                && this.serverPort == obj.serverPort
                && this.password == obj.password
                && this.method == obj.method
                && this.flow == obj.flow
                && this.username == obj.username

                && this.network == obj.network
                && this.headerType == obj.headerType
                && this.host == obj.host
                && this.path == obj.path
                && this.seed == obj.seed
                && this.quicSecurity == obj.quicSecurity
                && this.quicKey == obj.quicKey
                && this.mode == obj.mode
                && this.serviceName == obj.serviceName
                && this.authority == obj.authority
                && this.xhttpMode == obj.xhttpMode

                && this.security == obj.security
                && this.sni == obj.sni
                && this.alpn == obj.alpn
                && this.fingerPrint == obj.fingerPrint
                && this.publicKey == obj.publicKey
                && this.shortId == obj.shortId

                && this.secretKey == obj.secretKey
                && this.localAddress == obj.localAddress
                && this.reserved == obj.reserved
                && this.mtu == obj.mtu

                && this.obfsPassword == obj.obfsPassword
                && this.portHopping == obj.portHopping
                && this.portHoppingInterval == obj.portHoppingInterval
                && this.pinnedCA256 == obj.pinnedCA256
                )
    }

    override fun hashCode(): Int {
        var result = configVersion
        result = 31 * result + configType.hashCode()
        result = 31 * result + (server?.hashCode() ?: 0)
        result = 31 * result + (serverPort?.hashCode() ?: 0)
        return result
    }
}
