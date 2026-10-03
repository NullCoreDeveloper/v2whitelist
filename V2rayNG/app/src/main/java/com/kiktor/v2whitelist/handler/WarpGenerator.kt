package com.kiktor.v2whitelist.handler

import android.util.Log
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.dto.ProfileItem
import com.kiktor.v2whitelist.enums.EConfigType
import com.kiktor.v2whitelist.util.Curve25519
import com.kiktor.v2whitelist.util.JsonUtil
import com.google.gson.JsonObject
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.concurrent.TimeUnit

object WarpGenerator {

    private const val WARP_API_REG = "https://api.cloudflareclient.com/v0a2158/reg"

    /**
     * Builds an OkHttpClient optionally routed through local SOCKS5/HTTP proxy with auth.
     */
    fun buildClient(socksPort: Int = 0, httpPort: Int = 0, timeoutSeconds: Long = 8): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)

        val socksUser = SettingsManager.getSocksUser()
        val socksPass = SettingsManager.getSocksPass()

        if (httpPort > 0) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(AppConfig.LOOPBACK, httpPort)))
            if (socksUser.isNotEmpty() && socksPass.isNotEmpty()) {
                builder.proxyAuthenticator { _, response ->
                    val credential = Credentials.basic(socksUser, socksPass)
                    response.request.newBuilder().header("Proxy-Authorization", credential).build()
                }
            }
        } else if (socksPort > 0) {
            if (socksUser.isNotEmpty() && socksPass.isNotEmpty()) {
                Authenticator.setDefault(object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication? {
                        return PasswordAuthentication(socksUser, socksPass.toCharArray())
                    }
                })
            }
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(AppConfig.LOOPBACK, socksPort)))
        }

        return builder.build()
    }

    /**
     * Registers a new Cloudflare WARP account.
     * If socksPort or httpPort are provided (or VPN is currently running), routes through the active VPN.
     * Otherwise, attempts direct connection with a short timeout.
     */
    fun generateWarpProfile(socksPort: Int = 0, httpPort: Int = 0): ProfileItem? {
        val activeSocksPort = if (socksPort > 0) socksPort else if (V2RayServiceManager.isRunning()) SettingsManager.getSocksPort() else 0
        val activeHttpPort = if (httpPort > 0) httpPort else if (V2RayServiceManager.isRunning()) SettingsManager.getHttpPort() else 0

        // 1. Попытка через локальный HTTP прокси VPN (если активен)
        if (activeHttpPort > 0) {
            try {
                val proxyClient = buildClient(0, activeHttpPort, timeoutSeconds = 8)
                val profile = executeRegistration(proxyClient)
                if (profile != null) {
                    GeekModeLogger.log("WarpGenerator", "WARP profile generated successfully via VPN HTTP proxy (port=$activeHttpPort)")
                    return profile
                }
            } catch (e: Exception) {
                GeekModeLogger.log("WarpGenerator", "WARP registration via HTTP proxy failed: ${e.message}")
            }
        }

        // 2. Попытка через локальный SOCKS5 прокси VPN (если активен)
        if (activeSocksPort > 0) {
            try {
                val proxyClient = buildClient(activeSocksPort, 0, timeoutSeconds = 8)
                val profile = executeRegistration(proxyClient)
                if (profile != null) {
                    GeekModeLogger.log("WarpGenerator", "WARP profile generated successfully via VPN SOCKS proxy (port=$activeSocksPort)")
                    return profile
                }
            } catch (e: Exception) {
                GeekModeLogger.log("WarpGenerator", "WARP registration via SOCKS proxy failed: ${e.message}")
            }
        }

        // 3. Прямая попытка с коротким таймаутом (3 сек), чтобы не зависать при блокировке Cloudflare API
        return try {
            val directClient = buildClient(0, 0, timeoutSeconds = 3)
            val profile = executeRegistration(directClient)
            if (profile != null) {
                GeekModeLogger.log("WarpGenerator", "WARP profile generated successfully via direct connection")
            }
            profile
        } catch (e: Exception) {
            GeekModeLogger.log("WarpGenerator", "Direct WARP registration failed (API blocked or timeout): ${e.message}")
            null
        }
    }

    private fun executeRegistration(okHttpClient: OkHttpClient): ProfileItem? {
        return try {
            val keyPair = Curve25519.generateKeyPair()

            val jsonPayload = JsonObject().apply {
                addProperty("key", keyPair.publicKeyBase64)
                addProperty("install_id", "")
                addProperty("warp_enabled", true)
                addProperty("tos", "2024-10-01T00:00:00.000Z")
                addProperty("type", "Android")
                addProperty("locale", "en_US")
            }

            val request = Request.Builder()
                .url(WARP_API_REG)
                .header("Content-Type", "application/json")
                .header("User-Agent", "okhttp/3.12.1")
                .post(jsonPayload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                GeekModeLogger.log("WarpGenerator", "WARP registration failed with HTTP code ${response.code}")
                return null
            }

            val bodyStr = response.body?.string() ?: return null
            val root = JsonUtil.parseString(bodyStr)?.asJsonObject ?: return null
            val configObj = root.getAsJsonObject("config") ?: return null
            val ifaceObj = configObj.getAsJsonObject("interface") ?: return null
            val addressesObj = ifaceObj.getAsJsonObject("addresses") ?: return null
            val v4Address = addressesObj.get("v4")?.asString ?: "172.16.0.2"

            val peersArr = configObj.getAsJsonArray("peers") ?: return null
            if (peersArr.size() == 0) return null
            val peerObj = peersArr.get(0).asJsonObject
            val peerPubKey = peerObj.get("public_key")?.asString ?: return null

            var peerEndpointIp = "162.159.192.1"
            var peerPort = "2408"
            if (peerObj.has("endpoint")) {
                val epObj = peerObj.getAsJsonObject("endpoint")
                if (epObj.has("v4")) {
                    val epV4 = epObj.get("v4")?.asString?.split(":")?.firstOrNull()
                    if (!epV4.isNullOrBlank()) {
                        peerEndpointIp = epV4
                    }
                }
            }

            val jc = MmkvManager.decodeSettingsInt(AppConfig.PREF_WARP_AWG_JC, 5)
            val jmin = MmkvManager.decodeSettingsInt(AppConfig.PREF_WARP_AWG_JMIN, 15)
            val jmax = MmkvManager.decodeSettingsInt(AppConfig.PREF_WARP_AWG_JMAX, 150)

            val profile = ProfileItem.create(EConfigType.WIREGUARD).apply {
                subscriptionId = WarpManager.WARP_SUB_ID
                remarks = "Cloudflare WARP (Auto)"
                server = peerEndpointIp
                serverPort = peerPort
                secretKey = keyPair.privateKeyBase64
                publicKey = peerPubKey
                localAddress = "$v4Address/32"
                mtu = 1280
                reserved = "0,0,0"
                junkCount = jc
                junkMin = jmin
                junkMax = jmax
            }

            GeekModeLogger.log("WarpGenerator", "Successfully generated WARP profile with IP $v4Address and endpoint $peerEndpointIp:$peerPort (Jc=$jc, Jmin=$jmin, Jmax=$jmax)")
            profile
        } catch (e: Exception) {
            GeekModeLogger.log("WarpGenerator", "Exception during WARP generation: ${e.message}")
            Log.e(AppConfig.TAG, "WarpGenerator error", e)
            null
        }
    }
}
