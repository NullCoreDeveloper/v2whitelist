package com.kiktor.v2whitelist.dto

data class AssetUrlItem(
    var remarks: String = "",
    var url: String = "",
    val addedTime: Long = System.currentTimeMillis(),
    var lastUpdated: Long = -1,
    var locked: Boolean? = false,
)