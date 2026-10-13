package com.kiktor.v2whitelist.core.subscription.parser

import com.kiktor.v2whitelist.core.config.UniversalConfigParser
import com.kiktor.v2whitelist.core.config.util.Base64Util
import com.kiktor.v2whitelist.core.model.ProfileItem

object SubscriptionParser {

    /**
     * Parses subscription content into a list of ProfileItem objects.
     * Handles:
     * - Base64-encoded or plain-text node lists
     * - Stripping comments starting with #
     * - Filtering by regex pattern (matched against remarks)
     * - Filtering out insecure configurations (if filterInsecure is true)
     * - Attaching subscriptionId if specified
     */
    fun parse(
        content: String?,
        subscriptionId: String = "",
        filter: String? = null,
        filterInsecure: Boolean = false,
        allowInsecureUrl: Boolean = false
    ): List<ProfileItem> {
        if (content.isNullOrBlank()) return emptyList()

        // 1. Remove comment lines starting with #
        val cleanContent = content.lines()
            .filter { !it.trimStart().startsWith("#") }
            .joinToString("\n")

        // 2. Try Base64 decode first, then fallback to raw plain-text
        val decoded = Base64Util.decode(cleanContent)
        var configs = parseLines(decoded, subscriptionId, filter, filterInsecure, allowInsecureUrl)
        if (configs.isEmpty()) {
            configs = parseLines(cleanContent, subscriptionId, filter, filterInsecure, allowInsecureUrl)
        }

        return configs
    }

    private fun parseLines(
        text: String?,
        subscriptionId: String,
        filter: String?,
        filterInsecure: Boolean,
        allowInsecureUrl: Boolean
    ): List<ProfileItem> {
        if (text.isNullOrBlank()) return emptyList()

        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .distinct()

        val result = mutableListOf<ProfileItem>()
        val filterRegex = if (!filter.isNullOrBlank()) {
            try { Regex(filter) } catch (_: Exception) { null }
        } else null

        for (line in lines) {
            val config = try {
                UniversalConfigParser.parse(line, allowInsecure = allowInsecureUrl) ?: continue
            } catch (_: Exception) {
                continue
            }

            if (filterInsecure && !config.isSecure()) {
                continue
            }

            if (filterRegex != null && config.remarks.isNotEmpty()) {
                if (!filterRegex.containsMatchIn(config.remarks)) {
                    continue
                }
            }

            if (subscriptionId.isNotEmpty()) {
                config.subscriptionId = subscriptionId
            }

            result.add(config)
        }

        return result
    }
}
