package com.kiktor.v2whitelist.desktop.engine

import com.kiktor.v2whitelist.core.config.UniversalConfigParser
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

data class V2WScanSummary(
    val totalSuccess: Int,
    val totalFailed: Int,
    val stoppedEarly: Boolean
)

/**
 * Кроссплатформенный контроллер запуска ядра глубокого сканирования v2w-core на Desktop.
 * Реализует streaming/stateless логику 1-в-1 как на Android:
 * по мере ответа нод мгновенно вызывает коллбек и позволяет мгновенно подключиться к первому ответившему узлу.
 */
object V2WCoreScanner {

    private val processLock = Any()
    private var activeProcess: Process? = null

    fun stop() {
        synchronized(processLock) {
            activeProcess?.let { proc ->
                if (proc.isAlive) {
                    try {
                        proc.destroyForcibly()
                    } catch (_: Exception) {}
                }
            }
            activeProcess = null
        }
    }

    fun findV2WScanExecutable(customPath: String? = null): File? {
        return DesktopBinaryResolver.findBinary(DesktopBinaryResolver.v2wBinaryNames, customPath)
    }

    /**
     * Потоковое сканирование списка серверов через v2w-core.
     * @param servers список серверов (guid -> profile)
     * @param firstOnly если true, процесс v2w-core сам завершается после первого успешного узла
     * @param concurrency количество параллельных проверок
     * @param onNodeSuccess коллбек, вызываемый мгновенно при успехе ноды.
     *                      Если возвращает true — сканирование немедленно прерывается (stateless connect).
     */
    suspend fun runStreamingScan(
        servers: List<Pair<String, ProfileItem>>,
        customBinaryPath: String? = null,
        firstOnly: Boolean = false,
        concurrency: Int = 35,
        timeoutSec: Int = 10,
        onNodeSuccess: suspend (guid: String, profile: ProfileItem, delayMs: Long) -> Boolean
    ): V2WScanSummary = withContext(Dispatchers.IO) {
        val binary = findV2WScanExecutable(customBinaryPath)
        if (binary == null) {
            val err = "Бинарник v2w-scan не найден. Скомпилируйте v2w-core или укажите путь в настройках."
            GeekModeLogger.log("v2w-core", err)
            return@withContext V2WScanSummary(0, 0, stoppedEarly = false)
        }

        // Подготовка URL-ссылок и карты для обратного сопоставления
        val urlToNode = mutableMapOf<String, Pair<String, ProfileItem>>()
        val linksList = mutableListOf<String>()

        for (server in servers) {
            val uri = UniversalConfigParser.toUri(server.second) ?: continue
            val cleanUri = uri.trim()
            if (cleanUri.isNotEmpty()) {
                linksList.add(cleanUri)
                urlToNode[cleanUri] = server
                // Добавляем также ключ без fragment (#remarks) для надежного матчинга
                val hashIdx = cleanUri.indexOf('#')
                if (hashIdx != -1) {
                    val baseUri = cleanUri.substring(0, hashIdx)
                    urlToNode[baseUri] = server
                }
            }
        }

        if (linksList.isEmpty()) {
            GeekModeLogger.log("v2w-core", "Нет поддерживаемых ссылок для сканирования (VLESS/Trojan/Hysteria2/SS).")
            return@withContext V2WScanSummary(0, 0, stoppedEarly = false)
        }

        GeekModeLogger.log("v2w-core", "Запуск ядра сканирования: ${binary.absolutePath} (узлов: ${linksList.size}, потоков: $concurrency)")

        val cmd = mutableListOf(
            binary.absolutePath,
            "-stdin",
            "-stream",
            "-concurrency", concurrency.toString(),
            "-timeout", timeoutSec.toString()
        )
        if (firstOnly) {
            cmd.add("-first")
        }

        stop() // Гарантированно глушим предыдущий процесс сканера перед новым запуском
        var stoppedEarly = false
        var successCount = 0
        var failCount = 0

        val process: Process
        try {
            process = ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            val errMsg = "Ошибка запуска ядра сканирования (${binary.name}): ${e.message}"
            GeekModeLogger.log("v2w-core", errMsg)
            return@withContext V2WScanSummary(0, 0, stoppedEarly = false)
        }

        synchronized(processLock) {
            activeProcess = process
        }

        // Запись ссылок в stdin процесса в отдельном потоке (исключает взаимоблокировку буферов pipe)
        val stdinWriter = Thread({
            try {
                OutputStreamWriter(process.outputStream, Charsets.UTF_8).use { writer ->
                    for (link in linksList) {
                        if (!process.isAlive) break
                        writer.write(link)
                        writer.write("\n")
                    }
                    writer.flush()
                }
            } catch (_: Exception) {}
        }, "v2w-scan-stdin-feeder").apply {
            isDaemon = true
            start()
        }

        try {
            // Чтение потоковых результатов в реальном времени
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String? = null
                while (isActive && reader.readLine().also { line = it } != null) {
                    val l = line?.trim().orEmpty()
                    if (l.startsWith("SUCCESS\t")) {
                        successCount++
                        val parts = l.split("\t")
                        if (parts.size >= 3) {
                            val delayMs = parts[1].toLongOrNull() ?: 0L
                            val configUrl = parts[2].trim()

                            val matched = urlToNode[configUrl]
                                ?: urlToNode[configUrl.substringBefore('#')]

                            if (matched != null) {
                                GeekModeLogger.log("v2w-core", "Node responded: ${matched.second.remarks} (${delayMs}ms)")
                                val shouldAbort = onNodeSuccess(matched.first, matched.second, delayMs)
                                if (shouldAbort) {
                                    stoppedEarly = true
                                    GeekModeLogger.log("v2w-core", "Stateless fast-match triggered! Остановка сканера...")
                                    break
                                }
                            }
                        }
                    } else if (l.startsWith("COMPLETE\t")) {
                        val parts = l.split("\t")
                        if (parts.size >= 3) {
                            val totalSuccess = parts[1].toIntOrNull() ?: successCount
                            val totalFailed = parts[2].toIntOrNull() ?: 0
                            successCount = totalSuccess
                            failCount = totalFailed
                        }
                    }
                }
            }
        } finally {
            stdinWriter.interrupt()
            synchronized(processLock) {
                if (activeProcess == process) {
                    activeProcess = null
                }
            }
            if (process.isAlive) {
                try {
                    process.destroyForcibly()
                } catch (_: Exception) {}
            }
        }

        GeekModeLogger.log("v2w-core", "Сканирование завершено. Успешно: $successCount, Ошибок: $failCount, Прервано: $stoppedEarly")
        return@withContext V2WScanSummary(
            totalSuccess = successCount,
            totalFailed = failCount,
            stoppedEarly = stoppedEarly
        )
    }
}
