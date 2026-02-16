package com.kiktor.v2whitelist.handler

import android.content.Context
import android.util.Log
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.dto.SubscriptionItem
import com.kiktor.v2whitelist.dto.SubscriptionCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SmartConnectManager {
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
            Log.i(AppConfig.TAG, "Adding hardcoded subscription")
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
                Log.i(AppConfig.TAG, "Updating hardcoded subscription (time passed)")
                AngConfigManager.updateConfigViaSub(existingSub)
            }
        }
    }

    /**
     * Logic for "Smart Connect" - filter, sort by RealPing, and connect to best.
     */
    suspend fun smartConnect(context: Context) {
        // This will be implemented in the next steps
    }
}
