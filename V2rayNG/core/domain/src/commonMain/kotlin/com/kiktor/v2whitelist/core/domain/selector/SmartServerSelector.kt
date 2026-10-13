package com.kiktor.v2whitelist.core.domain.selector

import com.kiktor.v2whitelist.core.domain.network.NetworkStatusChecker
import com.kiktor.v2whitelist.core.model.AppScenario
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import com.kiktor.v2whitelist.core.subscription.model.CustomSubData
import com.kiktor.v2whitelist.core.subscription.tag.ServerTagResolver
import kotlin.math.abs

enum class LocationFilterMode {
    EXCLUDE,
    WHITELIST,
    DISABLED;

    companion object {
        fun fromString(mode: String?): LocationFilterMode {
            return when (mode?.lowercase()) {
                "exclude" -> EXCLUDE
                "whitelist" -> WHITELIST
                else -> EXCLUDE
            }
        }
    }
}

/**
 * Конфигурация параметров фильтрации серверов.
 */
data class SmartServerFilterConfig(
    val excludeGuid: String? = null,
    val disabledSubscriptionIds: Set<String> = emptySet(),
    val filterInsecure: Boolean = false,
    val locationFilterMode: LocationFilterMode = LocationFilterMode.EXCLUDE,
    val locationFilterSet: Set<String> = emptySet(),
    val groupRegexMap: Map<String, String> = emptyMap(),
    val appScenario: AppScenario = AppScenario.AUTO,
    val internetStatus: Int = NetworkStatusChecker.STATUS_FULL_INTERNET,
    val subscriptionScenariosMap: Map<String, Set<String>> = emptyMap(),
    val filterByScenario: Boolean = true
)

/**
 * Кроссплатформенный движок отбора, сценариев и ранжирования серверов.
 */
object SmartServerSelector {

    /**
     * Фильтрует кандидатов по локациям, безопасности, отключенным подпискам и сценариям (БС / ЧС / YouTube).
     */
    fun filterServerPairs(
        candidates: List<Pair<String, ProfileItem>>,
        config: SmartServerFilterConfig
    ): List<Pair<String, ProfileItem>> {
        // 1. Базовый фильтр по подпискам, безопасности, локациям
        val basicFiltered = candidates.filter { (guid, profile) ->
            if (config.excludeGuid != null && guid == config.excludeGuid) return@filter false
            if (config.disabledSubscriptionIds.contains(profile.subscriptionId)) return@filter false
            if (config.filterInsecure && !profile.isSecure()) return@filter false
            if (profile.configType == EConfigType.POLICYGROUP) return@filter false

            if (config.locationFilterSet.isEmpty() || config.locationFilterMode == LocationFilterMode.DISABLED) {
                return@filter true
            }

            val regexStr = config.groupRegexMap[profile.subscriptionId]
            val tag = ServerTagResolver.resolveServerTag(profile.remarks, regexStr)

            when (config.locationFilterMode) {
                LocationFilterMode.EXCLUDE -> !config.locationFilterSet.contains(tag)
                LocationFilterMode.WHITELIST -> config.locationFilterSet.contains(tag)
                LocationFilterMode.DISABLED -> true
            }
        }

        if (!config.filterByScenario || basicFiltered.isEmpty()) {
            return basicFiltered
        }

        // 2. Целевые сценарии приложения
        val targetScenarios: Set<String>? = when (config.appScenario) {
            AppScenario.AUTO -> {
                if (config.internetStatus == NetworkStatusChecker.STATUS_WHITELIST_ONLY) {
                    setOf(CustomSubData.SCENARIO_WHITELIST)
                } else {
                    setOf(CustomSubData.SCENARIO_BLACKLIST, CustomSubData.SCENARIO_YOUTUBE)
                }
            }
            AppScenario.WHITELIST -> setOf(CustomSubData.SCENARIO_WHITELIST)
            AppScenario.VPN_BLACKLIST -> setOf(CustomSubData.SCENARIO_BLACKLIST)
            AppScenario.YOUTUBE -> setOf(CustomSubData.SCENARIO_YOUTUBE)
            AppScenario.KEEP_CURRENT -> null
        }

        if (targetScenarios == null) {
            return basicFiltered
        }

        // 3. Сопоставление сценариев серверов и подписок
        val scenarioFiltered = basicFiltered.filter { (_, profile) ->
            val subScenarios = config.subscriptionScenariosMap[profile.subscriptionId]
            subScenarios.isNullOrEmpty() || subScenarios.any { targetScenarios.contains(it) }
        }

        return if (scenarioFiltered.isNotEmpty()) {
            scenarioFiltered
        } else {
            // Graceful Fallback: если в целевом пуле 0 серверов, сохраняем все доступные базовые
            GeekModeLogger.log(
                "SmartConnect",
                "Scenario filtering: 0 servers matched $targetScenarios. Fallback to all ${basicFiltered.size} enabled servers"
            )
            basicFiltered
        }
    }

    /**
     * Ранжирует серверы по результатам пинга.
     * Быстрые проверенные VIP-серверы получают преимущество при близком пинге (порог 50мс).
     */
    fun rankServers(
        candidates: List<Pair<String, ProfileItem>>,
        pingResults: Map<String, Long>,
        vipGuids: Set<String> = emptySet()
    ): List<Pair<String, ProfileItem>> {
        return candidates
            .filter { (guid, _) -> (pingResults[guid] ?: -1L) > 0L }
            .sortedWith { a, b ->
                val pingA = pingResults[a.first] ?: Long.MAX_VALUE
                val pingB = pingResults[b.first] ?: Long.MAX_VALUE
                val isVipA = vipGuids.contains(a.first)
                val isVipB = vipGuids.contains(b.first)

                if (isVipA != isVipB && abs(pingA - pingB) <= 50) {
                    if (isVipA) -1 else 1
                } else {
                    pingA.compareTo(pingB)
                }
            }
    }
}
