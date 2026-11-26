package com.kiktor.v2whitelist.desktop.engine

import com.kiktor.v2whitelist.core.config.xray.XrayConfigGenerator
import com.kiktor.v2whitelist.core.config.xray.XrayGeneratorOptions
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit

data class VerifyResult(
    val success: Boolean,
    val latencyMs: Long = -1L,
    val speedMbps: Double? = null,
    val errorMessage: String? = null
)

/**
 * Кроссплатформенный верификатор профилей через реальный инстанс Xray:
 * 1. Поднимает временный инстанс Xray на ephemeral SOCKS порту.
 * 2. Делает реальный e2e HTTP 204 запрос через SOCKS прокси в интернет (Google/Cloudflare).
 * 3. Замеряет скорость скачивания чанка (Мбит/с) и проверяет минимальный порог.
 */
object DesktopProfileVerifier {

    private val activeProcesses = java.util.concurrent.ConcurrentHashMap.newKeySet<Process>()

    fun stop() {
        val procs = activeProcesses.toList()
        activeProcesses.clear()
        for (proc in procs) {
            try {
                if (proc.isAlive) {
                    proc.destroyForcibly()
                    proc.waitFor(500, TimeUnit.MILLISECONDS)
                } else {
                    proc.waitFor()
                }
            } catch (_: Exception) {}
        }
    }

    private fun findFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    /**
     * Быстрая E2E-проверка конечного узла через временный инстанс Xray с цепочкой dialerProxy.
     * Используется полным сканером вместо net.Pipe внутри v2w-core.
     */
    suspend fun verifyEndpointChainedOnly(
        profile: ProfileItem,
        customEndpointUrl: String,
        xrayBinaryPath: String? = null,
        timeoutMs: Long = 7000L
    ): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
        if (!isActive) return@withContext false to -1L
        val epTrimmed = customEndpointUrl.trim()
        if (epTrimmed.isEmpty()) return@withContext false to -1L

        val runner = XrayDesktopRunner()
        val binary = runner.findXrayExecutable(xrayBinaryPath) ?: return@withContext false to -1L

        val testPort = findFreePort()
        val testHttpPort = findFreePort()
        val options = XrayGeneratorOptions(
            socksPort = testPort,
            httpPort = testHttpPort,
            enableHttpInbound = true,
            logLevel = "none",
            bypassRussianServices = false,
            bypassPrivateIps = false,
            customEndpointUrl = epTrimmed
        )

