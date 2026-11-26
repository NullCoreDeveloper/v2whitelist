package com.kiktor.v2whitelist.core.subscription.parser

import com.kiktor.v2whitelist.core.config.UniversalConfigParser
import com.kiktor.v2whitelist.core.config.security.ParseGuard
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
        if (content.length > ParseGuard.MAX_SUBSCRIPTION_CHARS) return emptyList()

        // 1. Remove comment lines starting with #
        val cleanContent = content.lineSequence()
            .take(ParseGuard.MAX_SCAN_LINES * 2)
            .filter { !it.trimStart().startsWith("#") }
            .joinToString("\n")

        // 2. Try Base64 decode first, then fallback to raw plain-text
        val decoded = if (cleanContent.length <= ParseGuard.MAX_BASE64_CHARS) {
            Base64Util.decode(cleanContent)?.takeIf { it.length <= ParseGuard.MAX_SUBSCRIPTION_CHARS }
        } else {
            null
        }
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

        val lines = text.lineSequence()
            .take(ParseGuard.MAX_SCAN_LINES)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && ParseGuard.checkLinkLength(it) }
            .distinct()

        val result = mutableListOf<ProfileItem>()
        val filterRegex = if (!filter.isNullOrBlank() && filter.length <= 512) {
            try { Regex(filter) } catch (_: Exception) { null }
        } else null

        for (line in lines) {
            if (result.size >= ParseGuard.MAX_PARSED_NODES) break
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
