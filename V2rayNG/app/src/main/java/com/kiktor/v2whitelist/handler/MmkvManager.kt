package com.kiktor.v2whitelist.handler

import com.tencent.mmkv.MMKV
import com.kiktor.v2whitelist.AppConfig.PREF_IS_BOOTED
import com.kiktor.v2whitelist.AppConfig.PREF_ROUTING_RULESET
import com.kiktor.v2whitelist.dto.AssetUrlCache
import com.kiktor.v2whitelist.dto.AssetUrlItem
import com.kiktor.v2whitelist.dto.ProfileItem
import com.kiktor.v2whitelist.dto.RulesetItem
import com.kiktor.v2whitelist.dto.ServerAffiliationInfo
import com.kiktor.v2whitelist.dto.SubscriptionCache
import com.kiktor.v2whitelist.dto.SubscriptionItem
import com.kiktor.v2whitelist.dto.WebDavConfig
import com.kiktor.v2whitelist.util.JsonUtil
import com.kiktor.v2whitelist.util.Utils

object MmkvManager {

    //region private

    //private const val ID_PROFILE_CONFIG = "PROFILE_CONFIG"
    private const val ID_MAIN = "MAIN"
    private const val ID_PROFILE_FULL_CONFIG = "PROFILE_FULL_CONFIG"
    private const val ID_SERVER_RAW = "SERVER_RAW"
    private const val ID_SERVER_AFF = "SERVER_AFF"
    private const val ID_SUB = "SUB"
    private const val ID_ASSET = "ASSET"
    private const val ID_SETTING = "SETTING"
    private const val KEY_SELECTED_SERVER = "SELECTED_SERVER"
    private const val KEY_ANG_CONFIGS = "ANG_CONFIGS"
    private const val KEY_SUB_IDS = "SUB_IDS"
    private const val KEY_WEBDAV_CONFIG = "WEBDAV_CONFIG"
    private const val KEY_BATTERY_ASKED = "BATTERY_ASKED"

    //private val profileStorage by lazy { MMKV.mmkvWithID(ID_PROFILE_CONFIG, MMKV.MULTI_PROCESS_MODE) }
    private val mainStorage by lazy { MMKV.mmkvWithID(ID_MAIN, MMKV.MULTI_PROCESS_MODE) }
    private val profileFullStorage by lazy { MMKV.mmkvWithID(ID_PROFILE_FULL_CONFIG, MMKV.MULTI_PROCESS_MODE) }
    private val serverRawStorage by lazy { MMKV.mmkvWithID(ID_SERVER_RAW, MMKV.MULTI_PROCESS_MODE) }
    private val serverAffStorage by lazy { MMKV.mmkvWithID(ID_SERVER_AFF, MMKV.MULTI_PROCESS_MODE) }
    private val subStorage by lazy { MMKV.mmkvWithID(ID_SUB, MMKV.MULTI_PROCESS_MODE) }
    private val assetStorage by lazy { MMKV.mmkvWithID(ID_ASSET, MMKV.MULTI_PROCESS_MODE) }
    private val settingsStorage by lazy { MMKV.mmkvWithID(ID_SETTING, MMKV.MULTI_PROCESS_MODE) }

    //endregion

    //region Server

    /**
     * Gets the selected server GUID.
     *
     * @return The selected server GUID.
     */
    fun getSelectServer(): String? {
        return mainStorage.decodeString(KEY_SELECTED_SERVER)
    }

    /**
     * Sets the selected server GUID.
     *
     * @param guid The server GUID.
     */
    fun setSelectServer(guid: String) {
        mainStorage.encode(KEY_SELECTED_SERVER, guid)
    }

    /**
     * Encodes the server list.
     *
     * @param serverList The list of server GUIDs.
     */
    fun encodeServerList(serverList: MutableList<String>) {
        mainStorage.encode(KEY_ANG_CONFIGS, JsonUtil.toJson(serverList))
    }

    /**
     * Decodes the server list.
     *
     * @return The list of server GUIDs.
     */
    fun decodeServerList(): MutableList<String> {
        val json = mainStorage.decodeString(KEY_ANG_CONFIGS)
        return if (json.isNullOrBlank()) {
            mutableListOf()
        } else {
            JsonUtil.fromJson(json, Array<String>::class.java)?.toMutableList() ?: mutableListOf()
        }
    }

