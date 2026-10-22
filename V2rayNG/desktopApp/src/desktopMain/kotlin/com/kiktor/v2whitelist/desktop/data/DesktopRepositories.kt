package com.kiktor.v2whitelist.desktop.data

import com.kiktor.v2whitelist.core.model.AppScenario
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.repository.ServerRepository
import com.kiktor.v2whitelist.core.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
internal data class ServersStorage(
    val servers: List<ServerRecord> = emptyList(),
    val selectedGuid: String? = null,
    val vipGuids: List<String> = emptyList()
)

@Serializable
internal data class ServerRecord(
    val guid: String,
    val profile: ProfileItem
)

@Serializable
internal data class SettingsStorage(
    val appScenario: String = AppScenario.AUTO.name,
    val smartFailover: Boolean = true,
    val socksPort: Int = 10808,
    val httpPort: Int = 10809,
    val stringMap: Map<String, String> = emptyMap(),
    val boolMap: Map<String, Boolean> = emptyMap(),
    val intMap: Map<String, Int> = emptyMap()
)

internal object DesktopStorageManager {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val baseDir: File by lazy {
        val userHome = System.getProperty("user.home") ?: "."
        val dir = File(userHome, ".config/v2whitelist")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    val serversFile: File get() = File(baseDir, "servers.json")
    val settingsFile: File get() = File(baseDir, "settings.json")

    fun loadServers(): ServersStorage {
        val f = serversFile
        if (!f.exists()) return ServersStorage()
        return try {
            json.decodeFromString<ServersStorage>(f.readText())
        } catch (e: Exception) {
            ServersStorage()
        }
    }

    fun saveServers(storage: ServersStorage) {
        try {
            val content = json.encodeToString(storage)
            serversFile.writeText(content)
        } catch (_: Exception) {}
    }

    fun loadSettings(): SettingsStorage {
        val f = settingsFile
        if (!f.exists()) return SettingsStorage()
        return try {
            json.decodeFromString<SettingsStorage>(f.readText())
        } catch (e: Exception) {
            SettingsStorage()
        }
    }

    fun saveSettings(storage: SettingsStorage) {
        try {
            val content = json.encodeToString(storage)
            settingsFile.writeText(content)
        } catch (_: Exception) {}
    }

    fun clearDiskStorage() {
        try { serversFile.delete() } catch (_: Exception) {}
        try { settingsFile.delete() } catch (_: Exception) {}
    }
}

class DesktopFileServerRepository : ServerRepository {
    private val repoLock = Any()
    private val serverMap = linkedMapOf<String, ProfileItem>()
    private val vipList = mutableListOf<String>()
    private var selectedGuid: String? = null

    private val _serversFlow = MutableStateFlow<List<Pair<String, ProfileItem>>>(emptyList())
    override val serversFlow: StateFlow<List<Pair<String, ProfileItem>>> = _serversFlow.asStateFlow()

    private val _vipGuidsFlow = MutableStateFlow<Set<String>>(emptySet())
    override val vipGuidsFlow: StateFlow<Set<String>> = _vipGuidsFlow.asStateFlow()

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() = synchronized(repoLock) {
        val storage = DesktopStorageManager.loadServers()
        serverMap.clear()
        vipList.clear()
        for (item in storage.servers) {
            serverMap[item.guid] = item.profile
        }
        vipList.addAll(storage.vipGuids.filter { serverMap.containsKey(it) })
        val saved = storage.selectedGuid
        selectedGuid = if (saved != null && serverMap.containsKey(saved)) saved else serverMap.keys.firstOrNull()
        updateFlow()
    }

    private fun persist() = synchronized(repoLock) {
        val records = serverMap.entries.map { ServerRecord(it.key, it.value) }
        DesktopStorageManager.saveServers(
            ServersStorage(
                servers = records,
                selectedGuid = selectedGuid,
                vipGuids = vipList.toList()
            )
        )
    }

    private fun updateFlow() {
        _serversFlow.value = serverMap.entries.map { it.key to it.value }
        _vipGuidsFlow.value = vipList.toSet()
    }

