package com.kiktor.v2whitelist.handler

import android.content.Context
import android.util.Log
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.extension.concatUrl
import com.kiktor.v2whitelist.util.HttpUtil
import com.kiktor.v2whitelist.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Автоматическое и ручное обновление geosite.dat и geoip.dat на Android (#44).
 */
object GeoAssetsAutoUpdater {

    private const val MIN_VALID_GEO_SIZE_BYTES = 100_000L
    private const val DEFAULT_INTERVAL_DAYS = 7
    private val builtInGeoFiles = listOf("geosite.dat", "geoip.dat")
    private val isRunning = AtomicBoolean(false)

    data class UpdateResult(
        val successCount: Int,
        val failureCount: Int,
        val failedAssets: List<String>,
        val updatedAtMs: Long? = null
    )

    fun getIntervalDays(): Int {
        val raw = MmkvManager.decodeSettingsString(AppConfig.PREF_GEO_AUTO_UPDATE_DAYS)
        return raw?.toIntOrNull()?.coerceIn(0, 30) ?: DEFAULT_INTERVAL_DAYS
    }

    fun getLastUpdatedMillis(context: Context): Long {
        val saved = MmkvManager.decodeSettingsString(AppConfig.PREF_GEO_ASSETS_LAST_UPDATE)?.toLongOrNull() ?: 0L
        val dir = File(Utils.userAssetPath(context))
        val geositeMod = File(dir, "geosite.dat").takeIf { it.exists() }?.lastModified() ?: 0L
        val geoipMod = File(dir, "geoip.dat").takeIf { it.exists() }?.lastModified() ?: 0L
        return maxOf(saved, geositeMod, geoipMod)
    }

    fun isUpdateDue(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val days = getIntervalDays()
        if (days <= 0) return false
        val lastMs = getLastUpdatedMillis(context)
        if (lastMs <= 0L) return true
        val intervalMs = days.toLong() * 24L * 60L * 60L * 1000L
        return (nowMs - lastMs) >= intervalMs
    }

    fun updateIfDueAsync(context: Context) {
        val appCtx = context.applicationContext
        if (!isUpdateDue(appCtx)) return
        if (!isRunning.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateNowInternal(appCtx)
            } finally {
                isRunning.set(false)
            }
        }
    }

    fun updateNow(context: Context): UpdateResult {
        if (!isRunning.compareAndSet(false, true)) {
            return UpdateResult(0, 0, emptyList())
        }
        return try {
            updateNowInternal(context.applicationContext)
        } finally {
            isRunning.set(false)
        }
    }

    private fun updateNowInternal(context: Context): UpdateResult {
        val extDir = File(Utils.userAssetPath(context))
        if (!extDir.exists()) {
            extDir.mkdirs()
        }
        val source = MmkvManager.decodeSettingsString(AppConfig.PREF_GEO_FILES_SOURCES)
            ?.takeIf { it.isNotBlank() }
            ?: AppConfig.GEO_FILES_SOURCES.first()
        val baseUrl = String.format(AppConfig.GITHUB_DOWNLOAD_URL, source)

        val vpnRunning = MmkvManager.decodeSettingsBool(AppConfig.PREF_IS_SERVICE_RUNNING, false)
        val httpPort = if (vpnRunning) SettingsManager.getHttpPort() else 0
        val portsToTry = if (httpPort > 0) listOf(httpPort, 0) else listOf(0)

        var successCount = 0
        val failures = mutableListOf<String>()

        for (fileName in builtInGeoFiles) {
            val url = baseUrl.concatUrl(fileName)
            if (portsToTry.any { port -> tryDownload(fileName, url, extDir, port) }) {
                successCount++
            } else {
                failures.add(fileName)
            }
        }

        val now = if (failures.isEmpty()) System.currentTimeMillis() else null
        if (now != null) {
            MmkvManager.encodeSettings(AppConfig.PREF_GEO_ASSETS_LAST_UPDATE, now.toString())
        }
        return UpdateResult(successCount, failures.size, failures, now)
    }

    private fun isValidGeoFile(file: File): Boolean {
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

    private fun tryDownload(fileName: String, url: String, extDir: File, httpPort: Int): Boolean {
        val tempFile = File(extDir, "${fileName}_temp")
        val targetFile = File(extDir, fileName)
        val conn = HttpUtil.createProxyConnection(url, httpPort, 15000, 30000, needStream = true) ?: return false
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                return false
            }
            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (!isValidGeoFile(tempFile)) {
                tempFile.delete()
                return false
            }
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            Log.i(AppConfig.TAG, "Updated geo file: $fileName via port=$httpPort")
            true
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to update geo file: $fileName", e)
            runCatching { tempFile.delete() }
            false
        } finally {
            conn.disconnect()
        }
    }
}
