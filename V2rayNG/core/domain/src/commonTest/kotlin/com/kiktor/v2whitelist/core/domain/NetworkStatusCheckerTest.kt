package com.kiktor.v2whitelist.core.domain

import com.kiktor.v2whitelist.core.domain.network.NetworkStatusChecker
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkStatusCheckerTest {

    @Test
    fun testStatusConstants() {
        assertEquals(0, NetworkStatusChecker.STATUS_FULL_INTERNET)
        assertEquals(1, NetworkStatusChecker.STATUS_WHITELIST_ONLY)
        assertEquals(2, NetworkStatusChecker.STATUS_NO_INTERNET)
    }
}
