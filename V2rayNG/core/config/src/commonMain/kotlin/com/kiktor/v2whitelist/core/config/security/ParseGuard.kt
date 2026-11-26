package com.kiktor.v2whitelist.core.config.security

/**
 * Исключение превышения бюджета парсинга (защита от DoS, JSON- и base64-бомб).
 */
class ParseBudgetExceededException(message: String) : IllegalArgumentException(message)

/**
 * Единый ограничитель ресурсов при разборе подписок, прокси-ссылок и JSON-конфигураций.
 */
object ParseGuard {
    const val MAX_SUBSCRIPTION_CHARS = 8 * 1024 * 1024
    const val MAX_BASE64_CHARS = 2 * 1024 * 1024
    const val MAX_SCAN_LINES = 5_000
    const val MAX_PARSED_NODES = 2_000
    const val MAX_LINK_CHARS = 64 * 1024
    const val MAX_QUERY_PARAMS = 128
    const val MAX_JSON_DEPTH = 64
    const val MAX_JSON_TOKENS = 150_000
    const val MAX_JSON_STRING_CHARS = 256 * 1024

    fun checkSubscriptionBody(raw: String) {
        if (raw.length > MAX_SUBSCRIPTION_CHARS) {
            throw ParseBudgetExceededException("Subscription body exceeds $MAX_SUBSCRIPTION_CHARS chars")
        }
    }

    fun checkBase64Input(raw: String) {
        if (raw.length > MAX_BASE64_CHARS) {
            throw ParseBudgetExceededException("Base64 payload exceeds $MAX_BASE64_CHARS chars")
        }
    }

    fun checkLinkLength(rawUri: String): Boolean {
        if (rawUri.length > MAX_LINK_CHARS) return false
        return rawUri.none { it == '\n' || it == '\r' || it.isISOControl() }
    }

    fun checkQueryParamCount(query: String, maxParams: Int = MAX_QUERY_PARAMS): Boolean {
        if (query.isEmpty()) return true
        var count = 1
        for (ch in query) {
            if (ch == '&') {
                if (++count > maxParams) return false
            }
        }
        return true
    }

    /**
     * Проверяет глубину вложенности, баланс скобок, длину строковых литералов и бюджет токенов JSON.
     */
    fun checkJsonBudget(
        text: String,
        maxDepth: Int = MAX_JSON_DEPTH,
        maxTokens: Int = MAX_JSON_TOKENS
    ): Boolean {
        if (text.isBlank() || text.length > MAX_SUBSCRIPTION_CHARS) return false
        var depth = 0
        var quoted = false
        var escaped = false
        var stringChars = 0
        var tokens = 0
        var hasRoot = false

        for (ch in text) {
            if (quoted) {
                if (++stringChars > MAX_JSON_STRING_CHARS) return false
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> quoted = false
                }
                continue
            }
            when (ch) {
                '"' -> {
                    if (++tokens > maxTokens) return false
                    quoted = true
                    stringChars = 0
                }
                '{', '[' -> {
                    hasRoot = true
                    if (++tokens > maxTokens) return false
                    if (++depth > maxDepth) return false
                }
                '}', ']' -> {
                    if (++tokens > maxTokens) return false
                    if (--depth < 0) return false
                }
                ':', ',' -> {
                    if (++tokens > maxTokens) return false
                }
            }
        }
        return hasRoot && depth == 0 && !quoted
    }
}
