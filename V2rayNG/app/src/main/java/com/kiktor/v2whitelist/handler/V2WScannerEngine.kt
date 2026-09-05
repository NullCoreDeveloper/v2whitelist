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
        var scanContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null
        val callback = object : V2WScanCallback {
            override fun onServerSuccess(configUrl: String?, delay: Long) {
                if (configUrl != null) {
                    val item = urlToGuid[configUrl]
                    if (item != null) {
                        GeekModeLogger.log("v2w-core", "Node responded: ${item.second.remarks} (${delay}ms)")
                        channel.trySend(Triple(item.first, item.second, delay))
                    }
                }
            }

            override fun onScanComplete(totalSuccess: Long, totalFailed: Long) {
                GeekModeLogger.log("v2w-core", "Scan complete. Success: $totalSuccess, Failed: $totalFailed")
                channel.close()
                if (scanContinuation?.isActive == true) {
                    scanContinuation?.resume(Unit) { }
                }
            }
        }

        var connected = false
        
        try {
            coroutineScope {
                val scannerJob = launch(Dispatchers.IO) {
                    try {
                        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                            scanContinuation = cont
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
                        if (e !is kotlinx.coroutines.CancellationException) {
                            GeekModeLogger.log("v2w-core", "error: ${e.message}")
                        }
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
                            Libv2ray.stopV2WScanner()
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
                        Libv2ray.stopV2WScanner()
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

        // If no working server was found across all servers, report status and return false
        sendStatus(context.getString(R.string.status_no_servers))
        return@coroutineScope false
    }
}
