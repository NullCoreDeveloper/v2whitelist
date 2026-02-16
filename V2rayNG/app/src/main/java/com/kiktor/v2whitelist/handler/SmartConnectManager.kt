package com.kiktor.v2whitelist.handler

import android.content.Context
import android.util.Log
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.dto.SubscriptionItem
import com.kiktor.v2whitelist.dto.SubscriptionCache
import com.kiktor.v2whitelist.enums.EConfigType
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.AngConfigManager
import com.kiktor.v2whitelist.handler.V2rayConfigManager
import com.kiktor.v2whitelist.handler.V2RayNativeManager
import com.kiktor.v2whitelist.handler.V2RayServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SmartConnectManager {
    private var failoverJob: Job? = null
    const val SUBSCRIPTION_URL = "https://raw.githubusercontent.com/zieng2/wl/main/vless_lite.txt"
    const val SUBSCRIPTION_ID = "v2whitelist_hardcoded_sub"
    const val SUBSCRIPTION_REMARKS = "v2Whitelist Official"
    const val UPDATE_INTERVAL_MS = 60 * 60 * 1000L // 1 hour

    /**
     * Ensures the hardcoded subscription is present and updated.
     */
    suspend fun checkAndSetupSubscription(context: Context) = withContext(Dispatchers.IO) {
        val subscriptions = MmkvManager.decodeSubscriptions()
        val existingSub = subscriptions.find { it.guid == SUBSCRIPTION_ID }

        if (existingSub == null) {
            Log.d(AppConfig.TAG, "Adding hardcoded subscription")
            val subItem = SubscriptionItem().apply {
                remarks = SUBSCRIPTION_REMARKS
                url = SUBSCRIPTION_URL
                enabled = true
            }
            MmkvManager.encodeSubscription(SUBSCRIPTION_ID, subItem)
            AngConfigManager.updateConfigViaSub(SubscriptionCache(SUBSCRIPTION_ID, subItem))
        } else {
            val lastUpdated = existingSub.subscription.lastUpdated
            if (System.currentTimeMillis() - lastUpdated > UPDATE_INTERVAL_MS) {
                Log.d(AppConfig.TAG, "Updating hardcoded subscription (time passed)")
                AngConfigManager.updateConfigViaSub(existingSub)
            }
        }
    }

    /**
     * Force updates the subscription.
     */
    suspend fun updateSubscription(context: Context) = withContext(Dispatchers.IO) {
        val subscriptions = MmkvManager.decodeSubscriptions()
        val existingSub = subscriptions.find { it.guid == SUBSCRIPTION_ID }
        if (existingSub != null) {
            Log.d(AppConfig.TAG, "Manually updating subscription")
            AngConfigManager.updateConfigViaSub(existingSub)
        } else {
            checkAndSetupSubscription(context)
        }
    }

    /**
     * Logic for "Smart Connect" - filter, sort by RealPing, and connect to best.
     */
    suspend fun smartConnect(context: Context) = withContext(Dispatchers.IO) {
        checkAndSetupSubscription(context)
        val allServers = MmkvManager.decodeServerList()
        val servers = allServers.mapNotNull { guid ->
            val profile = MmkvManager.decodeServerConfig(guid)
            if (profile?.subscriptionId == SUBSCRIPTION_ID) guid to profile else null
        }.filter { it.second.configType != EConfigType.POLICYGROUP }

        if (servers.isEmpty()) {
            Log.e(AppConfig.TAG, "No servers found in hardcoded subscription")
            return@withContext
        }

        val testUrl = AppConfig.DELAY_TEST_URL
        
        // Parallel testing
        val results = coroutineScope {
            servers.map { (guid, profile) ->
                async {
                    val config = V2rayConfigManager.getV2rayConfig(context, guid)
                    val delay = if (config.status) {
                        V2RayNativeManager.measureOutboundDelay(config.content, testUrl)
                    } else -1L
                    Triple(guid, profile, if (delay <= 0) Long.MAX_VALUE else delay)
                }
            }.awaitAll()
        }.sortedBy { it.third }

        val best = results.firstOrNull { it.third < Long.MAX_VALUE }
        if (best != null) {
            Log.d(AppConfig.TAG, "Connecting to best server: ${best.second.remarks} (${best.third}ms)")
            MmkvManager.setSelectServer(best.first)
            V2RayServiceManager.startVService(context)
            startFailoverTimer(context)
        } else {
            Log.e(AppConfig.TAG, "No valid servers found after RealPing")
        }
    }

    private fun startFailoverTimer(context: Context) {
        failoverJob?.cancel()
        failoverJob = CoroutineScope(Dispatchers.Main).launch {
            delay(5000)
            if (!V2RayServiceManager.isRunning()) {
                Log.w(AppConfig.TAG, "Connection failed within 5s, triggering failover")
                switchServer(context)
            }
        }
    }

    /**
     * Switches to the next best server.
     */
    suspend fun switchServer(context: Context) = withContext(Dispatchers.IO) {
        val currentGuid = MmkvManager.getSelectServer()
        val allServers = MmkvManager.decodeServerList()
        val servers = allServers.mapNotNull { guid ->
            val profile = MmkvManager.decodeServerConfig(guid)
            if (profile?.subscriptionId == SUBSCRIPTION_ID && guid != currentGuid) guid to profile else null
        }.filter { it.second.configType != EConfigType.POLICYGROUP }

        val testUrl = AppConfig.DELAY_TEST_URL
        
        // Parallel testing
        val results = coroutineScope {
            servers.map { (guid, profile) ->
                async {
                    val config = V2rayConfigManager.getV2rayConfig(context, guid)
                    val delay = if (config.status) {
                        V2RayNativeManager.measureOutboundDelay(config.content, testUrl)
                    } else -1L
                    Triple(guid, profile, if (delay <= 0) Long.MAX_VALUE else delay)
                }
            }.awaitAll()
        }.sortedBy { it.third }

        val nextBest = results.firstOrNull { it.third < Long.MAX_VALUE }
        if (nextBest != null) {
            V2RayServiceManager.stopVService(context)
            Log.d(AppConfig.TAG, "Switching to next best server: ${nextBest.second.remarks}")
            MmkvManager.setSelectServer(nextBest.first)
            V2RayServiceManager.startVService(context)
            startFailoverTimer(context)
        }
    }
}
