package com.kiktor.v2whitelist.handler

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.BuildConfig
import com.kiktor.v2whitelist.core.domain.update.UpdateChecker
import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import com.kiktor.v2whitelist.dto.CheckUpdateResult
import com.kiktor.v2whitelist.dto.GitHubRelease
import com.kiktor.v2whitelist.extension.concatUrl
import com.kiktor.v2whitelist.util.HttpUtil
import com.kiktor.v2whitelist.util.JsonUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.Locale

object UpdateCheckerManager {
    const val MAX_APK_BYTES = 256L * 1024 * 1024
    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        val isFdroid = BuildConfig.APPLICATION_ID.endsWith(".fdroid")
        val platform = com.kiktor.v2whitelist.core.model.update.TargetPlatform.Android(
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
            isFdroid = isFdroid
        )
        val targetUrl = if (includePreRelease) {
            AppConfig.APP_API_URL
        } else {
            "${AppConfig.APP_API_URL.trimEnd('/')}/latest"
        }

        // Используем системный HttpURLConnection (Conscrypt) вместо Ktor CIO,
        // чтобы на Android соблюдался hostname-aware checkServerTrusted при <domain-config>
        // в network_security_config.xml и поддерживался локальный прокси при активном VPN.
        var lastError: String? = null
        for (port in getGithubConnectionPorts(preferProxy = false)) {
            var connection: HttpURLConnection? = null
            try {
                connection = HttpUtil.createProxyConnection(targetUrl, port, 6000, 6000, true)
                if (connection == null) {
                    lastError = "Unable to create connection (port=$port)"
                    continue
                }
                connection.requestMethod = "GET"
                connection.setRequestProperty("Connection", "close")
                connection.setRequestProperty("User-Agent", "v2whitelist-updater")
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.connect()

                val code = connection.responseCode
                if (code !in 200..299) {
                    lastError = "HTTP error: $code"
                    continue
                }

                val responseBody = connection.inputStream.use {
                    HttpUtil.readStreamLimited(it, UpdateChecker.MAX_RELEASE_JSON_BYTES.toLong())
                }
                val release = if (includePreRelease) {
                    UpdateChecker.parseReleases(responseBody).firstOrNull()
                } else {
                    UpdateChecker.parseLatestRelease(responseBody)
                }
                if (release == null) {
                    return@withContext CheckUpdateResult(hasUpdate = false, error = "Release not found")
                }

                val latestVersion = release.tagName.trim().removePrefix("v").removePrefix("V")
                val hasUpdate = UpdateChecker.compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0
                if (!hasUpdate) {
                    return@withContext CheckUpdateResult(
                        hasUpdate = false,
                        latestVersion = latestVersion,
                        isPreRelease = release.prerelease
                    )
                }

                val compatibleAsset = UpdateChecker.findCompatibleAsset(release, platform)
                val safeDownloadUrl = compatibleAsset?.browserDownloadUrl?.takeIf { UpdateChecker.isAllowedUpdateUrl(it) }
                val safeChecksumUrl = release.assets.firstOrNull { it.name == "sha256sum.txt" }
                    ?.browserDownloadUrl?.takeIf { UpdateChecker.isAllowedUpdateUrl(it) }

                GeekModeLogger.log("UpdateChecker", "Found new version: $latestVersion (current: ${BuildConfig.VERSION_NAME})")
                return@withContext CheckUpdateResult(
                    hasUpdate = true,
                    latestVersion = latestVersion,
                    releaseNotes = release.body,
                    downloadUrl = safeDownloadUrl,
                    checksumUrl = safeChecksumUrl,
                    apkFileName = compatibleAsset?.name,
                    fileName = compatibleAsset?.name,
                    isPreRelease = release.prerelease
                )
            } catch (e: Exception) {
                lastError = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "Unknown error"
            } finally {
                try {
                    connection?.disconnect()
                } catch (_: Exception) {}
            }
        }

