package com.kiktor.v2whitelist.core.subscription.status

enum class SubStaleMode {
    CALENDAR_HOUR,
    RELATIVE;

    companion object {
        fun fromString(raw: String?): SubStaleMode {
            return when (raw?.trim()?.uppercase()) {
                "RELATIVE" -> RELATIVE
                else -> CALENDAR_HOUR
            }
        }
    }
}

enum class SubStatusKind {
    UPDATING,
    FAILED,
    NO_SUBS,
    NEUTRAL_UPDATED,
    ACTUAL,
    OUTDATED
}

data class SubStatusDisplayConfig(
    val showStalenessStatus: Boolean = false,
    val staleMode: SubStaleMode = SubStaleMode.CALENDAR_HOUR,
    val staleTimeoutMinutes: Int = 60,
    val showFailedStatus: Boolean = true,
    val showElapsedTime: Boolean = true
)

data class SubStatusEvaluation(
    val kind: SubStatusKind,
    val failedCount: Int = 0,
    val lastUpdateMs: Long = 0L,
    val elapsedMinutes: Long = 0L,
    val isActual: Boolean = false
)

object SubscriptionStatusEvaluator {

    const val MIN_TIMEOUT_MINUTES = 1
    const val MAX_TIMEOUT_MINUTES = 10080 // 7 days

    fun evaluate(
        lastUpdateMs: Long,
        failedCount: Int,
        isUpdating: Boolean,
        nowMs: Long,
        isSameCalendarHour: (Long, Long) -> Boolean,
        config: SubStatusDisplayConfig
    ): SubStatusEvaluation {
        if (isUpdating) {
            return SubStatusEvaluation(
                kind = SubStatusKind.UPDATING,
                lastUpdateMs = lastUpdateMs,
                isActual = true
            )
        }

        val elapsedMinutes = if (lastUpdateMs > 0L) {
            (nowMs - lastUpdateMs).coerceAtLeast(0L) / 60_000L
        } else {
            0L
        }

        if (config.showFailedStatus && failedCount > 0) {
            return SubStatusEvaluation(
                kind = SubStatusKind.FAILED,
                failedCount = failedCount,
                lastUpdateMs = lastUpdateMs,
                elapsedMinutes = elapsedMinutes,
                isActual = false
            )
        }

        if (lastUpdateMs <= 0L) {
            return SubStatusEvaluation(
                kind = SubStatusKind.NO_SUBS,
                failedCount = 0,
                lastUpdateMs = 0L,
                elapsedMinutes = 0L,
                isActual = false
            )
        }

        if (!config.showStalenessStatus) {
            return SubStatusEvaluation(
                kind = SubStatusKind.NEUTRAL_UPDATED,
                failedCount = 0,
                lastUpdateMs = lastUpdateMs,
                elapsedMinutes = elapsedMinutes,
                isActual = true
            )
        }

        val timeoutMin = config.staleTimeoutMinutes.coerceIn(MIN_TIMEOUT_MINUTES, MAX_TIMEOUT_MINUTES)
        val isActual = when (config.staleMode) {
            SubStaleMode.CALENDAR_HOUR -> isSameCalendarHour(lastUpdateMs, nowMs)
            SubStaleMode.RELATIVE -> (nowMs - lastUpdateMs).coerceAtLeast(0L) < timeoutMin * 60_000L
        }

        return SubStatusEvaluation(
            kind = if (isActual) SubStatusKind.ACTUAL else SubStatusKind.OUTDATED,
            failedCount = 0,
            lastUpdateMs = lastUpdateMs,
            elapsedMinutes = elapsedMinutes,
            isActual = isActual
        )
    }
}
