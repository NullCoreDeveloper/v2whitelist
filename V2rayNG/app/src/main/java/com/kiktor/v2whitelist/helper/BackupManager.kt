package com.kiktor.v2whitelist.helper

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.core.subscription.model.CustomSubData
import com.kiktor.v2whitelist.dto.BackupData
import com.kiktor.v2whitelist.dto.ProfileItem
import com.kiktor.v2whitelist.dto.SubscriptionItem
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.SettingsChangeManager
import com.kiktor.v2whitelist.handler.SubscriptionHelper
import com.kiktor.v2whitelist.util.JsonUtil
import com.kiktor.v2whitelist.util.MessageUtil
import com.kiktor.v2whitelist.util.ZipUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

object BackupManager {

    private val gson = Gson()

    private val EXCLUDED_SETTINGS_KEYS = setOf(
        "ANG_CONFIGS",
        "SUB_IDS",
        "SELECTED_SERVER",
        AppConfig.PREF_LAST_CONNECTED_SERVER,
        AppConfig.PREF_LAST_CONNECT_TIME,
        AppConfig.PREF_PAUSED_SERVER_GUID,
        AppConfig.PREF_IS_PAUSED,
        AppConfig.PREF_IS_BOOTED,
        AppConfig.CACHE_SUBSCRIPTION_ID,
        AppConfig.CACHE_KEYWORD_FILTER,
        AppConfig.PREF_VIP_CACHE,
        AppConfig.PREF_IS_SERVICE_RUNNING,
        AppConfig.PREF_WARP_COOLDOWN_UNTIL,
        AppConfig.PREF_WARP_COOLDOWN_STEP,
        MmkvManager.KEY_BATTERY_ASKED
    )

