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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import com.kiktor.v2whitelist.core.model.ProfileItem

typealias CustomSubData = com.kiktor.v2whitelist.core.subscription.model.CustomSubData

object SubscriptionHelper {

    const val SUBSCRIPTION_ID = "v2whitelist_hardcoded_sub"

    const val SCENARIO_WHITELIST = com.kiktor.v2whitelist.core.subscription.model.CustomSubData.SCENARIO_WHITELIST
    const val SCENARIO_BLACKLIST = com.kiktor.v2whitelist.core.subscription.model.CustomSubData.SCENARIO_BLACKLIST
    const val SCENARIO_YOUTUBE = com.kiktor.v2whitelist.core.subscription.model.CustomSubData.SCENARIO_YOUTUBE

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
        var changed = false

        // Версионированная миграция предустановленных подписок:
        // Полное очищение устаревших def_* подписок и серверов при 100% сохранении пользовательских кастомных подписок
        val CURRENT_DEFAULTS_VERSION = 3
        val currentDefaultsVersion = MmkvManager.decodeSettingsInt("pref_defaults_version_v2", 1)

        if (currentDefaultsVersion < CURRENT_DEFAULTS_VERSION) {
            val newDefaultIds = DefaultSubscriptions.PREPOPULATED_SUBS.map { it.id }.toSet()
            val obsoleteDefIds = customSubs.filter { it.id.startsWith("def_") && !newDefaultIds.contains(it.id) }.map { it.id }.toSet()
            val allDeprecated = obsoleteDefIds + setOf(
                "def_cid_white", "def_bywarm_rser", "def_vsevj_obwl",
                "def_zieng2", "def_kizyak_white", "def_kizyak_white_v6", "def_kizyak_black",
                "def_hiztin_gribi", "def_cyberportal_cp002", "def_cyberportal_cp035",
                "def_cyberportal_cp006", "def_cyberportal_cp008", "def_airlink_whitelist",
                "def_etoneya_gemini", "def_etoneya_ytm", "def_mifa_bobrik", "def_igareck_white"
            )

            val removedDeprecatedFromList = customSubs.removeAll { allDeprecated.contains(it.id) }
            if (removedDeprecatedFromList) {
                changed = true
                Log.i(AppConfig.TAG, "Purged deprecated subscriptions from customSubs: $allDeprecated")
            }
            for (depId in allDeprecated) {
                val subGuid = "custom_sub_$depId"
                MmkvManager.removeSubscription(subGuid)
                MmkvManager.removeServerViaSubid(subGuid)
                MmkvManager.removeServerViaSubid(depId)
            }
            MmkvManager.encodeSettings("pref_defaults_version_v2", CURRENT_DEFAULTS_VERSION)
        }

