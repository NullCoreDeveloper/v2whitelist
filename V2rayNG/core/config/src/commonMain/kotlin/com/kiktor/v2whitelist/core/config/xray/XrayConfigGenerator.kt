package com.kiktor.v2whitelist.core.config.xray

import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

data class XrayGeneratorOptions(
    val socksPort: Int = 10808,
    val httpPort: Int = 10809,
    val listenAddress: String = "127.0.0.1",
    val enableHttpInbound: Boolean = true,
    val logLevel: String = "warning",
    val allowInsecureDefault: Boolean = false,
    val dnsServers: List<String> = listOf("1.1.1.1", "8.8.8.8"),
    val bypassPrivateIps: Boolean = true,
    val scenario: com.kiktor.v2whitelist.core.model.AppScenario = com.kiktor.v2whitelist.core.model.AppScenario.AUTO,
    val customDirectDomains: List<String> = emptyList(),
    val customDirectIps: List<String> = emptyList(),
    val customProxyDomains: List<String> = emptyList(),
    val upstreamProxyHost: String? = null,
    val upstreamProxyPort: Int? = null,
    val upstreamProxyType: String? = null
)

object XrayConfigGenerator {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        encodeDefaults = false
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    /**
     * Builds an XrayConfig object from a ProfileItem and options.
     */
    fun buildConfig(profile: ProfileItem, options: XrayGeneratorOptions = XrayGeneratorOptions()): XrayConfig {
        val inbounds = mutableListOf<XrayInbound>()

        // SOCKS inbound
        inbounds.add(
            XrayInbound(
                tag = "socks-in",
                port = options.socksPort,
                protocol = "socks",
                listen = options.listenAddress,
                settings = InboundSettings(auth = "noauth", udp = true),
                sniffing = InboundSniffing(enabled = true, destOverride = listOf("http", "tls", "fakedns"))
            )
        )

        // HTTP inbound (optional)
        if (options.enableHttpInbound && options.httpPort > 0) {
            inbounds.add(
                XrayInbound(
                    tag = "http-in",
                    port = options.httpPort,
                    protocol = "http",
                    listen = options.listenAddress,
                    settings = null,
                    sniffing = InboundSniffing(enabled = true, destOverride = listOf("http", "tls"))
                )
            )
        }

        // Outbounds: [proxy, direct, block] (+ optional upstream proxy)
        val hasUpstream = !options.upstreamProxyHost.isNullOrBlank() && (options.upstreamProxyPort ?: 0) > 0
        val upstreamTag = "upstream-proxy"
        val rawProxyOutbound = buildProxyOutbound(profile, options)
        val proxyOutbound = if (hasUpstream) {
            rawProxyOutbound.copy(proxySettings = OutboundProxySettings(tag = upstreamTag))
        } else {
            rawProxyOutbound
        }

        val directOutbound = XrayOutbound(
            tag = "direct",
            protocol = "freedom",
            settings = OutboundSettings(domainStrategy = "UseIP")
        )
        val blockOutbound = XrayOutbound(
            tag = "block",
            protocol = "blackhole",
            settings = OutboundSettings(response = BlackholeResponse("http"))
        )

        val outbounds = mutableListOf(proxyOutbound, directOutbound, blockOutbound)
        if (hasUpstream) {
            val upProto = if (options.upstreamProxyType?.equals("http", ignoreCase = true) == true) "http" else "socks"
            outbounds.add(
                XrayOutbound(
                    tag = upstreamTag,
                    protocol = upProto,
                    settings = OutboundSettings(
                        servers = listOf(ServerEntry(address = options.upstreamProxyHost.orEmpty(), port = options.upstreamProxyPort ?: 0))
                    )
                )
            )
        }

        // Routing rules
        val rules = mutableListOf<XrayRule>()

        // Direct for private IPs
        if (options.bypassPrivateIps) {
            rules.add(
                XrayRule(
                    outboundTag = "direct",
                    ip = listOf("geoip:private")
                )
            )
        }

        // Custom direct domains / IPs (раздельное туннелирование)
        if (options.customDirectDomains.isNotEmpty()) {
            rules.add(
                XrayRule(
                    outboundTag = "direct",
                    domain = options.customDirectDomains
                )
            )
        }
        if (options.customDirectIps.isNotEmpty()) {
            rules.add(
                XrayRule(
                    outboundTag = "direct",
                    ip = options.customDirectIps
                )
            )
        }

        // Custom proxy domains (раздельное туннелирование)
        if (options.customProxyDomains.isNotEmpty()) {
            rules.add(
                XrayRule(
                    outboundTag = "proxy",
                    domain = options.customProxyDomains
                )
            )
        }

        // Пресеты маршрутизации сценариев
        when (options.scenario) {
            com.kiktor.v2whitelist.core.model.AppScenario.WHITELIST -> {
                // В сценарии белого списка разрешенные домены РФ идут напрямую, остальное через прокси
                rules.add(
                    XrayRule(
                        outboundTag = "direct",
                        domain = listOf("geosite:category-gov-ru", "geosite:ru", "domain:gov.ru", "domain:gosuslugi.ru", "domain:ya.ru", "domain:yandex.ru", "domain:vk.com", "domain:mail.ru")
                    )
                )
                rules.add(
                    XrayRule(
                        outboundTag = "proxy",
                        network = "tcp,udp"
                    )
                )
            }
            com.kiktor.v2whitelist.core.model.AppScenario.VPN_BLACKLIST -> {
                // В сценарии черного списка заблокированные домены идут через прокси, остальное напрямую
                rules.add(
                    XrayRule(
                        outboundTag = "proxy",
                        domain = listOf("geosite:category-ru-blocked", "domain:instagram.com", "domain:cdninstagram.com", "domain:facebook.com", "domain:fbcdn.net", "domain:twitter.com", "domain:x.com", "domain:t.co", "domain:notion.so")
                    )
                )
                rules.add(
                    XrayRule(
                        outboundTag = "direct",
                        network = "tcp,udp"
                    )
                )
            }
            com.kiktor.v2whitelist.core.model.AppScenario.YOUTUBE -> {
                // В сценарии YouTube только ютуб через прокси, остальное напрямую
                rules.add(
                    XrayRule(
                        outboundTag = "proxy",
                        domain = listOf("geosite:youtube", "domain:youtube.com", "domain:youtu.be", "domain:googlevideo.com", "domain:ytimg.com", "domain:ggpht.com")
                    )
                )
                rules.add(
                    XrayRule(
                        outboundTag = "direct",
                        network = "tcp,udp"
                    )
                )
            }
            else -> {
                // По умолчанию (AUTO) весь интернет через proxy
                rules.add(
                    XrayRule(
                        outboundTag = "proxy",
                        network = "tcp,udp"
                    )
                )
            }
        }

        val routing = XrayRouting(
            domainStrategy = "IPIfNonMatch",
            rules = rules
        )

        return XrayConfig(
            log = XrayLog(loglevel = options.logLevel),
            inbounds = inbounds,
            outbounds = outbounds,
            routing = routing,
            dns = XrayDns(servers = options.dnsServers)
        )
    }

