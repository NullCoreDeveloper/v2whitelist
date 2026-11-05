package com.kiktor.v2whitelist.core.domain.personal

import com.kiktor.v2whitelist.core.model.NetworkBindingRule
import com.kiktor.v2whitelist.core.model.PersonalServersConfig
import com.kiktor.v2whitelist.core.model.ProfileItem

sealed class PersonalConnectDecision {
    /** Мгновенное прямое подключение к выбранному личному серверу без сканирования */
    data class ConnectPersonalDirect(val guid: String, val profile: ProfileItem) : PersonalConnectDecision()

    /** Запуск сканера v2w-core только по пулу личных узлов */
    data class ScanPersonalPool(val candidates: List<Pair<String, ProfileItem>>) : PersonalConnectDecision()

    /** Переключение на публичные подписки (по правилу сети или фоллбеку) */
    data class UsePublicSubscriptions(val reason: String) : PersonalConnectDecision()

    /** Прямой интернет без VPN */
    object DirectNoVpn : PersonalConnectDecision()
}

object PersonalServerEvaluator {

    /**
     * Оценивает, какое действие необходимо выполнить при запуске подключения с учетом
     * личных настроек, типа активной сети и состояния блокировок.
     */
    fun evaluate(
        config: PersonalServersConfig,
        isWifi: Boolean,
        isCellular: Boolean,
        isBsJamming: Boolean,
        allPersonalCandidates: List<Pair<String, ProfileItem>>
    ): PersonalConnectDecision {
        if (!config.enabled) {
            return PersonalConnectDecision.UsePublicSubscriptions("Личные сервера выключены")
        }

        // 1. Оцениваем сетевое правило (Wi-Fi vs Сотовая сеть/БС)
        val activeRule = when {
            isBsJamming || isCellular -> config.cellularRule
            isWifi -> config.wifiRule
            else -> NetworkBindingRule.DEFAULT
        }

        when (activeRule) {
            NetworkBindingRule.USE_PUBLIC_SUBS -> {
                return PersonalConnectDecision.UsePublicSubscriptions("Правило сети: переключение на публичные подписки")
            }
            NetworkBindingRule.DIRECT_NO_VPN -> {
                return PersonalConnectDecision.DirectNoVpn
            }
            NetworkBindingRule.USE_PERSONAL_SELECTED -> {
                val selected = config.selectedServerGuid?.let { guid ->
                    allPersonalCandidates.find { it.first == guid }
                }
                if (selected != null) {
                    return PersonalConnectDecision.ConnectPersonalDirect(selected.first, selected.second)
                }
                // Если выбранного сервера нет в списке, откатываемся к автопоиску среди личных
            }
            NetworkBindingRule.USE_PERSONAL_AUTO -> {
                if (allPersonalCandidates.isNotEmpty()) {
                    return PersonalConnectDecision.ScanPersonalPool(allPersonalCandidates)
                }
            }
            NetworkBindingRule.DEFAULT -> {
                // Действуем согласно базовому выбору
            }
        }

        // 2. Базовый режим: конкретный сервер vs автопоиск
        if (!config.selectedServerGuid.isNullOrBlank()) {
            val selected = allPersonalCandidates.find { it.first == config.selectedServerGuid }
            if (selected != null) {
                return PersonalConnectDecision.ConnectPersonalDirect(selected.first, selected.second)
            }
        }

        // 3. Автовыбор среди доступных личных узлов
        if (allPersonalCandidates.isNotEmpty()) {
            return PersonalConnectDecision.ScanPersonalPool(allPersonalCandidates)
        }

        // 4. Если личных узлов нет вовсе
        return if (config.fallbackToPublicOnFailure) {
            PersonalConnectDecision.UsePublicSubscriptions("Пул личных серверов пуст -> фоллбек на публичные")
        } else {
            PersonalConnectDecision.UsePublicSubscriptions("Пул личных серверов пуст")
        }
    }
}

