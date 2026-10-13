package com.kiktor.v2whitelist.core.model.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubRelease(
    @SerialName("tag_name")
    val tagName: String = "",
    @SerialName("body")
    val body: String = "",
    @SerialName("assets")
    val assets: List<Asset> = emptyList(),
    @SerialName("prerelease")
    val prerelease: Boolean = false,
    @SerialName("published_at")
    val publishedAt: String = ""
) {
    @Serializable
    data class Asset(
        @SerialName("name")
        val name: String = "",
        @SerialName("browser_download_url")
        val browserDownloadUrl: String = "",
        @SerialName("size")
        val size: Long = 0L
    )
}

@Serializable
data class CheckUpdateResult(
    val hasUpdate: Boolean,
    val latestVersion: String? = null,
    val releaseNotes: String? = null,
    val downloadUrl: String? = null,
    val checksumUrl: String? = null,
    val apkFileName: String? = null,
    val fileName: String? = null,
    val error: String? = null,
    val isPreRelease: Boolean = false
)

sealed class TargetPlatform {
    data class Android(val abi: String = "arm64-v8a", val isFdroid: Boolean = false) : TargetPlatform()
    data class Desktop(val os: String = "linux", val arch: String = "x64") : TargetPlatform()
}
