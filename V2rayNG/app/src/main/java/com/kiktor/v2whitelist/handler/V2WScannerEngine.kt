package com.kiktor.v2whitelist.handler

import android.content.Context
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.dto.ProfileItem
import libv2ray.Libv2ray
import libv2ray.V2WScanCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

object V2WScannerEngine {

    suspend fun runV2WCoreScan(
        context: Context,
        servers: List<Pair<String, ProfileItem>>,
        isStartup: Boolean,
        internetStatus: Int = 0,
        sendStatus: (String) -> Unit,
        connectToBest: suspend (Pair<String, ProfileItem>, Boolean) -> Unit
    ): Boolean = coroutineScope {
        
        val batchSize = MmkvManager.getV2wCoreBatchSize()
        val concurrency = MmkvManager.getV2wCoreConcurrency()

        val chunks = servers.chunked(batchSize)
        val profileCheckEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROFILE_CHECK_ENABLED, true)

        for ((index, chunk) in chunks.withIndex()) {
            if (internetStatus == 0) {
                sendStatus(context.getString(R.string.v2w_core_init) + " (${index + 1}/${chunks.size})")
            }

            val sb = java.lang.StringBuilder()
            val urlToGuid = mutableMapOf<String, Pair<String, ProfileItem>>()
            for (server in chunk) {
                val url = AngConfigManager.shareConfig(server.first)
                if (url.isNotEmpty()) {
                    sb.append(url).append("\n")
                    urlToGuid[url] = server
                }
            }

            if (urlToGuid.isEmpty()) continue

            val channel = Channel<Triple<String, ProfileItem, Long>>(Channel.UNLIMITED)
            val callback = object : V2WScanCallback {
                override fun onServerSuccess(configUrl: String?, delay: Long) {
                    if (configUrl != null) {
                        val item = urlToGuid[configUrl]
                        if (item != null) {
                            channel.trySend(Triple(item.first, item.second, delay))
                        }
                    }
                }

                override fun onScanComplete(totalSuccess: Long, totalFailed: Long) {
                    channel.close()
                }
            }

            var connected = false
            
            try {
                coroutineScope {
                    launch(Dispatchers.IO) {
                        try {
                            Libv2ray.runV2WScanner(sb.toString(), concurrency.toLong(), callback)
                        } catch (e: Exception) {
                            GeekModeLogger.log("SmartConnect", "v2w-core error: ${e.message}")
                            channel.close()
                        }
                    }

                    for (candidate in channel) {
                        val candidatePair = Pair(candidate.first, candidate.second)
                        
                        if (profileCheckEnabled) {
                            if (NodeTesterManager.verifyProfile(context, candidatePair.first, showStatus = (internetStatus == 0))) {
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
        }

        // If no working server was found across all chunks, report status and return false
        sendStatus(context.getString(R.string.status_no_servers))
        return@coroutineScope false
    }
}
