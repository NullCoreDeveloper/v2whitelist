package com.kiktor.v2whitelist.handler

import android.content.Context
import android.util.Log
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.dto.ProfileItem
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancelChildren
import kotlin.random.Random

object SmartConnectManager {
    private var failoverJob: Job? = null
    private val testSemaphore = Semaphore(32)
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

        Log.i(AppConfig.TAG, "Starting Smart Connect for ${servers.size} servers (10s limit)")

        val testUrls = listOf(
            AppConfig.DELAY_TEST_URL,
            "https://www.google.com/generate_204",
            "https://www.cloudflare.com/cdn-cgi/trace",
            "https://connectivitycheck.gstatic.com/generate_204"
        )
        
        // Final results list
        val resultsList = mutableListOf<Triple<String, ProfileItem, Long>>()
        
        // Wrap the entire testing process in a total timeout
        withTimeoutOrNull(10000) {
            coroutineScope {
                val jobs = servers.map { (guid, profile) ->
                    async {
                        testSemaphore.withPermit {
                            // Check if we already found a "good enough" server
                            if (resultsList.any { it.third < 300 }) return@withPermit null

                            val randomUrl = testUrls[Random.nextInt(testUrls.size)]
                            val config = V2rayConfigManager.getV2rayConfig(context, guid)
                            val delay = if (config.status) {
                                withTimeoutOrNull(2000) { // 2s per server test
                                    V2RayNativeManager.measureOutboundDelay(config.content, randomUrl)
                                } ?: -1L
                            } else -1L
                            
                            val finalDelay = if (delay <= 0) Long.MAX_VALUE else delay
                            val result = Triple(guid, profile, finalDelay)
                            if (finalDelay < 300) {
                                synchronized(resultsList) { resultsList.add(result) }
                                // Stop other tests
                                this@coroutineScope.coroutineContext[Job]?.cancelChildren()
                            }
                            result
                        }
                    }
                }
                resultsList.addAll(jobs.awaitAll().filterNotNull())
            }
        }

        val results = resultsList.sortedBy { it.third }
        var best = results.firstOrNull { it.third < Long.MAX_VALUE }
        
        // Fallback: if no server found in time, just pick the first one from list
        if (best == null && servers.isNotEmpty()) {
            Log.w(AppConfig.TAG, "No servers found within timeout, picking first available")
            best = Triple(servers[0].first, servers[0].second, Long.MAX_VALUE)
        }

        if (best != null) {
            Log.i(AppConfig.TAG, "Smart Connect: Selected ${best.second.remarks} (${best.third}ms)")
            MmkvManager.setSelectServer(best.first)
            V2RayServiceManager.startVService(context)
            startFailoverTimer(context)
        } else {
            Log.e(AppConfig.TAG, "Critical: No servers available to connect")
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

        if (servers.isEmpty()) {
            return@withContext
        }

        Log.i(AppConfig.TAG, "Switching server: testing ${servers.size} alternatives (10s limit)")

        val testUrls = listOf(
            AppConfig.DELAY_TEST_URL,
            "https://www.google.com/generate_204",
            "https://www.cloudflare.com/cdn-cgi/trace",
            "https://connectivitycheck.gstatic.com/generate_204"
        )
        
        // Parallel testing with concurrency limit and early exit
        val resultsList = mutableListOf<Triple<String, ProfileItem, Long>>()
        withTimeoutOrNull(10000) {
            coroutineScope {
                val jobs = servers.map { (guid, profile) ->
                    async {
                        testSemaphore.withPermit {
                            // Check if we already found a "good enough" server
                            if (resultsList.any { it.third < 300 }) return@withPermit null

                            val randomUrl = testUrls[Random.nextInt(testUrls.size)]
                            val config = V2rayConfigManager.getV2rayConfig(context, guid)
                            val delay = if (config.status) {
                                withTimeoutOrNull(2000) {
                                    V2RayNativeManager.measureOutboundDelay(config.content, randomUrl)
                                } ?: -1L
                            } else -1L
                            
                            val finalDelay = if (delay <= 0) Long.MAX_VALUE else delay
                            val result = Triple(guid, profile, finalDelay)
                            if (finalDelay < 300) {
                                synchronized(resultsList) { resultsList.add(result) }
                                // Try to cancel other children if we found a great one
                                this@coroutineScope.coroutineContext[Job]?.cancelChildren()
                            }
                            result
                        }
                    }
                }
                resultsList.addAll(jobs.awaitAll().filterNotNull())
            }
        }
        val results = resultsList.sortedBy { it.third }

        var nextBest = results.firstOrNull { it.third < Long.MAX_VALUE }
        if (nextBest == null && servers.isNotEmpty()) {
            nextBest = Triple(servers[Random.nextInt(servers.size)].first, servers[0].second, Long.MAX_VALUE)
        }

        if (nextBest != null) {
            V2RayServiceManager.stopVService(context)
            Log.i(AppConfig.TAG, "Switching to next best server: ${nextBest.second.remarks}")
            MmkvManager.setSelectServer(nextBest.first)
            V2RayServiceManager.startVService(context)
            startFailoverTimer(context)
        }
    }
}
