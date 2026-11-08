package com.kiktor.v2whitelist.handler

import android.content.Context
import android.util.Log
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.core.config.UniversalConfigParser
import com.kiktor.v2whitelist.core.model.NetworkBindingRule
import com.kiktor.v2whitelist.core.model.PersonalServersConfig
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.dto.SubscriptionItem
import com.kiktor.v2whitelist.dto.SubscriptionCache
import com.kiktor.v2whitelist.util.JsonUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

object PersonalServersManager {

    /**
     * Загружает текущую конфигурацию личных серверов из MMKV.
     */
    fun getConfig(): PersonalServersConfig {
        val json = MmkvManager.decodeSettingsString(AppConfig.PREF_PERSONAL_SERVERS_CONFIG)
        if (json.isNullOrBlank()) {
            return PersonalServersConfig()
        }
        return try {
            JsonUtil.fromJson(json, PersonalServersConfig::class.java) ?: PersonalServersConfig()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to decode PersonalServersConfig: ${e.message}")
            PersonalServersConfig()
        }
    }

    /**
     * Сохраняет конфигурацию личных серверов в MMKV.
     */
    fun saveConfig(config: PersonalServersConfig) {
        val json = JsonUtil.toJson(config)
        MmkvManager.encodeSettings(AppConfig.PREF_PERSONAL_SERVERS_CONFIG, json)
    }

