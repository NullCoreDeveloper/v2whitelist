package com.kiktor.v2whitelist.dto

data class CheckUpdateResult(
    val hasUpdate: Boolean,
    val latestVersion: String? = null,
    val releaseNotes: String? = null,
    val downloadUrl: String? = null,
    val checksumUrl: String? = null,
    val apkFileName: String? = null,
    val error: String? = null,
    val isPreRelease: Boolean = false
)