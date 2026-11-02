package com.kiktor.v2whitelist.core.model.servicecheck

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ServiceCheckItem(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val isCustom: Boolean = false
) {
    companion object {
        val DEFAULT_SERVICES = listOf(
            ServiceCheckItem(id = "google", name = "Google", url = "https://www.google.com/generate_204", enabled = true),
            ServiceCheckItem(id = "youtube", name = "YouTube", url = "https://www.youtube.com/generate_204", enabled = true),
            ServiceCheckItem(id = "telegram", name = "Telegram", url = "https://web.telegram.org", enabled = true),
            ServiceCheckItem(id = "whatsapp", name = "WhatsApp", url = "https://web.whatsapp.com", enabled = true),
            ServiceCheckItem(id = "instagram", name = "Instagram", url = "https://www.instagram.com", enabled = true),
            ServiceCheckItem(id = "gemini", name = "Gemini", url = "https://gemini.google.com", enabled = true),
            ServiceCheckItem(id = "claude", name = "Claude", url = "https://claude.ai", enabled = true),
            ServiceCheckItem(id = "chatgpt", name = "ChatGPT", url = "https://chatgpt.com", enabled = true),
            ServiceCheckItem(id = "discord", name = "Discord", url = "https://discord.com", enabled = true)
        )

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = false
        }

        fun parseList(jsonStr: String?): List<ServiceCheckItem> {
            if (jsonStr.isNullOrBlank()) return DEFAULT_SERVICES
            return try {
                val list = json.decodeFromString<List<ServiceCheckItem>>(jsonStr)
                if (list.isEmpty()) return DEFAULT_SERVICES
                val existingIds = list.map { it.id.lowercase() }.toSet()
                val missingDefaults = DEFAULT_SERVICES.filter { it.id.lowercase() !in existingIds }
                if (missingDefaults.isNotEmpty()) {
                    list + missingDefaults
                } else {
                    list
                }
            } catch (_: Exception) {
                DEFAULT_SERVICES
            }
        }

        fun serializeList(list: List<ServiceCheckItem>): String {
            return json.encodeToString(list)
        }
    }
}

@Serializable
data class ServiceCheckResult(
    val service: ServiceCheckItem,
    val success: Boolean,
    val latencyMs: Long = -1L,
    val httpCode: Int = 0,
    val errorMessage: String? = null
)

