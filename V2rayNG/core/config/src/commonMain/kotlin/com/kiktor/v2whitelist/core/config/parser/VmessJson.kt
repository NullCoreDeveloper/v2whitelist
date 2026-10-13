package com.kiktor.v2whitelist.core.config.parser

import kotlinx.serialization.Serializable

@Serializable
data class VmessJson(
    val v: String? = "2",
    val ps: String? = "",
    val add: String? = "",
    val port: String? = "",
    val id: String? = "",
    val aid: String? = "0",
    val scy: String? = "auto",
    val net: String? = "tcp",
    val type: String? = "none",
    val host: String? = "",
    val path: String? = "",
    val tls: String? = "",
    val sni: String? = "",
    val alpn: String? = "",
    val fp: String? = ""
)
