package com.kiktor.v2whitelist.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

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

    // Точечная ClientHello-фрагментация для профиля (Xray dialerProxy -> freedom fragment)
    var fragmentEnabled: Boolean? = null,
    var fragmentPackets: String? = null,    // "1-3" (TCP Split) или "tlshello" (TLS Record)
    var fragmentLength: String? = null,     // "50-100"
    var fragmentInterval: String? = null,   // "10-20"
) {
    companion object {
        private val extraJson = Json { ignoreUnknownKeys = true; isLenient = true }

        val AEAD_SS_METHODS = setOf(
            "aes-128-gcm",
            "aead_aes_128_gcm",
            "aes-192-gcm",
            "aes-256-gcm",
            "aead_aes_256_gcm",
            "chacha20-poly1305",
            "aead_chacha20_poly1305",
            "chacha20-ietf-poly1305",
            "xchacha20-poly1305",
            "aead_xchacha20_poly1305",
            "xchacha20-ietf-poly1305"
        )

        fun isSupportedAeadShadowsocks(method: String?): Boolean {
            val normalized = method?.trim()?.lowercase().orEmpty()
            return normalized in AEAD_SS_METHODS || normalized.startsWith("2022-blake3-")
        }

        fun create(configType: EConfigType): ProfileItem {
            return ProfileItem(configType = configType)
        }
    }

    fun getAllOutboundTags(): MutableList<String> {
        return mutableListOf(TAG_PROXY, TAG_DIRECT, TAG_BLOCKED)
    }

    /**
     * Возвращает причину, по которой профиль считается небезопасным, или null если замечаний нет.
     */
    fun securityIssueReason(): String? {
        val hasPinnedCert = !pinnedCA256.isNullOrBlank() || !pinSHA256.isNullOrBlank()
        if (insecure == true && !hasPinnedCert) {
            return "Отключена проверка TLS-сертификата"
        }

        val sec = security?.trim()?.lowercase().orEmpty()
        val hasTls = sec == "tls" || sec == "reality" || sec == "xtls"

        when (configType) {
            EConfigType.VLESS -> {
                val enc = method?.trim()?.lowercase().orEmpty()
                val hasVlessEncryption = enc.isNotEmpty() && enc != "none"
                if (!hasTls && !hasVlessEncryption) {
                    return "Отсутствует TLS/Reality шифрование"
                }
            }
            EConfigType.VMESS -> {
                val scy = method?.trim()?.lowercase().orEmpty()
                if (!hasTls) {
                    return if (scy in setOf("none", "zero")) {
                        "Отключено шифрование VMess"
                    } else {
                        "Отсутствует TLS/Reality шифрование"
                    }
                }
            }
            EConfigType.TROJAN, EConfigType.HYSTERIA2 -> {
                if (sec == "none") {
                    return "Отключено TLS-шифрование"
                }
            }
            EConfigType.SHADOWSOCKS -> {
                val m = method?.trim()?.lowercase().orEmpty()
                if (m.isEmpty() || m in setOf("none", "plain", "table")) {
                    return "Отключено шифрование Shadowsocks"
                }
                if (!isSupportedAeadShadowsocks(m)) {
                    return "Устаревший не-AEAD шифр Shadowsocks"
                }
            }
            else -> Unit
        }

        val net = network?.trim()?.lowercase().orEmpty()
        if ((net == "xhttp" || net == "splithttp") && !xhttpExtra.isNullOrBlank()) {
            xhttpDownloadSecurityIssue()?.let { return it }
        }

        return null
    }

    private fun xhttpDownloadSecurityIssue(): String? {
        val raw = xhttpExtra?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 * 1024 } ?: return null
        val root = runCatching { extraJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
        val download = root["downloadSettings"] as? JsonObject ?: return null
        val tls = download["tlsSettings"] as? JsonObject
        val pinned = (tls?.get("pinnedPeerCertSha256") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val allowInsecure = (tls?.get("allowInsecure") as? JsonPrimitive)?.let {
            it.booleanOrNull == true || it.contentOrNull == "1"
        } == true
        if (allowInsecure && pinned == null) {
            return "Отключена проверка TLS-сертификата в xhttp downloadSettings"
        }
        val dlSec = (download["security"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase().orEmpty()
        if (dlSec.isEmpty() || dlSec == "none") {
            return "Отсутствует TLS в xhttp downloadSettings"
        }
        return null
    }

    fun isSecure(): Boolean = securityIssueReason() == null

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
        if (this === other) return true
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
