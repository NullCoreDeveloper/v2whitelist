package com.kiktor.v2whitelist.core.domain.inspection

import com.kiktor.v2whitelist.core.model.ProfileItem

/**
 * Стабильный кроссплатформенный отпечаток конфигурации сервера.
 * Игнорирует remarks, subscriptionId, addedTime и локальный GUID,
 * чтобы кэш инспекций переживал обновление подписок с перегенерацией UUID.
 */
object ServerFingerprint {

    private const val FNV_OFFSET_1 = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_PRIME_1 = 1099511628211L         // 0x100000001b3

    private const val FNV_OFFSET_2 = 0x517cc1b727220a95L
    private const val FNV_PRIME_2 = -0x61c8864680b583ebL

    fun compute(profile: ProfileItem): String {
        val hostOrSni = (profile.sni?.takeIf { it.isNotBlank() } ?: profile.host)
            .orEmpty()
            .trim()
            .lowercase()

        val canonical = buildString(128) {
            append(profile.configType.name)
            append('|')
            append(profile.server.orEmpty().trim().lowercase())
            append('|')
            append(profile.serverPort.orEmpty().trim())
            append('|')
            append(profile.password.orEmpty().trim())
            append('|')
            append(hostOrSni)
            append('|')
            append(profile.network.orEmpty().trim().lowercase())
            append('|')
            append(profile.path.orEmpty().trim())
            append('|')
            append(profile.serviceName.orEmpty().trim())
            append('|')
            append(profile.publicKey.orEmpty().trim())
            append('|')
            append(profile.shortId.orEmpty().trim())
            append('|')
            append(profile.secretKey.orEmpty().trim())
        }

        val bytes = canonical.encodeToByteArray()
        var h1 = FNV_OFFSET_1
        var h2 = FNV_OFFSET_2
        for (i in bytes.indices) {
            val b = bytes[i].toLong() and 0xFFL
            h1 = (h1 xor b) * FNV_PRIME_1
            h2 = ((h2 xor (b + i.toLong())) * FNV_PRIME_2) xor (h2 ushr 29)
        }

        return buildString(35) {
            append("fp_")
            appendHex64(h1)
            appendHex64(h2)
        }
    }

    private fun StringBuilder.appendHex64(value: Long) {
        val hexChars = "0123456789abcdef"
        var shift = 60
        while (shift >= 0) {
            append(hexChars[((value ushr shift) and 0x0FL).toInt()])
            shift -= 4
        }
    }
}

/**
 * Политика времени жизни кэша инспекций серверов.
 */
object ServerInspectionCachePolicy {
    const val STALE_AFTER_MS: Long = 6L * 60L * 60L * 1000L
    const val EXPIRE_AFTER_MS: Long = 24L * 60L * 60L * 1000L

    fun isStale(testedAtMs: Long, nowMs: Long): Boolean {
        return testedAtMs > 0L && (nowMs - testedAtMs) > STALE_AFTER_MS
    }

    fun isExpired(testedAtMs: Long, nowMs: Long): Boolean {
        return testedAtMs > 0L && (nowMs - testedAtMs) > EXPIRE_AFTER_MS
    }
}
