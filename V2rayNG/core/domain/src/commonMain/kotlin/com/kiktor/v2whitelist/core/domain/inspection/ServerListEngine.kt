package com.kiktor.v2whitelist.core.domain.inspection

import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.inspection.ServerInspectionInfo

data class ServerListEntry(
    val guid: String,
    val profile: ProfileItem,
    val subscriptionId: String,
    val subscriptionName: String,
    val isVip: Boolean,
    val isPersonal: Boolean,
    val fallbackPingMs: Long = 0L,
    val inspection: ServerInspectionInfo? = null,
    val remarksLower: String = profile.remarks.lowercase()
) {
    val isInspected: Boolean
        get() = inspection != null

    val isAlive: Boolean
        get() = inspection?.alive == true

    val isDead: Boolean
        get() = inspection?.alive == false

    val effectivePingMs: Long
        get() = if (inspection != null) {
            if (inspection.alive) inspection.delayMs else -1L
        } else {
            if (fallbackPingMs > 0L) fallbackPingMs else -1L
        }

    val effectiveCountryCode: String
        get() = inspection?.countryCode?.trim()?.uppercase().orEmpty()

    val okServicesCount: Int = inspection?.okServicesCount ?: 0
    val serviceSuccessRatio: Double = inspection?.serviceSuccessRatio ?: 0.0
}

enum class ServerSortOrder {
    SMART,
    PING_ASC,
    SERVICES_DESC,
    COUNTRY_ASC,
    NAME_ASC
}

enum class ServerStatusFilter {
    ALL,
    ALIVE_ONLY,
    HIDE_DEAD
}

enum class EndpointFilterMode {
    ANY,
    PASSED_ONLY,
    FAILED_ONLY
}

data class ServerListFilter(
    val searchQuery: String = "",
    val statusFilter: ServerStatusFilter = ServerStatusFilter.ALL,
    val selectedCountryCodes: Set<String> = emptySet(),
    val selectedSubscriptionIds: Set<String> = emptySet(),
    val selectedProtocols: Set<EConfigType> = emptySet(),
    val requiredServiceIds: Set<String> = emptySet(),
    val endpointFilter: EndpointFilterMode = EndpointFilterMode.ANY,
    val sortOrder: ServerSortOrder = ServerSortOrder.SMART
) {
    fun activeFilterCount(endpointEnabled: Boolean): Int {
        var count = 0
        if (statusFilter != ServerStatusFilter.ALL) count++
        if (selectedCountryCodes.isNotEmpty()) count++
        if (selectedSubscriptionIds.isNotEmpty()) count++
        if (selectedProtocols.isNotEmpty()) count++
        if (endpointEnabled) {
            if (endpointFilter != EndpointFilterMode.ANY) count++
        } else {
            if (requiredServiceIds.isNotEmpty()) count++
        }
        return count
    }

    fun isDefault(endpointEnabled: Boolean): Boolean {
        return activeFilterCount(endpointEnabled) == 0 &&
            searchQuery.isBlank() &&
            sortOrder == ServerSortOrder.SMART
    }
}

data class ServerListStats(
    val totalServers: Int,
    val filteredServers: Int,
    val vipCount: Int,
    val aliveCount: Int,
    val deadCount: Int,
    val untestedCount: Int,
    val removedDuplicatesCount: Int
)

data class ServiceFilterOption(
    val id: String,
    val name: String,
    val aliveServerCount: Int
)

data class ServerListViewResult(
    val vipServers: List<ServerListEntry>,
    val regularServers: List<ServerListEntry>,
    val availableCountryCounts: Map<String, Int>,
    val availableServices: List<ServiceFilterOption>,
    val stats: ServerListStats
)

object ServerListEngine {