    /**
     * Decodes the server configuration.
     * Returns null if the entry is missing OR if JSON is corrupted.
     * In both cases the server is NOT automatically removed — caller decides.
     *
     * @param guid The server GUID.
     * @return The server configuration, or null if missing/corrupted.
     */
    fun decodeServerConfig(guid: String): ProfileItem? {
        if (guid.isBlank()) {
            return null
        }
        val json = profileFullStorage.decodeString(guid)
        if (json.isNullOrBlank()) {
            return null
        }
        return JsonUtil.fromJson(json, ProfileItem::class.java)
    }

    /**
     * Проверяет, существует ли запись о сервере в MMKV (не важно, валидный JSON или нет).
     * Используется чтобы отличить "нет записи" от "запись есть, но JSON повреждён".
     *
     * @param guid The server GUID.
     * @return true если запись присутствует в хранилище (даже если JSON битый).
     */
    private fun serverRawJsonExists(guid: String): Boolean {
        if (guid.isBlank()) return false
        val json = profileFullStorage.decodeString(guid)
        return !json.isNullOrBlank()
    }

//    fun decodeProfileConfig(guid: String): ProfileLiteItem? {
//        if (guid.isBlank()) {
//            return null
//        }
//        val json = profileStorage.decodeString(guid)
//        if (json.isNullOrBlank()) {
//            return null
//        }
//        return JsonUtil.fromJson(json, ProfileLiteItem::class.java)
//    }

    /**
     * Encodes the server configuration.
     *
     * @param guid The server GUID.
     * @param config The server configuration.
     * @return The server GUID.
     */
    fun encodeServerConfig(guid: String, config: ProfileItem): String {
        val key = guid.ifBlank { Utils.getUuid() }
        profileFullStorage.encode(key, JsonUtil.toJson(config))
        val serverList = decodeServerList()
        if (!serverList.contains(key)) {
            serverList.add(0, key)
            encodeServerList(serverList)
            if (getSelectServer().isNullOrBlank()) {
                mainStorage.encode(KEY_SELECTED_SERVER, key)
            }
        }
        return key
    }

    /**
     * Encodes multiple server configurations efficiently.
     *
     * @param configs The list of server configurations.
     * @return The list of generated GUIDs.
     */
    fun encodeServerConfigs(configs: List<ProfileItem>): List<String> {
        val serverList = decodeServerList()
        var changed = false
        val guids = mutableListOf<String>()
        
        for (config in configs) {
            val key = Utils.getUuid()
            profileFullStorage.encode(key, JsonUtil.toJson(config))
            guids.add(key)
        }
        
        // Reverse because each add(0) prepends, preserving original list order
        for (key in guids.reversed()) {
            if (!serverList.contains(key)) {
                serverList.add(0, key)
                changed = true
                if (getSelectServer().isNullOrBlank()) {
                    mainStorage.encode(KEY_SELECTED_SERVER, key)
                }
            }
        }
        
        if (changed) {
            encodeServerList(serverList)
        }
        
        return guids
    }

    /**
     * Removes the server configuration.
     *
     * @param guid The server GUID.
     */
    fun removeServer(guid: String) {
        if (guid.isBlank()) {
            return
        }
        if (getSelectServer() == guid) {
            mainStorage.remove(KEY_SELECTED_SERVER)
        }
        val serverList = decodeServerList()
        serverList.remove(guid)
        encodeServerList(serverList)
        profileFullStorage.remove(guid)
        //profileStorage.remove(guid)
        serverAffStorage.remove(guid)
    }