        for (defaultSub in DefaultSubscriptions.PREPOPULATED_SUBS) {
            // Если пользователь явно удалил эту подписку, никогда не воскрешаем её
            if (removedSubIds.contains(defaultSub.id)) {
                continue
            }

            val existing = customSubs.find { it.id == defaultSub.id }
            if (existing == null) {
                Log.d(AppConfig.TAG, "Pre-populating subscription: ${defaultSub.name}")
                customSubs.add(defaultSub)
                changed = true
            } else {
                if (existing.name != defaultSub.name) {
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
                if (existing.targetScenarios != defaultSub.targetScenarios) {
                    existing.targetScenarios = defaultSub.targetScenarios
                    changed = true
                }
            }
        }
        if (changed) {
            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_SUB_URLS, com.kiktor.v2whitelist.util.JsonUtil.toJson(customSubs))
            MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_RELOAD_SERVER_LIST, "")
        }
        MmkvManager.encodeSettings("pref_defaults_added_v1", true)

        // Санитизация: для всех отключенных подписок сбрасываем lastUpdated и удаляем остаточные серверы,
        // а также удаляем осиротевшие custom_sub_* записи, которых нет в списке кастомных подписок
        val validCustomGuids = customSubs.map { "custom_sub_${it.id}" }.toSet()
        for (subCache in MmkvManager.decodeSubscriptions()) {
            if (subCache.guid.startsWith("custom_sub_") && subCache.guid !in validCustomGuids) {
                MmkvManager.removeServerViaSubid(subCache.guid)
                MmkvManager.removeSubscription(subCache.guid)
            }
        }
        val allSubsInStorage = MmkvManager.decodeSubscriptions()
        for (sub in customSubs.filter { !it.enabled }) {
            sub.lastUpdated = 0L
            sub.lastUpdateFailed = false
            val guid = "custom_sub_${sub.id}"
            MmkvManager.removeServerViaSubid(guid)
            MmkvManager.removeServerViaSubid(sub.id)
            val realSub = MmkvManager.decodeSubscription(guid)
            if (realSub != null && (realSub.lastUpdated > 0L || realSub.enabled)) {
                realSub.lastUpdated = 0L
                realSub.enabled = false
                realSub.lastUpdateFailed = false
                MmkvManager.encodeSubscription(guid, realSub)
            }
        }
        for (subCache in allSubsInStorage.filter { !it.subscription.enabled }) {
            if (subCache.subscription.lastUpdated > 0L) {
                subCache.subscription.lastUpdated = 0L
                subCache.subscription.lastUpdateFailed = false
                MmkvManager.encodeSubscription(subCache.guid, subCache.subscription)
            }
            MmkvManager.removeServerViaSubid(subCache.guid)
        }

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
            val subs = com.kiktor.v2whitelist.util.JsonUtil.fromJson(json, Array<CustomSubData>::class.java)?.toList() ?: emptyList()
            // Подтягиваем дефолтные сценарии для подписок, где сценарии еще не были назначены, и сбрасываем время у отключенных
            subs.forEach { sub ->
                if (!sub.enabled) {
                    sub.lastUpdated = 0L
                    sub.lastUpdateFailed = false
                }
                if (sub.targetScenarios.isEmpty()) {
                    val defaultMatch = DefaultSubscriptions.PREPOPULATED_SUBS.find { it.id == sub.id }
                    if (defaultMatch != null && defaultMatch.targetScenarios.isNotEmpty()) {
                        sub.targetScenarios = defaultMatch.targetScenarios
                    }
                }
            }
            subs
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Возвращает карту привязки подписок к целевым сценариям.
     * Ключи включают как "custom_sub_${id}", так и "${id}".
     */
    fun getSubScenariosMap(): Map<String, Set<String>> {
        val map = mutableMapOf<String, Set<String>>()

        // 1. Дефолтные сценарии из DefaultSubscriptions.PREPOPULATED_SUBS
        for (sub in DefaultSubscriptions.PREPOPULATED_SUBS) {
            val scenarios = sub.targetScenarios.toSet()
            if (scenarios.isNotEmpty()) {
                map["custom_sub_${sub.id}"] = scenarios
                map[sub.id] = scenarios
            }
        }

        // 2. Настройки кастомных подписок из MMKV (пользовательские настройки)
        val customSubs = loadCustomSubs()
        for (sub in customSubs) {
            val scenarios = if (sub.targetScenarios.isNotEmpty()) {
                sub.targetScenarios.toSet()
            } else {
                val defaultMatch = DefaultSubscriptions.PREPOPULATED_SUBS.find { it.id == sub.id }
                defaultMatch?.targetScenarios?.toSet() ?: emptySet()
            }
            if (scenarios.isNotEmpty()) {
                map["custom_sub_${sub.id}"] = scenarios
                map[sub.id] = scenarios
            }
        }

        return map
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

    typealias AppScenario = com.kiktor.v2whitelist.core.model.AppScenario

    /**
     * Нормализует устаревшие сценарии (YOUTUBE -> VPN_BLACKLIST, KEEP_CURRENT -> AUTO).
     */
    fun normalizeScenario(scenario: AppScenario): AppScenario {
        return when (scenario) {
            AppScenario.YOUTUBE -> AppScenario.VPN_BLACKLIST
            AppScenario.KEEP_CURRENT -> AppScenario.AUTO
            else -> scenario
        }
    }

    /**
     * Возвращает текущий активный сценарий с учётом нормализации устаревших значений.
     */
    fun getCurrentScenario(): AppScenario {
        val raw = MmkvManager.decodeSettingsString(
            AppConfig.PREF_CURRENT_APP_SCENARIO,
            AppScenario.AUTO.name
        ) ?: AppScenario.AUTO.name
        val parsed = try {
            AppScenario.valueOf(raw)
        } catch (_: Exception) {
            AppScenario.AUTO
        }
        return normalizeScenario(parsed)
    }

    /**
     * Сохраняет выбранный режим работы (Авто / БС / ЧС) без изменения статуса подписок.
     */
    fun setScenario(scenario: AppScenario) {
        val normalized = normalizeScenario(scenario)
        MmkvManager.encodeSettings(AppConfig.PREF_CURRENT_APP_SCENARIO, normalized.name)
        MmkvManager.encodeSettings(AppConfig.PREF_ONBOARDING_PURPOSE_SHOWN, true)
    }

    /**
     * Одноразовая миграция для пользователей со старым визардом и сценарием YouTube:
     * - YOUTUBE -> VPN_BLACKLIST (ЧС) + скорость 10 Мбит/с (Баланс)
     * - KEEP_CURRENT -> AUTO
     * - Все предустановленные подписки (кроме удалённых пользователем) включаются по умолчанию.
     */
    fun migrateLegacyModeIfNeeded() {
        val rawScenario = MmkvManager.decodeSettingsString(
            AppConfig.PREF_CURRENT_APP_SCENARIO,
            null
        )
        if (rawScenario == AppScenario.YOUTUBE.name) {
            MmkvManager.encodeSettings(AppConfig.PREF_CURRENT_APP_SCENARIO, AppScenario.VPN_BLACKLIST.name)
            MmkvManager.encodeSettings(AppConfig.PREF_PROFILE_MIN_SPEED_MBPS, "10.0")
            MmkvManager.encodeSettings(AppConfig.PREF_PROFILE_SPEED_CHECK_ENABLED, true)
        } else if (rawScenario == AppScenario.KEEP_CURRENT.name) {
            MmkvManager.encodeSettings(AppConfig.PREF_CURRENT_APP_SCENARIO, AppScenario.AUTO.name)
        }

        val migrationKey = "pref_mode_redesign_v1_migrated"
        if (MmkvManager.decodeSettingsBool(migrationKey, false)) {
            return
        }

        MmkvManager.encodeSettings(AppConfig.PREF_ONBOARDING_PURPOSE_SHOWN, true)

        val removedIds = getRemovedSubIds()
        val customSubs = loadCustomSubs().toMutableList()
        var customSubsChanged = false

        for (defaultSub in DefaultSubscriptions.PREPOPULATED_SUBS) {
            if (removedIds.contains(defaultSub.id)) continue
            val existing = customSubs.find { it.id == defaultSub.id }
            if (existing == null) {
                customSubs.add(defaultSub.copy(enabled = true))
                customSubsChanged = true
            } else if (!existing.enabled) {
                existing.enabled = true
                customSubsChanged = true
            }

            val guid = "custom_sub_${defaultSub.id}"
            val storedSub = MmkvManager.decodeSubscription(guid)
            if (storedSub != null && !storedSub.enabled) {
                storedSub.enabled = true
                MmkvManager.encodeSubscription(guid, storedSub)
            }
        }

        if (customSubsChanged) {
            MmkvManager.encodeSettings(
                AppConfig.PREF_CUSTOM_SUB_URLS,
                com.kiktor.v2whitelist.util.JsonUtil.toJson(customSubs)
            )
        }

        MmkvManager.encodeSettings(migrationKey, true)
    }

    /**
     * Применяет выбранный сценарий подписок.
     * Не отключает подписки — режим влияет только на выбор узлов в SmartConnect.
     */
    suspend fun applyScenario(context: Context, scenario: AppScenario) = withContext(Dispatchers.IO) {
        setScenario(scenario)
    }

    /**
     * Force updates all active subscriptions.
     * Сбрасывает кэш последнего сервера — после обновления старый GUID может не существовать.
     * @param sequential если true — последовательная подкачка (фоновый воркер),
     *                    false — параллельная гонка зеркал (UI).
     */
    suspend fun updateSubscription(
        context: Context,
        isStartup: Boolean = false,
        sequential: Boolean = false,
        silent: Boolean = false
    ) = withContext(Dispatchers.IO) {
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
        val vipRecords = vipGuids.mapNotNull { guid ->
            MmkvManager.decodeServerConfig(guid)?.let { p ->
                val netKey = "${p.configType.name}:${p.server.orEmpty().trim().lowercase()}:${p.serverPort.orEmpty().trim()}:${p.password.orEmpty().trim()}"
                Triple(guid, "${p.server}|${p.serverPort}|${p.remarks}", netKey)
            }
        }

        if (vipRecords.isNotEmpty()) {
            Log.i(AppConfig.TAG, "updateSubscription: снимок VIP-кэша: ${vipRecords.size} серверов")
        }

        val candidateSocksPort = SettingsManager.getSocksPort()
        var socksPort = 0
        var vpnStarted = false
        
        // Ожидаем запуска прокси (дольше при старте приложения, так как он может запускаться SmartConnect'ом)
        val waitLoops = if (isStartup) 8 else 2
        for (i in 0 until waitLoops) {
            if (SmartConnectManager.isProxyRunning(candidateSocksPort)) {
                socksPort = candidateSocksPort
                vpnStarted = true
                break
            }
            if (i < waitLoops - 1) delay(500)
        }
        if (socksPort == 0 && V2RayServiceManager.isRunning()) {
            socksPort = candidateSocksPort
            vpnStarted = true
        }
        
        val onlyViaVpn = MmkvManager.decodeSettingsBool(AppConfig.PREF_UPDATE_SUB_ONLY_VIA_VPN, false)

        // Если обнаружен висящий tun-интерфейс, но сервис не работает и системного VPN нет — это зомби!
        if (NetworkManager.hasTunInterface() && !V2RayServiceManager.isRunning() && !NetworkManager.isSystemVpnTransport(context)) {
            Log.w(AppConfig.TAG, "updateSubscription: Detected lingering zombie TUN interface before update, cleaning up...")
            V2RayServiceManager.forceCleanupZombieVpn(context)
        }

        val isVpnActive = NetworkManager.isVpnActive(context)
        Log.i(AppConfig.TAG, "updateSubscription: isVpnActive=$isVpnActive, VPN=$vpnStarted, socksPort=$socksPort, sequential=$sequential, onlyViaVpn=$onlyViaVpn")

        if (onlyViaVpn) {
            val hasSystemVpn = NetworkManager.isSystemVpnTransport(context)
            val hasActiveProxy = socksPort > 0 && SmartConnectManager.isProxyRunning(socksPort)
            val isVpnRunning = V2RayServiceManager.isRunning()

            // Для обновления только через VPN обязательно требуется реально активный VPN и рабочий прокси/транспорт
            if (!isVpnActive || (!hasActiveProxy && !hasSystemVpn && !isVpnRunning)) {
                Log.w(AppConfig.TAG, "updateSubscription aborted: PREF_UPDATE_SUB_ONLY_VIA_VPN is enabled but no active VPN connection or working proxy found (isVpnActive=$isVpnActive, socksPort=$socksPort, hasSystemVpn=$hasSystemVpn)")
                if (!sequential) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Обновление прервано: требуется активное VPN-соединение", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                return@withContext
            }
        }

        val updateViaYandexOnBs = !onlyViaVpn && MmkvManager.decodeSettingsBool(AppConfig.PREF_UPDATE_VIA_YANDEX_ON_BS, false)
        val isBs = NetworkManager.checkInternetStatus() == 1

        // Яндекс-обход работает ТОЛЬКО если:
        // 1. Включен в настройках (updateViaYandexOnBs == true)
        // 2. Пользователь под БС (isBs == true)
        // 3. VPN ВЫКЛЮЧЕН (нет ни tun интерфейса, ни запущенного ядра/VPN транспорта: !isVpnActive)
        // Если же VPN включен (или есть tun) — обновление всегда идет стандартно через VPN!
        if (updateViaYandexOnBs && isBs && !isVpnActive) {
            Log.i(AppConfig.TAG, "updateSubscription: Whitelist (БС) mode active and VPN is off, updating via Yandex Translate proxy (sequential=$sequential)")
            val count = YandexTranslateUpdater.updateAllViaYandex(context, isDebug = false, sequential = sequential)
            Log.i(AppConfig.TAG, "updateSubscription: Yandex Translate update finished, total configs: $count")
            return@withContext
        }

        // Ensure base subscriptions are initialized if this is the first launch
        checkAndSetupSubscription(context)

        // Обновляем кастомные подписки
        val customSubs = loadCustomSubs()
        val enabledCustomSubs = customSubs.filter { it.enabled }
        val allSubscriptions = MmkvManager.decodeSubscriptions()
        val regularSubs = allSubscriptions.filter {
            !it.guid.startsWith("custom_sub_") &&
                !com.kiktor.v2whitelist.core.model.PersonalServersConfig.isPersonalServer(it.guid) &&
                it.subscription.enabled
        }
        val totalSubs = enabledCustomSubs.size + regularSubs.size
        val concurrency = if (sequential) 1 else MmkvManager.getSubConcurrency().coerceIn(1, 20)
        val semaphore = Semaphore(concurrency)
        val startedCount = AtomicInteger(0)
        val finishedCount = AtomicInteger(0)

        data class SubTask(val name: String, val updateAction: suspend () -> Int)
        val taskList = mutableListOf<SubTask>()

        for (sub in enabledCustomSubs) {
            val subId = "custom_sub_${sub.id}"
            val existing = allSubscriptions.find { it.guid == subId }
            taskList.add(SubTask(sub.name) {
                val cache = if (existing != null) {
                    existing.subscription.enabled = true
                    existing.subscription.filter = sub.filter
                    existing.subscription.sharePercent = sub.sharePercent
                    MmkvManager.encodeSubscription(subId, existing.subscription)
                    Log.d(AppConfig.TAG, "Updating custom subscription: ${sub.name}")
                    existing
                } else {
                    val subItem = SubscriptionItem().apply {
                        remarks = sub.name
                        url = sub.url
                        filter = sub.filter
                        sharePercent = sub.sharePercent
                        enabled = true
                    }
                    MmkvManager.encodeSubscription(subId, subItem)
                    SubscriptionCache(subId, subItem)
                }
                AngConfigManager.updateConfigViaSub(cache, socksPort, sequential, deduplicate = false)
            })
        }

        // Обновляем обычные подписки (добавленные пользователем вручную)
        for (sub in regularSubs) {
            taskList.add(SubTask(sub.subscription.remarks) {
                Log.d(AppConfig.TAG, "Updating regular subscription: ${sub.subscription.remarks}")
                AngConfigManager.updateConfigViaSub(sub, socksPort, sequential, deduplicate = false)
            })
        }

        coroutineScope {
            taskList.map { task ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val currentStarted = startedCount.incrementAndGet()
                        val prefix = "[$currentStarted/$totalSubs]"
                        if (!silent && !SmartConnectManager.isScanning.get()) {
                            MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE, "$prefix: ${task.name}")
                        }
                        val count = task.updateAction()
                        val done = finishedCount.incrementAndGet()
                        if (count > 0 && !silent && !SmartConnectManager.isScanning.get()) {
                            MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE, "[$done/$totalSubs] ${task.name}: +$count")
                        }
                    }
                }
            }.awaitAll()
        }

        // Один проход дедупликации по итогам обновления всех подписок (вместо прохода после каждой подписки)
        synchronized(AngConfigManager::class.java) {
            MmkvManager.removeDuplicateServer()
        }

        // ══════════════════════════════════════════════════════════════════════
        // РЕМАППИНГ КЭШЕЙ ПОСЛЕ ОБНОВЛЕНИЯ
        // Строим индекс identity → newGuid для быстрого поиска.
        // MMKV — memory-mapped, декодинг 300+ профилей занимает ~20мс.
        // ══════════════════════════════════════════════════════════════════════
        if (lastServerIdentity != null || vipRecords.isNotEmpty()) {
            val updatedServers = MmkvManager.decodeServerList()
            val identityIndex = mutableMapOf<String, String>() // "server|port|remarks" → newGuid
            val networkIndex = mutableMapOf<String, String>()  // "type:server:port:password" → newGuid
            for (guid in updatedServers) {
                val profile = MmkvManager.decodeServerConfig(guid) ?: continue
                val key = "${profile.server}|${profile.serverPort}|${profile.remarks}"
                val netKey = "${profile.configType.name}:${profile.server.orEmpty().trim().lowercase()}:${profile.serverPort.orEmpty().trim()}:${profile.password.orEmpty().trim()}"
                identityIndex.putIfAbsent(key, guid)
                networkIndex.putIfAbsent(netKey, guid)
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
            if (vipRecords.isNotEmpty()) {
                val updatedSet = updatedServers.toSet()
                val remapped = mutableListOf<String>()
                for ((oldGuid, exactKey, netKey) in vipRecords) {
                    if (updatedSet.contains(oldGuid)) {
                        remapped.add(oldGuid)
                        continue
                    }
                    val byExact = identityIndex[exactKey]
                    if (byExact != null) {
                        remapped.add(byExact)
                        continue
                    }
                    val byNet = networkIndex[netKey]
                    if (byNet != null) {
                        remapped.add(byNet)
                    }
                }
                if (remapped.isNotEmpty()) {
                    MmkvManager.replaceVipCache(remapped.distinct())
                    Log.i(AppConfig.TAG, "updateSubscription: VIP-кэш ремаппирован: ${remapped.size}/${vipRecords.size} серверов сохранено")
                }
            }
        }

        // ── Контрольная проверка ошибок обновления подписок ──
        // Если какие-то подписки помечены как ошибочные, но интернет ограничен
        // (нет доступа одновременно к ya.ru и google.com), подавляем ошибки,
        // чтобы не тревожить пользователя ложными алертами при изоляции сети.
        val allSubs = MmkvManager.decodeSubscriptions()
        val failedSubs = allSubs.filter {
            it.subscription.enabled &&
                !com.kiktor.v2whitelist.core.model.PersonalServersConfig.isPersonalServer(it.guid) &&
                it.subscription.lastUpdateFailed
        }
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

    fun getSubscriptionName(subId: String?): String? {
        if (subId.isNullOrBlank()) return null
        // 1. subStorage
        MmkvManager.decodeSubscription(subId)?.remarks?.takeIf { it.isNotBlank() }?.let { return it }
        val altId = if (subId.startsWith("custom_sub_")) subId.removePrefix("custom_sub_") else "custom_sub_$subId"
        MmkvManager.decodeSubscription(altId)?.remarks?.takeIf { it.isNotBlank() }?.let { return it }
        // 2. loadCustomSubs
        loadCustomSubs().find { it.id == subId || it.id == altId || "custom_sub_${it.id}" == subId }?.name?.takeIf { it.isNotBlank() }?.let { return it }
        // 3. decodeSubscriptions
        MmkvManager.decodeSubscriptions().find { it.guid == subId || it.guid == altId }?.subscription?.remarks?.takeIf { it.isNotBlank() }?.let { return it }
        // 4. DefaultSubscriptions
        DefaultSubscriptions.PREPOPULATED_SUBS.find { it.id == subId || it.id == altId || "custom_sub_${it.id}" == subId }?.name?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    fun getSubscriptionName(config: ProfileItem?): String? {
        if (config == null) return null
        val direct = getSubscriptionName(config.subscriptionId)
        if (!direct.isNullOrBlank()) return direct

        // Fallback: match by server address + port + remarks against all stored profiles
        try {
            val allGuids = MmkvManager.decodeServerList()
            for (guid in allGuids) {
                val p = MmkvManager.decodeServerConfig(guid) ?: continue
                if (p.remarks == config.remarks && p.server == config.server && p.serverPort == config.serverPort && p.subscriptionId.isNotBlank()) {
                    val fallbackName = getSubscriptionName(p.subscriptionId)
                    if (!fallbackName.isNullOrBlank()) return fallbackName
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
