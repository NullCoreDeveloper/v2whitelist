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
    var targetScenarios: List<String> = emptyList(),
    var uploadBytes: Long = 0L,
    var downloadBytes: Long = 0L,
    var totalBytes: Long = 0L,
    var expireTimestampSec: Long = 0L
) {
    val usedBytes: Long
        get() = (uploadBytes.coerceAtLeast(0L)) + (downloadBytes.coerceAtLeast(0L))

    val hasQuotaInfo: Boolean
        get() = totalBytes > 0L || uploadBytes > 0L || downloadBytes > 0L || expireTimestampSec > 0L

    companion object {
        const val SCENARIO_WHITELIST = "WHITELIST"
        const val SCENARIO_BLACKLIST = "BLACKLIST"
        const val SCENARIO_YOUTUBE = "YOUTUBE"

        /**
         * Мигрирует устаревший тег YOUTUBE в BLACKLIST и удаляет дубликаты.
         */
        fun normalizeScenarios(scenarios: List<String>): List<String> {
            if (scenarios.isEmpty()) return scenarios
            return scenarios.map { if (it == SCENARIO_YOUTUBE) SCENARIO_BLACKLIST else it }.distinct()
        }
    }
}