    fun buildView(
        entries: List<ServerListEntry>,
        filter: ServerListFilter,
        endpointEnabled: Boolean,
        removedDuplicatesCount: Int = 0
    ): ServerListViewResult {
        val rawCountryCounts = mutableMapOf<String, Int>()
        val serviceNameMap = linkedMapOf<String, String>()
        val serviceAliveCounts = mutableMapOf<String, Int>()

        var aliveTotal = 0
        var deadTotal = 0
        var untestedTotal = 0

        for (entry in entries) {
            val cc = entry.effectiveCountryCode
            if (cc.isNotEmpty()) {
                rawCountryCounts[cc] = (rawCountryCounts[cc] ?: 0) + 1
            }

            val insp = entry.inspection
            when {
                insp == null -> untestedTotal++
                insp.alive -> aliveTotal++
                else -> deadTotal++
            }

            if (insp != null) {
                for (svc in insp.services) {
                    val normId = svc.id.trim().lowercase()
                    if (normId.isEmpty()) continue
                    if (!serviceNameMap.containsKey(normId) && svc.name.isNotBlank()) {
                        serviceNameMap[normId] = svc.name
                    }
                    if (insp.alive && svc.success) {
                        serviceAliveCounts[normId] = (serviceAliveCounts[normId] ?: 0) + 1
                    }
                }
            }
        }

        val availableCountryCounts = rawCountryCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .associate { it.key to it.value }

        val availableServices = serviceNameMap.entries.map { (id, name) ->
            ServiceFilterOption(
                id = id,
                name = name,
                aliveServerCount = serviceAliveCounts[id] ?: 0
            )
        }

        val normalizedQuery = filter.searchQuery.trim().lowercase()
        val normalizedCountries = filter.selectedCountryCodes.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
        val normalizedReqServices = filter.requiredServiceIds.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

        val vipServers = ArrayList<ServerListEntry>()
        val regularServers = ArrayList<ServerListEntry>()

        for (entry in entries) {
            if (normalizedQuery.isNotEmpty()) {
                val matchesQuery = entry.remarksLower.contains(normalizedQuery) ||
                    entry.subscriptionName.contains(normalizedQuery, ignoreCase = true) ||
                    entry.inspection?.exitIp?.contains(normalizedQuery, ignoreCase = true) == true ||
                    entry.effectiveCountryCode.contains(normalizedQuery, ignoreCase = true) ||
                    entry.profile.configType.name.contains(normalizedQuery, ignoreCase = true) ||
                    entry.profile.server?.contains(normalizedQuery, ignoreCase = true) == true
                if (!matchesQuery) continue
            }

            when (filter.statusFilter) {
                ServerStatusFilter.ALL -> Unit
                ServerStatusFilter.ALIVE_ONLY -> if (!entry.isAlive) continue
                ServerStatusFilter.HIDE_DEAD -> if (entry.isDead) continue
            }

            if (normalizedCountries.isNotEmpty() && entry.effectiveCountryCode !in normalizedCountries) {
                continue
            }

            if (filter.selectedSubscriptionIds.isNotEmpty() && entry.subscriptionId !in filter.selectedSubscriptionIds) {
                continue
            }

            if (filter.selectedProtocols.isNotEmpty() && entry.profile.configType !in filter.selectedProtocols) {
                continue
            }

            if (endpointEnabled) {
                when (filter.endpointFilter) {
                    EndpointFilterMode.ANY -> Unit
                    EndpointFilterMode.PASSED_ONLY -> if (entry.inspection?.endpointPassed != true) continue
                    EndpointFilterMode.FAILED_ONLY -> if (entry.inspection?.endpointPassed != false) continue
                }
            } else if (normalizedReqServices.isNotEmpty()) {
                val services = entry.inspection?.services ?: continue
                var allMatched = true
                for (req in normalizedReqServices) {
                    var found = false
                    for (svc in services) {
                        if (svc.success && svc.id.trim().equals(req, ignoreCase = true)) {
                            found = true
                            break
                        }
                    }
                    if (!found) {
                        allMatched = false
                        break
                    }
                }
                if (!allMatched) continue
            }

            if (entry.isVip) {
                vipServers.add(entry)
            } else {
                regularServers.add(entry)
            }
        }

        val comparator = buildComparator(filter.sortOrder, endpointEnabled)
        vipServers.sortWith(comparator)
        regularServers.sortWith(comparator)

        val stats = ServerListStats(
            totalServers = entries.size,
            filteredServers = vipServers.size + regularServers.size,
            vipCount = vipServers.size,
            aliveCount = aliveTotal,
            deadCount = deadTotal,
            untestedCount = untestedTotal,
            removedDuplicatesCount = removedDuplicatesCount
        )

        return ServerListViewResult(
            vipServers = vipServers,
            regularServers = regularServers,
            availableCountryCounts = availableCountryCounts,
            availableServices = availableServices,
            stats = stats
        )
    }

