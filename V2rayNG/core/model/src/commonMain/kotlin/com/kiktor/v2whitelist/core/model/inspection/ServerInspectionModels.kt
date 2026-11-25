package com.kiktor.v2whitelist.core.model.inspection

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class ScanNetworkCondition {
    /** Обычный режим / ЧС (internetStatus == 0) */
    BLACKLIST,

    /** Белые списки / глушат (internetStatus == 1) */
    WHITELIST,

    /** Нет доступа в интернет (internetStatus == 2) */
    NO_INTERNET,

    /** Проверка статуса сети отключена или не выполнялась */
    UNKNOWN;

    companion object {
        fun fromInternetStatus(status: Int, networkCheckEnabled: Boolean): ScanNetworkCondition {
            if (!networkCheckEnabled) return UNKNOWN
            return when (status) {
                0 -> BLACKLIST
                1 -> WHITELIST
                2 -> NO_INTERNET
                else -> UNKNOWN
            }
        }
    }
}

@Serializable
data class ServiceProbeStatus(
    val id: String,
    val name: String,
    val success: Boolean,
    val latencyMs: Long = -1L,
    val error: String? = null
)

@Serializable
data class ServerInspectionInfo(
    val alive: Boolean,
    val delayMs: Long = -1L,
    val exitIp: String = "",
    val countryCode: String = "",
    val fragMode: String = "",
    val endpointPassed: Boolean? = null,
    val services: List<ServiceProbeStatus> = emptyList(),
    val error: String = "",
    val testedAtMs: Long = 0L
) {
    val okServicesCount: Int
        get() = services.count { it.success }

    val totalServicesCount: Int
        get() = services.size

    val serviceSuccessRatio: Double
        get() = if (services.isEmpty()) 0.0 else okServicesCount.toDouble() / services.size.toDouble()

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

        fun toJson(info: ServerInspectionInfo): String {
            return json.encodeToString(info)
        }

        fun fromJson(raw: String?): ServerInspectionInfo? {
            if (raw.isNullOrBlank()) return null
            return try {
                json.decodeFromString<ServerInspectionInfo>(raw)
            } catch (_: Exception) {
                null
            }
        }
    }
}

@Serializable
data class ScanSessionMeta(
    val startedAtMs: Long = 0L,
    val finishedAtMs: Long = 0L,
    val networkCondition: ScanNetworkCondition = ScanNetworkCondition.UNKNOWN,
    val networkCheckEnabled: Boolean = true,
    val endpointEnabled: Boolean = false,
    val totalScanned: Int = 0,
    val aliveCount: Int = 0,
    val failedCount: Int = 0
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

        fun toJson(meta: ScanSessionMeta): String {
            return json.encodeToString(meta)
        }

        fun fromJson(raw: String?): ScanSessionMeta? {
            if (raw.isNullOrBlank()) return null
            return try {
                json.decodeFromString<ScanSessionMeta>(raw)
            } catch (_: Exception) {
                null
            }
        }
    }
}

@Serializable
data class SingleServiceProbeDto(
    val id: String = "",
    val name: String = "",
    val success: Boolean = false,
    @SerialName("latency_ms") val latencyMs: Long = -1L,
    val error: String? = null
)

@Serializable
data class FullScanNodeResultDto(
    val link: String = "",
    val alive: Boolean = false,
    @SerialName("delay_ms") val delayMs: Long = -1L,
    @SerialName("exit_ip") val exitIp: String = "",
    @SerialName("country_code") val countryCode: String = "",
    @SerialName("frag_mode") val fragMode: String = "",
    @SerialName("endpoint_passed") val endpointPassed: Boolean? = null,
    val services: List<SingleServiceProbeDto> = emptyList(),
    val error: String = ""
) {
    fun toInspectionInfo(testedAtMs: Long): ServerInspectionInfo {
        return ServerInspectionInfo(
            alive = alive,
            delayMs = delayMs,
            exitIp = exitIp.trim(),
            countryCode = countryCode.trim().uppercase(),
            fragMode = fragMode.trim().lowercase(),
            endpointPassed = endpointPassed,
            services = services.map {
                ServiceProbeStatus(
                    id = it.id,
                    name = it.name,
                    success = it.success,
                    latencyMs = it.latencyMs,
                    error = it.error
                )
            },
            error = error,
            testedAtMs = testedAtMs
        )
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun fromJson(raw: String?): FullScanNodeResultDto? {
            if (raw.isNullOrBlank()) return null
            return try {
                json.decodeFromString<FullScanNodeResultDto>(raw)
            } catch (_: Exception) {
                null
            }
        }
    }
}

private fun codePointToUtf16(cp: Int): String {
    val high = ((cp - 0x10000) shr 10) + 0xD800
    val low = ((cp - 0x10000) and 0x3FF) + 0xDC00
    return charArrayOf(high.toChar(), low.toChar()).concatToString()
}

fun countryCodeToFlagEmoji(countryCode: String?): String {
    val code = countryCode?.trim()?.uppercase() ?: return ""
    if (code.length != 2) return ""
    val first = code[0]
    val second = code[1]
    if (first !in 'A'..'Z' || second !in 'A'..'Z') return ""
    val base = 0x1F1E6
    return codePointToUtf16(base + (first - 'A')) + codePointToUtf16(base + (second - 'A'))
}