    /**
     * Removes duplicate servers based on address, port and remarks.
     *
     * ВАЖНО: серверы с повреждённым JSON (profile == null, но запись есть в MMKV)
     * НЕ удаляются — они просто пропускаются. Это защищает от потери серверов
     * при обрыве интернета/записи (MalformedJsonException).
     * Удаляются только GUID-ы у которых запись реально отсутствует в MMKV.
     *
     * @return The number of removed servers.
     */
    fun removeDuplicateServer(): Int {
        val serverList = decodeServerList()
        val uniqueServers = mutableSetOf<String>() // key = address:port:password
        val toDelete = mutableListOf<String>()
        val newList = mutableListOf<String>()

        for (guid in serverList) {
            val profile = decodeServerConfig(guid)
            if (profile == null) {
                // Различаем: JSON отсутствует VS JSON повреждён
                if (serverRawJsonExists(guid)) {
                    // Запись в MMKV есть, но JSON битый — СОХРАНЯЕМ сервер, не трогаем
                    android.util.Log.w(
                        com.kiktor.v2whitelist.AppConfig.TAG,
                        "removeDuplicateServer: GUID $guid — JSON повреждён, пропускаем (сервер сохранён)"
                    )
                    newList.add(guid)
                } else {
                    // Записи нет вообще — сиротский GUID, удаляем
                    android.util.Log.w(
                        com.kiktor.v2whitelist.AppConfig.TAG,
                        "removeDuplicateServer: GUID $guid — запись отсутствует в MMKV, удаляем"
                    )
                    toDelete.add(guid)
                }
                continue
            }

            // Уникальность определяем по техническим параметрам: Адрес + Порт + Пароль (ключ)
            val key = "${profile.server}:${profile.serverPort}:${profile.password}"
            if (uniqueServers.contains(key)) {
                toDelete.add(guid)
            } else {
                uniqueServers.add(key)
                newList.add(guid)
            }
        }

        for (guid in toDelete) {
            profileFullStorage.remove(guid)
            serverRawStorage.remove(guid)
            serverAffStorage.remove(guid)
        }

        if (toDelete.isNotEmpty()) {
            encodeServerList(newList)
        }

        return toDelete.size
    }

    /**
     * Removes the server configurations via subscription ID.
     *
     * @param subid The subscription ID.
     */
    fun removeServerViaSubid(subid: String) {
        if (subid.isBlank()) {
            return
        }
        val serverList = decodeServerList()
        var changed = false
        val keysToRemove = mutableListOf<String>()
        
        profileFullStorage.allKeys()?.forEach { key ->
            decodeServerConfig(key)?.let { config ->
                if (config.subscriptionId == subid) {
                    keysToRemove.add(key)
                }
            }
        }
        
        keysToRemove.forEach { key ->
            if (getSelectServer() == key) {
                mainStorage.remove(KEY_SELECTED_SERVER)
            }
            if (serverList.remove(key)) changed = true
            profileFullStorage.remove(key)
            serverAffStorage.remove(key)
        }
        
        if (changed) {
            encodeServerList(serverList)
        }
    }

    /**
     * Decodes the server affiliation information.
     *
     * @param guid The server GUID.
     * @return The server affiliation information.
     */
    fun decodeServerAffiliationInfo(guid: String): ServerAffiliationInfo? {
        if (guid.isBlank()) {
            return null
        }
        val json = serverAffStorage.decodeString(guid)
        if (json.isNullOrBlank()) {
            return null
        }
        return JsonUtil.fromJson(json, ServerAffiliationInfo::class.java)
    }

    /**
     * Encodes the server test delay in milliseconds.
     *
     * @param guid The server GUID.
     * @param testResult The test delay in milliseconds.
     */
    fun encodeServerTestDelayMillis(guid: String, testResult: Long) {
        if (guid.isBlank()) {
            return
        }
        val aff = decodeServerAffiliationInfo(guid) ?: ServerAffiliationInfo()
        aff.testDelayMillis = testResult
        serverAffStorage.encode(guid, JsonUtil.toJson(aff))
    }

    /**
     * Clears all test delay results.
     *
     * @param keys The list of server GUIDs.
     */
    fun clearAllTestDelayResults(keys: List<String>?) {
        keys?.forEach { key ->
            decodeServerAffiliationInfo(key)?.let { aff ->
                aff.testDelayMillis = 0
                serverAffStorage.encode(key, JsonUtil.toJson(aff))
            }
        }
    }

