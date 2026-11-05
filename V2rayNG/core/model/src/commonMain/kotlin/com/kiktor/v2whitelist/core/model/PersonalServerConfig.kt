package com.kiktor.v2whitelist.core.model

import kotlinx.serialization.Serializable

/**
 * Правила привязки поведения подключения к типу активной сети.
 */
@Serializable
enum class NetworkBindingRule {
    DEFAULT,                // По умолчанию (согласно общему выбору)
    USE_PERSONAL_SELECTED,  // Использовать выбранный доверенный сервер
    USE_PERSONAL_AUTO,      // Автопоиск по доверенному пулу серверов
    USE_PUBLIC_SUBS,        // Переключаться на сторонние подписки
    DIRECT_NO_VPN           // Без VPN / Прямой интернет
}

/**
 * Конфигурация фичи «Доверенные сервера» (Trusted Servers).
 */
@Serializable
data class PersonalServersConfig(
    val enabled: Boolean = false,
    val selectedServerGuid: String? = null,              // null = Режим автопоиска среди доверенных
    val ignoreExitNodeForPersonal: Boolean = true,        // 1. Игнорирование конечного узла при использовании
    val autoConnectOnStartup: Boolean = true,            // 2. Автоподключение и выбор при старте
    val fallbackToPublicOnFailure: Boolean = true,       // 3. Фоллбек на сторонние подписки при неудаче
    val wifiRule: NetworkBindingRule = NetworkBindingRule.USE_PERSONAL_AUTO,
    val cellularRule: NetworkBindingRule = NetworkBindingRule.USE_PUBLIC_SUBS,
    val exitNodeServerGuid: String? = null,              // Доверенный сервер в роли конечного узла (Exit Node)
    val exitNodeEnabled: Boolean = false                 // Включен ли Proxy Chaining через доверенный узел
) {
    companion object {
        const val SUB_ID_PERSONAL_DIRECT = "personal_direct"
        const val SUB_ID_PERSONAL_PREFIX = "personal_sub_"

        /**
         * Проверяет, принадлежит ли сервер к пулу доверенных серверов пользователя.
         */
        fun isPersonalServer(subscriptionId: String?): Boolean {
            if (subscriptionId.isNullOrEmpty()) return false
            return subscriptionId == SUB_ID_PERSONAL_DIRECT || subscriptionId.startsWith(SUB_ID_PERSONAL_PREFIX)
        }
    }
}