    /**
     * Exports all settings, subscriptions, and servers to the specified URI.
     */
    fun exportData(context: Context, uri: Uri): Boolean {
        return try {
            val settings = MmkvManager.getAllSettings().filterKeys { key ->
                !EXCLUDED_SETTINGS_KEYS.contains(key)
            }
            val subscriptions = MmkvManager.decodeSubscriptions()
            val customSubscriptions = SubscriptionHelper.loadCustomSubs()

            // Get all server profiles
            val serverListGuids = MmkvManager.decodeServerList()
            val servers = mutableListOf<ProfileItem>()
            for (guid in serverListGuids) {
                MmkvManager.decodeServerConfig(guid)?.let { servers.add(it) }
            }

            val routing = MmkvManager.decodeRoutingRulesets()

            val backupData = BackupData(
                version = 2,
                settings = settings,
                subscriptions = subscriptions,
                customSubscriptions = customSubscriptions,
                servers = servers,
                routing = routing
            )

            val outputStream = context.contentResolver.openOutputStream(uri) ?: return false
            outputStream.use { stream ->
                OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
                    gson.toJson(backupData, writer)
                    writer.flush()
                }
            }
            true
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to export data", e)
            false
        }
    }

    /**
     * Imports all settings, subscriptions, and servers from the specified URI.
     * Supports both .json and .zip backup archives.
     */
    fun importData(context: Context, uri: Uri): Boolean {
        val tempFile = File(context.cacheDir, "import_temp_${System.currentTimeMillis()}")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return false

            restoreFromFile(context, tempFile)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import data from URI", e)
            false
        } finally {
            try {
                if (tempFile.exists()) tempFile.delete()
            } catch (_: Exception) {}
        }
    }

    /**
     * Restores backup from a file (can be a JSON backup or a ZIP archive).
     */
    fun restoreFromFile(context: Context, file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false

        return if (isZipFile(file)) {
            restoreFromZipFile(context, file)
        } else {
            try {
                InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8).use { reader ->
                    val backupData = gson.fromJson(reader, BackupData::class.java)
                    if (backupData != null) {
                        restoreFromJson(context, backupData)
                    } else {
                        false
                    }
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to parse JSON backup", e)
                false
            }
        }
    }

    /**
     * Checks if the given file starts with ZIP magic bytes.
     */
    private fun isZipFile(file: File): Boolean {
        if (file.length() < 4) return false
        return try {
            FileInputStream(file).use { stream ->
                val b0 = stream.read()
                val b1 = stream.read()
                val b2 = stream.read()
                val b3 = stream.read()
                b0 == 0x50 && b1 == 0x4B &&
                    ((b2 == 0x03 && b3 == 0x04) || (b2 == 0x05 && b3 == 0x06) || (b2 == 0x07 && b3 == 0x08))
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Restores backup from a ZIP file containing either JSON backup or MMKV storage files.
     */
    fun restoreFromZipFile(context: Context, zipFile: File): Boolean {
        val unzipDir = File(context.cacheDir, "unzip_${System.currentTimeMillis()}")
        return try {
            if (!ZipUtil.unzipToFolder(zipFile, unzipDir.absolutePath)) {
                return false
            }

            // 1. Проверяем, есть ли внутри ZIP распакованный JSON бэкап
            val jsonFiles = unzipDir.walkTopDown().filter { it.isFile && it.extension.lowercase() == "json" }.toList()
            for (jFile in jsonFiles) {
                try {
                    val parsed = InputStreamReader(FileInputStream(jFile), StandardCharsets.UTF_8).use {
                        gson.fromJson(it, BackupData::class.java)
                    }
                    if (parsed != null && (parsed.servers != null || parsed.settings != null || parsed.subscriptions != null || parsed.customSubscriptions != null)) {
                        return restoreFromJson(context, parsed)
                    }
                } catch (_: Exception) {}
            }

            // 2. Иначе это архив файлов MMKV
            var count = MMKV.restoreAllFromDirectory(unzipDir.absolutePath)
            if (count <= 0) {
                // Возможно, файлы упакованы в поддиректорию
                unzipDir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
                    if (count <= 0) {
                        count = MMKV.restoreAllFromDirectory(subDir.absolutePath)
                    }
                }
            }

            if (count > 0) {
                // Сбрасываем кэш MMKV в памяти
                MmkvManager.reloadAll()

                // Синхронизируем подписки (если старый бэкап содержал подписки только в subStorage)
                syncSubscriptionsAfterRestore(context)

                // Оповещаем UI и перезапускаем сервисы
                SettingsChangeManager.makeSetupGroupTab()
                SettingsChangeManager.makeRestartService()
                MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_RELOAD_SERVER_LIST, "")

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        SubscriptionHelper.checkAndSetupSubscription(context)
                    } catch (e: Exception) {
                        Log.e(AppConfig.TAG, "Failed checkAndSetupSubscription after zip restore", e)
                    }
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to restore from ZIP file", e)
            false
        } finally {
            try {
                unzipDir.deleteRecursively()
            } catch (_: Exception) {}
        }
    }

    /**
     * Restores backup from BackupData object.
     */
    fun restoreFromJson(context: Context, backupData: BackupData): Boolean {
        return try {
            if (backupData.version < 1) return false

            // Clear current data completely
            MmkvManager.removeAllServer()
            MmkvManager.decodeSubscriptions().forEach {
                MmkvManager.removeSubscription(it.guid)
            }
            MmkvManager.removeSettings(AppConfig.PREF_CUSTOM_SUB_URLS)

            // Restore Settings
            backupData.settings?.let { MmkvManager.importSettings(it) }

            // Restore and merge Subscriptions
            val mergedCustomSubs = mutableListOf<CustomSubData>()

            // 1. Из специального поля customSubscriptions (бэкапы v2)
            backupData.customSubscriptions?.forEach { sub ->
                if (mergedCustomSubs.none { it.id == sub.id || (it.url.isNotEmpty() && it.url == sub.url) }) {
                    mergedCustomSubs.add(sub)
                }
            }

            // 2. Из settings[PREF_CUSTOM_SUB_URLS] (если экспортировался в настройках)
            val settingsSubRaw = backupData.settings?.get(AppConfig.PREF_CUSTOM_SUB_URLS)
            if (settingsSubRaw is String && settingsSubRaw.isNotEmpty()) {
                try {
                    val parsed = JsonUtil.fromJson(settingsSubRaw, Array<CustomSubData>::class.java)
                    parsed?.forEach { sub ->
                        if (mergedCustomSubs.none { it.id == sub.id || (it.url.isNotEmpty() && it.url == sub.url) }) {
                            mergedCustomSubs.add(sub)
                        }
                    }
                } catch (_: Exception) {}
            }

            // 3. Из legacy subscriptions (v2rayNG и ранние версии v2whitelist)
            backupData.subscriptions?.forEach { subCache ->
                val cleanId = if (subCache.guid.startsWith("custom_sub_")) {
                    subCache.guid.removePrefix("custom_sub_")
                } else {
                    subCache.guid
                }
                val existing = mergedCustomSubs.find {
                    it.id == cleanId || (it.url.isNotEmpty() && it.url == subCache.subscription.url)
                }
                if (existing == null) {
                    val newSub = CustomSubData(
                        id = cleanId,
                        name = subCache.subscription.remarks.ifEmpty { "Subscription" },
                        url = subCache.subscription.url,
                        filter = subCache.subscription.filter ?: "",
                        groupRegex = "",
                        enabled = subCache.subscription.enabled,
                        lastUpdated = subCache.subscription.lastUpdated,
                        sharePercent = subCache.subscription.sharePercent,
                        lastUpdateFailed = subCache.subscription.lastUpdateFailed,
                        targetScenarios = emptyList()
                    )
                    mergedCustomSubs.add(newSub)
                }
            }

            // Сохраняем объединенный список кастомных подписок в PREF_CUSTOM_SUB_URLS
            if (mergedCustomSubs.isNotEmpty()) {
                MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_SUB_URLS, JsonUtil.toJson(mergedCustomSubs))
            }

            // Записываем все подписки в subStorage (и с префиксом custom_sub_, и с оригинальными guid)
            mergedCustomSubs.forEach { sub ->
                val subItem = SubscriptionItem().apply {
                    remarks = sub.name
                    url = sub.url
                    filter = sub.filter
                    enabled = sub.enabled
                    lastUpdated = sub.lastUpdated
                    sharePercent = sub.sharePercent
                    lastUpdateFailed = sub.lastUpdateFailed
                }
                MmkvManager.encodeSubscription("custom_sub_${sub.id}", subItem)
            }

            backupData.subscriptions?.forEach { subCache ->
                MmkvManager.encodeSubscription(subCache.guid, subCache.subscription)
            }

            // Restore Servers
            backupData.servers?.let {
                MmkvManager.encodeServerConfigs(it)
            }

            // Restore Routing
            backupData.routing?.let {
                MmkvManager.encodeRoutingRulesets(it.toMutableList())
            }

            // Сбрасываем кэш MMKV
            MmkvManager.reloadAll()

            // Оповещаем UI и сервисы
            SettingsChangeManager.makeSetupGroupTab()
            SettingsChangeManager.makeRestartService()
            MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_RELOAD_SERVER_LIST, "")

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    SubscriptionHelper.checkAndSetupSubscription(context)
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Failed checkAndSetupSubscription after JSON restore", e)
                }
            }

            true
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to restore from JSON backup", e)
            false
        }
    }

    /**
     * Synchronizes subscriptions between subStorage and PREF_CUSTOM_SUB_URLS.
     * Crucial for legacy MMKV backups where subscriptions only existed in subStorage.
     */
    private fun syncSubscriptionsAfterRestore(context: Context) {
        try {
            val customSubsJson = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_SUB_URLS)
            val existingCustomSubs = try {
                if (!customSubsJson.isNullOrEmpty()) {
                    JsonUtil.fromJson(customSubsJson, Array<CustomSubData>::class.java)?.toMutableList() ?: mutableListOf()
                } else {
                    mutableListOf()
                }
            } catch (_: Exception) {
                mutableListOf()
            }

            val mmkvSubs = MmkvManager.decodeSubscriptions()
            var changed = false

            // Переносим подписки из subStorage в PREF_CUSTOM_SUB_URLS
            for (subCache in mmkvSubs) {
                val rawGuid = subCache.guid
                val cleanId = if (rawGuid.startsWith("custom_sub_")) rawGuid.removePrefix("custom_sub_") else rawGuid
                val url = subCache.subscription.url

                val found = existingCustomSubs.find { it.id == cleanId || (it.url.isNotEmpty() && it.url == url) }
                if (found == null) {
                    val newSub = CustomSubData(
                        id = cleanId,
                        name = subCache.subscription.remarks.ifEmpty { "Subscription" },
                        url = url,
                        filter = subCache.subscription.filter ?: "",
                        groupRegex = "",
                        enabled = subCache.subscription.enabled,
                        lastUpdated = subCache.subscription.lastUpdated,
                        sharePercent = subCache.subscription.sharePercent,
                        lastUpdateFailed = subCache.subscription.lastUpdateFailed,
                        targetScenarios = emptyList()
                    )
                    existingCustomSubs.add(newSub)
                    changed = true
                }
            }

            // Убеждаемся, что все кастомные подписки присутствуют в subStorage
            for (customSub in existingCustomSubs) {
                val targetGuid = "custom_sub_${customSub.id}"
                val existingInMmkv = mmkvSubs.find { it.guid == targetGuid || it.guid == customSub.id }
                if (existingInMmkv == null) {
                    val subItem = SubscriptionItem().apply {
                        remarks = customSub.name
                        url = customSub.url
                        filter = customSub.filter
                        enabled = customSub.enabled
                        lastUpdated = customSub.lastUpdated
                        sharePercent = customSub.sharePercent
                        lastUpdateFailed = customSub.lastUpdateFailed
                    }
                    MmkvManager.encodeSubscription(targetGuid, subItem)
                }
            }

            if (changed || (customSubsJson.isNullOrEmpty() && existingCustomSubs.isNotEmpty())) {
                MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_SUB_URLS, JsonUtil.toJson(existingCustomSubs))
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed syncSubscriptionsAfterRestore", e)
        }
    }
}
