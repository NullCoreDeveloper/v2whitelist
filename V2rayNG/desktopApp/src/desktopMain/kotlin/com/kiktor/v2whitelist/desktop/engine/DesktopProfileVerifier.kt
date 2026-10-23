package com.kiktor.v2whitelist.desktop.engine

import com.kiktor.v2whitelist.core.config.xray.XrayConfigGenerator
import com.kiktor.v2whitelist.core.config.xray.XrayGeneratorOptions
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

    private fun findFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
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
        onStatusUpdate: ((String) -> Unit)? = null
    ): VerifyResult = withContext(Dispatchers.IO) {
        val runner = XrayDesktopRunner()
        val binary = runner.findXrayExecutable(xrayBinaryPath)
        if (binary == null) {
            return@withContext VerifyResult(
                success = false,
                errorMessage = "Исполняемый файл Xray не найден для верификации"
            )
        }

        val testPort = findFreePort()
        val options = XrayGeneratorOptions(
            socksPort = testPort,
            httpPort = 0,
            enableHttpInbound = false,
            logLevel = "none"
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
            process = pb.start()

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

            // ── Шаг 1: e2e HTTP 204 проверка через SOCKS ──
            val serverTitle = profile.remarks.ifBlank { profile.server }
            onStatusUpdate?.invoke("Проверяем профиль: $serverTitle…")
            val latency = measureE2ELatency(testPort, timeoutMs)
            if (latency <= 0) {
                GeekModeLogger.log("Verifier", "Трафик не прошел через ${profile.remarks}")
                onStatusUpdate?.invoke("Профиль не прошёл проверку, пробуем другой…")
                return@withContext VerifyResult(
                    success = false,
                    errorMessage = "e2e проверка не прошла"
                )
            }

            GeekModeLogger.log("Verifier", "${profile.remarks}: e2e HTTP 204 успешен (${latency}ms)")

            // ── Шаг 1.5 (предпоследний этап перед проверкой скорости): e2e HTTP 204 через конечный узел ──
            var testPortForSpeed = testPort
            if (!customEndpointUrl.isNullOrBlank() && endpointE2eCheckEnabled) {
                onStatusUpdate?.invoke("Проверка сквозного соединения через конечный узел…")
                val chainedPort = findFreePort()
                val chainedOptions = options.copy(
                    socksPort = chainedPort,
                    customEndpointUrl = customEndpointUrl
                )
                try {
                    val chainedJson = XrayConfigGenerator.generateJson(profile, chainedOptions)
                    chainedTempConfig = File.createTempFile("v2w_endpoint_e2e_", ".json").apply {
                        deleteOnExit()
                        writeText(chainedJson)
                    }
                    val chainedPb = ProcessBuilder(binary.absolutePath, "run", "-c", chainedTempConfig.absolutePath)
                        .redirectErrorStream(true)
                    chainedProcess = chainedPb.start()
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

                    val chainedLatency = measureE2ELatency(chainedPort, timeoutMs)
                    if (chainedLatency <= 0) {
                        GeekModeLogger.log("Verifier", "⚠️ e2e проверка через конечный узел FAILED для ${profile.remarks}")
                        return@withContext VerifyResult(
                            success = false,
                            errorMessage = "Конечный узел не отвечает через данный сервер"
                        )
                    }
                    GeekModeLogger.log("Verifier", "${profile.remarks}: сквозное соединение через конечный узел успешно (${chainedLatency}ms)")
                    testPortForSpeed = chainedPort
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    GeekModeLogger.log("Verifier", "Ошибка при проверке цепочки: ${e.message}")
                    return@withContext VerifyResult(success = false, errorMessage = e.message)
                }
            }

            // ── Шаг 2: Замер скорости (если включен) ──
            var mbps: Double? = null
            if (speedCheckEnabled) {
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
                if (proc.isAlive) {
                    try { proc.destroyForcibly() } catch (_: Exception) {}
                }
            }
            chainedProcess?.let { proc ->
                if (proc.isAlive) {
                    try { proc.destroyForcibly() } catch (_: Exception) {}
                }
            }
            try {
                tempConfig?.delete()
            } catch (_: Exception) {}
            try {
                chainedTempConfig?.delete()
            } catch (_: Exception) {}
        }
    }

    private fun measureE2ELatency(socksPort: Int, timeoutMs: Long): Long {
        val testUrls = listOf(
            "https://www.google.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
            "https://connectivitycheck.gstatic.com/generate_204"
        )
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))

        for (urlStr in testUrls) {
            try {
                val url = java.net.URI.create(urlStr).toURL()
                val start = System.currentTimeMillis()
                val conn = (url.openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = timeoutMs.toInt()
                    readTimeout = timeoutMs.toInt()
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                }

                try {
                    conn.connect()
                    val code = conn.responseCode
                    val elapsed = System.currentTimeMillis() - start
                    if (code in 200..399) {
                        return elapsed
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {}
        }
        return -1L
    }

    private fun measureSpeed(socksPort: Int, bytes: Long, timeoutMs: Long): Double? {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val urlStr = "https://speed.cloudflare.com/__down?bytes=$bytes"

        return try {
            val url = java.net.URI.create(urlStr).toURL()
            val conn = (url.openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = timeoutMs.toInt()
                readTimeout = timeoutMs.toInt()
                requestMethod = "GET"
            }

            var stream: InputStream? = null
            var totalRead = 0L
            var firstByteTime = 0L

            try {
                conn.connect()
                stream = conn.inputStream
                val buf = ByteArray(16384)
                var n: Int
                while (stream.read(buf).also { n = it } != -1) {
                    if (firstByteTime == 0L) {
                        firstByteTime = System.currentTimeMillis()
                    }
                    totalRead += n
                }
            } finally {
                try { stream?.close() } catch (_: Exception) {}
                conn.disconnect()
            }

            if (firstByteTime == 0L || totalRead == 0L) return null
            val elapsedSec = (System.currentTimeMillis() - firstByteTime) / 1000.0
            if (elapsedSec <= 0.05) return null

            val mbps = (totalRead * 8.0) / (elapsedSec * 1_000_000.0)
            mbps
        } catch (_: Exception) {
            null
        }
    }
}