    /**
     * Converts a ProfileItem to the primary proxy XrayOutbound.
     */
    fun buildProxyOutbound(profile: ProfileItem, options: XrayGeneratorOptions): XrayOutbound {
        val serverAddr = profile.server.orEmpty()
        val port = profile.serverPort?.toIntOrNull() ?: 443

        return when (profile.configType) {
            EConfigType.VLESS -> {
                XrayOutbound(
                    tag = "proxy",
                    protocol = "vless",
                    settings = OutboundSettings(
                        vnext = listOf(
                            VnextServer(
                                address = serverAddr,
                                port = port,
                                users = listOf(
                                    VnextUser(
                                        id = profile.password.orEmpty(),
                                        encryption = profile.method ?: "none",
                                        flow = profile.flow
                                    )
                                )
                            )
                        )
                    ),
                    streamSettings = buildStreamSettings(profile, options)
                )
            }

            EConfigType.VMESS -> {
                XrayOutbound(
                    tag = "proxy",
                    protocol = "vmess",
                    settings = OutboundSettings(
                        vnext = listOf(
                            VnextServer(
                                address = serverAddr,
                                port = port,
                                users = listOf(
                                    VnextUser(
                                        id = profile.password.orEmpty(),
                                        alterId = 0,
                                        security = profile.method ?: "auto"
                                    )
                                )
                            )
                        )
                    ),
                    streamSettings = buildStreamSettings(profile, options)
                )
            }

            EConfigType.TROJAN -> {
                XrayOutbound(
                    tag = "proxy",
                    protocol = "trojan",
                    settings = OutboundSettings(
                        servers = listOf(
                            ServerEntry(
                                address = serverAddr,
                                port = port,
                                password = profile.password.orEmpty(),
                                flow = profile.flow
                            )
                        )
                    ),
                    streamSettings = buildStreamSettings(profile, options)
                )
            }

            EConfigType.SHADOWSOCKS -> {
                XrayOutbound(
                    tag = "proxy",
                    protocol = "shadowsocks",
                    settings = OutboundSettings(
                        servers = listOf(
                            ServerEntry(
                                address = serverAddr,
                                port = port,
                                method = profile.method.orEmpty(),
                                password = profile.password.orEmpty()
                            )
                        )
                    ),
                    streamSettings = if (profile.headerType == "http") {
                        XrayStreamSettings(
                            network = "tcp",
                            tcpSettings = TcpSettings(
                                header = HeaderTypeSettings(
                                    type = "http",
                                    request = HttpRequestSettings(
                                        path = listOf(profile.path ?: "/"),
                                        headers = if (!profile.host.isNullOrEmpty()) mapOf("Host" to listOf(profile.host!!)) else null
                                    )
                                )
                            )
                        )
                    } else null
                )
            }

            EConfigType.HYSTERIA2 -> {
                XrayOutbound(
                    tag = "proxy",
                    protocol = "hysteria",
                    settings = OutboundSettings(
                        version = 2,
                        auth = profile.password.orEmpty(),
                        up = profile.bandwidthUp?.ifEmpty { "0" },
                        down = profile.bandwidthDown?.ifEmpty { "0" }
                    ),
                    streamSettings = buildStreamSettings(profile, options)
                )
            }

            EConfigType.SOCKS -> {
                XrayOutbound(
                    tag = "proxy",
                    protocol = "socks",
                    settings = OutboundSettings(
                        servers = listOf(
                            ServerEntry(
                                address = serverAddr,
                                port = port,
                                password = profile.password
                            )
                        )
                    )
                )
            }

            EConfigType.HTTP -> {
                XrayOutbound(
                    tag = "proxy",
                    protocol = "http",
                    settings = OutboundSettings(
                        servers = listOf(
                            ServerEntry(
                                address = serverAddr,
                                port = port,
                                password = profile.password
                            )
                        )
                    )
                )
            }

            else -> {
                XrayOutbound(tag = "proxy", protocol = "freedom")
            }
        }
    }

