package com.kiktor.v2whitelist.service

import android.content.Context
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.handler.SettingsManager
import com.kiktor.v2whitelist.handler.V2RayNativeManager
import com.kiktor.v2whitelist.handler.V2rayConfigManager
import com.kiktor.v2whitelist.util.MessageUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.net.ServerSocket
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import com.kiktor.v2whitelist.handler.SpeedtestManager

/**
 * Worker that runs a batch of real-ping tests independently.
 * Each batch owns its own CoroutineScope/dispatcher and can be cancelled separately.
 */
class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val onFinish: (status: String) -> Unit = {}
) {
    private val job = SupervisorJob()
    private val cpu = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val dispatcher = Executors.newFixedThreadPool(cpu).asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("RealPingBatchWorker"))

    private val runningCount = AtomicInteger(0)
    private val totalCount = AtomicInteger(0)

    fun start() {
        val jobs = guids.map { guid ->
            totalCount.incrementAndGet()
            scope.launch {
                runningCount.incrementAndGet()
                try {
                    val result = startRealPing(guid)
                    MessageUtil.sendMsg2UI(context, AppConfig.MSG_MEASURE_CONFIG_SUCCESS, Pair(guid, result))
                } finally {
                    val count = totalCount.decrementAndGet()
                    val left = runningCount.decrementAndGet()
                    MessageUtil.sendMsg2UI(context, AppConfig.MSG_MEASURE_CONFIG_NOTIFY, "$left / $count")
                }
            }
        }

        scope.launch {
            try {
                joinAll(*jobs.toTypedArray())
                onFinish("0")
            } catch (_: CancellationException) {
                onFinish("-1")
            } finally {
                close()
            }
        }
    }

    fun cancel() {
        job.cancel()
    }

    private fun close() {
        try {
            dispatcher.close()
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun startRealPing(guid: String): Long {
        val port = getFreePort()
        if (port == -1) return -1L

        val configResult = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid, port)
        if (!configResult.status) return -1L

        var coreController: CoreController? = null
        try {
            coreController = V2RayNativeManager.newCoreController(object : CoreCallbackHandler {
                override fun startup(): Long = 0
                override fun shutdown(): Long = 0
                override fun onEmitStatus(p0: Long, p1: String?): Long = 0
            })

            coreController.startLoop(configResult.content, 0)
            
            // Give it a bit of time to start
            Thread.sleep(300)
            
            val (delay, _) = SpeedtestManager.testConnection(context, port)
            return delay
        } catch (e: Exception) {
            return -1L
        } finally {
            try {
                coreController?.stopLoop()
            } catch (_: Exception) {}
        }
    }

    private fun getFreePort(): Int {
        return try {
            ServerSocket(0).use { it.localPort }
        } catch (e: Exception) {
            -1
        }
    }
}

