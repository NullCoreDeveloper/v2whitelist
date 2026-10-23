package com.kiktor.v2whitelist.dto

import com.kiktor.v2whitelist.core.subscription.model.CustomSubData

data class BackupData(
    val version: Int = 2,
    val settings: Map<String, Any?>? = null,
    val subscriptions: List<SubscriptionCache>? = null,
    val customSubscriptions: List<CustomSubData>? = null,
    val servers: List<ProfileItem>? = null,
    val routing: List<RulesetItem>? = null
)
