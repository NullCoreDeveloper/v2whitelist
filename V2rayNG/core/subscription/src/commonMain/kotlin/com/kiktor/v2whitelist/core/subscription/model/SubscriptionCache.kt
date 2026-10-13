package com.kiktor.v2whitelist.core.subscription.model

import com.kiktor.v2whitelist.core.model.SubscriptionItem
import kotlinx.serialization.Serializable

/**
 * Cache representation of a subscription pairing its persistent GUID and item.
 */
@Serializable
data class SubscriptionCache(
    val guid: String,
    val subscription: SubscriptionItem
)
