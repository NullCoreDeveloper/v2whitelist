package com.kiktor.v2whitelist.handler

import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.dto.ProfileItem
import kotlin.random.Random

object ChunkingHelper {
    fun buildProportionalChunks(servers: List<Pair<String, ProfileItem>>): List<List<Pair<String, ProfileItem>>> {
        if (servers.isEmpty()) return emptyList()

        val chunkSizeStr = MmkvManager.decodeSettingsString(AppConfig.PREF_CHUNK_SIZE) ?: "20"
        val chunkSize = chunkSizeStr.toIntOrNull()?.takeIf { it > 0 } ?: 20
        val preset = MmkvManager.decodeSettingsString(AppConfig.PREF_CHUNK_PRESET) ?: "equal"
        
        if (preset == "random") {
            return servers.chunked(chunkSize)
        }

        // Group by subscription
        val subGroups = servers.groupBy { it.second.subscriptionId }
            .mapValues { it.value.toMutableList() }
            .toMutableMap()
        
        // Remove null or empty subscription keys if any
        val allSubs = MmkvManager.decodeSubscriptions().associateBy { it.guid }
        
        // Calculate shares
        val subShares = mutableMapOf<String, Double>()
        var remainingShare = 100.0
        val unassignedSubs = mutableListOf<String>()

        for ((subId, _) in subGroups) {
            val subItem = allSubs[subId]?.subscription
            if (subItem?.sharePercent != null) {
                subShares[subId] = subItem.sharePercent!!.toDouble()
                remainingShare -= subItem.sharePercent!!.toDouble()
            } else {
                unassignedSubs.add(subId)
            }
        }

        // Normalize if hardcoded shares > 100%
        val totalHardcoded = subShares.values.sum()
        if (totalHardcoded > 100.0) {
            val ratio = 100.0 / totalHardcoded
            for (key in subShares.keys) {
                subShares[key] = subShares[key]!! * ratio
            }
            remainingShare = 0.0
        } else if (remainingShare < 0.0) {
            remainingShare = 0.0
        }

        // Distribute remaining share based on preset
        if (unassignedSubs.isNotEmpty() && remainingShare > 0) {
            when (preset) {
                "proportional" -> {
                    val totalUnassignedServers = unassignedSubs.sumOf { subGroups[it]?.size ?: 0 }
                    for (subId in unassignedSubs) {
                        val count = subGroups[subId]?.size ?: 0
                        subShares[subId] = if (totalUnassignedServers > 0) {
                            (count.toDouble() / totalUnassignedServers) * remainingShare
                        } else 0.0
                    }
                }
                "inverse" -> {
                    // Inverse: 1/size
                    val totalServers = unassignedSubs.sumOf { subGroups[it]?.size ?: 0 }
                    if (totalServers > 0) {
                        val maxCount = unassignedSubs.maxOf { subGroups[it]?.size ?: 0 }
                        val inverseScores = unassignedSubs.associateWith { subId ->
                            maxCount - (subGroups[subId]?.size ?: 0) + 1.0
                        }
                        val sumInverse = inverseScores.values.sum()
                        for (subId in unassignedSubs) {
                            subShares[subId] = (inverseScores[subId]!! / sumInverse) * remainingShare
                        }
                    }
                }
                else -> { // "equal"
                    val sharePerSub = remainingShare / unassignedSubs.size
                    for (subId in unassignedSubs) {
                        subShares[subId] = sharePerSub
                    }
                }
            }
        }

        val chunks = mutableListOf<List<Pair<String, ProfileItem>>>()
        
        while (subGroups.values.any { it.isNotEmpty() }) {
            val currentChunk = mutableListOf<Pair<String, ProfileItem>>()
            val targetSizes = mutableMapOf<String, Int>()
            var activeSharesSum = 0.0
            
            // active subscriptions for this chunk
            val activeSubs = subGroups.filter { it.value.isNotEmpty() }.keys
            for (subId in activeSubs) {
                activeSharesSum += subShares[subId] ?: 0.0
            }

            if (activeSharesSum <= 0.0) activeSharesSum = 1.0 // fallback
            
            var remainingChunkSlots = chunkSize
            for (subId in activeSubs) {
                val share = subShares[subId] ?: 0.0
                var slots = Math.round((share / activeSharesSum) * chunkSize).toInt()
                if (slots > remainingChunkSlots) slots = remainingChunkSlots
                targetSizes[subId] = slots
                remainingChunkSlots -= slots
            }
            
            // Distribute leftovers if any
            if (remainingChunkSlots > 0 && activeSubs.isNotEmpty()) {
                val activeList = activeSubs.toList()
                for (i in 0 until remainingChunkSlots) {
                    val subId = activeList[Random.nextInt(activeList.size)]
                    targetSizes[subId] = (targetSizes[subId] ?: 0) + 1
                }
            }
            
            // Pull servers
            var actuallyNeeded = chunkSize
            for (subId in activeSubs) {
                var quota = targetSizes[subId] ?: 0
                val groupList = subGroups[subId]!!
                while (quota > 0 && groupList.isNotEmpty()) {
                    currentChunk.add(groupList.removeAt(0))
                    quota--
                    actuallyNeeded--
                }
            }
            
            // If some sub couldn't fulfill quota, fill with others
            while (actuallyNeeded > 0 && subGroups.values.any { it.isNotEmpty() }) {
                val activeList = subGroups.filter { it.value.isNotEmpty() }.keys.toList()
                if (activeList.isEmpty()) break
                val subId = activeList[Random.nextInt(activeList.size)]
                currentChunk.add(subGroups[subId]!!.removeAt(0))
                actuallyNeeded--
            }
            
            if (currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.shuffled()) // shuffle within chunk
            }
        }
        
        return chunks
    }
}
