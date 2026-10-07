package com.kiktor.v2whitelist.handler

import android.content.Context
import android.util.Log
import com.kiktor.v2whitelist.handler.GeekModeLogger
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.dto.ProfileItem
import com.kiktor.v2whitelist.util.MessageUtil
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress
import com.kiktor.v2whitelist.enums.EConfigType
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

object NodeTesterManager {

    private val testSemaphore = Semaphore(48)

    /**
     * Тестирует серверы батча параллельно в 2 этапа и возвращает результаты, отсортированные по задержке.
     * Этап 1: Быстрый TCP пинг (1200 мс). Если ни один сервер не ответил, чанк сразу пропускается.
     * Этап 2: HTTP 204 проверка задержки через ядро только для живых серверов.
     */
    suspend fun testServers(
        context: Context,
        servers: List<Pair<String, ProfileItem>>,
        totalTimeoutMs: Long = 6000,
        perServerTimeoutMs: Long = 1500
    ): List<Triple<String, ProfileItem, Long>> {
        if (servers.isEmpty()) return emptyList()

        GeekModeLogger.log("NodeTester", "testServers: Phase 1: TCP ping pre-check (1200ms) for chunk of ${servers.size} servers")

        // ── Этап 1: Быстрый параллельный TCP pre-check (1200 мс) ──────────────────
        val tcpAliveServers = coroutineScope {
            servers.map { (guid, profile) ->
                async(Dispatchers.IO) {
                    if (!currentCoroutineContext().isActive) return@async null
                    val isUdpProtocol = profile.configType == EConfigType.WIREGUARD ||
                                        profile.configType == EConfigType.HYSTERIA2
                    if (isUdpProtocol) {
                        // Для UDP протоколов TCP сокет неприменим — пропускаем на этап 204
                        Pair(guid, profile)
                    } else {
                        val host = profile.server
                        val port = profile.serverPort?.trim()?.toIntOrNull()
                        if (host.isNullOrBlank() || port == null || port <= 0 || port > 65535) {
                            null
                        } else {
                            val ok = try {
                                kotlinx.coroutines.withTimeoutOrNull(1200L) {
                                    Socket().use { socket ->
                                        socket.tcpNoDelay = true
                                        socket.connect(InetSocketAddress(host, port), 1200)
                                        true
                                    }
                                } ?: false
                            } catch (e: Exception) {
                                false
                            }
                            if (ok) Pair(guid, profile) else null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

        // Если ни один сервер не прошёл TCP пинг — не тратим время на Xray и сразу переходим к следующему батчу!
        if (tcpAliveServers.isEmpty()) {
            GeekModeLogger.log("NodeTester", "testServers: 0/${servers.size} servers passed TCP pre-check (1200ms), skipping chunk")
            return emptyList()
        }

        GeekModeLogger.log("NodeTester", "testServers: Phase 2: ${tcpAliveServers.size}/${servers.size} servers alive by TCP, checking HTTP 204 delay")

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
                val jobs = tcpAliveServers.map { (guid, profile) ->
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
                    GeekModeLogger.log("NodeTester", "Chunk testing timed out: proceeding with partial results (${resultsList.size})")
                }
            }
        }

        return resultsList.sortedBy { it.third }
    }

    /**
     * Проверяет профиль: поднимает настоящий экземпляр V2Ray-ядра с локальным SOCKS-прокси
     * и делает реальный HTTP-запрос через него.
     */
    suspend fun verifyProfile(
        context: Context,
        guid: String,
        showStatus: Boolean = true,
        customMinMbps: Double? = null,
        customBytes: Long? = null,
        customTimeoutMs: Int? = null
    ): Boolean {
        if (!currentCoroutineContext().isActive) return false
        
        // Выделяем свободный локальный порт для SOCKS-прокси
        val port = try {
            ServerSocket(0).use { it.localPort }
        } catch (e: Exception) {
            GeekModeLogger.log("NodeTester", "verifyProfile: failed to allocate port for $guid")
            return false
        }

        // Получаем конфиг с реальным SOCKS inbound на выделенном порту
        val configResult = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid, port)
        if (!configResult.status) {
            GeekModeLogger.log("NodeTester", "verifyProfile: failed to create speedtest config for $guid")
            return false
        }

        if (showStatus) {
            MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE, context.getString(R.string.status_verifying_profile))
        }

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
            if (!currentCoroutineContext().isActive) return false

            val timeout = customTimeoutMs ?: (MmkvManager.decodeSettingsString(AppConfig.PREF_PROFILE_SPEED_CHECK_TIMEOUT, "3000")
                               ?.toIntOrNull()?.takeIf { it > 0 } ?: 3_000)

            // Реальная проверка: HTTP-запрос через SOCKS прокси → VPN сервер → интернет
            val (elapsed, _) = SpeedtestManager.testConnection(context, port, timeout)
            
            if (!currentCoroutineContext().isActive) return false

            if (elapsed <= 0) {
                GeekModeLogger.log("NodeTester", "verifyProfile: traffic did not pass through server for $guid")
                false
            } else {
                GeekModeLogger.log("NodeTester", "verifyProfile: server $guid is working, latency = ${elapsed}ms")
                MmkvManager.encodeServerTestDelayMillis(guid, elapsed)

                // --- Тест скорости (если включён) ---
                val speedCheckEnabled = customMinMbps != null || MmkvManager.decodeSettingsBool(AppConfig.PREF_PROFILE_SPEED_CHECK_ENABLED, true)
                if (speedCheckEnabled) {
                    val bytes = customBytes ?: (MmkvManager.decodeSettingsString(AppConfig.PREF_PROFILE_SPEED_CHECK_BYTES, "2000000")
                                       ?.toLongOrNull()?.takeIf { it > 0 } ?: 2_000_000L)
                    val minMbps = customMinMbps ?: (MmkvManager.decodeSettingsString(AppConfig.PREF_PROFILE_MIN_SPEED_MBPS, "1.0")
                        ?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: 1.0)

                    if (showStatus) {
                        MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE,
                            context.getString(R.string.status_speed_check_running))
                    }

                    val mbps = SpeedtestManager.measureSpeedThroughProxy(port, bytes, timeout)
                    
                    if (!currentCoroutineContext().isActive) return false
                    
                    if (mbps != null) {
                        val mbpsStr = "%.1f".format(mbps)
                        if (minMbps > 0 && mbps < minMbps) {
                            GeekModeLogger.log("NodeTester", "verifyProfile: speed for $guid = ${mbpsStr} Mbps is below minimum threshold ${minMbps} Mbps, rejecting server")
                            if (showStatus) {
                                MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE,
                                    context.getString(R.string.status_profile_speed_too_low, mbpsStr, "%.1f".format(minMbps)))
                            }
                            return false
                        }
                        GeekModeLogger.log("NodeTester", "verifyProfile: speed for $guid = ${mbpsStr} Мбит/с")
                        if (showStatus) {
                            MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE,
                                context.getString(R.string.status_profile_check_passed_speed, mbpsStr))
                        }
                    } else {
                        GeekModeLogger.log("NodeTester", "verifyProfile: speed test failed for $guid (timeout or no data)")
                        if (minMbps > 0) {
                            GeekModeLogger.log("NodeTester", "verifyProfile: speed check failed and minMbps > 0, rejecting server $guid")
                            return false
                        }
                        if (showStatus) {
                            MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE,
                                context.getString(R.string.status_profile_check_passed))
                        }
                    }
                } else {
                    if (showStatus) {
                        MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE,
                            context.getString(R.string.status_profile_check_passed))
                    }
                }

                true
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            GeekModeLogger.log("NodeTester", "verifyProfile: exception for $guid: ${e.message}")
            false
        } finally {
            // Обязательно останавливаем ядро чтобы освободить порт и ресурсы
            try {
                coreController?.stopLoop()
            } catch (_: Exception) {}
        }
    }

    fun verifyAndCacheLeftovers(context: Context, candidates: List<Triple<String, ProfileItem, Long>>) {
        val limitedCandidates = candidates.take(3)
        CoroutineScope(Dispatchers.IO).launch {
            kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                val profileCheckEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROFILE_CHECK_ENABLED, true)
                for (candidate in limitedCandidates) {
                    if (!isActive) break
                    if (MmkvManager.getVipCache().size >= MmkvManager.getVipCacheLimit()) break
                    if (profileCheckEnabled) {
                        if (verifyProfile(context, candidate.first, showStatus = false)) {
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
    }

    fun buildProportionalChunks(servers: List<Pair<String, ProfileItem>>): List<List<Pair<String, ProfileItem>>> {
        return ChunkingHelper.buildProportionalChunks(servers)
    }
}
