package com.kiktor.v2whitelist.core.subscription.model

import kotlinx.serialization.Serializable

/**
 * Data class for custom subscription items (stored as JSON and pre-populated).
 */
@Serializable
data class CustomSubData(
    val id: String = "",
    var name: String = "",
    var url: String = "",
    var filter: String = "",
    var groupRegex: String = "",
    var enabled: Boolean = true,
    var lastUpdated: Long = 0L,
    var sharePercent: Int? = null,
    var lastUpdateFailed: Boolean = false,
    var targetScenarios: List<String> = emptyList()
) {
    companion object {
        const val SCENARIO_WHITELIST = "WHITELIST"
        const val SCENARIO_BLACKLIST = "BLACKLIST"
        const val SCENARIO_YOUTUBE = "YOUTUBE"
    }
}
