package com.kiktor.v2whitelist.core.domain.update

import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import com.kiktor.v2whitelist.core.model.update.CheckUpdateResult
import com.kiktor.v2whitelist.core.model.update.GitHubRelease
import com.kiktor.v2whitelist.core.model.update.TargetPlatform
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

/**
 * Кроссплатформенный модуль проверки обновлений через GitHub Releases API.
 */
object UpdateChecker {

    val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Сравнивает две семантические версии (например "1.2.3" и "1.2.0").
     * Возвращает > 0 если v1 новее v2, < 0 если v1 старее v2, 0 если равны.
     */
    fun compareVersions(version1: String, version2: String): Int {
        val clean1 = version1.trim().removePrefix("v").removePrefix("V")
        val clean2 = version2.trim().removePrefix("v").removePrefix("V")

        val parts1 = clean1.split(".")
        val parts2 = clean2.split(".")

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val num1 = if (i < parts1.size) parts1[i].takeWhile { it.isDigit() }.toIntOrNull() ?: 0 else 0
            val num2 = if (i < parts2.size) parts2[i].takeWhile { it.isDigit() }.toIntOrNull() ?: 0 else 0
            if (num1 != num2) {
                return num1 - num2
            }
        }
        return 0
    }

    fun parseReleases(jsonText: String): List<GitHubRelease> {
        return jsonParser.decodeFromString(jsonText)
    }

    fun parseLatestRelease(jsonText: String): GitHubRelease? {
        return jsonParser.decodeFromString(jsonText)
    }

    /**
     * Находит подходящий артефакт в релизе под целевую платформу и архитектуру.
     */
    fun findCompatibleAsset(release: GitHubRelease, targetPlatform: TargetPlatform): GitHubRelease.Asset? {
        return when (targetPlatform) {
            is TargetPlatform.Android -> {
                val flavorAssets = release.assets.filter { asset ->
                    if (targetPlatform.isFdroid) {
                        asset.name.contains("fdroid", ignoreCase = true)
                    } else {
                        !asset.name.contains("fdroid", ignoreCase = true)
                    }
                }

                // 1. Поиск конкретного ABI (например arm64-v8a, x86_64)
                var asset = flavorAssets.firstOrNull { it.name.contains(targetPlatform.abi, ignoreCase = true) }

                // 2. Fallback на universal c текущим флейвором
                if (asset == null) {
                    asset = flavorAssets.firstOrNull { it.name.contains("universal", ignoreCase = true) }
                }

                // 3. Fallback на общий universal без префикса флейвора
                if (asset == null) {
                    asset = release.assets.firstOrNull {
                        it.name.contains("universal", ignoreCase = true) && !it.name.contains("fdroid", ignoreCase = true)
                    }
                }
                asset
            }
            is TargetPlatform.Desktop -> {
                // Поиск дистрибутивов для ПК (Linux .tar.gz/.deb/.AppImage, Windows .zip/.exe, macOS .dmg)
                val osAssets = release.assets.filter { it.name.contains(targetPlatform.os, ignoreCase = true) }
                var asset = osAssets.firstOrNull { it.name.contains(targetPlatform.arch, ignoreCase = true) }
                if (asset == null) {
                    asset = osAssets.firstOrNull()
                }
                if (asset == null) {
                    // Общий jar/zip
                    asset = release.assets.firstOrNull { it.name.endsWith(".jar") || it.name.endsWith(".zip") }
                }
                asset
            }
        }
    }

    /**
     * Извлекает ожидаемый sha256 хеш для файла из содержимого sha256sum.txt.
     */
    fun extractExpectedChecksum(sha256sumText: String, fileName: String): String? {
        for (line in sha256sumText.lines()) {
            if (line.contains(fileName)) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.isNotEmpty()) {
                    return parts[0]
                }
            }
        }
        return null
    }

    /**
     * Выполняет проверку обновлений через GitHub Releases API.
     */
    suspend fun checkForUpdate(
        apiUrl: String,
        currentVersion: String,
        targetPlatform: TargetPlatform,
        includePreRelease: Boolean = false,
        client: HttpClient? = null
    ): CheckUpdateResult {
        val targetUrl = if (includePreRelease) {
            apiUrl
        } else {
            "${apiUrl.trimEnd('/')}/latest"
        }

        val internalClient = client ?: HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 6000L
                connectTimeoutMillis = 6000L
                socketTimeoutMillis = 6000L
            }
        }

        val closeClientOnFinish = client == null

        return try {
            val response = internalClient.get(targetUrl) {
                header(HttpHeaders.UserAgent, "v2whitelist-updater")
            }

            if (!response.status.isSuccess()) {
                return CheckUpdateResult(
                    hasUpdate = false,
                    error = "HTTP error: ${response.status.value}"
                )
            }

            val responseBody = response.bodyAsText()
            val release = if (includePreRelease) {
                parseReleases(responseBody).firstOrNull()
            } else {
                parseLatestRelease(responseBody)
            }

            if (release == null) {
                return CheckUpdateResult(hasUpdate = false, error = "Release not found")
            }

            val latestVersion = release.tagName.trim().removePrefix("v").removePrefix("V")
            val hasUpdate = compareVersions(latestVersion, currentVersion) > 0

            if (!hasUpdate) {
                return CheckUpdateResult(
                    hasUpdate = false,
                    latestVersion = latestVersion,
                    isPreRelease = release.prerelease
                )
            }

            val compatibleAsset = findCompatibleAsset(release, targetPlatform)
            val checksumUrl = release.assets.firstOrNull { it.name == "sha256sum.txt" }?.browserDownloadUrl

            GeekModeLogger.log("UpdateChecker", "Found new version: $latestVersion (current: $currentVersion)")

            CheckUpdateResult(
                hasUpdate = true,
                latestVersion = latestVersion,
                releaseNotes = release.body,
                downloadUrl = compatibleAsset?.browserDownloadUrl,
                checksumUrl = checksumUrl,
                apkFileName = compatibleAsset?.name,
                fileName = compatibleAsset?.name,
                isPreRelease = release.prerelease
            )
        } catch (e: Exception) {
            GeekModeLogger.log("UpdateChecker", "Error checking for update: ${e.message}")
            CheckUpdateResult(
                hasUpdate = false,
                error = e.message ?: "Unknown error"
            )
        } finally {
            if (closeClientOnFinish) {
                internalClient.close()
            }
        }
    }
}
