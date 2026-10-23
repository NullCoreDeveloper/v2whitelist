package com.kiktor.v2whitelist.core.subscription.endpoint

import com.kiktor.v2whitelist.core.config.UniversalConfigParser
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.subscription.network.SubscriptionFetcher
import com.kiktor.v2whitelist.core.subscription.parser.SubscriptionParser

/**
 * Менеджер работы с кастомным конечным узлом (Proxy Chaining).
 * Поддерживает:
 * - Загрузку списка серверов из URL подписки
 * - Преобразование ProfileItem в ссылку конфигурации
 * - Форматирование отображаемого имени узла
 */
object CustomEndpointManager {

    /**
     * Загружает и парсит серверы из URL подписки для конечного узла.
     */
    suspend fun fetchServersFromSubscription(
        subUrl: String,
        httpProxyPort: Int = 0,
        userAgent: String = SubscriptionFetcher.DEFAULT_USER_AGENT
    ): Result<List<ProfileItem>> {
        val trimmed = subUrl.trim()
        if (trimmed.isEmpty()) return Result.success(emptyList())

        val urls = SubscriptionFetcher.parseMirrors(trimmed)
        if (urls.isEmpty()) {
            return Result.failure(IllegalArgumentException("Некорректный URL подписки"))
        }

        return SubscriptionFetcher.fetchSequential(
            urls = urls,
            userAgent = userAgent,
            httpProxyPort = httpProxyPort
        ).map { content ->
            SubscriptionParser.parse(content)
        }
    }

    /**
     * Преобразует выбранный профиль в строку URI (vless://, vmess://, trojan://, ss:// и т.д.)
     */
    fun profileToUri(profile: ProfileItem): String? {
        return UniversalConfigParser.toUri(profile)
    }

    /**
     * Возвращает читаемое имя сервера для UI (remarks или протокол + хост).
     */
    fun getDisplayName(profile: ProfileItem): String {
        return if (profile.remarks.isNotBlank()) {
            profile.remarks
        } else {
            "${profile.configType.name} — ${profile.server}:${profile.serverPort}"
        }
    }
}
