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
}

class DesktopFileServerRepository : ServerRepository {
    private val serverMap = linkedMapOf<String, ProfileItem>()
    private val vipSet = linkedSetOf<String>()
    private var selectedGuid: String? = null

    private val _serversFlow = MutableStateFlow<List<Pair<String, ProfileItem>>>(emptyList())
    override val serversFlow: StateFlow<List<Pair<String, ProfileItem>>> = _serversFlow.asStateFlow()

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        val storage = DesktopStorageManager.loadServers()
        serverMap.clear()
        vipSet.clear()
        for (item in storage.servers) {
            serverMap[item.guid] = item.profile
        }
        vipSet.addAll(storage.vipGuids)
        selectedGuid = storage.selectedGuid ?: serverMap.keys.firstOrNull()
        updateFlow()
    }

    private fun persist() {
        val records = serverMap.entries.map { ServerRecord(it.key, it.value) }
        DesktopStorageManager.saveServers(
            ServersStorage(
                servers = records,
                selectedGuid = selectedGuid,
                vipGuids = vipSet.toList()
            )
        )
    }

    private fun updateFlow() {
        _serversFlow.value = serverMap.entries.map { it.key to it.value }
    }

    override fun getAllServerGuids(): List<String> = serverMap.keys.toList()

    override fun getServerConfig(guid: String): ProfileItem? = serverMap[guid]

    override fun saveServerConfig(guid: String, profile: ProfileItem) {
        serverMap[guid] = profile
        if (selectedGuid == null) {
            selectedGuid = guid
        }
        updateFlow()
        persist()
    }

    override fun saveServerConfigs(configs: List<Pair<String, ProfileItem>>) {
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
        vipSet.remove(guid)
        if (selectedGuid == guid) {
            selectedGuid = serverMap.keys.firstOrNull()
        }
        updateFlow()
        persist()
    }

    override fun getSelectedServerGuid(): String? {
        return selectedGuid ?: serverMap.keys.firstOrNull()
    }

    override fun setSelectedServerGuid(guid: String?) {
        selectedGuid = guid
        persist()
    }

    override fun getVipServerGuids(): Set<String> = vipSet.toSet()

    override fun addVipServer(guid: String) {
        vipSet.add(guid)
        persist()
    }

    override fun removeVipServer(guid: String) {
        vipSet.remove(guid)
        persist()
    }

    override fun setVipServerGuids(guids: Set<String>) {
        vipSet.clear()
        vipSet.addAll(guids)
        persist()
    }

    override fun clearAllServers() {
        serverMap.clear()
        vipSet.clear()
        selectedGuid = null
        updateFlow()
        persist()
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
        persist()
    }

    override fun getHttpPort(): Int = httpPort
    override fun setHttpPort(port: Int) {
        httpPort = port
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
