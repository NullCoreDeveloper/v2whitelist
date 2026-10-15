package com.kiktor.v2whitelist.core.repository

import com.kiktor.v2whitelist.core.model.ProfileItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Кроссплатформенный интерфейс репозитория серверов.
 */
interface ServerRepository {
    fun getAllServerGuids(): List<String>
    fun getServerConfig(guid: String): ProfileItem?
    fun saveServerConfig(guid: String, profile: ProfileItem)
    fun saveServerConfigs(configs: List<Pair<String, ProfileItem>>) {
        for ((g, p) in configs) saveServerConfig(g, p)
    }
    fun removeServer(guid: String)
    fun removeServers(guids: Collection<String>) {
        for (g in guids) removeServer(g)
    }
    fun replaceSubscriptionServers(oldGuids: Collection<String>, newConfigs: List<Pair<String, ProfileItem>>) {
        removeServers(oldGuids)
        saveServerConfigs(newConfigs)
    }

    fun getSelectedServerGuid(): String?
    fun setSelectedServerGuid(guid: String?)

    fun getVipServerGuids(): Set<String>
    fun addVipServer(guid: String)
    fun addVipServer(guid: String, limit: Int) {
        addVipServer(guid)
    }
    fun removeVipServer(guid: String)
    fun setVipServerGuids(guids: Set<String>)

    fun clearAllServers()

    /**
     * Удаляет дубликаты серверов по формуле: address + port + password + (sni или host).
     * @return Количество удаленных серверов-дубликатов.
     */
    fun removeDuplicates(): Int

    val serversFlow: StateFlow<List<Pair<String, ProfileItem>>>
}

/**
 * Потокобезопасная реализация репозитория серверов в памяти (для Desktop / тестов).
 */
class InMemoryServerRepository(
    initialServers: List<Pair<String, ProfileItem>> = emptyList()
) : ServerRepository {

    private val mutex = Mutex()
    private val serverMap = linkedMapOf<String, ProfileItem>()
    private val vipSet = linkedSetOf<String>()
    private var selectedGuid: String? = null

    private val _serversFlow = MutableStateFlow<List<Pair<String, ProfileItem>>>(emptyList())
    override val serversFlow: StateFlow<List<Pair<String, ProfileItem>>> = _serversFlow.asStateFlow()

    init {
        for ((guid, profile) in initialServers) {
            serverMap[guid] = profile
        }
        _serversFlow.value = serverMap.entries.map { it.key to it.value }
    }

    override fun getAllServerGuids(): List<String> {
        return serverMap.keys.toList()
    }

    override fun getServerConfig(guid: String): ProfileItem? {
        return serverMap[guid]
    }

    override fun saveServerConfig(guid: String, profile: ProfileItem) {
        serverMap[guid] = profile
        if (selectedGuid == null) {
            selectedGuid = guid
        }
        _serversFlow.value = serverMap.entries.map { it.key to it.value }
    }

    override fun saveServerConfigs(configs: List<Pair<String, ProfileItem>>) {
        for ((guid, profile) in configs) {
            serverMap[guid] = profile
        }
        if (selectedGuid == null) {
            selectedGuid = serverMap.keys.firstOrNull()
        }
        _serversFlow.value = serverMap.entries.map { it.key to it.value }
    }

    override fun removeServer(guid: String) {
        serverMap.remove(guid)
        vipSet.remove(guid)
        if (selectedGuid == guid) {
            selectedGuid = serverMap.keys.firstOrNull()
        }
        _serversFlow.value = serverMap.entries.map { it.key to it.value }
    }

    override fun getSelectedServerGuid(): String? {
        return selectedGuid ?: serverMap.keys.firstOrNull()
    }

    override fun setSelectedServerGuid(guid: String?) {
        selectedGuid = guid
    }

    override fun getVipServerGuids(): Set<String> {
        return vipSet.toSet()
    }

    override fun addVipServer(guid: String) {
        vipSet.add(guid)
    }

    override fun removeVipServer(guid: String) {
        vipSet.remove(guid)
    }

    override fun setVipServerGuids(guids: Set<String>) {
        vipSet.clear()
        vipSet.addAll(guids)
    }

    override fun clearAllServers() {
        serverMap.clear()
        vipSet.clear()
        selectedGuid = null
        _serversFlow.value = emptyList()
    }

    override fun removeDuplicates(): Int {
        val uniqueKeys = mutableSetOf<String>()
        val guidsToDelete = mutableListOf<String>()

        for ((guid, profile) in serverMap) {
            val sniOrHost = profile.sni?.takeIf { it.isNotBlank() } ?: profile.host.orEmpty()
            val key = "${profile.server}:${profile.serverPort}:${profile.password}:$sniOrHost"
            if (uniqueKeys.contains(key)) {
                guidsToDelete.add(guid)
            } else {
                uniqueKeys.add(key)
            }
        }

        for (g in guidsToDelete) {
            serverMap.remove(g)
            vipSet.remove(g)
            if (selectedGuid == g) {
                selectedGuid = serverMap.keys.firstOrNull()
            }
        }

        if (guidsToDelete.isNotEmpty()) {
            _serversFlow.value = serverMap.entries.map { it.key to it.value }
        }
        return guidsToDelete.size
    }
}
