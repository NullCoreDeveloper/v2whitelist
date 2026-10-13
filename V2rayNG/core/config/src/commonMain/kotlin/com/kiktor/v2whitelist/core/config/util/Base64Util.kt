package com.kiktor.v2whitelist.core.config.util

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object Base64Util {
    @OptIn(ExperimentalEncodingApi::class)
    fun decode(src: String): String {
        var clean = src.trim()
            .replace("-", "+")
            .replace("_", "/")
            .replace("\r", "")
            .replace("\n", "")

        val mod = clean.length % 4
        if (mod > 0) {
            clean += "=".repeat(4 - mod)
        }

        return try {
            Base64.decode(clean).decodeToString()
        } catch (_: Exception) {
            ""
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun encode(src: String): String {
        return Base64.encode(src.encodeToByteArray())
    }
}