    override fun getAllServerGuids(): List<String> = synchronized(repoLock) {
        serverMap.keys.toList()
    }

    override fun getServerConfig(guid: String): ProfileItem? = synchronized(repoLock) {
        serverMap[guid]
    }

    override fun saveServerConfig(guid: String, profile: ProfileItem) = synchronized(repoLock) {
        serverMap[guid] = profile
        if (selectedGuid == null) {
            selectedGuid = guid
        }
        updateFlow()
        persist()
    }

    override fun saveServerConfigs(configs: List<Pair<String, ProfileItem>>) = synchronized(repoLock) {
        for ((guid, profile) in configs) {
            serverMap[guid] = profile
        }
        if (selectedGuid == null) {
            selectedGuid = serverMap.keys.firstOrNull()
        }
        updateFlow()
        persist()
    }

    override fun removeServer(guid: String) {
        serverMap.remove(guid)
        vipList.remove(guid)
        if (selectedGuid == guid) {
            selectedGuid = serverMap.keys.firstOrNull()
        }
        updateFlow()
        persist()
    }

    override fun removeServers(guids: Collection<String>) {
        synchronized(repoLock) {
            if (guids.isEmpty()) return
            for (g in guids) {
                serverMap.remove(g)
                vipList.remove(g)
            }
            if (selectedGuid in guids) {
                selectedGuid = serverMap.keys.firstOrNull()
            }
            updateFlow()
            persist()
        }
    }

    private fun getNetworkIdentityKey(profile: ProfileItem): String {
        val hostOrSni = (profile.sni?.takeIf { it.isNotBlank() } ?: profile.host).orEmpty().trim().lowercase()
        val srv = profile.server.orEmpty().trim().lowercase()
        val pwd = profile.password.orEmpty().trim()
        return "${profile.configType}:$srv:${profile.serverPort}:$pwd:$hostOrSni"
    }

    override fun replaceSubscriptionServers(oldGuids: Collection<String>, newConfigs: List<Pair<String, ProfileItem>>) = synchronized(repoLock) {
        val oldVipGuids = vipList.filter { it in oldGuids }.toSet()
        val oldVipKeys = oldVipGuids.mapNotNull { g ->
            serverMap[g]?.let { getNetworkIdentityKey(it) }
        }.toSet()

        val oldSelectedKey = selectedGuid?.let { serverMap[it]?.let { p -> getNetworkIdentityKey(p) } }

        for (g in oldGuids) {
            serverMap.remove(g)
            vipList.remove(g)
        }

        var restoredSelectedGuid: String? = null

        for ((guid, profile) in newConfigs) {
            serverMap[guid] = profile
            val key = getNetworkIdentityKey(profile)
            if (guid in oldVipGuids || key in oldVipKeys) {
                if (!vipList.contains(guid)) {
                    vipList.add(guid)
                }
            }
            if (oldSelectedKey != null && key == oldSelectedKey) {
                restoredSelectedGuid = guid
            }
        }

        if (restoredSelectedGuid != null) {
            selectedGuid = restoredSelectedGuid
        } else if (selectedGuid == null || selectedGuid in oldGuids) {
            selectedGuid = serverMap.keys.firstOrNull()
        }
        updateFlow()
        persist()
    }

    override fun getSelectedServerGuid(): String? = synchronized(repoLock) {
        val guid = selectedGuid
        if (guid != null && serverMap.containsKey(guid)) {
            return guid
        }
        return serverMap.keys.firstOrNull()
    }

    override fun setSelectedServerGuid(guid: String?) = synchronized(repoLock) {
        selectedGuid = guid
        persist()
    }

    override fun getVipServerGuids(): Set<String> = synchronized(repoLock) { vipList.toSet() }

    override fun addVipServer(guid: String) {
        addVipServer(guid, 5)
    }

