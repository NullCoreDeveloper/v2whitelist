package com.kiktor.v2whitelist.dto

data class BackupData(
    val version: Int = 1,
    val settings: Map<String, Any?>? = null,
    val subscriptions: List<SubscriptionCache>? = null,
    val servers: List<ProfileItem>? = null,
    val routing: List<RulesetItem>? = null
)
