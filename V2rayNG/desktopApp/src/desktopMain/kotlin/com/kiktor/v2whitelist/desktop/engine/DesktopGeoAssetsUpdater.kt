package com.kiktor.v2whitelist.desktop.engine

import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class GeoAssetsUpdateResult(
    val success: Boolean,
    val message: String,
    val updatedFiles: List<String> = emptyList(),
    val updatedAtMs: Long? = null
)

/**
 * Автоматическое и ручное обновление гео-баз (geosite.dat, geoip.dat) на Desktop
 * без прав администратора (сохранение в [DesktopBinaryResolver.getUserAssetsDir]).
 */
object DesktopGeoAssetsUpdater {

    const val MIN_VALID_GEO_SIZE_BYTES = 100_000L

    private val GEO_FILES = listOf(
        "geosite.dat" to "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat",
        "geoip.dat" to "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat"
    )

    fun isUpdateDue(intervalDays: Int, lastUpdatedMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (intervalDays <= 0) return false
        if (lastUpdatedMs <= 0L) return true
        val intervalMs = intervalDays.toLong() * 24L * 60L * 60L * 1000L
        return (nowMs - lastUpdatedMs) >= intervalMs
    }

    fun getEffectiveLastUpdatedMillis(storedTimestampMs: Long): Long {
        val dir = DesktopBinaryResolver.findAssetDirectory()
        val geositeMod = dir?.let { File(it, "geosite.dat").takeIf { f -> f.exists() }?.lastModified() } ?: 0L
        val geoipMod = dir?.let { File(it, "geoip.dat").takeIf { f -> f.exists() }?.lastModified() } ?: 0L
        return maxOf(storedTimestampMs, geositeMod, geoipMod)
    }

    fun isValidGeoFile(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < MIN_VALID_GEO_SIZE_BYTES) {
            return false
        }
        val header = runCatching {
            file.inputStream().use { input ->
                val buf = ByteArray(128)
                val read = input.read(buf)
                if (read <= 0) "" else String(buf, 0, read, Charsets.UTF_8).trimStart().lowercase()
            }
        }.getOrDefault("")
        return !(header.startsWith("<!doctype") || header.startsWith("<html"))
    }

    fun updateNow(httpProxyPort: Int? = null): GeoAssetsUpdateResult {
        val targetDir = DesktopBinaryResolver.getUserAssetsDir()
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return GeoAssetsUpdateResult(
                success = false,
                message = "Не удалось создать папку ${targetDir.absolutePath}"
            )
        }

        val updated = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for ((fileName, url) in GEO_FILES) {
            val portsToTry = buildList<Int?> {
                if (httpProxyPort != null && httpProxyPort > 0) add(httpProxyPort)
                add(null)
            }
            val downloaded = portsToTry.any { proxyPort ->
                downloadSingleFile(url, File(targetDir, fileName), proxyPort)
            }
            if (downloaded) {
                updated.add(fileName)
            } else {
                failed.add(fileName)
            }
        }

        return if (failed.isEmpty()) {
            val now = System.currentTimeMillis()
            GeekModeLogger.log("GeoAssets", "Базы geoip.dat и geosite.dat успешно обновлены в ${targetDir.absolutePath}")
            GeoAssetsUpdateResult(
                success = true,
                message = "Базы geoip.dat и geosite.dat обновлены",
                updatedFiles = updated,
                updatedAtMs = now
            )
        } else {
            GeekModeLogger.log("GeoAssets", "Ошибка обновления гео-баз: не удалось скачать ${failed.joinToString(", ")}")
            GeoAssetsUpdateResult(
                success = false,
                message = "Не удалось скачать: ${failed.joinToString(", ")}",
                updatedFiles = updated
            )
        }
    }

    private fun downloadSingleFile(url: String, target: File, httpProxyPort: Int?): Boolean {
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        return try {
            val builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL)
            if (httpProxyPort != null && httpProxyPort > 0) {
                builder.proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", httpProxyPort)))
            }
            val client = builder.build()
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "v2whitelist-desktop")
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                response.body().close()
                return false
            }

            response.body().use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (!isValidGeoFile(tempFile)) {
                tempFile.delete()
                return false
            }

            if (target.exists() && !target.delete()) {
                // В Windows существующий файл мог быть открыт только на запись или заблокирован
                val backup = File(target.parentFile, "${target.name}.bak")
                backup.delete()
                target.renameTo(backup)
            }
            if (!tempFile.renameTo(target)) {
                tempFile.copyTo(target, overwrite = true)
                tempFile.delete()
            }
            true
        } catch (e: Exception) {
            GeekModeLogger.log("GeoAssets", "Ошибка скачивания ${target.name} (proxy=${httpProxyPort ?: "direct"}): ${e.message}")
            runCatching { tempFile.delete() }
            false
        }
    }
}
