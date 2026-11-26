package com.kiktor.v2whitelist.desktop.data

import com.kiktor.v2whitelist.core.model.AppScenario
import com.kiktor.v2whitelist.core.model.PersonalServersConfig
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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet

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

@Serializable
internal data class DesktopBackupPayload(
    val version: Int = 1,
    val exportedAtMs: Long = 0L,
    val settings: SettingsStorage = SettingsStorage(),
    val servers: ServersStorage = ServersStorage()
)

internal object DesktopStorageManager {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    private val compactJson = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    private val isWindows = System.getProperty("os.name")?.lowercase()?.contains("windows") == true
    private val isMac = System.getProperty("os.name")?.lowercase()?.contains("mac") == true

    private fun getAppCandidateDirs(): List<File> {
        val list = mutableListOf<File>()
        System.getProperty("app.dir")?.let { list.add(File(it)) }
        System.getProperty("compose.application.resources.dir")?.let { res ->
            val resDir = File(res)
            resDir.parentFile?.parentFile?.let { list.add(it) }
        }
        System.getProperty("java.home")?.let { jh ->
            val jHome = File(jh)
            jHome.parentFile?.let { list.add(it) }
            jHome.parentFile?.parentFile?.let { list.add(it) }
        }
        runCatching {
            DesktopStorageManager::class.java.protectionDomain?.codeSource?.location?.toURI()?.let { uri ->
                val f = File(uri)
                val jarDir = if (f.isDirectory) f else f.parentFile
                jarDir?.parentFile?.let { list.add(it) }
            }
        }
        list.add(File("."))
        return list.filter { it.exists() }
    }

    /**
     * Стандартная директория настроек операционной системы:
     * - Windows: %LOCALAPPDATA%\v2whitelist (C:\Users\<user>\AppData\Local\v2whitelist)
     * - Linux: ~/.config/v2whitelist
     * - macOS: ~/Library/Application Support/v2whitelist
     */
    val standardConfigDir: File by lazy {
        val userHome = System.getProperty("user.home") ?: "."
        val standardDir = when {
            isWindows -> {
                val localAppData = System.getenv("LOCALAPPDATA")
                val legacyConfig = File(userHome, ".config/v2whitelist")
                if (localAppData != null) {
                    val appDataDir = File(localAppData, "v2whitelist")
                    if (!appDataDir.exists() && legacyConfig.exists()) {
                        try {
                            legacyConfig.copyRecursively(appDataDir, overwrite = true)
                            legacyConfig.deleteRecursively()
                        } catch (_: Exception) {}
                    }
                    appDataDir
                } else {
                    File(userHome, "AppData/Local/v2whitelist")
                }
            }
            isMac -> File(userHome, "Library/Application Support/v2whitelist")
            else -> File(userHome, ".config/v2whitelist")
        }
        if (!standardDir.exists()) standardDir.mkdirs()
        applyPrivateDirPermissions(standardDir)
        standardDir
    }

    /**
     * Определяет, активно ли портативное хранилище:
     * если в каталоге приложения есть маркер `.portable` / `portable` или папка `data`,
     * приложение не установлено в системную Program Files и директория доступна на запись.
     */
    val isPortableMode: Boolean by lazy {
        if (System.getProperty("v2w.portable") == "true") return@lazy true
        val candidateDirs = getAppCandidateDirs()
        candidateDirs.any { dir ->
            val isProgramFiles = isWindows && dir.absolutePath.contains("Program Files", ignoreCase = true)
            !isProgramFiles && dir.canWrite() && (File(dir, "data").isDirectory || File(dir, ".portable").exists() || File(dir, "portable").exists())
        }
    }

    val baseDir: File by lazy {
        val appDirs = getAppCandidateDirs()
        for (dir in appDirs) {
            val isProgramFiles = isWindows && dir.absolutePath.contains("Program Files", ignoreCase = true)
            if (isProgramFiles) continue
            val dataDir = File(dir, "data")
            if (dataDir.isDirectory || File(dir, ".portable").exists() || File(dir, "portable").exists() || System.getProperty("v2w.portable") == "true") {
                if (!dataDir.exists()) {
                    try { dataDir.mkdirs() } catch (_: Exception) {}
                }
                if (dataDir.isDirectory && dataDir.canWrite()) {
                    applyPrivateDirPermissions(dataDir)
                    return@lazy dataDir
                }
            }
        }
        standardConfigDir
    }

    val runtimeDir: File
        get() {
            val dir = File(baseDir, "runtime")
            if (!dir.exists()) dir.mkdirs()
            applyPrivateDirPermissions(dir)
            return dir
        }

