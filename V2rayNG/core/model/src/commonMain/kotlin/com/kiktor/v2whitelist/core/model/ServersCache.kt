package com.kiktor.v2whitelist.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ServersCache(
    val guid: String,
    val profile: ProfileItem
)
