package com.kiktor.v2whitelist.handler

import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.dto.ProfileItem

/**
 * Android-адаптер для пропорционального чанкинга серверов.
 * Извлекает настройки из MMKV и вызывает общий алгоритм из :core:subscription.
 */
object ChunkingHelper {
    fun buildProportionalChunks(servers: List<Pair<String, ProfileItem>>): List<List<Pair<String, ProfileItem>>> {
        if (servers.isEmpty()) return emptyList()

        val chunkSizeStr = MmkvManager.decodeSettingsString(AppConfig.PREF_CHUNK_SIZE) ?: "20"
        val chunkSize = chunkSizeStr.toIntOrNull()?.takeIf { it > 0 } ?: 20
        val preset = MmkvManager.decodeSettingsString(AppConfig.PREF_CHUNK_PRESET) ?: "equal"

        val allSubs = MmkvManager.decodeSubscriptions().associate { it.guid to it.subscription }

        return com.kiktor.v2whitelist.core.subscription.chunk.ChunkingHelper.buildProportionalChunks(
            servers = servers,
            subscriptions = allSubs,
            chunkSize = chunkSize,
            preset = preset
        )
    }
}
