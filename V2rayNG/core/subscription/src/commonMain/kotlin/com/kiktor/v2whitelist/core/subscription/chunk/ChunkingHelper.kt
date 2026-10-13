package com.kiktor.v2whitelist.core.subscription.chunk

import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.SubscriptionItem
import kotlin.math.roundToInt
import kotlin.random.Random

object ChunkingHelper {

    /**
     * Разбивает серверы на пропорциональные чанки согласно долям подписок (sharePercent).
     *
     * @param servers Список пар (GUID, ProfileItem).
     * @param subscriptions Карта подписок (GUID -> SubscriptionItem).
     * @param chunkSize Размер чанка (по умолчанию 20).
     * @param preset Стратегия распределения ("equal", "proportional", "inverse", "random").
     */
    fun buildProportionalChunks(
        servers: List<Pair<String, ProfileItem>>,
        subscriptions: Map<String, SubscriptionItem>,
        chunkSize: Int = 20,
        preset: String = "equal"
    ): List<List<Pair<String, ProfileItem>>> {
        if (servers.isEmpty()) return emptyList()
        val effectiveChunkSize = if (chunkSize > 0) chunkSize else 20

        if (preset == "random") {
            return servers.chunked(effectiveChunkSize)
        }

        // Группируем серверы по subscriptionId
        val subGroups = servers.groupBy { it.second.subscriptionId }
            .mapValues { it.value.toMutableList() }
            .toMutableMap()

        // Рассчитываем доли
        val subShares = mutableMapOf<String, Double>()
        var remainingShare = 100.0
        val unassignedSubs = mutableListOf<String>()

        for ((subId, _) in subGroups) {
            val subItem = subscriptions[subId]
            if (subItem?.sharePercent != null) {
                val share = subItem.sharePercent!!.toDouble()
                subShares[subId] = share
                remainingShare -= share
            } else {
                unassignedSubs.add(subId)
            }
        }

        // Нормализация, если хардкодные доли превышают 100%
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

        // Распределяем оставшуюся долю по выбранному пресету
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

            val activeSubs = subGroups.filter { it.value.isNotEmpty() }.keys
            for (subId in activeSubs) {
                activeSharesSum += subShares[subId] ?: 0.0
            }

            if (activeSharesSum <= 0.0) activeSharesSum = 1.0

            var remainingChunkSlots = effectiveChunkSize
            for (subId in activeSubs) {
                val share = subShares[subId] ?: 0.0
                var slots = ((share / activeSharesSum) * effectiveChunkSize).roundToInt()
                if (slots > remainingChunkSlots) slots = remainingChunkSlots
                targetSizes[subId] = slots
                remainingChunkSlots -= slots
            }

            if (remainingChunkSlots > 0 && activeSubs.isNotEmpty()) {
                val activeList = activeSubs.toList()
                for (i in 0 until remainingChunkSlots) {
                    val subId = activeList[Random.nextInt(activeList.size)]
                    targetSizes[subId] = (targetSizes[subId] ?: 0) + 1
                }
            }

            var actuallyNeeded = effectiveChunkSize
            for (subId in activeSubs) {
                var quota = targetSizes[subId] ?: 0
                val groupList = subGroups[subId]!!
                while (quota > 0 && groupList.isNotEmpty()) {
                    currentChunk.add(groupList.removeAt(0))
                    quota--
                    actuallyNeeded--
                }
            }

            while (actuallyNeeded > 0 && subGroups.values.any { it.isNotEmpty() }) {
                val activeList = subGroups.filter { it.value.isNotEmpty() }.keys.toList()
                if (activeList.isEmpty()) break
                val subId = activeList[Random.nextInt(activeList.size)]
                currentChunk.add(subGroups[subId]!!.removeAt(0))
                actuallyNeeded--
            }

            if (currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.shuffled())
            }
        }

        return chunks
    }
}
