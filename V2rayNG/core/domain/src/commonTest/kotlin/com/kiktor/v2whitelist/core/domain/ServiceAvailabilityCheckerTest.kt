package com.kiktor.v2whitelist.core.domain

import com.kiktor.v2whitelist.core.domain.servicecheck.ServiceAvailabilityChecker
import com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckItem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceAvailabilityCheckerTest {

    @Test
    fun testEmptyServicesList() = runTest {
        val results = ServiceAvailabilityChecker.checkServicesParallel(emptyList())
        assertTrue(results.isEmpty())
        assertFalse(ServiceAvailabilityChecker.areAllServicesAvailable(emptyList()))
    }

    @Test
    fun testDisabledServicesAreSkipped() = runTest {
        val list = listOf(
            ServiceCheckItem(id = "google", name = "Google", url = "https://www.google.com/generate_204", enabled = false)
        )
        val results = ServiceAvailabilityChecker.checkServicesParallel(list)
        assertTrue(results.isEmpty())
    }

    @Test
    fun testParallelCheckExecution() = runTest {
        val items = listOf(
            ServiceCheckItem(id = "s1", name = "Mock1", url = "https://httpbin.org/status/200", enabled = true),
            ServiceCheckItem(id = "s2", name = "Mock2", url = "https://httpbin.org/status/404", enabled = true)
        )
        val results = ServiceAvailabilityChecker.checkServicesParallel(items, timeoutMs = 2000L)
        assertEquals(2, results.size)
    }
}

