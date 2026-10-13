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
    val subscriptionUrl: String = "",
    val xrayBinaryPath: String = "",
    val v2wScanPath: String = "",
    val autoEnableSystemProxy: Boolean = false,
    val allowLan: Boolean = false,
    val minimizeToTray: Boolean = true,
    val routingMode: String = "PROXY", // PROXY или TUN
    val uiTheme: String = "CYAN", // CYAN, AMOLED, PURPLE
    val uiCompactCards: Boolean = false,
    val subscriptionAutoUpdate: Boolean = false,
    val subscriptionUpdateIntervalHours: Int = 12,
    val subscriptionUpdateOnlyViaProxy: Boolean = false,
    val subscriptionUserAgent: String = "v2rayNG/1.10.x",
    val subscriptionThreads: Int = 3,
    val customDirectDomains: String = "",
    val customProxyDomains: String = "",
    val upstreamProxyHost: String = "",
    val upstreamProxyPort: Int = 0,
    val upstreamProxyType: String = "socks5",
    val minSpeedMbps: Double = 1.0,
    val connectivityCheckUrl: String = "https://cp.cloudflare.com/generate_204"
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
    private var cached = DesktopStorageManager.loadSettings()

    override fun getAppScenario(): AppScenario {
        return try {
            AppScenario.valueOf(cached.appScenario)
        } catch (_: Exception) {
            AppScenario.AUTO
        }
    }

    override fun setAppScenario(scenario: AppScenario) {
        cached = cached.copy(appScenario = scenario.name)
        persist()
    }

    override fun isSmartFailoverEnabled(): Boolean = cached.smartFailover

    override fun setSmartFailoverEnabled(enabled: Boolean) {
        cached = cached.copy(smartFailover = enabled)
        persist()
    }

    override fun getSocksPort(): Int = cached.socksPort

    override fun setSocksPort(port: Int) {
        cached = cached.copy(socksPort = port)
        persist()
    }

    override fun getHttpPort(): Int = cached.httpPort

    override fun setHttpPort(port: Int) {
        cached = cached.copy(httpPort = port)
        persist()
    }

    override fun getString(key: String, defaultValue: String): String {
        return when (key) {
            "subscription_url" -> cached.subscriptionUrl
            "xray_path" -> cached.xrayBinaryPath
            "v2w_scan_path" -> cached.v2wScanPath
            "routing_mode" -> cached.routingMode
            "ui_theme" -> cached.uiTheme
            "subscription_user_agent" -> cached.subscriptionUserAgent
            "custom_direct_domains" -> cached.customDirectDomains
            "custom_proxy_domains" -> cached.customProxyDomains
            "upstream_proxy_host" -> cached.upstreamProxyHost
            "upstream_proxy_type" -> cached.upstreamProxyType
            "connectivity_check_url" -> cached.connectivityCheckUrl
            else -> defaultValue
        }
    }

    override fun setString(key: String, value: String) {
        cached = when (key) {
            "subscription_url" -> cached.copy(subscriptionUrl = value)
            "xray_path" -> cached.copy(xrayBinaryPath = value)
            "v2w_scan_path" -> cached.copy(v2wScanPath = value)
            "routing_mode" -> cached.copy(routingMode = value)
            "ui_theme" -> cached.copy(uiTheme = value)
            "subscription_user_agent" -> cached.copy(subscriptionUserAgent = value)
            "custom_direct_domains" -> cached.copy(customDirectDomains = value)
            "custom_proxy_domains" -> cached.copy(customProxyDomains = value)
            "upstream_proxy_host" -> cached.copy(upstreamProxyHost = value)
            "upstream_proxy_type" -> cached.copy(upstreamProxyType = value)
            "connectivity_check_url" -> cached.copy(connectivityCheckUrl = value)
            else -> cached
        }
        persist()
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return when (key) {
            "auto_enable_system_proxy" -> cached.autoEnableSystemProxy
            "allow_lan" -> cached.allowLan
            "smart_failover" -> cached.smartFailover
            "minimize_to_tray" -> cached.minimizeToTray
            "ui_compact_cards" -> cached.uiCompactCards
            "subscription_auto_update" -> cached.subscriptionAutoUpdate
            "subscription_update_only_via_proxy" -> cached.subscriptionUpdateOnlyViaProxy
            else -> defaultValue
        }
    }

    override fun setBoolean(key: String, value: Boolean) {
        cached = when (key) {
            "auto_enable_system_proxy" -> cached.copy(autoEnableSystemProxy = value)
            "allow_lan" -> cached.copy(allowLan = value)
            "smart_failover" -> cached.copy(smartFailover = value)
            "minimize_to_tray" -> cached.copy(minimizeToTray = value)
            "ui_compact_cards" -> cached.copy(uiCompactCards = value)
            "subscription_auto_update" -> cached.copy(subscriptionAutoUpdate = value)
            "subscription_update_only_via_proxy" -> cached.copy(subscriptionUpdateOnlyViaProxy = value)
            else -> cached
        }
        persist()
    }

    override fun getInt(key: String, defaultValue: Int): Int {
        return when (key) {
            "subscription_update_interval_hours" -> cached.subscriptionUpdateIntervalHours
            "subscription_threads" -> cached.subscriptionThreads
            "upstream_proxy_port" -> cached.upstreamProxyPort
            else -> defaultValue
        }
    }

    override fun setInt(key: String, value: Int) {
        cached = when (key) {
            "subscription_update_interval_hours" -> cached.copy(subscriptionUpdateIntervalHours = value)
            "subscription_threads" -> cached.copy(subscriptionThreads = value)
            "upstream_proxy_port" -> cached.copy(upstreamProxyPort = value)
            else -> cached
        }
        persist()
    }

    private fun persist() {
        DesktopStorageManager.saveSettings(cached)
    }
}
