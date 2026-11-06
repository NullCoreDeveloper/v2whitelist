package com.kiktor.v2whitelist.core.domain.personal

import com.kiktor.v2whitelist.core.model.AppScenario
import com.kiktor.v2whitelist.core.model.NetworkBindingRule
import com.kiktor.v2whitelist.core.model.PersonalServersConfig
import com.kiktor.v2whitelist.core.model.ProfileItem

sealed class PersonalConnectDecision {
    /** Мгновенное прямое подключение к выбранному личному серверу без сканирования */
    data class ConnectPersonalDirect(val guid: String, val profile: ProfileItem) : PersonalConnectDecision()

    /** Запуск сканера v2w-core только по пулу личных узлов */
    data class ScanPersonalPool(val candidates: List<Pair<String, ProfileItem>>) : PersonalConnectDecision()

    /** Переключение на сторонние подписки (по правилу сети или фоллбеку) */
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
                return PersonalConnectDecision.UsePublicSubscriptions("Правило сети: переключение на сторонние подписки")
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

        // 4. Если доверенных узлов нет вовсе
        return if (config.fallbackToPublicOnFailure) {
            PersonalConnectDecision.UsePublicSubscriptions("Пул доверенных серверов пуст -> фоллбек на сторонние")
        } else {
            PersonalConnectDecision.UsePublicSubscriptions("Пул доверенных серверов пуст")
        }
    }

    /**
     * Оценивает решение для личных серверов на основе сценария работы приложения (БС vs ЧС).
     * Применяется на Desktop и в общих кроссплатформенных сценариях.
     */
    fun evaluateScenario(
        config: PersonalServersConfig,
        effectiveScenario: AppScenario,
        allPersonalCandidates: List<Pair<String, ProfileItem>>
    ): PersonalConnectDecision {
        if (!config.enabled) {
            return PersonalConnectDecision.UsePublicSubscriptions("Личные сервера выключены")
        }

        // Выбираем активное правило в зависимости от сценария (БС vs ЧС/YouTube)
        val activeRule = when (effectiveScenario) {
            AppScenario.WHITELIST -> config.whitelistRule
            else -> config.blacklistRule
        }

        when (activeRule) {
            NetworkBindingRule.USE_PUBLIC_SUBS -> {
                return PersonalConnectDecision.UsePublicSubscriptions("Правило сценария $effectiveScenario: переключение на сторонние подписки")
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
                if (allPersonalCandidates.isNotEmpty()) {
                    return PersonalConnectDecision.ScanPersonalPool(allPersonalCandidates)
                }
            }
            NetworkBindingRule.USE_PERSONAL_AUTO -> {
                if (allPersonalCandidates.isNotEmpty()) {
                    return PersonalConnectDecision.ScanPersonalPool(allPersonalCandidates)
                }
            }
            NetworkBindingRule.DEFAULT -> {
                // Если выбран конкретный сервер, используем его, иначе автопоиск
                if (!config.selectedServerGuid.isNullOrBlank()) {
                    val selected = allPersonalCandidates.find { it.first == config.selectedServerGuid }
                    if (selected != null) {
                        return PersonalConnectDecision.ConnectPersonalDirect(selected.first, selected.second)
                    }
                }
                if (allPersonalCandidates.isNotEmpty()) {
                    return PersonalConnectDecision.ScanPersonalPool(allPersonalCandidates)
                }
            }
        }

        // Если доверенных узлов в списке нет
        return if (config.fallbackToPublicOnFailure) {
            PersonalConnectDecision.UsePublicSubscriptions("Пул доверенных серверов пуст -> фоллбек на сторонние")
        } else {
            PersonalConnectDecision.UsePublicSubscriptions("Пул доверенных серверов пуст")
        }
    }
}

