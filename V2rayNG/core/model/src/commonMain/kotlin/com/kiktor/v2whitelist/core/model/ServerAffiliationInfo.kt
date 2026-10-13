package com.kiktor.v2whitelist.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ServerAffiliationInfo(var testDelayMillis: Long = 0L) {
    fun getTestDelayString(): String {
        if (testDelayMillis == 0L) {
            return ""
        }
        return "${testDelayMillis}ms"
    }
}