    fun applyPrivateDirPermissions(dir: File) {
        if (!dir.exists()) return
        runCatching {
            if (!isWindows) {
                Files.setPosixFilePermissions(
                    dir.toPath(),
                    EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE
                    )
                )
            } else {
                dir.setReadable(true, true)
                dir.setWritable(true, true)
                dir.setExecutable(true, true)
            }
        }
    }

    fun applyPrivateFilePermissions(file: File) {
        if (!file.exists()) return
        runCatching {
            if (!isWindows) {
                Files.setPosixFilePermissions(
                    file.toPath(),
                    EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE
                    )
                )
            } else {
                file.setReadable(false, false)
                file.setReadable(true, true)
                file.setWritable(false, false)
                file.setWritable(true, true)
            }
        }
    }

    private var lockFileChannel: java.nio.channels.FileChannel? = null
    private var instanceLock: java.nio.channels.FileLock? = null

    /**
     * Захватывает межпроцессную блокировку единственного экземпляра приложения.
     * Предотвращает запуск второй копии (конфликты портов 10808/10809 и перезапись настроек).
     */
    fun acquireSingleInstanceLock(): Boolean {
        return try {
            val lockDir = if (isPortableMode && baseDir.canWrite()) baseDir else standardConfigDir
            if (!lockDir.exists()) lockDir.mkdirs()
            val lockFile = File(lockDir, ".app_instance.lock")
            val raf = java.io.RandomAccessFile(lockFile, "rw")
            val channel = raf.channel
            val lock = channel.tryLock()
            if (lock != null) {
                lockFileChannel = channel
                instanceLock = lock
                Runtime.getRuntime().addShutdownHook(Thread {
                    runCatching {
                        instanceLock?.release()
                        lockFileChannel?.close()
                        lockFile.delete()
                    }
                })
                true
            } else {
                raf.close()
                false
            }
        } catch (_: Exception) {
            true
        }
    }

    val serversFile: File get() = File(baseDir, "servers.json")
    val settingsFile: File get() = File(baseDir, "settings.json")

    fun loadServers(): ServersStorage {
        val f = serversFile
        if (!f.exists()) return ServersStorage()
        return try {
            compactJson.decodeFromString<ServersStorage>(f.readText())
        } catch (e: Exception) {
            ServersStorage()
        }
    }

    fun saveServers(storage: ServersStorage) {
        try {
            val content = compactJson.encodeToString(storage)
            val parent = serversFile.parentFile ?: baseDir
            if (!parent.exists()) parent.mkdirs()
            applyPrivateDirPermissions(parent)
            val tempFile = File(parent, "${serversFile.name}.tmp")
            tempFile.writeText(content)
            applyPrivateFilePermissions(tempFile)
            try {
                Files.move(
                    tempFile.toPath(),
                    serversFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                Files.move(
                    tempFile.toPath(),
                    serversFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            applyPrivateFilePermissions(serversFile)
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

    private val settingsFileLock = Any()

    fun saveSettings(storage: SettingsStorage) {
        synchronized(settingsFileLock) {
            val content = try {
                json.encodeToString(storage)
            } catch (e: Exception) {
                System.err.println("[DesktopStorageManager] Ошибка сериализации настроек: ${e.message}")
                return
            }
            val parent = settingsFile.parentFile ?: baseDir
            if (!parent.exists()) parent.mkdirs()
            applyPrivateDirPermissions(parent)
            val tempFile = File(parent, "${settingsFile.name}.tmp")
            var lastError: Exception? = null
            for (attempt in 0 until 4) {
                try {
                    tempFile.writeText(content)
                    applyPrivateFilePermissions(tempFile)
                    try {
                        Files.move(
                            tempFile.toPath(),
                            settingsFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE
                        )
                    } catch (_: Exception) {
                        Files.move(
                            tempFile.toPath(),
                            settingsFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    }
                    applyPrivateFilePermissions(settingsFile)
                    return
                } catch (e: Exception) {
                    lastError = e
                    if (attempt < 3) {
                        try { Thread.sleep(25L * (attempt + 1)) } catch (_: InterruptedException) {}
                    }
                }
            }
            // Резервная прямая запись, если переименование файла заблокировано
            try {
                settingsFile.writeText(content)
                applyPrivateFilePermissions(settingsFile)
            } catch (e: Exception) {
                System.err.println("[DesktopStorageManager] Не удалось сохранить settings.json: ${lastError?.message ?: e.message}")
            } finally {
                runCatching { if (tempFile.exists()) tempFile.delete() }
            }
        }
    }

    fun clearDiskStorage() {
        try { serversFile.delete() } catch (_: Exception) {}
        try { settingsFile.delete() } catch (_: Exception) {}
        try { File(baseDir, "inspections.json").delete() } catch (_: Exception) {}
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

    internal fun exportSnapshot(): ServersStorage = synchronized(repoLock) {
        ServersStorage(
            servers = serverMap.entries.map { ServerRecord(it.key, it.value) },
            selectedGuid = selectedGuid,
            vipGuids = vipList.toList()
        )
    }

    internal fun importSnapshot(snapshot: ServersStorage) = synchronized(repoLock) {
        serverMap.clear()
        vipList.clear()
        for (item in snapshot.servers) {
            serverMap[item.guid] = item.profile
        }
        vipList.addAll(snapshot.vipGuids.filter { serverMap.containsKey(it) })
        val saved = snapshot.selectedGuid
        selectedGuid = if (saved != null && serverMap.containsKey(saved)) saved else serverMap.keys.firstOrNull()
        updateFlow()
        persist()
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

    private fun applySubReplacementLocked(
        oldGuids: Collection<String>,
        newConfigs: List<Pair<String, ProfileItem>>
    ) {
        val oldSet = if (oldGuids is Set<String>) oldGuids else oldGuids.toSet()
        val oldVipGuids = vipList.filter { it in oldSet }.toSet()
        val oldVipKeys = oldVipGuids.mapNotNull { g ->
            serverMap[g]?.let { getNetworkIdentityKey(it) }
        }.toSet()

        val oldSelectedKey = selectedGuid?.let { serverMap[it]?.let { p -> getNetworkIdentityKey(p) } }

        for (g in oldGuids) {
            serverMap.remove(g)
        }
        vipList.removeAll(oldSet)

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
        } else if (selectedGuid == null || selectedGuid in oldSet) {
            selectedGuid = serverMap.keys.firstOrNull()
        }
    }

    override fun replaceSubscriptionServers(oldGuids: Collection<String>, newConfigs: List<Pair<String, ProfileItem>>) = synchronized(repoLock) {
        applySubReplacementLocked(oldGuids, newConfigs)
        updateFlow()
        persist()
    }

    override fun replaceSubscriptionsBatch(
        batches: List<Pair<Collection<String>, List<Pair<String, ProfileItem>>>>,
        deduplicate: Boolean
    ): Int = synchronized(repoLock) {
        for ((oldGuids, newConfigs) in batches) {
            applySubReplacementLocked(oldGuids, newConfigs)
        }
        val removed = if (deduplicate) deduplicateLocked() else 0
        if (batches.isNotEmpty() || removed > 0) {
            updateFlow()
            persist()
        }
        removed
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

    private fun deduplicateLocked(): Int {
        val uniqueKeys = mutableSetOf<String>()
        val guidsToDelete = mutableListOf<String>()

        // 1. Сначала регистрируем ключи всех личных серверов (они никогда не удаляются)
        for ((_, profile) in serverMap) {
            if (PersonalServersConfig.isPersonalServer(profile.subscriptionId)) {
                val sniOrHost = profile.sni?.takeIf { it.isNotBlank() } ?: profile.host.orEmpty()
                val key = "${profile.server}:${profile.serverPort}:${profile.password}:$sniOrHost"
                uniqueKeys.add(key)
            }
        }

        // 2. Дедуплицируем сторонние/публичные серверы
        for ((guid, profile) in serverMap) {
            if (PersonalServersConfig.isPersonalServer(profile.subscriptionId)) {
                continue
            }
            val sniOrHost = profile.sni?.takeIf { it.isNotBlank() } ?: profile.host.orEmpty()
            val key = "${profile.server}:${profile.serverPort}:${profile.password}:$sniOrHost"
            if (uniqueKeys.contains(key)) {
                guidsToDelete.add(guid)
            } else {
                uniqueKeys.add(key)
            }
        }

        val deleteSet = guidsToDelete.toSet()
        for (g in guidsToDelete) {
            serverMap.remove(g)
        }
        if (deleteSet.isNotEmpty()) {
            vipList.removeAll(deleteSet)
            if (selectedGuid in deleteSet) {
                selectedGuid = serverMap.keys.firstOrNull()
            }
        }
        return guidsToDelete.size
    }

    override fun removeDuplicates(): Int = synchronized(repoLock) {
        val count = deduplicateLocked()
        if (count > 0) {
            updateFlow()
            persist()
        }
        return count
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
    private val repoLock = Any()
    private var inBatch = false
    private var dirtyInBatch = false

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        synchronized(repoLock) {
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
    }

    private fun persist() {
        val snapshot = synchronized(repoLock) {
            if (inBatch) {
                dirtyInBatch = true
                return
            }
            SettingsStorage(
                appScenario = appScenario.name,
                smartFailover = smartFailover,
                socksPort = socksPort,
                httpPort = httpPort,
                stringMap = stringMap.toMap(),
                boolMap = boolMap.toMap(),
                intMap = intMap.toMap()
            )
        }
        DesktopStorageManager.saveSettings(snapshot)
    }

    override fun runBatch(block: () -> Unit) {
        val snapshotToPersist = synchronized(repoLock) {
            val alreadyInBatch = inBatch
            if (!alreadyInBatch) {
                inBatch = true
                dirtyInBatch = false
            }
            try {
                block()
            } finally {
                if (!alreadyInBatch) {
                    inBatch = false
                }
            }
            if (!alreadyInBatch && dirtyInBatch) {
                dirtyInBatch = false
                SettingsStorage(
                    appScenario = appScenario.name,
                    smartFailover = smartFailover,
                    socksPort = socksPort,
                    httpPort = httpPort,
                    stringMap = stringMap.toMap(),
                    boolMap = boolMap.toMap(),
                    intMap = intMap.toMap()
                )
            } else {
                null
            }
        }
        if (snapshotToPersist != null) {
            DesktopStorageManager.saveSettings(snapshotToPersist)
        }
    }

    override fun getAppScenario(): AppScenario = synchronized(repoLock) { appScenario }
    override fun setAppScenario(scenario: AppScenario) {
        synchronized(repoLock) { appScenario = scenario }
        persist()
    }

    override fun isSmartFailoverEnabled(): Boolean = synchronized(repoLock) { smartFailover }
    override fun setSmartFailoverEnabled(enabled: Boolean) {
        synchronized(repoLock) { smartFailover = enabled }
        persist()
    }

    override fun getSocksPort(): Int = synchronized(repoLock) { socksPort }
    override fun setSocksPort(port: Int) {
        synchronized(repoLock) {
            socksPort = port
            httpPort = port + 1
        }
        persist()
    }

    override fun getHttpPort(): Int = synchronized(repoLock) {
        if (httpPort == socksPort || httpPort <= 0) socksPort + 1 else httpPort
    }
    override fun setHttpPort(port: Int) {
        synchronized(repoLock) { httpPort = port }
        persist()
    }

    override fun clearAll() {
        synchronized(repoLock) {
            appScenario = AppScenario.AUTO
            smartFailover = true
            socksPort = 10808
            httpPort = 10809
            stringMap.clear()
            boolMap.clear()
            intMap.clear()
        }
        persist()
    }

    internal fun exportSnapshot(): SettingsStorage = synchronized(repoLock) {
        SettingsStorage(
            appScenario = appScenario.name,
            smartFailover = smartFailover,
            socksPort = socksPort,
            httpPort = httpPort,
            stringMap = stringMap.toMap(),
            boolMap = boolMap.toMap(),
            intMap = intMap.toMap()
        )
    }

    internal fun importSnapshot(snapshot: SettingsStorage) {
        synchronized(repoLock) {
            appScenario = try { AppScenario.valueOf(snapshot.appScenario) } catch (_: Exception) { AppScenario.AUTO }
            smartFailover = snapshot.smartFailover
            socksPort = snapshot.socksPort.coerceIn(1024, 65534)
            httpPort = if (snapshot.httpPort in 1024..65535 && snapshot.httpPort != socksPort) snapshot.httpPort else socksPort + 1
            stringMap.clear()
            stringMap.putAll(snapshot.stringMap)
            boolMap.clear()
            boolMap.putAll(snapshot.boolMap)
            intMap.clear()
            intMap.putAll(snapshot.intMap)
        }
        persist()
    }

    override fun getString(key: String, defaultValue: String): String = synchronized(repoLock) {
        stringMap[key] ?: defaultValue
    }

    override fun setString(key: String, value: String) {
        synchronized(repoLock) { stringMap[key] = value }
        persist()
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = synchronized(repoLock) {
        boolMap[key] ?: defaultValue
    }

    override fun setBoolean(key: String, value: Boolean) {
        synchronized(repoLock) { boolMap[key] = value }
        persist()
    }

    override fun getInt(key: String, defaultValue: Int): Int = synchronized(repoLock) {
        intMap[key] ?: defaultValue
    }

    override fun setInt(key: String, value: Int) {
        synchronized(repoLock) { intMap[key] = value }
        persist()
    }
}
