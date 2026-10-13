package com.kiktor.v2whitelist.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionItem(
    var remarks: String = "",
    var url: String = "",
    var enabled: Boolean = true,
    val addedTime: Long = currentTimeMillis(),
    var lastUpdated: Long = -1,
    var autoUpdate: Boolean = false,
    val updateInterval: Int? = null,
    var prevProfile: String? = null,
    var nextProfile: String? = null,
    var filter: String? = null,
    var allowInsecureUrl: Boolean = false,
    var userAgent: String? = null,
    var sharePercent: Int? = null,
    var lastUpdateFailed: Boolean = false
)
