package com.kiktor.v2whitelist.repository

import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.repository.ServerRepository
import com.kiktor.v2whitelist.handler.MmkvManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android-адаптер репозитория серверов над постоянным хранилищем MMKV.
 * Гарантирует полную обратную совместимость с базой данных существующих установок.
 */
object MmkvServerRepository : ServerRepository {

    private val _serversFlow = MutableStateFlow<List<Pair<String, ProfileItem>>>(emptyList())
    override val serversFlow: StateFlow<List<Pair<String, ProfileItem>>> = _serversFlow.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val guids = MmkvManager.decodeServerList()
        val list = guids.mapNotNull { guid ->
            val profile = MmkvManager.decodeServerConfig(guid)
            if (profile != null) guid to profile else null
        }
        _serversFlow.value = list
    }

    override fun getAllServerGuids(): List<String> = MmkvManager.decodeServerList()

    override fun getServerConfig(guid: String): ProfileItem? = MmkvManager.decodeServerConfig(guid)

    override fun saveServerConfig(guid: String, profile: ProfileItem) {
        MmkvManager.encodeServerConfig(guid, profile)
        refresh()
    }

    override fun removeServer(guid: String) {
        MmkvManager.removeServer(guid)
        refresh()
    }

    override fun getSelectedServerGuid(): String? = MmkvManager.getSelectServer()

    override fun setSelectedServerGuid(guid: String?) {
        MmkvManager.setSelectServer(guid.orEmpty())
    }

    override fun getVipServerGuids(): Set<String> = MmkvManager.getVipCache().toSet()

    override fun addVipServer(guid: String) {
        MmkvManager.addVipServer(guid)
    }

    override fun removeVipServer(guid: String) {
        MmkvManager.removeVipServer(guid)
    }

    override fun setVipServerGuids(guids: Set<String>) {
        MmkvManager.replaceVipCache(guids.toList())
    }

    override fun clearAllServers() {
        MmkvManager.removeAllServer()
        refresh()
    }
}