    /**
     * Removes all server configurations.
     *
     * @return The number of server configurations removed.
     */
    fun removeAllServer(): Int {
        val count = profileFullStorage.allKeys()?.count() ?: 0
        mainStorage.clearAll()
        profileFullStorage.clearAll()
        //profileStorage.clearAll()
        serverAffStorage.clearAll()
        return count
    }

    /**
     * Removes invalid server configurations.
     *
     * @param guid The server GUID.
     * @return The number of server configurations removed.
     */
    fun removeInvalidServer(guid: String): Int {
        var count = 0
        if (guid.isNotEmpty()) {
            decodeServerAffiliationInfo(guid)?.let { aff ->
                if (aff.testDelayMillis < 0L) {
                    removeServer(guid)
                    count++
                }
            }
        } else {
            serverAffStorage.allKeys()?.forEach { key ->
                decodeServerAffiliationInfo(key)?.let { aff ->
                    if (aff.testDelayMillis < 0L) {
                        removeServer(key)
                        count++
                    }
                }
            }
        }
        return count
    }

    /**
     * Encodes the raw server configuration.
     *
     * @param guid The server GUID.
     * @param config The raw server configuration.
     */
    fun encodeServerRaw(guid: String, config: String) {
        serverRawStorage.encode(guid, config)
    }

    /**
     * Decodes the raw server configuration.
     *
     * @param guid The server GUID.
     * @return The raw server configuration.
     */
    fun decodeServerRaw(guid: String): String? {
        return serverRawStorage.decodeString(guid)
    }

    //endregion

    //region Subscriptions

    /**
     * Initializes the subscription list.
     */
    private fun initSubsList() {
        val subsList = decodeSubsList()
        if (subsList.isNotEmpty()) {
            return
        }
        subStorage.allKeys()?.forEach { key ->
            subsList.add(key)
        }
        encodeSubsList(subsList)
    }

    /**
     * Decodes the subscriptions.
     *
     * @return The list of subscriptions.
     */
    fun decodeSubscriptions(): List<SubscriptionCache> {
        initSubsList()

        val subscriptions = mutableListOf<SubscriptionCache>()
        decodeSubsList().forEach { key ->
            val json = subStorage.decodeString(key)
            if (!json.isNullOrBlank()) {
                val item = JsonUtil.fromJson(json, SubscriptionItem::class.java)?: SubscriptionItem()
                subscriptions.add(SubscriptionCache(key, item))
            }
        }
        return subscriptions
    }

    /**
     * Removes the subscription.
     *
     * @param subid The subscription ID.
     */
    fun removeSubscription(subid: String) {
        subStorage.remove(subid)
        val subsList = decodeSubsList()
        subsList.remove(subid)
        encodeSubsList(subsList)

        removeServerViaSubid(subid)
    }

    /**
     * Encodes the subscription.
     *
     * @param guid The subscription GUID.
     * @param subItem The subscription item.
     */
    fun encodeSubscription(guid: String, subItem: SubscriptionItem) {
        val key = guid.ifBlank { Utils.getUuid() }
        subStorage.encode(key, JsonUtil.toJson(subItem))

        val subsList = decodeSubsList()
        if (!subsList.contains(key)) {
            subsList.add(key)
            encodeSubsList(subsList)
        }
    }

    /**
     * Decodes the subscription.
     *
     * @param subscriptionId The subscription ID.
     * @return The subscription item.
     */
    fun decodeSubscription(subscriptionId: String): SubscriptionItem? {
        val json = subStorage.decodeString(subscriptionId) ?: return null
        return JsonUtil.fromJson(json, SubscriptionItem::class.java)
    }

    /**
     * Encodes the subscription list.
     *
     * @param subsList The list of subscription IDs.
     */
    fun encodeSubsList(subsList: MutableList<String>) {
        mainStorage.encode(KEY_SUB_IDS, JsonUtil.toJson(subsList))
    }

    /**
     * Decodes the subscription list.
     *
     * @return The list of subscription IDs.
     */
    fun decodeSubsList(): MutableList<String> {
        val json = mainStorage.decodeString(KEY_SUB_IDS)
        return if (json.isNullOrBlank()) {
            mutableListOf()
        } else {
            JsonUtil.fromJson(json, Array<String>::class.java)?.toMutableList()?: mutableListOf()
        }
    }

