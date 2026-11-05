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
        val allGuids = MmkvManager.decodeServerList().toMutableList()
        if (!allGuids.contains(guid)) {
            allGuids.add(0, guid) // Добавляем наверх списка
        }
        MmkvManager.encodeServerConfig(guid, profile)
        MmkvManager.encodeServerList(allGuids)
        Log.i(AppConfig.TAG, "Personal server added: ${profile.remarks} ($guid)")
        return guid
    }

    /**
     * Добавляет личный сервер напрямую из объекта ProfileItem.
     */
    fun addPersonalServer(profile: ProfileItem): String {
        profile.subscriptionId = PersonalServersConfig.SUB_ID_PERSONAL_DIRECT
        val guid = UUID.randomUUID().toString()
        val allGuids = MmkvManager.decodeServerList().toMutableList()
        if (!allGuids.contains(guid)) {
            allGuids.add(0, guid)
        }
        MmkvManager.encodeServerConfig(guid, profile)
        MmkvManager.encodeServerList(allGuids)
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

        val profile = MmkvManager.decodeServerConfig(guid) ?: return
        val uri = UniversalConfigParser.toUri(profile) ?: return

        saveConfig(config.copy(exitNodeServerGuid = guid, exitNodeEnabled = true))
        MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_ENABLED, true)
        MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_URL, uri)
        MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_SELECTED_NAME, profile.remarks)
        Log.i(AppConfig.TAG, "Personal exit node set to: ${profile.remarks}")
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
}

