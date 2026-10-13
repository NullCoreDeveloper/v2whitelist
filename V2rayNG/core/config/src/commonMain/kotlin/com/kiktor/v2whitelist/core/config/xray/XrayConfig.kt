package com.kiktor.v2whitelist.core.config.xray

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class XrayConfig(
    val log: XrayLog = XrayLog(),
    val inbounds: List<XrayInbound> = emptyList(),
    val outbounds: List<XrayOutbound> = emptyList(),
    val routing: XrayRouting = XrayRouting(),
    val dns: XrayDns? = null
)

@Serializable
data class XrayLog(
    val loglevel: String = "warning"
)

@Serializable
data class XrayInbound(
    val tag: String,
    val port: Int,
    val protocol: String,
    val listen: String = "127.0.0.1",
    val settings: InboundSettings? = null,
    val sniffing: InboundSniffing? = null
)

@Serializable
data class InboundSettings(
    val auth: String = "noauth",
    val udp: Boolean = true
)

@Serializable
data class InboundSniffing(
    val enabled: Boolean = true,
    val destOverride: List<String> = listOf("http", "tls", "fakedns"),
    val routeOnly: Boolean = false
)

@Serializable
data class OutboundProxySettings(
    val tag: String? = null
)

@Serializable
data class XrayOutbound(
    val tag: String = "proxy",
    val protocol: String,
    val settings: OutboundSettings? = null,
    val streamSettings: XrayStreamSettings? = null,
    val proxySettings: OutboundProxySettings? = null,
    val mux: XrayMux? = null
)

@Serializable
data class OutboundSettings(
    // VLESS / VMess
    val vnext: List<VnextServer>? = null,
    // Trojan / Shadowsocks / Socks / HTTP
    val servers: List<ServerEntry>? = null,
    // Freedom (direct)
    val domainStrategy: String? = null,
    // Blackhole (block)
    val response: BlackholeResponse? = null,
    // Hysteria 2
    val version: Int? = null,
    val auth: String? = null,
    val up: String? = null,
    val down: String? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BlackholeResponse(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "http"
)

@Serializable
data class VnextServer(
    val address: String,
    val port: Int,
    val users: List<VnextUser>
)

@Serializable
data class VnextUser(
    val id: String,
    val encryption: String? = null,
    val flow: String? = null,
    val alterId: Int? = null,
    val security: String? = null
)

@Serializable
data class ServerEntry(
    val address: String,
    val port: Int,
    val password: String? = null,
    val method: String? = null,
    val flow: String? = null
)

@Serializable
data class XrayStreamSettings(
    val network: String = "tcp",
    val security: String? = null,
    val tcpSettings: TcpSettings? = null,
    val wsSettings: WsSettings? = null,
    val grpcSettings: GrpcSettings? = null,
    val httpupgradeSettings: HttpUpgradeSettings? = null,
    val xhttpSettings: XhttpSettings? = null,
    val tlsSettings: TlsSettings? = null,
    val realitySettings: RealitySettings? = null,
    val hysteriaSettings: HysteriaSettings? = null
)

@Serializable
data class TcpSettings(
    val header: HeaderTypeSettings? = null
)

@Serializable
data class HeaderTypeSettings(
    val type: String = "none",
    val request: HttpRequestSettings? = null
)

@Serializable
data class HttpRequestSettings(
    val path: List<String>? = null,
    val headers: Map<String, List<String>>? = null
)

@Serializable
data class WsSettings(
    val path: String = "/",
    val headers: Map<String, String>? = null
)

@Serializable
data class GrpcSettings(
    val serviceName: String? = null,
    val multiMode: Boolean = false,
    val authority: String? = null
)

@Serializable
data class HttpUpgradeSettings(
    val host: String? = null,
    val path: String = "/"
)

@Serializable
data class XhttpSettings(
    val host: String? = null,
    val path: String = "/",
    val mode: String? = null,
    val extra: JsonElement? = null
)

@Serializable
data class TlsSettings(
    val serverName: String? = null,
    val allowInsecure: Boolean = false,
    val alpn: List<String>? = null,
    val fingerprint: String? = null,
    val pinnedPeerCertSha256: String? = null
)

@Serializable
data class RealitySettings(
    val serverName: String? = null,
    val fingerprint: String? = null,
    val show: Boolean = false,
    val publicKey: String? = null,
    val shortId: String? = null,
    val spiderX: String? = null
)

@Serializable
data class HysteriaSettings(
    val version: Int = 2,
    val auth: String? = null,
    val udphop: HysteriaUdpHop? = null
)

@Serializable
data class HysteriaUdpHop(
    val port: String,
    val interval: Int = 30
)

@Serializable
data class XrayMux(
    val enabled: Boolean = false,
    val concurrency: Int? = null,
    val xudpConcurrency: Int? = null,
    val xudpProxyUDP443: String? = null
)

@Serializable
data class XrayRouting(
    val domainStrategy: String = "IPIfNonMatch",
    val rules: List<XrayRule> = emptyList()
)

@Serializable
data class XrayRule(
    val type: String = "field",
    val outboundTag: String,
    val ip: List<String>? = null,
    val domain: List<String>? = null,
    val port: String? = null,
    val network: String? = null
)

@Serializable
data class XrayDns(
    val servers: List<String> = listOf("1.1.1.1", "8.8.8.8")
)