    //endregion

    //region Asset

    /**
     * Decodes the asset URLs.
     *
     * @return The list of asset URLs.
     */
    fun decodeAssetUrls(): List<AssetUrlCache> {
        val assetUrlItems = mutableListOf<AssetUrlCache>()
        assetStorage.allKeys()?.forEach { key ->
            val json = assetStorage.decodeString(key)
            if (!json.isNullOrBlank()) {
                val item = JsonUtil.fromJson(json, AssetUrlItem::class.java)?: AssetUrlItem()
                assetUrlItems.add(AssetUrlCache(key, item))
            }
        }
        return assetUrlItems.sortedBy { it.assetUrl.addedTime }
    }

    /**
     * Removes the asset URL.
     *
     * @param assetid The asset ID.
     */
    fun removeAssetUrl(assetid: String) {
        assetStorage.remove(assetid)
    }

    /**
     * Encodes the asset.
     *
     * @param assetid The asset ID.
     * @param assetItem The asset item.
     */
    fun encodeAsset(assetid: String, assetItem: AssetUrlItem) {
        val key = assetid.ifBlank { Utils.getUuid() }
        assetStorage.encode(key, JsonUtil.toJson(assetItem))
    }

    /**
     * Decodes the asset.
     *
     * @param assetid The asset ID.
     * @return The asset item.
     */
    fun decodeAsset(assetid: String): AssetUrlItem? {
        val json = assetStorage.decodeString(assetid) ?: return null
        return JsonUtil.fromJson(json, AssetUrlItem::class.java)
    }

    //endregion

    //region Routing

    /**
     * Decodes the routing rulesets.
     *
     * @return The list of routing rulesets.
     */
    fun decodeRoutingRulesets(): MutableList<RulesetItem>? {
        val ruleset = settingsStorage.decodeString(PREF_ROUTING_RULESET)
        if (ruleset.isNullOrEmpty()) return null
        return JsonUtil.fromJson(ruleset, Array<RulesetItem>::class.java)?.toMutableList()?: mutableListOf()
    }

    /**
     * Encodes the routing rulesets.
     *
     * @param rulesetList The list of routing rulesets.
     */
    fun encodeRoutingRulesets(rulesetList: MutableList<RulesetItem>?) {
        if (rulesetList.isNullOrEmpty())
            encodeSettings(PREF_ROUTING_RULESET, "")
        else
            encodeSettings(PREF_ROUTING_RULESET, JsonUtil.toJson(rulesetList))
    }

    //endregion