    override fun addVipServer(guid: String, limit: Int) {
        synchronized(repoLock) {
            if (guid.isBlank()) return
            vipList.remove(guid)
            vipList.add(0, guid)
            val maxLimit = if (limit > 0) limit else 5
            while (vipList.size > maxLimit) {
                vipList.removeAt(vipList.size - 1)
            }
            updateFlow()
            persist()
        }
    }

    override fun removeVipServer(guid: String) = synchronized(repoLock) {
        vipList.remove(guid)
        updateFlow()
        persist()
    }

    override fun setVipServerGuids(guids: Set<String>) = synchronized(repoLock) {
        vipList.clear()
        vipList.addAll(guids.filter { serverMap.containsKey(it) })
        updateFlow()
        persist()
    }

    override fun clearAllServers() = synchronized(repoLock) {
        serverMap.clear()
        vipList.clear()
        selectedGuid = null
        updateFlow()
        persist()
    }

    override fun removeDuplicates(): Int = synchronized(repoLock) {
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
            vipList.remove(g)
            if (selectedGuid == g) {
                selectedGuid = serverMap.keys.firstOrNull()
            }
        }

        if (guidsToDelete.isNotEmpty()) {
            updateFlow()
            persist()
        }
        return guidsToDelete.size
    }
}

class DesktopFileSettingsRepository : SettingsRepository {
    private val stringMap = mutableMapOf<String, String>()
    private val boolMap = mutableMapOf<String, Boolean>()
    private val intMap = mutableMapOf<String, Int>()
    private var appScenario: AppScenario = AppScenario.AUTO
    private var smartFailover: Boolean = true
    private var socksPort: Int = 10808
    private var httpPort: Int = 10809

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        val loaded = DesktopStorageManager.loadSettings()
        appScenario = try { AppScenario.valueOf(loaded.appScenario) } catch (_: Exception) { AppScenario.AUTO }
        smartFailover = loaded.smartFailover
        socksPort = loaded.socksPort
        httpPort = loaded.httpPort
        stringMap.clear()
        stringMap.putAll(loaded.stringMap)
        boolMap.clear()
        boolMap.putAll(loaded.boolMap)
        intMap.clear()
        intMap.putAll(loaded.intMap)
    }

    private fun persist() {
        DesktopStorageManager.saveSettings(
            SettingsStorage(
                appScenario = appScenario.name,
                smartFailover = smartFailover,
                socksPort = socksPort,
                httpPort = httpPort,
                stringMap = stringMap.toMap(),
                boolMap = boolMap.toMap(),
                intMap = intMap.toMap()
            )
        )
    }

    override fun getAppScenario(): AppScenario = appScenario
    override fun setAppScenario(scenario: AppScenario) {
        appScenario = scenario
        persist()
    }

    override fun isSmartFailoverEnabled(): Boolean = smartFailover
    override fun setSmartFailoverEnabled(enabled: Boolean) {
        smartFailover = enabled
        persist()
    }

    override fun getSocksPort(): Int = socksPort
    override fun setSocksPort(port: Int) {
        socksPort = port
        httpPort = port + 1
        persist()
    }

    override fun getHttpPort(): Int = if (httpPort == socksPort || httpPort <= 0) socksPort + 1 else httpPort
    override fun setHttpPort(port: Int) {
        httpPort = port
        persist()
    }

    override fun clearAll() {
        appScenario = AppScenario.AUTO
        smartFailover = true
        socksPort = 10808
        httpPort = 10809
        stringMap.clear()
        boolMap.clear()
        intMap.clear()
        persist()
    }

    override fun getString(key: String, defaultValue: String): String {
        return stringMap[key] ?: defaultValue
    }

    override fun setString(key: String, value: String) {
        stringMap[key] = value
        persist()
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return boolMap[key] ?: defaultValue
    }

    override fun setBoolean(key: String, value: Boolean) {
        boolMap[key] = value
        persist()
    }

    override fun getInt(key: String, defaultValue: Int): Int {
        return intMap[key] ?: defaultValue
    }

    override fun setInt(key: String, value: Int) {
        intMap[key] = value
        persist()
    }
}