    /**
     * Возвращает список всех личных серверов (добавленных вручную или через личные подписки).
     */
    fun getPersonalServers(): List<Pair<String, ProfileItem>> {
        val allGuids = MmkvManager.decodeServerList()
        val result = mutableListOf<Pair<String, ProfileItem>>()
        for (guid in allGuids) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            if (PersonalServersConfig.isPersonalServer(profile.subscriptionId)) {
                result.add(Pair(guid, profile))
            }
        }
        return result
    }

    /**
     * Добавляет один личный сервер из распознанной ссылки конфигурации (VLESS, VMess, Trojan, SS, Hy2...).
     * @return GUID добавленного сервера или null при ошибке парсинга.
     */
    fun addPersonalServerFromUri(uri: String, customRemarks: String? = null): String? {
        val profile = UniversalConfigParser.parse(uri) ?: return null
        if (!customRemarks.isNullOrBlank()) {
            profile.remarks = customRemarks.trim()
        }
        profile.subscriptionId = PersonalServersConfig.SUB_ID_PERSONAL_DIRECT

        val guid = UUID.randomUUID().toString()
        MmkvManager.encodeServerConfig(guid, profile)
        Log.i(AppConfig.TAG, "Personal server added: ${profile.remarks} ($guid)")
        return guid
    }

    /**
     * Добавляет личный сервер напрямую из объекта ProfileItem.
     */
    fun addPersonalServer(profile: ProfileItem): String {
        profile.subscriptionId = PersonalServersConfig.SUB_ID_PERSONAL_DIRECT
        val guid = UUID.randomUUID().toString()
        MmkvManager.encodeServerConfig(guid, profile)
        return guid
    }

    /**
     * Удаляет личный сервер и сбрасывает связанные с ним указатели выбора и конечного узла.
     */
    fun removePersonalServer(guid: String) {
        MmkvManager.removeServer(guid)

        val config = getConfig()
        var updated = false
        var newSelected = config.selectedServerGuid
        var newExit = config.exitNodeServerGuid
        var exitEnabled = config.exitNodeEnabled

        if (config.selectedServerGuid == guid) {
            newSelected = null
            updated = true
        }
        if (config.exitNodeServerGuid == guid) {
            newExit = null
            exitEnabled = false
            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_ENABLED, false)
            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_URL, "")
            updated = true
        }

        if (updated) {
            saveConfig(config.copy(
                selectedServerGuid = newSelected,
                exitNodeServerGuid = newExit,
                exitNodeEnabled = exitEnabled
            ))
        }
    }

    /**
     * Назначает или отменяет назначение личного сервера в качестве Конечного узла (Exit Node).
     * Строго проверяет принадлежность сервера к пулу доверенных серверов.
     */
    fun setExitNode(guid: String?) {
        val config = getConfig()
        if (guid.isNullOrBlank()) {
            saveConfig(config.copy(exitNodeServerGuid = null, exitNodeEnabled = false))
            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_ENABLED, false)
            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_URL, "")
            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_SELECTED_NAME, "")
            return
        }

        val profile = MmkvManager.decodeServerConfig(guid)
        if (profile == null || !PersonalServersConfig.isPersonalServer(profile.subscriptionId)) {
            Log.w(AppConfig.TAG, "Rejected non-personal server as exit node: guid=$guid, subId=${profile?.subscriptionId}")
            saveConfig(config.copy(exitNodeServerGuid = null, exitNodeEnabled = false))
            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_ENABLED, false)
            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_URL, "")
            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_SELECTED_NAME, "")
            return
        }

        val uri = UniversalConfigParser.toUri(profile) ?: return

        saveConfig(config.copy(exitNodeServerGuid = guid, exitNodeEnabled = true))
        MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_ENABLED, true)
        MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_URL, uri)
        MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_SELECTED_NAME, profile.remarks)
        Log.i(AppConfig.TAG, "Personal exit node set to: ${profile.remarks}")
    }

    /**
     * Проверяет и синхронизирует настройки конечного узла (Self-healing).
     * Если в PREF_CUSTOM_ENDPOINT_URL оказался сервер из публичной подписки или удаленный узел,
     * автоматически очищает настройку и выключает Proxy Chaining.
     */
    fun sanitizeExitNode() {
        val config = getConfig()
        val mmkvEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_CUSTOM_ENDPOINT_ENABLED, false)
        val mmkvUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_ENDPOINT_URL)

        val exitGuid = config.exitNodeServerGuid
        if (!exitGuid.isNullOrBlank()) {
            val exitProfile = MmkvManager.decodeServerConfig(exitGuid)
            if (exitProfile == null || !PersonalServersConfig.isPersonalServer(exitProfile.subscriptionId)) {
                Log.w(AppConfig.TAG, "sanitizeExitNode: clearing invalid or non-personal exitNodeServerGuid=$exitGuid")
                saveConfig(config.copy(exitNodeServerGuid = null, exitNodeEnabled = false))
                MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_ENABLED, false)
                MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_URL, "")
                MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_SELECTED_NAME, "")
                return
            }

            // Синхронизируем MMKV с флагом exitNodeEnabled из PersonalServersConfig
            if (config.exitNodeEnabled) {
                val uri = UniversalConfigParser.toUri(exitProfile)
                if (!uri.isNullOrBlank()) {
                    if (!mmkvEnabled) MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_ENABLED, true)
                    if (mmkvUrl != uri) MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_URL, uri)
                    MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_SELECTED_NAME, exitProfile.remarks)
                }
            } else {
                if (mmkvEnabled) {
                    MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_ENABLED, false)
                }
            }
            return
        }

        // Если exitNodeServerGuid не задан, но в MMKV остался старый URL или включен флаг
        if (mmkvEnabled || !mmkvUrl.isNullOrBlank()) {
            val personalServers = getPersonalServers()
            val matchedPersonal = if (!mmkvUrl.isNullOrBlank()) {
                val parsed = AngConfigManager.identifyConfigType(mmkvUrl)
                personalServers.firstOrNull { (_, p) ->
                    UniversalConfigParser.toUri(p) == mmkvUrl ||
                        (parsed != null && !p.server.isNullOrBlank() && p.server == parsed.server && p.serverPort == parsed.serverPort)
                }
            } else null

            if (matchedPersonal != null) {
                saveConfig(config.copy(exitNodeServerGuid = matchedPersonal.first, exitNodeEnabled = mmkvEnabled))
                MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_SELECTED_NAME, matchedPersonal.second.remarks)
            } else {
                Log.w(AppConfig.TAG, "sanitizeExitNode: clearing orphan/public custom endpoint from MMKV")
                MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_ENABLED, false)
                MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_URL, "")
                MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_SELECTED_NAME, "")
                if (config.exitNodeEnabled) {
                    saveConfig(config.copy(exitNodeServerGuid = null, exitNodeEnabled = false))
                }
            }
        }
    }

    /**
     * Выбирает активный личный сервер (null = автопоиск среди личных).
     */
    fun setSelectedServer(guid: String?) {
        val config = getConfig()
        saveConfig(config.copy(selectedServerGuid = guid))
    }

    /**
     * Включает или выключает использование раздела Личных серверов.
     */
    fun setEnabled(enabled: Boolean) {
        val config = getConfig()
        saveConfig(config.copy(enabled = enabled))
    }

    /**
     * Обновляет тумблеры настроек.
     */
    fun updateSettings(
        ignoreExitNode: Boolean = true,
        autoConnect: Boolean = true,
        fallbackToPublic: Boolean = true,
        wifiRule: NetworkBindingRule = NetworkBindingRule.USE_PERSONAL_AUTO,
        cellularRule: NetworkBindingRule = NetworkBindingRule.USE_PUBLIC_SUBS
    ) {
        val config = getConfig()
        saveConfig(config.copy(
            ignoreExitNodeForPersonal = ignoreExitNode,
            autoConnectOnStartup = autoConnect,
            fallbackToPublicOnFailure = fallbackToPublic,
            wifiRule = wifiRule,
            cellularRule = cellularRule
        ))
    }

    /**
     * Добавляет личную подписку по URL.
     */
    fun addPersonalSubscription(url: String, remarks: String): String {
        val subId = "${PersonalServersConfig.SUB_ID_PERSONAL_PREFIX}${UUID.randomUUID().toString().take(8)}"
        val subItem = SubscriptionItem().apply {
            this.remarks = remarks.ifBlank { "Личная подписка" }
            this.url = url.trim()
            this.enabled = true
        }
        MmkvManager.encodeSubscription(subId, subItem)
        return subId
    }

    /**
     * Возвращает список всех личных подписок.
     */
    fun getPersonalSubscriptions(): List<SubscriptionCache> {
        return MmkvManager.decodeSubscriptions().filter {
            it.guid.startsWith(PersonalServersConfig.SUB_ID_PERSONAL_PREFIX)
        }
    }

    /**
     * Загружает и обновляет серверы для конкретной доверенной подписки.
     */
    suspend fun fetchSubscription(subId: String): Int = withContext(Dispatchers.IO) {
        val subItem = MmkvManager.decodeSubscription(subId) ?: return@withContext 0
        val cache = SubscriptionCache(subId, subItem)
        val socksPort = if (V2RayServiceManager.isRunning()) SettingsManager.getSocksPort() else 0
        AngConfigManager.updateConfigViaSub(cache, socksPort = socksPort, sequential = false)
    }

    /**
     * Создает доверенную подписку и сразу скачивает её серверы.
     */
    suspend fun addAndFetchPersonalSubscription(url: String, remarks: String): Pair<String, Int> = withContext(Dispatchers.IO) {
        val subId = addPersonalSubscription(url, remarks)
        val count = fetchSubscription(subId)
        Pair(subId, count)
    }

    /**
     * Обновляет все доверенные подписки.
     */
    suspend fun updateAllPersonalSubscriptions(): Int = withContext(Dispatchers.IO) {
        val subs = getPersonalSubscriptions()
        var total = 0
        for (sub in subs) {
            total += fetchSubscription(sub.guid)
        }
        total
    }

    /**
     * Удаляет доверенную подписку и связанные с ней серверы.
     */
    fun removePersonalSubscription(subId: String) {
        val config = getConfig()
        val selectedProfile = config.selectedServerGuid?.let { MmkvManager.decodeServerConfig(it) }
        val exitProfile = config.exitNodeServerGuid?.let { MmkvManager.decodeServerConfig(it) }

        var newSelected = config.selectedServerGuid
        var newExit = config.exitNodeServerGuid
        var newExitEnabled = config.exitNodeEnabled
        var updated = false

        if (selectedProfile?.subscriptionId == subId) {
            newSelected = null
            updated = true
        }
        if (exitProfile?.subscriptionId == subId) {
            newExit = null
            newExitEnabled = false
            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_ENABLED, false)
            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_URL, "")
            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_SELECTED_NAME, "")
            updated = true
        }

        MmkvManager.removeSubscription(subId)
        MmkvManager.removeServerViaSubid(subId)

        if (updated) {
            saveConfig(config.copy(
                selectedServerGuid = newSelected,
                exitNodeServerGuid = newExit,
                exitNodeEnabled = newExitEnabled
            ))
        }
    }

    /**
     * Импортирует батч серверов из текста (несколько строк или base64).
     */
    fun importBatchPersonalServers(text: String): Int {
        val decoded = com.kiktor.v2whitelist.util.Utils.decode(text)
        val raw = if (!decoded.isNullOrBlank()) decoded else text
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return 0

        val newProfiles = mutableListOf<ProfileItem>()
        for (line in lines) {
            val profile = UniversalConfigParser.parse(line) ?: continue
            profile.subscriptionId = PersonalServersConfig.SUB_ID_PERSONAL_DIRECT
            newProfiles.add(profile)
        }
        if (newProfiles.isEmpty()) return 0

        var count = 0
        for (profile in newProfiles) {
            val guid = UUID.randomUUID().toString()
            MmkvManager.encodeServerConfig(guid, profile)
            count++
        }
        Log.i(AppConfig.TAG, "Batch imported $count personal servers")
        return count
    }
}