        var tempConfig: File? = null
        var process: Process? = null
        try {
            val jsonConfig = XrayConfigGenerator.generateJson(profile, options)
            tempConfig = File.createTempFile("v2w_endpoint_e2e_", ".json").apply {
                deleteOnExit()
                writeText(jsonConfig)
            }
            val pb = ProcessBuilder(binary.absolutePath, "run", "-c", tempConfig.absolutePath)
                .redirectErrorStream(true)
            pb.directory(binary.parentFile)
            val assetDir = runner.findAssetLocation(binary) ?: binary.parentFile
            if (assetDir != null && assetDir.exists()) {
                pb.environment()["XRAY_LOCATION_ASSET"] = assetDir.absolutePath
                pb.environment()["V2RAY_LOCATION_ASSET"] = assetDir.absolutePath
            }
            process = pb.start()
            activeProcesses.add(process)

            delay(if (profile.configType == EConfigType.HYSTERIA2) 750L else 450L)
            if (!process.isAlive || !isActive) {
                return@withContext false to -1L
            }

            val latency = measureE2ELatency(testPort, testHttpPort, timeoutMs)
            (latency > 0L) to (if (latency > 0L) latency else -1L)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GeekModeLogger.log("Verifier", "Xray endpoint E2E check error: ${e.message}")
            false to -1L
        } finally {
            process?.let { proc ->
                activeProcesses.remove(proc)
                try {
                    if (proc.isAlive) {
                        proc.destroyForcibly()
                        proc.waitFor(1, TimeUnit.SECONDS)
                    } else {
                        proc.waitFor()
                    }
                } catch (_: Exception) {}
            }
            try { tempConfig?.delete() } catch (_: Exception) {}
        }
    }

    suspend fun verifyProfile(
        profile: ProfileItem,
        xrayBinaryPath: String? = null,
        timeoutMs: Long = 7000L,
        speedCheckEnabled: Boolean = true,
        minSpeedMbps: Double = 1.0,
        bytesToDownload: Long = 500_000L,
        customEndpointUrl: String? = null,
        endpointE2eCheckEnabled: Boolean = true,
        serviceCheckEnabled: Boolean = false,
        servicesToCheck: List<com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckItem> = emptyList(),
        speedOnlyMode: Boolean = false,
        preMeasuredLatencyMs: Long = 0L,
        onStatusUpdate: ((String) -> Unit)? = null
    ): VerifyResult = withContext(Dispatchers.IO) {
        if (!isActive) throw CancellationException("Верификация отменена")
        val serverTitle = profile.remarks.ifBlank { profile.server }
        if (speedOnlyMode && !speedCheckEnabled) {
            onStatusUpdate?.invoke("Проверено в v2w-core ✔: $serverTitle")
            return@withContext VerifyResult(
                success = true,
                latencyMs = preMeasuredLatencyMs.coerceAtLeast(1L)
            )
        }

        val runner = XrayDesktopRunner()
        val binary = runner.findXrayExecutable(xrayBinaryPath)
        if (binary == null) {
            return@withContext VerifyResult(
                success = false,
                errorMessage = "Исполняемый файл Xray не найден для верификации"
            )
        }

        val effectiveEndpointUrl = customEndpointUrl?.trim()?.takeIf { epUrl ->
            if (epUrl.isBlank()) return@takeIf false
            val epParsed = com.kiktor.v2whitelist.core.config.UniversalConfigParser.parse(epUrl)
            !(epParsed != null && epParsed.server == profile.server && epParsed.serverPort == profile.serverPort)
        }
        val hasEndpoint = !effectiveEndpointUrl.isNullOrBlank()

        val testPort = findFreePort()
        val testHttpPort = findFreePort()
        val singleChainEndpoint = if (speedOnlyMode && hasEndpoint) {
            effectiveEndpointUrl
        } else {
            null
        }
        val options = XrayGeneratorOptions(
            socksPort = testPort,
            httpPort = testHttpPort,
            enableHttpInbound = true,
            logLevel = "none",
            bypassRussianServices = false,
            bypassPrivateIps = false,
            customEndpointUrl = singleChainEndpoint
        )

        var tempConfig: File? = null
        var process: Process? = null
        var chainedTempConfig: File? = null
        var chainedProcess: Process? = null

        try {
            val jsonConfig = XrayConfigGenerator.generateJson(profile, options)
            tempConfig = File.createTempFile("v2w_speedtest_", ".json").apply {
                deleteOnExit()
                writeText(jsonConfig)
            }

            val pb = ProcessBuilder(binary.absolutePath, "run", "-c", tempConfig.absolutePath)
                .redirectErrorStream(true)
            pb.directory(binary.parentFile)
            val assetDir = runner.findAssetLocation(binary) ?: binary.parentFile
            if (assetDir != null && assetDir.exists()) {
                pb.environment()["XRAY_LOCATION_ASSET"] = assetDir.absolutePath
                pb.environment()["V2RAY_LOCATION_ASSET"] = assetDir.absolutePath
            }
            process = pb.start()
            activeProcesses.add(process)

            // Ждем поднятия ядра
            delay(400)
            if (!process.isAlive) {
                val exitCode = try { process.exitValue() } catch (_: Exception) { -1 }
                val err = try { process.inputStream.bufferedReader().readText().trim() } catch (_: Exception) { "" }
                GeekModeLogger.log("Verifier", "Тестовый инстанс Xray упал при старте (code=$exitCode): $err")
                return@withContext VerifyResult(
                    success = false,
                    errorMessage = "Тестовый инстанс Xray упал при старте: $err"
                )
            }

            // ── Шаг 1: Проверка доступности сервисов либо e2e HTTP 204 (пропускается в speedOnlyMode, т.к. уже проверено в v2w-core) ──
            var latency: Long
            var testPortForSpeed = testPort
            if (speedOnlyMode) {
                latency = preMeasuredLatencyMs.coerceAtLeast(1L)
                GeekModeLogger.log("Verifier", "${profile.remarks}: v2w-core уже выполнил полную проверку (${latency}ms) -> Xray только замер скорости")
            } else {
                val enabledServices = servicesToCheck.filter { it.enabled }
                val shouldCheckServices = serviceCheckEnabled && enabledServices.isNotEmpty()
                val checkServicesOnOuterHop = shouldCheckServices && !hasEndpoint

                if (checkServicesOnOuterHop) {
                    onStatusUpdate?.invoke("Проверяем доступность сервисов (${enabledServices.size}): $serverTitle…")
                    val results = com.kiktor.v2whitelist.core.domain.servicecheck.ServiceAvailabilityChecker.checkServicesParallel(
                        services = enabledServices,
                        socksPort = testPort,
                        httpPort = testHttpPort,
                        timeoutMs = maxOf(timeoutMs, 5000L)
                    )
                    val failed = results.filter { !it.success }
                    if (failed.isNotEmpty()) {
                        val failedNames = failed.joinToString(", ") { it.service.name }
                        GeekModeLogger.log("Verifier", "${profile.remarks}: сервисы недоступны [$failedNames]")
                        onStatusUpdate?.invoke("Сервисы недоступны ($failedNames), пробуем другой…")
                        return@withContext VerifyResult(
                            success = false,
                            errorMessage = "Сервисы недоступны: $failedNames"
                        )
                    }
                    latency = results.map { it.latencyMs }.filter { it > 0 }.average().toLong().takeIf { it > 0 } ?: 100L
                    GeekModeLogger.log("Verifier", "${profile.remarks}: все ${enabledServices.size} сервисов доступны (ср. ${latency}ms)")
                } else {
                    onStatusUpdate?.invoke("Проверяем профиль: $serverTitle…")
                    val l = measureE2ELatency(testPort, testHttpPort, timeoutMs)
                    if (l <= 0) {
                        GeekModeLogger.log("Verifier", "Трафик не прошел через ${profile.remarks}")
                        onStatusUpdate?.invoke("Профиль не прошёл проверку, пробуем другой…")
                        return@withContext VerifyResult(
                            success = false,
                            errorMessage = "e2e проверка не прошла"
                        )
                    }
                    latency = l
                    GeekModeLogger.log("Verifier", "${profile.remarks}: e2e HTTP 204 успешен (${latency}ms)")
                }

                // ── Шаг 1.5: Подключение цепочки через конечный узел (сервисы / e2e 204 и замер скорости) ──
                if (hasEndpoint) {
                    onStatusUpdate?.invoke("Проверка соединения через конечный узел…")
                    val chainedPort = findFreePort()
                    val chainedHttpPort = findFreePort()
                    val chainedOptions = options.copy(
                        socksPort = chainedPort,
                        httpPort = chainedHttpPort,
                        enableHttpInbound = true,
                        customEndpointUrl = effectiveEndpointUrl
                    )
                    try {
                        val chainedJson = XrayConfigGenerator.generateJson(profile, chainedOptions)
                        chainedTempConfig = File.createTempFile("v2w_endpoint_e2e_", ".json").apply {
                            deleteOnExit()
                            writeText(chainedJson)
                        }
                        val chainedPb = ProcessBuilder(binary.absolutePath, "run", "-c", chainedTempConfig.absolutePath)
                            .redirectErrorStream(true)
                        chainedPb.directory(binary.parentFile)
                        if (assetDir != null && assetDir.exists()) {
                            chainedPb.environment()["XRAY_LOCATION_ASSET"] = assetDir.absolutePath
                            chainedPb.environment()["V2RAY_LOCATION_ASSET"] = assetDir.absolutePath
                        }
                        chainedProcess = chainedPb.start()
                        activeProcesses.add(chainedProcess)
                        delay(400)
                        if (!chainedProcess.isAlive) {
                            val exitCode = try { chainedProcess.exitValue() } catch (_: Exception) { -1 }
                            val err = try { chainedProcess.inputStream.bufferedReader().readText().trim() } catch (_: Exception) { "" }
                            GeekModeLogger.log("Verifier", "Тестовый инстанс Xray для цепочки конечного узла упал при старте (code=$exitCode): $err")
                            return@withContext VerifyResult(
                                success = false,
                                errorMessage = "Инстанс цепочки не запустился: $err"
                            )
                        }

                        if (!isActive) throw CancellationException("Верификация отменена")

                        if (endpointE2eCheckEnabled) {
                            onStatusUpdate?.invoke("Проверка сквозного соединения через конечный узел…")
                            val chainedLatency = measureE2ELatency(chainedPort, chainedHttpPort, maxOf(timeoutMs, 6000L))
                            if (chainedLatency <= 0) {
                                GeekModeLogger.log("Verifier", "⚠️ e2e проверка через конечный узел FAILED для ${profile.remarks}")
                                return@withContext VerifyResult(
                                    success = false,
                                    errorMessage = "Конечный узел не отвечает через данный сервер"
                                )
                            }
                            latency = chainedLatency
                            GeekModeLogger.log("Verifier", "${profile.remarks}: сквозное соединение через конечный узел успешно (${chainedLatency}ms)")
                        }
                        testPortForSpeed = chainedPort
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        GeekModeLogger.log("Verifier", "Ошибка при проверке цепочки: ${e.message}")
                        return@withContext VerifyResult(success = false, errorMessage = e.message)
                    }
                }
            }

            // ── Шаг 2: Замер скорости (если включен) ──
            var mbps: Double? = null
            if (speedCheckEnabled) {
                if (!isActive) throw CancellationException("Верификация отменена")
                onStatusUpdate?.invoke("Замер скорости соединения: $serverTitle…")
                mbps = measureSpeed(testPortForSpeed, bytesToDownload, timeoutMs = timeoutMs)
                if (mbps != null) {
                    val formatted = "%.2f".format(mbps)
                    GeekModeLogger.log("Verifier", "${profile.remarks}: замер скорости = $formatted Мбит/с")
                    if (minSpeedMbps > 0.0 && mbps < minSpeedMbps) {
                        GeekModeLogger.log(
                            "Verifier",
                            "${profile.remarks}: скорость $formatted Мбит/с ниже порога $minSpeedMbps Мбит/с -> отклонен"
                        )
                        onStatusUpdate?.invoke("Низкая скорость ($formatted Мбит/с < $minSpeedMbps Мбит/с), ищем следующий…")
                        return@withContext VerifyResult(
                            success = false,
                            latencyMs = latency,
                            speedMbps = mbps,
                            errorMessage = "Скорость ниже минимального порога"
                        )
                    }
                    onStatusUpdate?.invoke("Профиль проверен ✔ · $formatted Мбит/с")
                } else if (minSpeedMbps > 0.0) {
                    GeekModeLogger.log(
                        "Verifier",
                        "${profile.remarks}: не удалось подтвердить скорость ≥ $minSpeedMbps Мбит/с (таймаут/сбой замера) -> отклонен"
                    )
                    onStatusUpdate?.invoke("Скорость не подтверждена (< $minSpeedMbps Мбит/с), пробуем другой…")
                    return@withContext VerifyResult(
                        success = false,
                        latencyMs = latency,
                        errorMessage = "Не удалось подтвердить скорость ≥ $minSpeedMbps Мбит/с"
                    )
                } else if (hasEndpoint && (speedOnlyMode || !endpointE2eCheckEnabled)) {
                    onStatusUpdate?.invoke("Проверка соединения через конечный узел…")
                    val fallbackChainLatency = measureE2ELatency(
                        socksPort = testPortForSpeed,
                        httpPort = if (testPortForSpeed == testPort) testHttpPort else null,
                        timeoutMs = maxOf(timeoutMs, 6000L)
                    )
                    if (fallbackChainLatency <= 0) {
                        GeekModeLogger.log("Verifier", "⚠️ Замер скорости и контрольный HTTP 204 через конечный узел не прошли для ${profile.remarks} -> отклонен")
                        return@withContext VerifyResult(
                            success = false,
                            latencyMs = latency,
                            errorMessage = "Конечный узел не отвечает через данный сервер"
                        )
                    }
                    GeekModeLogger.log("Verifier", "${profile.remarks}: замер скорости завершился таймаутом, но HTTP 204 через конечный узел успешен (${fallbackChainLatency}ms) -> узел допущен")
                    onStatusUpdate?.invoke("Профиль прошёл проверку ✔")
                } else {
                    GeekModeLogger.log("Verifier", "${profile.remarks}: замер скорости завершился таймаутом, но HTTP 204 успешен -> узел допущен")
                    onStatusUpdate?.invoke("Профиль прошёл проверку ✔")
                }
            } else {
                onStatusUpdate?.invoke("Профиль прошёл проверку ✔")
            }

            return@withContext VerifyResult(
                success = true,
                latencyMs = latency,
                speedMbps = mbps
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext VerifyResult(
                success = false,
                errorMessage = e.message
            )
        } finally {
            process?.let { proc ->
                activeProcesses.remove(proc)
                try {
                    if (proc.isAlive) {
                        proc.destroyForcibly()
                        proc.waitFor(1, TimeUnit.SECONDS)
                    } else {
                        proc.waitFor()
                    }
                } catch (_: Exception) {}
            }
            chainedProcess?.let { proc ->
                activeProcesses.remove(proc)
                try {
                    if (proc.isAlive) {
                        proc.destroyForcibly()
                        proc.waitFor(1, TimeUnit.SECONDS)
                    } else {
                        proc.waitFor()
                    }
                } catch (_: Exception) {}
            }
            try {
                tempConfig?.delete()
            } catch (_: Exception) {}
            try {
                chainedTempConfig?.delete()
            } catch (_: Exception) {}
        }
    }

    private suspend fun measureE2ELatency(socksPort: Int, httpPort: Int? = null, timeoutMs: Long = 4000L): Long = coroutineScope {
        val testUrls = listOf(
            "https://www.google.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
            "https://connectivitycheck.gstatic.com/generate_204"
        )
        val proxy = when {
            httpPort != null && httpPort > 0 -> Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))
            socksPort > 0 -> Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            else -> Proxy.NO_PROXY
        }
        val perUrlTimeout = timeoutMs.coerceIn(1500L, 4500L).toInt()
        val resultChannel = kotlinx.coroutines.channels.Channel<Long>(testUrls.size)
        val pending = java.util.concurrent.atomic.AtomicInteger(testUrls.size)

        val jobs = testUrls.map { urlStr ->
            launch(Dispatchers.IO) {
                var latency = -1L
                try {
                    val url = java.net.URI.create(urlStr).toURL()
                    val start = System.currentTimeMillis()
                    val conn = (url.openConnection(proxy) as HttpURLConnection).apply {
                        connectTimeout = perUrlTimeout
                        readTimeout = perUrlTimeout
                        instanceFollowRedirects = false
                        requestMethod = "GET"
                    }
                    try {
                        conn.connect()
                        val code = conn.responseCode
                        val elapsed = System.currentTimeMillis() - start
                        if (code in 200..399) {
                            latency = elapsed
                        }
                    } finally {
                        conn.disconnect()
                    }
                } catch (_: Exception) {
                } finally {
                    if (latency > 0) {
                        resultChannel.trySend(latency)
                    } else if (pending.decrementAndGet() == 0) {
                        resultChannel.trySend(-1L)
                    }
                }
            }
        }

        val winnerLatency = withTimeoutOrNull(perUrlTimeout.toLong() + 300L) {
            resultChannel.receive()
        } ?: -1L
        jobs.forEach { it.cancel() }
        winnerLatency
    }

    internal fun computeSustainedMbps(measuredBytes: Long, elapsedNanos: Long): Double? {
        if (measuredBytes <= 0L || elapsedNanos <= 0L) return null
        val elapsedSec = elapsedNanos / 1_000_000_000.0
        if (elapsedSec < 0.08) return null
        return (measuredBytes * 8.0) / (elapsedSec * 1_000_000.0)
    }

    internal fun measureSpeed(socksPort: Int, bytes: Long, timeoutMs: Long): Double? {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val targetBytes = maxOf(bytes, 1_500_000L)
        val effectiveTimeoutMs = maxOf(timeoutMs, 3500L).coerceAtMost(15_000L).toInt()
        val urlStr = "https://speed.cloudflare.com/__down?bytes=$targetBytes"
        val warmupBytesThreshold = minOf(targetBytes / 5L, 262_144L)
        val warmupNanosThreshold = 250_000_000L // 250ms прогрева отсекают стартовый TCP-всплеск буфера

        return try {
            val url = java.net.URI.create(urlStr).toURL()
            val conn = (url.openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = minOf(effectiveTimeoutMs, 4500)
                readTimeout = effectiveTimeoutMs
                requestMethod = "GET"
            }

            var stream: InputStream? = null
            var totalRead = 0L
            var firstByteNs = 0L
            var measureStartNs = 0L
            var measuredBytes = 0L
            val deadlineNs = System.nanoTime() + effectiveTimeoutMs.toLong() * 1_000_000L

            try {
                conn.connect()
                stream = conn.inputStream
                val buf = ByteArray(16384)
                while (System.nanoTime() < deadlineNs) {
                    val n = stream.read(buf)
                    if (n == -1) break
                    val nowNs = System.nanoTime()
                    if (firstByteNs == 0L) {
                        firstByteNs = nowNs
                    }
                    totalRead += n
                    if (measureStartNs == 0L) {
                        if (totalRead >= warmupBytesThreshold && (nowNs - firstByteNs) >= warmupNanosThreshold) {
                            measureStartNs = nowNs
                        }
                    } else {
                        measuredBytes += n
                    }
                }
            } catch (_: Exception) {
                // При срабатывании readTimeout или обрыве оцениваем реально прокачанный объём после прогрева
            } finally {
                try { stream?.close() } catch (_: Exception) {}
                conn.disconnect()
            }

            val endNs = System.nanoTime()
            if (measureStartNs > 0L && measuredBytes > 0L) {
                computeSustainedMbps(measuredBytes, endNs - measureStartNs)
            } else if (firstByteNs > 0L && totalRead > 0L) {
                // Фоллбек, если соединение за всё время не смогло выйти за порог прогрева (очень медленный узел)
                computeSustainedMbps(totalRead, endNs - firstByteNs)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
