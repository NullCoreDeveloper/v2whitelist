package com.kiktor.v2whitelist.handler

import android.content.Context
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.dto.ProfileItem
import libv2ray.Libv2ray
import libv2ray.V2WScanCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlin.coroutines.resumeWithException

object V2WScannerEngine {

    suspend fun runV2WCoreScan(
        context: Context,
        servers: List<Pair<String, ProfileItem>>,
        isStartup: Boolean,
        internetStatus: Int = 0,
        sendStatus: (String) -> Unit,
        connectToBest: suspend (Pair<String, ProfileItem>, Boolean) -> Unit
    ): Boolean = coroutineScope {
        
        val concurrency = MmkvManager.getV2wCoreConcurrency()
        val profileCheckEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROFILE_CHECK_ENABLED, true)

        if (internetStatus == 0) {
            sendStatus(context.getString(R.string.v2w_core_init))
        }

        val sb = java.lang.StringBuilder()
        val urlToGuid = mutableMapOf<String, Pair<String, ProfileItem>>()
        for (server in servers) {
            val url = AngConfigManager.shareConfig(server.first)
            if (url.isNotEmpty()) {
                sb.append(url).append("\n")
                urlToGuid[url] = server
            }
        }

        if (urlToGuid.isEmpty()) return@coroutineScope false

        val channel = Channel<Triple<String, ProfileItem, Long>>(Channel.UNLIMITED)
        val scanContinuation = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.CancellableContinuation<Unit>?>(null)
        val callback = object : V2WScanCallback {
            override fun onServerSuccess(configUrl: String?, delay: Long) {
                val cont = scanContinuation.get()
                if (cont?.isActive != true) return // Жесткий игнор после отмены
                
                if (configUrl != null) {
                    val cleanUrl = configUrl.trim()
                    val isFragTcp = cleanUrl.contains("v2w_fragment=tcp") || cleanUrl.contains("⚡[FRAG:TCP]") || cleanUrl.contains("[FRAG:TCP]")
                    val isFragRecord = cleanUrl.contains("v2w_fragment=record") || cleanUrl.contains("⚡[FRAG:RECORD]") || cleanUrl.contains("[FRAG:RECORD]")

                    // Ищем сопоставление: точное, без параметров v2w_fragment и без хэша remarks
                    var item = urlToGuid[cleanUrl]
                    if (item == null) {
                        val baseWithoutFragParam = cleanUrl
                            .replace("&v2w_fragment=tcp", "")
                            .replace("&v2w_fragment=record", "")
                            .replace("?v2w_fragment=tcp&", "?")
                            .replace("?v2w_fragment=record&", "?")
                            .replace("?v2w_fragment=tcp", "")
                            .replace("?v2w_fragment=record", "")
                        item = urlToGuid[baseWithoutFragParam]
                            ?: urlToGuid[baseWithoutFragParam.substringBefore('#')]
                            ?: urlToGuid[cleanUrl.substringBefore('#')]
                    }
                    if (item == null) {
                        try {
                            val uri = android.net.Uri.parse(cleanUrl)
                            val host = uri.host?.lowercase()
                            val port = uri.port.toString()
                            if (!host.isNullOrEmpty() && port != "-1") {
                                item = servers.find {
                                    it.second.server?.lowercase() == host && it.second.serverPort == port
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    if (item != null) {
                        val profile = item.second
                        if (isFragTcp) {
                            profile.fragmentEnabled = true
                            profile.fragmentPackets = "1-3"
                            profile.fragmentLength = "50-100"
                            profile.fragmentInterval = "10-20"
                            if (!profile.remarks.contains("[FRAG:")) {
                                profile.remarks = "${profile.remarks} ⚡[FRAG:TCP]"
                            }
                            MmkvManager.encodeServerConfig(item.first, profile)
                        } else if (isFragRecord) {
                            profile.fragmentEnabled = true
                            profile.fragmentPackets = "tlshello"
                            profile.fragmentLength = "50-100"
                            profile.fragmentInterval = "10-20"
                            if (!profile.remarks.contains("[FRAG:")) {
                                profile.remarks = "${profile.remarks} ⚡[FRAG:RECORD]"
                            }
                            MmkvManager.encodeServerConfig(item.first, profile)
                        }

                        GeekModeLogger.log("v2w-core", "Node responded: ${profile.remarks} (${delay}ms)")
                        channel.trySend(Triple(item.first, profile, delay))
                    }
                }
            }

            override fun onScanComplete(totalSuccess: Long, totalFailed: Long) {
                val cont = scanContinuation.get()
                if (cont?.isActive != true) return // Жесткий игнор после отмены
                
                GeekModeLogger.log("v2w-core", "Scan complete. Success: $totalSuccess, Failed: $totalFailed")
                if (cont.isActive) {
                    cont.resume(Unit) { }
                }
            }
        }

        var connected = false
        
        try {
            coroutineScope {
                val scannerJob = launch(Dispatchers.IO) {
                    try {
                        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                            scanContinuation.set(cont)
                            cont.invokeOnCancellation {
                                GeekModeLogger.log("v2w-core", "Scan cancelled by user, forcing stop...")
                                Libv2ray.stopV2WScanner()
                            }
                            
                            kotlin.concurrent.thread {
                                try {
                                    Libv2ray.runV2WScanner(sb.toString(), concurrency.toLong(), callback)
                                } catch (e: Exception) {
                                    if (cont.isActive) {
                                        cont.resumeWithException(e)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        GeekModeLogger.log("v2w-core", "error: ${e.message}")
                    } finally {
                        channel.close()
                    }
                }

                for (candidate in channel) {
                    if (!isActive) break
                    val candidatePair = Pair(candidate.first, candidate.second)
                    
                    if (profileCheckEnabled) {
                        if (NodeTesterManager.verifyProfile(context, candidatePair.first, showStatus = (internetStatus == 0))) {
                            GeekModeLogger.log("v2w-core", "Connecting to verified node: ${candidatePair.second.remarks}")
                            scannerJob.cancel()
                            connectToBest(candidatePair, isStartup)
                            connected = true
                            break
                        } else {
                            if (internetStatus == 0) {
                                sendStatus(context.getString(R.string.status_profile_check_failed))
                            }
                        }
                    } else {
                        GeekModeLogger.log("v2w-core", "Connecting to node (no profile check): ${candidatePair.second.remarks}")
                        scannerJob.cancel()
                        connectToBest(candidatePair, isStartup)
                        connected = true
                        break
                    }
                }
            }
        } finally {
            Libv2ray.stopV2WScanner()
        }

        if (connected) {
            // Drain the channel for any extra servers that succeeded before we stopped the scanner
            val leftovers = mutableListOf<Triple<String, ProfileItem, Long>>()
            while (true) {
                val item = channel.tryReceive().getOrNull() ?: break
                leftovers.add(item)
            }
            if (leftovers.isNotEmpty()) {
                GeekModeLogger.log("SmartConnect", "v2w-core: found ${leftovers.size} leftover servers, sending to VIP cache verification")
                NodeTesterManager.verifyAndCacheLeftovers(context.applicationContext, leftovers)
            }
            return@coroutineScope true
        }

        // If no working server was found across all servers, report status if fallback is disabled
        if (!MmkvManager.isV2wFallbackEnabled()) {
            sendStatus(context.getString(R.string.status_no_servers))
        }
        return@coroutineScope false
    }
}