    //region settings
    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: String?): Boolean {
        return settingsStorage.encode(key, value)
    }

    fun containsSettings(key: String): Boolean {
        return settingsStorage.containsKey(key)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Int): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Long): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Float): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Boolean): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: MutableSet<String>): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Decodes the settings string.
     *
     * @param key The settings key.
     * @return The settings value.
     */
    fun decodeSettingsString(key: String): String? {
        return settingsStorage.decodeString(key)
    }

    /**
     * Decodes the settings string.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsString(key: String, defaultValue: String?): String? {
        return settingsStorage.decodeString(key, defaultValue)
    }

    /**
     * Decodes the settings integer.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsInt(key: String, defaultValue: Int): Int {
        return settingsStorage.decodeInt(key, defaultValue)
    }

    /**
     * Decodes the settings long.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsLong(key: String, defaultValue: Long): Long {
        return settingsStorage.decodeLong(key, defaultValue)
    }

    /**
     * Decodes the settings float.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsFloat(key: String, defaultValue: Float): Float {
        return settingsStorage.decodeFloat(key, defaultValue)
    }

    /**
     * Decodes the settings boolean.
     *
     * @param key The settings key.
     * @return The settings value.
     */
    fun decodeSettingsBool(key: String): Boolean {
        return settingsStorage.decodeBool(key, false)
    }

    /**
     * Decodes the settings boolean.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsBool(key: String, defaultValue: Boolean): Boolean {
        return settingsStorage.decodeBool(key, defaultValue)
    }

    /**
     * Decodes the settings string set.
     *
     * @param key The settings key.
     * @return The settings value.
     */
    fun decodeSettingsStringSet(key: String): MutableSet<String>? {
        return settingsStorage.decodeStringSet(key)
    }

    /**
     * Gets all settings as a map.
     */
    fun getAllSettings(): Map<String, *>? {
        return settingsStorage.all
    }

    /**
     * Imports a map of settings.
     */
    fun importSettings(settings: Map<String, *>) {
        for ((key, value) in settings) {
            when (value) {
                is String -> encodeSettings(key, value)
                is Boolean -> encodeSettings(key, value)
                is Int -> encodeSettings(key, value)
                is Long -> encodeSettings(key, value)
                is Float -> encodeSettings(key, value)
                is Double -> encodeSettings(key, value.toFloat()) // GSON sometimes parses numbers as Double
                is Number -> encodeSettings(key, value.toDouble().toLong()) // Try fallback for generic Numbers
                is MutableSet<*> -> encodeSettings(key, value as MutableSet<String>)
            }
        }
    }

    /**
     * Encodes the start on boot setting.
     *
     * @param startOnBoot Whether to start on boot.
     */
    fun encodeStartOnBoot(startOnBoot: Boolean) {
        encodeSettings(PREF_IS_BOOTED, startOnBoot)
    }

    /**
     * Decodes the start on boot setting.
     *
     * @return Whether to start on boot.
     */
    fun decodeStartOnBoot(): Boolean {
        return decodeSettingsBool(PREF_IS_BOOTED, false)
    }

    fun isV2wCoreEnabled(): Boolean {
        return decodeSettingsBool("pref_v2w_core_enabled", false)
    }

    fun getV2wCoreBatchSize(): Int {
        val sizeStr = decodeSettingsString("pref_v2w_core_batch_size", "100")
        return sizeStr?.toIntOrNull() ?: 100
    }

    fun getV2wCoreConcurrency(): Int {
        val concStr = decodeSettingsString("pref_v2w_core_concurrency", "20")
        return concStr?.toIntOrNull() ?: 20
    }

    //endregion

    //region Last Server Cache

    /**
     * Сохраняет GUID последнего успешно подключённого сервера
     * и текущий timestamp (момент включения VPN).
     */
    fun saveLastConnectedServer(guid: String) {
        settingsStorage.encode(com.kiktor.v2whitelist.AppConfig.PREF_LAST_CONNECTED_SERVER, guid)
        settingsStorage.encode(com.kiktor.v2whitelist.AppConfig.PREF_LAST_CONNECT_TIME, System.currentTimeMillis())
    }

    /**
     * Возвращает GUID кэшированного сервера, если он ещё валиден
     * (с момента последнего включения VPN прошло меньше LAST_SERVER_CACHE_TTL_MS),
     * И если этот сервер всё ещё существует в списке.
     * Иначе возвращает null — нужен полный SmartConnect.
     */
    fun getValidLastServer(): String? {
        val guid = settingsStorage.decodeString(com.kiktor.v2whitelist.AppConfig.PREF_LAST_CONNECTED_SERVER)
        if (guid.isNullOrBlank()) return null

        val savedTime = settingsStorage.decodeLong(com.kiktor.v2whitelist.AppConfig.PREF_LAST_CONNECT_TIME, 0L)
        val elapsed = System.currentTimeMillis() - savedTime
        if (elapsed > com.kiktor.v2whitelist.AppConfig.LAST_SERVER_CACHE_TTL_MS) {
            android.util.Log.i(
                com.kiktor.v2whitelist.AppConfig.TAG,
                "LastServerCache: кэш устарел (${elapsed / 1000 / 60} мин), нужен SmartConnect"
            )
            return null
        }

        // Проверяем, что сервер ещё существует
        val profile = decodeServerConfig(guid)
        if (profile == null) {
            android.util.Log.w(com.kiktor.v2whitelist.AppConfig.TAG, "LastServerCache: сервер $guid не найден, кэш невалиден")
            return null
        }

        android.util.Log.i(
            com.kiktor.v2whitelist.AppConfig.TAG,
            "LastServerCache: кэш валиден (${elapsed / 1000}с назад) → ${profile.remarks}"
        )
        return guid
    }

    /**
     * Сбрасывает кэш последнего сервера (например, при обновлении подписки).
     */
    fun clearLastConnectedServer() {
        settingsStorage.remove(com.kiktor.v2whitelist.AppConfig.PREF_LAST_CONNECTED_SERVER)
        settingsStorage.remove(com.kiktor.v2whitelist.AppConfig.PREF_LAST_CONNECT_TIME)
    }

    /**
     * Возвращает список VIP-серверов (кэш проверенных серверов, до 5 штук).
     */
    fun getVipCache(): MutableList<String> {
        val json = settingsStorage.decodeString(com.kiktor.v2whitelist.AppConfig.PREF_VIP_CACHE)
        return if (json.isNullOrBlank()) {
            mutableListOf()
        } else {
            JsonUtil.fromJson(json, Array<String>::class.java)?.toMutableList() ?: mutableListOf()
        }
    }

    /**
     * Добавляет сервер в VIP-кэш (в начало, LRU).
     * Если превышает 5, удаляет самые старые.
     */
    fun addVipServer(guid: String) {
        if (guid.isBlank()) return
        val cache = getVipCache()
        // Удаляем если уже есть, чтобы переместить в начало
        cache.remove(guid)
        cache.add(0, guid)
        // Обрезаем до 5 элементов
        while (cache.size > 5) {
            cache.removeAt(cache.size - 1)
        }
        settingsStorage.encode(com.kiktor.v2whitelist.AppConfig.PREF_VIP_CACHE, JsonUtil.toJson(cache))
    }

    /**
     * Удаляет сервер из VIP-кэша.
     */
    fun removeVipServer(guid: String) {
        if (guid.isBlank()) return
        val cache = getVipCache()
        if (cache.remove(guid)) {
            settingsStorage.encode(com.kiktor.v2whitelist.AppConfig.PREF_VIP_CACHE, JsonUtil.toJson(cache))
        }
    }

    /**
     * Очищает весь VIP-кэш.
     */
    fun clearVipCache() {
        settingsStorage.remove(com.kiktor.v2whitelist.AppConfig.PREF_VIP_CACHE)
    }

    /**
     * Полностью заменяет VIP-кэш на новый список GUID-ов.
     * Используется для ремаппинга после обновления подписок,
     * когда старые GUID-ы умирают и заменяются новыми.
     */
    fun replaceVipCache(guids: List<String>) {
        if (guids.isEmpty()) {
            clearVipCache()
            return
        }
        val trimmed = guids.take(5)
        settingsStorage.encode(com.kiktor.v2whitelist.AppConfig.PREF_VIP_CACHE, JsonUtil.toJson(trimmed))
    }

    /**
     * Обновляет GUID в LastServerCache БЕЗ сброса timestamp.
     * Нужно после обновления подписок: сервер тот же, но GUID новый.
     */
    fun remapLastConnectedServer(newGuid: String) {
        settingsStorage.encode(com.kiktor.v2whitelist.AppConfig.PREF_LAST_CONNECTED_SERVER, newGuid)
        // НЕ трогаем PREF_LAST_CONNECT_TIME — таймстамп от реального подключения
    }

    //endregion

    //region WebDAV

    /**
     * Encodes the WebDAV config as JSON into storage.
     */
    fun encodeWebDavConfig(config: WebDavConfig): Boolean {
        return mainStorage.encode(KEY_WEBDAV_CONFIG, JsonUtil.toJson(config))
    }

    /**
     * Decodes the WebDAV config from storage.
     */
    fun decodeWebDavConfig(): WebDavConfig? {
        val json = mainStorage.decodeString(KEY_WEBDAV_CONFIG) ?: return null
        return JsonUtil.fromJson(json, WebDavConfig::class.java)
    }

    //endregion

    fun setBatteryOptimizationAsked(asked: Boolean) {
        settingsStorage.encode(KEY_BATTERY_ASKED, asked)
    }

    fun getBatteryOptimizationAsked(): Boolean {
        return settingsStorage.decodeBool(KEY_BATTERY_ASKED, false)
    }
}