    private fun statusRank(entry: ServerListEntry): Int = when {
        entry.isAlive -> 0
        !entry.isInspected -> 1
        else -> 2
    }

    private fun sortablePing(pingMs: Long): Long = if (pingMs > 0L) pingMs else Long.MAX_VALUE

    private fun buildComparator(sortOrder: ServerSortOrder, endpointEnabled: Boolean): Comparator<ServerListEntry> {
        return Comparator { a, b ->
            val rankDiff = statusRank(a) - statusRank(b)
            if (rankDiff != 0) return@Comparator rankDiff

            when (statusRank(a)) {
                0 -> compareAlive(a, b, sortOrder, endpointEnabled)
                1 -> compareUntested(a, b, sortOrder)
                else -> a.remarksLower.compareTo(b.remarksLower)
            }
        }
    }

    private fun compareAlive(
        a: ServerListEntry,
        b: ServerListEntry,
        sortOrder: ServerSortOrder,
        endpointEnabled: Boolean
    ): Int {
        return when (sortOrder) {
            ServerSortOrder.SMART -> {
                if (endpointEnabled) {
                    val epA = if (a.inspection?.endpointPassed == true) 0 else 1
                    val epB = if (b.inspection?.endpointPassed == true) 0 else 1
                    if (epA != epB) return epA - epB
                } else {
                    val cntDiff = b.okServicesCount - a.okServicesCount
                    if (cntDiff != 0) return cntDiff

                    val ratioDiff = b.serviceSuccessRatio.compareTo(a.serviceSuccessRatio)
                    if (ratioDiff != 0) return ratioDiff
                }
                val pingDiff = sortablePing(a.effectivePingMs).compareTo(sortablePing(b.effectivePingMs))
                if (pingDiff != 0) return pingDiff
                a.remarksLower.compareTo(b.remarksLower)
            }

            ServerSortOrder.PING_ASC -> {
                val pingDiff = sortablePing(a.effectivePingMs).compareTo(sortablePing(b.effectivePingMs))
                if (pingDiff != 0) return pingDiff
                val cntDiff = b.okServicesCount - a.okServicesCount
                if (cntDiff != 0) return cntDiff
                a.remarksLower.compareTo(b.remarksLower)
            }

            ServerSortOrder.SERVICES_DESC -> {
                val cntDiff = b.okServicesCount - a.okServicesCount
                if (cntDiff != 0) return cntDiff
                val ratioDiff = b.serviceSuccessRatio.compareTo(a.serviceSuccessRatio)
                if (ratioDiff != 0) return ratioDiff
                val pingDiff = sortablePing(a.effectivePingMs).compareTo(sortablePing(b.effectivePingMs))
                if (pingDiff != 0) return pingDiff
                a.remarksLower.compareTo(b.remarksLower)
            }

            ServerSortOrder.COUNTRY_ASC -> {
                val ccA = a.effectiveCountryCode.ifEmpty { "ZZZ" }
                val ccB = b.effectiveCountryCode.ifEmpty { "ZZZ" }
                val ccDiff = ccA.compareTo(ccB)
                if (ccDiff != 0) return ccDiff
                val pingDiff = sortablePing(a.effectivePingMs).compareTo(sortablePing(b.effectivePingMs))
                if (pingDiff != 0) return pingDiff
                a.remarksLower.compareTo(b.remarksLower)
            }

            ServerSortOrder.NAME_ASC -> {
                val nameDiff = a.remarksLower.compareTo(b.remarksLower)
                if (nameDiff != 0) return nameDiff
                sortablePing(a.effectivePingMs).compareTo(sortablePing(b.effectivePingMs))
            }
        }
    }

    private fun compareUntested(
        a: ServerListEntry,
        b: ServerListEntry,
        sortOrder: ServerSortOrder
    ): Int {
        if (sortOrder == ServerSortOrder.NAME_ASC) {
            return a.remarksLower.compareTo(b.remarksLower)
        }
        val pingDiff = sortablePing(a.fallbackPingMs).compareTo(sortablePing(b.fallbackPingMs))
        if (pingDiff != 0) return pingDiff
        return a.remarksLower.compareTo(b.remarksLower)
    }
}