        val detail = lastError ?: "Unknown error"
        GeekModeLogger.log("UpdateChecker", "Error checking for update: $detail")
        CheckUpdateResult(hasUpdate = false, error = detail)
    }

    suspend fun downloadApk(context: Context, downloadUrl: String, onProgress: ((Int) -> Unit)? = null): File? = withContext(Dispatchers.IO) {
        for (port in getGithubConnectionPorts(preferProxy = true)) {
            val apkFile = downloadApkViaPort(context, downloadUrl, port, onProgress)
            if (apkFile != null) return@withContext apkFile
        }
        null
    }

    private fun getGithubConnectionPorts(preferProxy: Boolean = false): List<Int> {
        if (!com.kiktor.v2whitelist.handler.V2RayServiceManager.isRunning()) return listOf(0)
        if (SettingsManager.isVpnMode() && !SettingsManager.isUsingHevTun() && SettingsManager.isHideTunnelEnabled()) {
            return listOf(0)
        }
        val httpPort = SettingsManager.getHttpPort()
        return if (preferProxy) listOf(httpPort, 0).distinct() else listOf(0, httpPort).distinct()
    }

    private fun downloadApkViaPort(
        context: Context,
        downloadUrl: String,
        port: Int,
        onProgress: ((Int) -> Unit)?
    ): File? {
        if (!UpdateChecker.isAllowedUpdateUrl(downloadUrl)) {
            Log.e(AppConfig.TAG, "Rejected untrusted update URL")
            return null
        }
        val connection = HttpUtil.createProxyConnection(downloadUrl, port, 30_000, 30_000, true)
        if (connection == null) {
            Log.w(AppConfig.TAG, "Unable to create GitHub APK connection (port=$port)")
            return null
        }

        try {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val rawName = downloadUrl.substringAfterLast("/", "update.apk")
            val safeName = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "update.apk" }
                .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
            updatesDir.listFiles { file -> file.name.endsWith(".apk") && file.name != safeName }?.forEach { it.delete() }

            val apkFile = File(updatesDir, safeName)
            Log.i(AppConfig.TAG, "Downloading APK to private cache: ${apkFile.name} (port=$port)")

            var downloadedBytes = apkFile.takeIf { it.exists() && it.length() <= MAX_APK_BYTES }?.length() ?: 0L
            if (apkFile.exists() && apkFile.length() > MAX_APK_BYTES) {
                apkFile.delete()
                downloadedBytes = 0L
            }
            if (downloadedBytes > 0) {
                connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
                Log.i(AppConfig.TAG, "Requesting range: bytes=$downloadedBytes-")
            }

            connection.connect()
            val responseCode = connection.responseCode
            Log.i(AppConfig.TAG, "APK response code: $responseCode (port=$port)")

            if (responseCode == 416) {
                Log.i(AppConfig.TAG, "Range not satisfiable; file already fully downloaded.")
                return apkFile
            }
            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                Log.w(AppConfig.TAG, "Unexpected APK response code: $responseCode (port=$port)")
                return null
            }

            val append = responseCode == HttpURLConnection.HTTP_PARTIAL
            if (!append) {
                downloadedBytes = 0L
                if (apkFile.exists()) {
                    apkFile.delete()
                }
            }

            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_APK_BYTES || (append && contentLength > 0 && downloadedBytes + contentLength > MAX_APK_BYTES)) {
                Log.e(AppConfig.TAG, "APK exceeds max allowed size ($MAX_APK_BYTES)")
                apkFile.delete()
                return null
            }
            val fileLength = if (append && contentLength > 0) downloadedBytes + contentLength else contentLength

            var lastProgress = 0
            var totalRead = downloadedBytes

            FileOutputStream(apkFile, append).use { outputStream ->
                connection.inputStream.use { inputStream ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        totalRead += bytesRead
                        if (totalRead > MAX_APK_BYTES) {
                            apkFile.delete()
                            throw SecurityException("Downloaded APK exceeds size limit")
                        }
                        outputStream.write(buffer, 0, bytesRead)
                        if (fileLength > 0) {
                            val progress = (totalRead * 100 / fileLength).toInt()
                            if (progress > lastProgress) {
                                lastProgress = progress
                                onProgress?.invoke(progress)
                            }
                        }
                    }
                }
            }
            if (fileLength > 0 && totalRead < fileLength) {
                Log.w(AppConfig.TAG, "Incomplete APK download: $totalRead / $fileLength bytes")
                apkFile.delete()
                return null
            }
            Log.i(AppConfig.TAG, "APK download completed")
            return apkFile
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "APK download attempt failed (port=$port): ${e.message}")
            return null
        } finally {
            try {
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Error closing connection: ${e.message}")
            }
        }
    }

    private fun compareVersions(version1: String, version2: String): Int {
        val v1 = version1.split(".")
        val v2 = version2.split(".")

        for (i in 0 until maxOf(v1.size, v2.size)) {
            val num1 = if (i < v1.size) v1[i].takeWhile { it.isDigit() }.toIntOrNull() ?: 0 else 0
            val num2 = if (i < v2.size) v2[i].takeWhile { it.isDigit() }.toIntOrNull() ?: 0 else 0
            if (num1 != num2) return num1 - num2
        }
        return 0
    }

    private fun getDownloadUrl(release: GitHubRelease, abi: String): String {
        val isFdroid = BuildConfig.APPLICATION_ID.endsWith(".fdroid")
        
        // Filter assets by flavor first
        val flavorAssets = release.assets.filter { asset ->
            if (isFdroid) {
                asset.name.contains("fdroid", ignoreCase = true)
            } else {
                !asset.name.contains("fdroid", ignoreCase = true)
            }
        }
        
        // Search specific ABI first
        var asset = flavorAssets.firstOrNull { 
            it.name.contains(abi, ignoreCase = true) 
        }
        
        // Fallback to universal
        if (asset == null) {
            asset = flavorAssets.firstOrNull {
                it.name.contains("universal", ignoreCase = true)
            }
        }
        
        // Fallback for old universal without flavor prefix
        if (asset == null) {
            asset = release.assets.firstOrNull { 
                it.name.contains("universal", ignoreCase = true) && !it.name.contains("fdroid", ignoreCase = true) 
            }
        }
        
        return asset?.browserDownloadUrl
            ?: throw IllegalStateException("No compatible APK found")
    }

    suspend fun verifyDownloadedUpdate(
        context: Context,
        apkFile: File,
        checksumUrl: String?,
        apkFileName: String?
    ): Boolean {
        if (checksumUrl.isNullOrBlank() || apkFileName.isNullOrBlank()) {
            Log.e(AppConfig.TAG, "Missing checksum metadata for downloaded APK")
            return false
        }
        if (!UpdateChecker.isAllowedUpdateUrl(checksumUrl)) {
            Log.e(AppConfig.TAG, "Rejected untrusted checksum URL")
            return false
        }
        if (!verifyChecksum(apkFile, checksumUrl, apkFileName)) {
            return false
        }
        return verifyApkPackageAndSignature(context, apkFile)
    }

    @Suppress("DEPRECATION")
    fun verifyApkPackageAndSignature(context: Context, apkFile: File): Boolean {
        return try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
            } else {
                PackageManager.GET_SIGNATURES
            }
            val archiveInfo = pm.getPackageArchiveInfo(apkFile.path, flags) ?: run {
                Log.e(AppConfig.TAG, "Failed to parse downloaded APK archive")
                return false
            }
            if (archiveInfo.packageName != context.packageName) {
                Log.e(AppConfig.TAG, "APK packageName mismatch: ${archiveInfo.packageName}")
                return false
            }
            val installedInfo = pm.getPackageInfo(context.packageName, flags)
            if (PackageInfoCompat.getLongVersionCode(archiveInfo) < PackageInfoCompat.getLongVersionCode(installedInfo)) {
                Log.e(AppConfig.TAG, "APK versionCode downgrade rejected")
                return false
            }
            val expectedSigners = extractSignerDigests(installedInfo)
            val archiveSigners = extractSignerDigests(archiveInfo)
            if (expectedSigners.isEmpty() || archiveSigners != expectedSigners) {
                Log.e(AppConfig.TAG, "APK signing certificate mismatch")
                return false
            }
            true
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "APK signature verification error: ${e.message}", e)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun extractSignerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners ?: info.signatures
        } else {
            info.signatures
        }
        val sha256 = MessageDigest.getInstance("SHA-256")
        return signatures.orEmpty().map { sig ->
            sha256.digest(sig.toByteArray()).joinToString("") { "%02x".format(Locale.ROOT, it) }
        }.toSet()
    }

    suspend fun verifyChecksum(apkFile: File, checksumUrl: String, apkFileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            var response = ""
            for (port in getGithubConnectionPorts()) {
                try {
                    response = HttpUtil.getUrlContentWithUserAgent(checksumUrl, null, 10000, port)
                    if (response.isNotBlank()) break
                } catch (e: Exception) {
                    Log.w(AppConfig.TAG, "Checksum fetch failed on port $port: ${e.message}")
                }
            }
            if (response.isBlank()) {
                Log.e(AppConfig.TAG, "Failed to download sha256sum.txt across all ports")
                return@withContext false
            }
            
            val expectedHash = com.kiktor.v2whitelist.core.domain.update.UpdateChecker.extractExpectedChecksum(response, apkFileName)
            if (expectedHash == null) {
                Log.e(AppConfig.TAG, "Checksum for $apkFileName not found in sha256sum.txt")
                return@withContext false
            }
            
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val fis = java.io.FileInputStream(apkFile)
            val buffer = ByteArray(8192)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
            fis.close()
            
            val hashBytes = digest.digest()
            val actualHash = hashBytes.joinToString("") { "%02x".format(it) }
            
            Log.i(AppConfig.TAG, "Expected SHA-256: $expectedHash, Actual: $actualHash")
            return@withContext expectedHash.equals(actualHash, ignoreCase = true)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to verify checksum: ${e.message}", e)
            return@withContext false
        }
    }
}
