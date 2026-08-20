package com.kiktor.v2whitelist.handler

import android.content.Context
import android.util.Log
import com.kiktor.v2whitelist.handler.GeekModeLogger
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.dto.ProfileItem
import com.kiktor.v2whitelist.util.MessageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

object NodeTesterManager {

    private val testSemaphore = Semaphore(48)

    /**
     * Тестирует серверы параллельно и возвращает результаты, отсортированные по задержке.
     */
    suspend fun testServers(
        context: Context,
        servers: List<Pair<String, ProfileItem>>,
        totalTimeoutMs: Long = 6000,
        perServerTimeoutMs: Long = 1500
    ): List<Triple<String, ProfileItem, Long>> {
        GeekModeLogger.log("NodeTester", "testServers: Запуск проверки TCP пинга для чанка из ${servers.size} серверов")
        val testUrls = listOf(
            AppConfig.DELAY_TEST_URL,
            "https://www.google.com/generate_204",
            "https://www.cloudflare.com/cdn-cgi/trace",
            "https://connectivitycheck.gstatic.com/generate_204"
        )

        // AtomicBoolean вместо cancelChildren() — не ломает awaitAll() CancellationException-ом
        val foundFastServer = AtomicBoolean(false)
        val resultsList = mutableListOf<Triple<String, ProfileItem, Long>>()

        withTimeoutOrNull(totalTimeoutMs) {
            coroutineScope {
                val jobs = servers.map { (guid, profile) ->
                    async {
                        testSemaphore.withPermit {
                            // Ранний выход если уже нашли хороший сервер — не через cancelChildren!
                            if (foundFastServer.get()) return@withPermit null

                            try {
                                val randomUrl = testUrls[Random.nextInt(testUrls.size)]
                                val config = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid)
                                val delay = if (config.status) {
                                    withTimeoutOrNull(perServerTimeoutMs) {
                                        V2RayNativeManager.measureOutboundDelay(config.content, randomUrl)
                                    } ?: -1L
                                } else -1L

                                val finalDelay = if (delay <= 0) Long.MAX_VALUE else delay
                                val result = Triple(guid, profile, finalDelay)
                                
                                // Добавляем результат сразу, чтобы не потерять при таймауте чанка
                                synchronized(resultsList) {
                                    if (resultsList.none { it.first == guid }) {
                                        resultsList.add(result)
                                    }
                                }
                                
                                if (finalDelay < 500) {
                                    // Атомарно помечаем — остальные корутины пропустят тест
                                    foundFastServer.set(true)
                                }
                                result
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e // Прокидываем CancellationException дальше
                            } catch (e: Exception) {
                                GeekModeLogger.log("NodeTester", "testServers error for $guid" + ": " + e)
                                null
                            }
                        }
                    }
                }
                
                try {
                    jobs.awaitAll()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    GeekModeLogger.log("NodeTester", "Chunk testing timed out + ": " + proceeding with partial results (${resultsList.size})")
                }
            }
        }

        return resultsList.sortedBy { it.third }
    }

    /**
     * Проверяет профиль: поднимает настоящий экземпляр V2Ray-ядра с локальным SOCKS-прокси
     * и делает реальный HTTP-запрос через него.
     */
    suspend fun verifyProfile(context: Context, guid: String): Boolean {
        // Выделяем свободный локальный порт для SOCKS-прокси
        val port = try {
            ServerSocket(0).use { it.localPort }
        } catch (e: Exception) {
            GeekModeLogger.log("NodeTester", "verifyProfile: не удалось выделить порт для $guid")
            return false
        }

        // Получаем конфиг с реальным SOCKS inbound на выделенном порту
        val configResult = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid, port)
        if (!configResult.status) {
            GeekModeLogger.log("NodeTester", "verifyProfile: не удалось создать конфиг speedtest для $guid")
            return false
        }

        MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE, context.getString(R.string.status_verifying_profile))

        var coreController: CoreController? = null
        return try {
            // Запускаем отдельный экземпляр ядра V2Ray
            coreController = V2RayNativeManager.newCoreController(object : CoreCallbackHandler {
                override fun startup(): Long = 0
                override fun shutdown(): Long = 0
                override fun onEmitStatus(p0: Long, p1: String?): Long = 0
            })

            // fd=0: запуск без TUN (только SOCKS прокси на локальном порту)
            coreController.startLoop(configResult.content, 0)

            // Ждём пока ядро поднимется и установит соединение с сервером
            delay(500L)

            // Реальная проверка: HTTP-запрос через SOCKS прокси → VPN сервер → интернет
            val (elapsed, _) = SpeedtestManager.testConnection(context, port)

            if (elapsed <= 0) {
                GeekModeLogger.log("NodeTester", "verifyProfile: трафик через сервер не прошёл для $guid")
                false
            } else {
                GeekModeLogger.log("NodeTester", "verifyProfile: сервер $guid рабочий, задержка = ${elapsed}ms")
                MmkvManager.encodeServerTestDelayMillis(guid, elapsed)
                MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE, context.getString(R.string.status_profile_check_passed))
                true
            }
        } catch (e: Exception) {
            GeekModeLogger.log("NodeTester", "verifyProfile: исключение для $guid: ${e.message}")
            false
        } finally {
            // Обязательно останавливаем ядро чтобы освободить порт и ресурсы
            try {
                coreController?.stopLoop()
            } catch (_: Exception) {}
        }
    }

    fun verifyAndCacheLeftovers(context: Context, candidates: List<Triple<String, ProfileItem, Long>>) {
        GlobalScope.launch(Dispatchers.IO) {
            val profileCheckEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROFILE_CHECK_ENABLED, true)
            for (candidate in candidates) {
                if (MmkvManager.getVipCache().size >= 5) break
                if (profileCheckEnabled) {
                    if (verifyProfile(context, candidate.first)) {
                        GeekModeLogger.log("NodeTester", "Background: added ${candidate.second.remarks} to VIP cache")
                        MmkvManager.addVipServer(candidate.first)
                    }
                } else {
                    GeekModeLogger.log("NodeTester", "Background: added ${candidate.second.remarks} to VIP cache (no deep check)")
                    MmkvManager.addVipServer(candidate.first)
                }
            }
        }
    }

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
