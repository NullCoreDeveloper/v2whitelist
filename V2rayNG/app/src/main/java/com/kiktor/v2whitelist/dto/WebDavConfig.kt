package com.kiktor.v2whitelist.dto

data class WebDavConfig(
    val baseUrl: String,
    val username: String? = null,
    val password: String? = null,
    val remoteBasePath: String = "/",
    val timeoutSeconds: Long = 30
)