    private fun buildStreamSettings(profile: ProfileItem, options: XrayGeneratorOptions): XrayStreamSettings {
        val network = profile.network?.lowercase() ?: "tcp"
        val security = profile.security?.lowercase()
        val sni = if (!profile.sni.isNullOrBlank()) profile.sni else profile.server

        val tlsSettings = if (security == "tls") {
            TlsSettings(
                serverName = sni,
                allowInsecure = profile.insecure ?: options.allowInsecureDefault,
                alpn = profile.alpn?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() },
                fingerprint = profile.fingerPrint,
                pinnedPeerCertSha256 = profile.pinnedCA256
            )
        } else null

        val realitySettings = if (security == "reality") {
            RealitySettings(
                serverName = sni,
                fingerprint = profile.fingerPrint,
                publicKey = profile.publicKey,
                shortId = profile.shortId,
                spiderX = profile.spiderX
            )
        } else null

        val wsSettings = if (network == "ws") {
            WsSettings(
                path = profile.path ?: "/",
                headers = if (!profile.host.isNullOrBlank()) mapOf("Host" to profile.host!!) else null
            )
        } else null

        val grpcSettings = if (network == "grpc") {
            GrpcSettings(
                serviceName = profile.serviceName,
                multiMode = profile.mode == "multi",
                authority = profile.authority ?: sni
            )
        } else null

        val httpUpgradeSettings = if (network == "httpupgrade") {
            HttpUpgradeSettings(
                host = profile.host ?: sni,
                path = profile.path ?: "/"
            )
        } else null

        val hysteriaSettings = if (network == "hysteria" || profile.configType == EConfigType.HYSTERIA2) {
            val udphop = profile.portHopping?.let {
                HysteriaUdpHop(
                    port = it,
                    interval = profile.portHoppingInterval?.toIntOrNull() ?: 30
                )
            }
            HysteriaSettings(
                version = 2,
                auth = profile.password,
                udphop = udphop
            )
        } else null

        return XrayStreamSettings(
            network = network,
            security = security,
            wsSettings = wsSettings,
            grpcSettings = grpcSettings,
            httpupgradeSettings = httpUpgradeSettings,
            tlsSettings = tlsSettings,
            realitySettings = realitySettings,
            hysteriaSettings = hysteriaSettings
        )
    }

    /**
     * Converts a ProfileItem directly into the complete Xray config.json string.
     */
    fun generateJson(profile: ProfileItem, options: XrayGeneratorOptions = XrayGeneratorOptions()): String {
        val config = buildConfig(profile, options)
        return json.encodeToString(XrayConfig.serializer(), config)
    }
}
