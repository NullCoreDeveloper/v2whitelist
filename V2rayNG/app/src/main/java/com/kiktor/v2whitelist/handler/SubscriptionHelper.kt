package com.kiktor.v2whitelist.handler

import android.content.Context
import android.util.Log
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.dto.SubscriptionCache
import com.kiktor.v2whitelist.dto.SubscriptionItem
import com.kiktor.v2whitelist.util.MessageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object SubscriptionHelper {

    const val SUBSCRIPTION_ID = "v2whitelist_hardcoded_sub"

    /** Дата-класс для JSON-десериализации кастомных подписок */
    data class CustomSubData(
        val id: String = "",
        var name: String = "",
        var url: String = "",
        var filter: String = "",
        var groupRegex: String = "",
        var enabled: Boolean = true,
        var sharePercent: Int? = null
    )

    /**
     * Pre-populates the zieng2/wl subscription with mirrors on first launch,
     * and sets up all custom subscriptions.
     */
    suspend fun checkAndSetupSubscription(context: Context) = withContext(Dispatchers.IO) {
        // Мигрируем серверы из старой хардкод-подписки (матрешки), чтобы не оставить пользователя без связи
        val subscriptions = MmkvManager.decodeSubscriptions()
        if (subscriptions.any { it.guid == SUBSCRIPTION_ID }) {
            Log.d(AppConfig.TAG, "Migrating old hardcoded subscription servers to the new custom sub")
            val newSubId = "custom_sub_def_zieng2"
            val serverList = MmkvManager.decodeServerList()
            var migratedCount = 0
            for (guid in serverList) {
                val profile = MmkvManager.decodeServerConfig(guid)
                if (profile != null && profile.subscriptionId == SUBSCRIPTION_ID) {
                    profile.subscriptionId = newSubId
                    MmkvManager.encodeServerConfig(guid, profile)
                    migratedCount++
                }
            }
            Log.d(AppConfig.TAG, "Migrated $migratedCount servers. Removing old subscription object.")
            
            MmkvManager.removeSubscription(SUBSCRIPTION_ID)
            MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_RELOAD_SERVER_LIST, "")
        }

        val customSubs = loadCustomSubs().toMutableList()
        var changed = false
        for (defaultSub in DefaultSubscriptions.PREPOPULATED_SUBS) {
            val existing = customSubs.find { it.id == defaultSub.id || it.name == defaultSub.name }
            if (existing == null) {
                Log.d(AppConfig.TAG, "Pre-populating subscription: ${defaultSub.name}")
                customSubs.add(defaultSub)
                changed = true
            } else {
                if (existing.groupRegex != defaultSub.groupRegex) {
                    Log.d(AppConfig.TAG, "Updating groupRegex for ${defaultSub.name}: '${existing.groupRegex}' -> '${defaultSub.groupRegex}'")
                    existing.groupRegex = defaultSub.groupRegex
                    changed = true
                }
                if (existing.url != defaultSub.url) {
                    existing.url = defaultSub.url
                    changed = true
                }
            }
        }
        if (changed) {
            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_SUB_URLS, com.kiktor.v2whitelist.util.JsonUtil.toJson(customSubs))
        }
        MmkvManager.encodeSettings("pref_defaults_added_v1", true)

        // Обработка кастомных подписок (zieng2/wl теперь обычная кастомная подписка)
        setupCustomSubscriptions(context)
    }

    /**
     * Настраивает кастомные подписки из MMKV.
     */
    private suspend fun setupCustomSubscriptions(context: Context) {
        val customSubs = loadCustomSubs()
        for (sub in customSubs.filter { it.enabled }) {
            val subId = "custom_sub_${sub.id}"
            val subscriptions = MmkvManager.decodeSubscriptions()
            val existing = subscriptions.find { it.guid == subId }

            if (existing == null) {
                val subItem = SubscriptionItem().apply {
                    remarks = sub.name
                    url = sub.url
                    filter = sub.filter
                    enabled = true
                    sharePercent = sub.sharePercent
                }
                MmkvManager.encodeSubscription(subId, subItem)
                AngConfigManager.updateConfigViaSub(SubscriptionCache(subId, subItem))
            } else {
                val subItem = existing.subscription
                if (subItem.url != sub.url || subItem.filter != sub.filter || subItem.remarks != sub.name || subItem.sharePercent != sub.sharePercent) {
                    subItem.url = sub.url
                    subItem.remarks = sub.name
                    subItem.filter = sub.filter
                    subItem.sharePercent = sub.sharePercent
                    MmkvManager.encodeSubscription(subId, subItem)
                    // URL или фильтр изменились — перезагружаем серверы немедленно
                    AngConfigManager.updateConfigViaSub(SubscriptionCache(subId, subItem))
                }
            }
        }
    }

    /**
     * Загружает кастомные подписки из MMKV.
     */
    fun loadCustomSubs(): List<CustomSubData> {
        val json = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_SUB_URLS)
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            com.kiktor.v2whitelist.util.JsonUtil.fromJson(json, Array<CustomSubData>::class.java)?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Force updates all active subscriptions.
     * Сбрасывает кэш последнего сервера — после обновления старый GUID может не существовать.
     * @param sequential если true — последовательная подкачка (фоновый воркер),
     *                    false — параллельная гонка зеркал (UI).
     */
    suspend fun updateSubscription(context: Context, isStartup: Boolean = false, sequential: Boolean = false) = withContext(Dispatchers.IO) {
        // ══════════════════════════════════════════════════════════════════════
        // СНИМОК КЭШЕЙ ПЕРЕД ОБНОВЛЕНИЕМ
        // parseBatchConfig() удаляет ВСЕ старые серверы и создаёт НОВЫЕ GUID-ы.
        // Поэтому нужно запомнить identity серверов (server+port+remarks),
        // чтобы потом найти их новые GUID-ы и ремаппить кэши.
        // ══════════════════════════════════════════════════════════════════════
        data class ServerIdentity(val server: String?, val port: String?, val remarks: String)

        val lastServerGuid = MmkvManager.getValidLastServer()
        val lastServerIdentity = lastServerGuid?.let { guid ->
            MmkvManager.decodeServerConfig(guid)?.let { p ->
                ServerIdentity(p.server, p.serverPort, p.remarks)
            }
        }

        val vipGuids = MmkvManager.getVipCache()
        val vipIdentities = vipGuids.mapNotNull { guid ->
            MmkvManager.decodeServerConfig(guid)?.let { p ->
                ServerIdentity(p.server, p.serverPort, p.remarks)
            }
        }

        if (vipIdentities.isNotEmpty()) {
            Log.i(AppConfig.TAG, "updateSubscription: снимок VIP-кэша: ${vipIdentities.size} серверов (${vipIdentities.joinToString { it.remarks }})")
        }

        val candidateSocksPort = SettingsManager.getSocksPort()
        var socksPort = 0
        var vpnStarted = false
        
        // Ожидаем запуска прокси (дольше при старте приложения, так как он может запускаться SmartConnect'ом)
        val waitLoops = if (isStartup) 8 else 1
        for (i in 0 until waitLoops) {
            if (SmartConnectManager.isProxyRunning(candidateSocksPort)) {
                socksPort = candidateSocksPort
                vpnStarted = true
                break
            }
            if (i < waitLoops - 1) delay(1000)
        }
        
        Log.i(AppConfig.TAG, "updateSubscription: VPN=$vpnStarted, socksPort=$socksPort, sequential=$sequential")

        // Ensure base subscriptions are initialized if this is the first launch
        checkAndSetupSubscription(context)

        // Обновляем кастомные подписки
        val customSubs = loadCustomSubs()
        for (sub in customSubs.filter { it.enabled }) {
            val subId = "custom_sub_${sub.id}"
            val subscriptions = MmkvManager.decodeSubscriptions()
            val existing = subscriptions.find { it.guid == subId }
            if (existing != null) {
                existing.subscription.enabled = true
                Log.d(AppConfig.TAG, "Manually updating custom subscription: ${sub.name}")
                AngConfigManager.updateConfigViaSub(existing, socksPort, sequential)
            } else {
                // Создаём если нет
                val subItem = SubscriptionItem().apply {
                    remarks = sub.name
                    url = sub.url
                    enabled = true
                }
                MmkvManager.encodeSubscription(subId, subItem)
                AngConfigManager.updateConfigViaSub(SubscriptionCache(subId, subItem), socksPort, sequential)
            }
        }

        // Обновляем обычные подписки (добавленные пользователем вручную)
        val allSubscriptions = MmkvManager.decodeSubscriptions()
        val regularSubs = allSubscriptions.filter { !it.guid.startsWith("custom_sub_") && it.subscription.enabled }
        for (sub in regularSubs) {
            Log.d(AppConfig.TAG, "Manually updating regular subscription: ${sub.subscription.remarks}")
            AngConfigManager.updateConfigViaSub(sub, socksPort, sequential)
        }

        // ══════════════════════════════════════════════════════════════════════
        // РЕМАППИНГ КЭШЕЙ ПОСЛЕ ОБНОВЛЕНИЯ
        // Строим индекс identity → newGuid для быстрого поиска.
        // MMKV — memory-mapped, декодинг 300+ профилей занимает ~20мс.
        // ══════════════════════════════════════════════════════════════════════
        if (lastServerIdentity != null || vipIdentities.isNotEmpty()) {
            val updatedServers = MmkvManager.decodeServerList()
            val identityIndex = mutableMapOf<String, String>() // "server|port|remarks" → newGuid
            for (guid in updatedServers) {
                val profile = MmkvManager.decodeServerConfig(guid) ?: continue
                val key = "${profile.server}|${profile.serverPort}|${profile.remarks}"
                if (!identityIndex.containsKey(key)) {
                    identityIndex[key] = guid
                }
            }

            // ── Ремаппим LastServerCache ──
            if (lastServerIdentity != null) {
                val key = "${lastServerIdentity.server}|${lastServerIdentity.port}|${lastServerIdentity.remarks}"
                val newGuid = identityIndex[key]
                if (newGuid != null) {
                    MmkvManager.remapLastConnectedServer(newGuid)
                    Log.i(AppConfig.TAG, "updateSubscription: LastServerCache ремаппирован → ${lastServerIdentity.remarks}")
                } else {
                    MmkvManager.clearLastConnectedServer()
                    Log.w(AppConfig.TAG, "updateSubscription: LastServerCache сервер исчез после обновления, кэш сброшен")
                }
            }

            // ── Ремаппим VIP-кэш ──
            if (vipIdentities.isNotEmpty()) {
                val remapped = vipIdentities.mapNotNull { id ->
                    val key = "${id.server}|${id.port}|${id.remarks}"
                    identityIndex[key]
                }
                if (remapped.isNotEmpty()) {
                    MmkvManager.replaceVipCache(remapped)
                    Log.i(AppConfig.TAG, "updateSubscription: VIP-кэш ремаппирован: ${remapped.size}/${vipIdentities.size} серверов сохранено")
                } else {
                    MmkvManager.clearVipCache()
                    Log.w(AppConfig.TAG, "updateSubscription: все VIP-серверы исчезли после обновления, кэш очищен")
                }
            }
        }
        
        MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_RELOAD_SERVER_LIST, "")
    }
}
