package com.kiktor.v2whitelist.core.subscription

import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.SubscriptionItem
import com.kiktor.v2whitelist.core.subscription.chunk.ChunkingHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkingHelperTest {

    @Test
    fun testEmptyServers() {
        val result = ChunkingHelper.buildProportionalChunks(emptyList(), emptyMap())
        assertTrue(result.isEmpty())
    }

    @Test
    fun testRandomPreset() {
        val servers = (1..50).map { id ->
            "guid_$id" to ProfileItem(configType = EConfigType.VLESS, remarks = "Server $id", subscriptionId = "sub_1")
        }
        val chunks = ChunkingHelper.buildProportionalChunks(
            servers = servers,
            subscriptions = emptyMap(),
            chunkSize = 20,
            preset = "random"
        )
        assertEquals(3, chunks.size)
        assertEquals(20, chunks[0].size)
        assertEquals(20, chunks[1].size)
        assertEquals(10, chunks[2].size)
    }

    @Test
    fun testProportionalDistribution() {
        val serversSub1 = (1..30).map { "s1_$it" to ProfileItem(configType = EConfigType.VLESS, remarks = "Sub1 Node $it", subscriptionId = "sub_1") }
        val serversSub2 = (1..30).map { "s2_$it" to ProfileItem(configType = EConfigType.VLESS, remarks = "Sub2 Node $it", subscriptionId = "sub_2") }
        val allServers = serversSub1 + serversSub2

        val subs = mapOf(
            "sub_1" to SubscriptionItem(sharePercent = 70),
            "sub_2" to SubscriptionItem(sharePercent = 30)
        )

        val chunks = ChunkingHelper.buildProportionalChunks(
            servers = allServers,
            subscriptions = subs,
            chunkSize = 20,
            preset = "equal"
        )

        // All 60 servers should be present across all chunks
        val totalServersInChunks = chunks.sumOf { it.size }
        assertEquals(60, totalServersInChunks)

        // Verify sub_1 has around 70% in early chunks
        val sub1InFirstChunk = chunks[0].count { it.second.subscriptionId == "sub_1" }
        assertTrue(sub1InFirstChunk in 12..16) // ~14 out of 20
    }
}
