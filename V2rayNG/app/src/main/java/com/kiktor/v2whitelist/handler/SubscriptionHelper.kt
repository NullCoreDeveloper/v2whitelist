package com.kiktor.v2whitelist.handler

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.dto.SubscriptionCache
import com.kiktor.v2whitelist.dto.SubscriptionItem
import com.kiktor.v2whitelist.util.MessageUtil
import com.kiktor.v2whitelist.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        val removedSubIds = getRemovedSubIds()
        val isAlreadyInitialized = MmkvManager.decodeSettingsBool("pref_defaults_added_v1", false)
        var changed = false

        for (defaultSub in DefaultSubscriptions.PREPOPULATED_SUBS) {
            // Если пользователь явно удалил эту подписку, никогда не воскрешаем её
            if (removedSubIds.contains(defaultSub.id)) {
                continue
            }

            val existing = customSubs.find { it.id == defaultSub.id }
                ?: customSubs.find { it.name == defaultSub.name }
            if (existing == null) {
                Log.d(AppConfig.TAG, "Pre-populating subscription: ${defaultSub.name}")
                customSubs.add(defaultSub)
                changed = true
            } else {
                if (existing.id == defaultSub.id && existing.name != defaultSub.name) {
                    existing.name = defaultSub.name
                    changed = true
                }
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
     * Возвращает набор ID подписок, которые пользователь намеренно удалил.
     */
    fun getRemovedSubIds(): Set<String> {
        val json = MmkvManager.decodeSettingsString(AppConfig.PREF_REMOVED_CUSTOM_SUB_IDS)
        if (json.isNullOrEmpty()) return emptySet()
        return try {
            com.kiktor.v2whitelist.util.JsonUtil.fromJson(json, Array<String>::class.java)?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * Помечает ID подписки как удалённый пользователем, чтобы она никогда не воскрешалась при авто-обновлениях.
     */
    fun markSubRemoved(subId: String) {
        val current = getRemovedSubIds().toMutableSet()
        current.add(subId)
        MmkvManager.encodeSettings(AppConfig.PREF_REMOVED_CUSTOM_SUB_IDS, com.kiktor.v2whitelist.util.JsonUtil.toJson(current.toList()))
    }

    /**
     * Сценарии работы для стартового опросника
     */
    enum class AppScenario {
        VPN_BLACKLIST, // 1. Просто VPN (igareck чс, кизяк чс)
        WHITELIST,     // 2. БС (zieng2, igareck бс, кизяк бс обе, киберпортал все бс, это не я бс)
        YOUTUBE,       // 3. YouTube и Музыка (ЭтоНеЯ YouTube, Музыка, Aetris)
        KEEP_CURRENT   // 4. Оставить как есть
    }

    /**
     * Применяет выбранный сценарий подписок из стартового опросника.
     */
    suspend fun applyScenario(context: Context, scenario: AppScenario) = withContext(Dispatchers.IO) {
        if (scenario == AppScenario.KEEP_CURRENT) {
            MmkvManager.encodeSettings(AppConfig.PREF_ONBOARDING_PURPOSE_SHOWN, true)
            return@withContext
        }

        val targetSubIds = when (scenario) {
            AppScenario.VPN_BLACKLIST -> setOf(
                "def_igareck_black",
                "def_kizyak_black",
                "def_cyberportal_cp002",
                "def_rkp_blacklist",
                "def_etoneya_blacklist"
            )
            AppScenario.WHITELIST -> setOf(
                "def_zieng2",
                "def_igareck_white",
                "def_kizyak_white",
                "def_kizyak_white_v6",
                "def_cyberportal_cp001",
                "def_cyberportal_cp035",
                "def_cyberportal_cp006",
                "def_cyberportal_cp008",
                "def_cyberportal_cp042",
                "def_etoneya_whitelist"
            )
            AppScenario.YOUTUBE -> setOf(
                "def_etoneya_youtube",
                "def_etoneya_ytm",
                "def_aetris"
            )
            AppScenario.KEEP_CURRENT -> emptySet()
        }

        val customSubs = loadCustomSubs().toMutableList()
        val allSubs = MmkvManager.decodeSubscriptions()

        // Добавляем целевые подписки из DefaultSubscriptions, если их еще не было в customSubs
        for (defaultSub in DefaultSubscriptions.PREPOPULATED_SUBS) {
            if (targetSubIds.contains(defaultSub.id) && customSubs.none { it.id == defaultSub.id }) {
                customSubs.add(defaultSub.copy(enabled = true))
            }
        }

        // Если выбранные подписки были в списке удаленных, возвращаем их
        val removedSet = getRemovedSubIds().toMutableSet()
        var removedModified = false
        for (id in targetSubIds) {
            if (removedSet.remove(id)) {
                removedModified = true
            }
        }
        if (removedModified) {
            MmkvManager.encodeSettings(AppConfig.PREF_REMOVED_CUSTOM_SUB_IDS, com.kiktor.v2whitelist.util.JsonUtil.toJson(removedSet.toList()))
        }

        for (sub in customSubs) {
            val shouldEnable = targetSubIds.contains(sub.id)
            sub.enabled = shouldEnable
            val guid = "custom_sub_${sub.id}"
            val realSub = allSubs.find { it.guid == guid }

            if (!shouldEnable) {
                MmkvManager.removeServerViaSubid(guid)
                realSub?.let {
                    it.subscription.lastUpdated = 0L
                    it.subscription.enabled = false
                    it.subscription.lastUpdateFailed = false
                    MmkvManager.encodeSubscription(it.guid, it.subscription)
                }
            } else {
                if (realSub == null) {
                    val subItem = SubscriptionItem().apply {
                        remarks = sub.name
                        url = sub.url
                        filter = sub.filter
                        enabled = true
                        sharePercent = sub.sharePercent
                    }
                    MmkvManager.encodeSubscription(guid, subItem)
                } else {
                    realSub.subscription.enabled = true
                    MmkvManager.encodeSubscription(realSub.guid, realSub.subscription)
                }
            }
        }

        MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_SUB_URLS, com.kiktor.v2whitelist.util.JsonUtil.toJson(customSubs))
        MmkvManager.encodeSettings(AppConfig.PREF_ONBOARDING_PURPOSE_SHOWN, true)

        try {
            updateSubscription(context)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to update subscriptions after applying scenario: ${e.message}")
        }
        MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_RELOAD_SERVER_LIST, "")
    }

    /**
     * Отображает диалог с мастером выбора сценария (пресета) подписок.
     * На ТВ-устройствах отображается как центрированный диалог с поддержкой D-pad пульта.
     */
    fun showSetupWizard(activity: Activity, onApplied: (() -> Unit)? = null) {
        val view = activity.layoutInflater.inflate(R.layout.layout_onboarding_purpose_bottom_sheet, null)
        val isTvMode = Utils.isTv(activity)

        val cardVpn = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_scenario_vpn)
        val cardWhitelist = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_scenario_whitelist)
        val cardYoutube = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_scenario_youtube)
        val cardKeep = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_scenario_keep)

        val cards = listOfNotNull(cardVpn, cardWhitelist, cardYoutube, cardKeep)

        // На ТВ настраиваем D-pad фокус и убираем полоску свайпа
        if (isTvMode) {
            view.findViewById<View>(R.id.drag_handle)?.visibility = View.GONE
            for (card in cards) {
                card.isFocusable = true
                card.isFocusableInTouchMode = true
                card.setOnFocusChangeListener { _, hasFocus ->
                    card.strokeWidth = Utils.dp2px(activity, if (hasFocus) 3 else 1)
                    card.cardElevation = Utils.dp2px(activity, if (hasFocus) 6 else 1).toFloat()
                }
            }
        }

        var dismissAction: () -> Unit = {}

        cardVpn?.setOnClickListener {
            dismissAction()
            Toast.makeText(activity, R.string.scenario_toast_vpn_applied, Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.Main).launch {
                applyScenario(activity, AppScenario.VPN_BLACKLIST)
                onApplied?.invoke()
            }
        }

        cardWhitelist?.setOnClickListener {
            dismissAction()
            Toast.makeText(activity, R.string.scenario_toast_whitelist_applied, Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.Main).launch {
                applyScenario(activity, AppScenario.WHITELIST)
                onApplied?.invoke()
            }
        }

        cardYoutube?.setOnClickListener {
            dismissAction()
            Toast.makeText(activity, R.string.scenario_toast_youtube_applied, Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.Main).launch {
                applyScenario(activity, AppScenario.YOUTUBE)
                onApplied?.invoke()
            }
        }

        cardKeep?.setOnClickListener {
            dismissAction()
            MmkvManager.encodeSettings(AppConfig.PREF_ONBOARDING_PURPOSE_SHOWN, true)
            Toast.makeText(
                activity,
                R.string.scenario_toast_manual_hint,
                Toast.LENGTH_LONG
            ).show()
        }

        if (isTvMode) {
            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setView(view)
                .setCancelable(true)
                .setOnCancelListener {
                    MmkvManager.encodeSettings(AppConfig.PREF_ONBOARDING_PURPOSE_SHOWN, true)
                }
                .create()

            dismissAction = { dialog.dismiss() }
            dialog.show()

            dialog.window?.let { window ->
                val displayMetrics = activity.resources.displayMetrics
                val targetWidth = (displayMetrics.widthPixels * 0.7).toInt().coerceAtMost(Utils.dp2px(activity, 640))
                window.setLayout(targetWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            view.post {
                cardVpn?.requestFocus()
            }
        } else {
            val bottomSheetDialog = BottomSheetDialog(activity)
            bottomSheetDialog.setCancelable(true)
            bottomSheetDialog.setCanceledOnTouchOutside(true)
            bottomSheetDialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            bottomSheetDialog.behavior.skipCollapsed = true
            bottomSheetDialog.setOnCancelListener {
                MmkvManager.encodeSettings(AppConfig.PREF_ONBOARDING_PURPOSE_SHOWN, true)
            }
            dismissAction = { bottomSheetDialog.dismiss() }
            bottomSheetDialog.setContentView(view)
            bottomSheetDialog.show()
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

        // ── Контрольная проверка ошибок обновления подписок ──
        // Если какие-то подписки помечены как ошибочные, но интернет ограничен
        // (нет доступа одновременно к ya.ru и google.com), подавляем ошибки,
        // чтобы не тревожить пользователя ложными алертами при изоляции сети.
        val allSubs = MmkvManager.decodeSubscriptions()
        val failedSubs = allSubs.filter { it.subscription.enabled && it.subscription.lastUpdateFailed }
        if (failedSubs.isNotEmpty()) {
            val shouldReport = NetworkManager.shouldReportSubscriptionFailure()
            if (!shouldReport) {
                Log.i(AppConfig.TAG, "updateSubscription: suppression of failure alert (only local or no internet access)")
                for (sub in failedSubs) {
                    sub.subscription.lastUpdateFailed = false
                    MmkvManager.encodeSubscription(sub.guid, sub.subscription)
                }
            }
        }
        
        MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_RELOAD_SERVER_LIST, "")
    }
}
