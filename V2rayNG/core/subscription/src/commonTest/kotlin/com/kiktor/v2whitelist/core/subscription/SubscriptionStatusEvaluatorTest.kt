package com.kiktor.v2whitelist.core.subscription

import com.kiktor.v2whitelist.core.subscription.status.SubStaleMode
import com.kiktor.v2whitelist.core.subscription.status.SubStatusDisplayConfig
import com.kiktor.v2whitelist.core.subscription.status.SubStatusKind
import com.kiktor.v2whitelist.core.subscription.status.SubscriptionStatusEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionStatusEvaluatorTest {

    @Test
    fun testDefaultConfigReturnsNeutralUpdatedInsteadOfOutdated() {
        val now = 1_700_000_000_000L
        val last = now - 6 * 60_000L
        val res = SubscriptionStatusEvaluator.evaluate(
            lastUpdateMs = last,
            failedCount = 0,
            isUpdating = false,
            nowMs = now,
            isSameCalendarHour = { _, _ -> false },
            config = SubStatusDisplayConfig()
        )
        assertEquals(SubStatusKind.NEUTRAL_UPDATED, res.kind)
        assertEquals(6L, res.elapsedMinutes)
        assertTrue(res.isActual)
    }

    @Test
    fun testCalendarHourAndRelativeModesWhenStalenessEnabled() {
        val now = 1_700_000_000_000L
        val last = now - 25 * 60_000L

        val calOutdated = SubscriptionStatusEvaluator.evaluate(
            lastUpdateMs = last,
            failedCount = 0,
            isUpdating = false,
            nowMs = now,
            isSameCalendarHour = { _, _ -> false },
            config = SubStatusDisplayConfig(
                showStalenessStatus = true,
                staleMode = SubStaleMode.CALENDAR_HOUR
            )
        )
        assertEquals(SubStatusKind.OUTDATED, calOutdated.kind)
        assertFalse(calOutdated.isActual)

        val relActual = SubscriptionStatusEvaluator.evaluate(
            lastUpdateMs = last,
            failedCount = 0,
            isUpdating = false,
            nowMs = now,
            isSameCalendarHour = { _, _ -> false },
            config = SubStatusDisplayConfig(
                showStalenessStatus = true,
                staleMode = SubStaleMode.RELATIVE,
                staleTimeoutMinutes = 30
            )
        )
        assertEquals(SubStatusKind.ACTUAL, relActual.kind)
        assertTrue(relActual.isActual)

        val relOutdated = SubscriptionStatusEvaluator.evaluate(
            lastUpdateMs = last,
            failedCount = 0,
            isUpdating = false,
            nowMs = now,
            isSameCalendarHour = { _, _ -> true },
            config = SubStatusDisplayConfig(
                showStalenessStatus = true,
                staleMode = SubStaleMode.RELATIVE,
                staleTimeoutMinutes = 15
            )
        )
        assertEquals(SubStatusKind.OUTDATED, relOutdated.kind)
    }

    @Test
    fun testFailedStatusRespectsShowFailedFlag() {
        val now = 1_700_000_000_000L
        val last = now - 5 * 60_000L

        val withFailed = SubscriptionStatusEvaluator.evaluate(
            lastUpdateMs = last,
            failedCount = 2,
            isUpdating = false,
            nowMs = now,
            isSameCalendarHour = { _, _ -> true },
            config = SubStatusDisplayConfig(showFailedStatus = true)
        )
        assertEquals(SubStatusKind.FAILED, withFailed.kind)
        assertEquals(2, withFailed.failedCount)

        val withoutFailed = SubscriptionStatusEvaluator.evaluate(
            lastUpdateMs = last,
            failedCount = 2,
            isUpdating = false,
            nowMs = now,
            isSameCalendarHour = { _, _ -> true },
            config = SubStatusDisplayConfig(showFailedStatus = false)
        )
        assertEquals(SubStatusKind.NEUTRAL_UPDATED, withoutFailed.kind)
    }
}
