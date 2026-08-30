package com.kiktor.v2whitelist.handler

import android.content.Context
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.dto.ProfileItem
import libv2ray.Libv2ray
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
        sendStatus: (String) -> Unit,
        connectToBest: suspend (Pair<String, ProfileItem>, Boolean) -> Unit
    ): Boolean = coroutineScope {
        
        val batchSize = MmkvManager.getV2wCoreBatchSize()
        val concurrency = MmkvManager.getV2wCoreConcurrency()

        val candidates = servers.take(batchSize)
        val sb = java.lang.StringBuilder()
        val urlToGuid = mutableMapOf<String, Pair<String, ProfileItem>>()
        for (server in candidates) {
            val url = AngConfigManager.shareConfig(server.first)
            if (url.isNotEmpty()) {
                sb.append(url).append("\n")
                urlToGuid[url] = server
            }
        }

        if (urlToGuid.isEmpty()) return@coroutineScope false

        sendStatus("Инициализация v2w-core сканера...")

        val channel = Channel<Triple<String, ProfileItem, Long>>(Channel.UNLIMITED)

        val callback = object : libv2ray.V2WScanCallback {
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

        // Run scanner in background thread
        launch(Dispatchers.IO) {
            try {
                libv2ray.Libv2ray.runV2WScanner(sb.toString(), concurrency.toLong(), callback)
            } catch (e: Exception) {
                GeekModeLogger.log("SmartConnect", "v2w-core error: ${e.message}")
                channel.close()
            }
        }

        val profileCheckEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROFILE_CHECK_ENABLED, true)
        var connected = false

        for (candidate in channel) {
            val candidatePair = Pair(candidate.first, candidate.second)
            
            if (profileCheckEnabled) {
                if (NodeTesterManager.run { verifyProfile(context, candidatePair.first) }) {
                    // Working node found! Stop the Go scanner to save resources
                    libv2ray.Libv2ray.stopV2WScanner()
                    
                    connectToBest(candidatePair, isStartup)
                    connected = true
                    break
                } else {
                    sendStatus(context.getString(R.string.status_profile_check_failed))
                }
            } else {
                // Working node found! Stop the Go scanner to save resources
                libv2ray.Libv2ray.stopV2WScanner()
                
                connectToBest(candidatePair, isStartup)
                connected = true
                break
            }
        }

        // Make sure scanner is stopped if we exhausted the channel without connecting
        if (!connected) {
            libv2ray.Libv2ray.stopV2WScanner()
        } else {
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
        }

        return@coroutineScope connected
    }
}
