package com.kiktor.v2whitelist.helper

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.kiktor.v2whitelist.dto.BackupData
import com.kiktor.v2whitelist.dto.ProfileItem
import com.kiktor.v2whitelist.handler.MmkvManager
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

object BackupManager {

    private val gson = Gson()

    private val EXCLUDED_SETTINGS_KEYS = setOf(
        "ANG_CONFIGS",
        "SUB_IDS",
        "SELECTED_SERVER",
        com.kiktor.v2whitelist.AppConfig.PREF_LAST_CONNECTED_SERVER,
        com.kiktor.v2whitelist.AppConfig.PREF_LAST_CONNECT_TIME,
        com.kiktor.v2whitelist.AppConfig.PREF_PAUSED_SERVER_GUID,
        com.kiktor.v2whitelist.AppConfig.PREF_IS_PAUSED,
        com.kiktor.v2whitelist.AppConfig.PREF_IS_BOOTED,
        com.kiktor.v2whitelist.AppConfig.CACHE_SUBSCRIPTION_ID,
        com.kiktor.v2whitelist.AppConfig.CACHE_KEYWORD_FILTER,
        com.kiktor.v2whitelist.AppConfig.PREF_VIP_CACHE,
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
            
            // Get all server profiles
            val serverListGuids = MmkvManager.decodeServerList()
            val servers = mutableListOf<ProfileItem>()
            for (guid in serverListGuids) {
                MmkvManager.decodeServerConfig(guid)?.let { servers.add(it) }
            }
            
            val routing = MmkvManager.decodeRoutingRulesets()

            val backupData = BackupData(
                version = 1,
                settings = settings,
                subscriptions = subscriptions,
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
            android.util.Log.e(com.kiktor.v2whitelist.AppConfig.TAG, "Failed to export data", e)
            e.printStackTrace()
            false
        }
    }

    /**
     * Imports all settings, subscriptions, and servers from the specified URI.
     */
    fun importData(context: Context, uri: Uri): Boolean {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return false
            inputStream.use { stream ->
                InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                    val backupData = gson.fromJson(reader, BackupData::class.java) ?: return false
                    
                    if (backupData.version >= 1) {
                        // Clear current data completely
                        MmkvManager.removeAllServer()
                        MmkvManager.decodeSubscriptions().forEach { 
                            MmkvManager.removeSubscription(it.guid)
                        }

                        // Restore Settings
                        backupData.settings?.let { MmkvManager.importSettings(it) }

                        // Restore Subscriptions
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
                    }
                }
            }
            true
        } catch (e: Exception) {
            android.util.Log.e(com.kiktor.v2whitelist.AppConfig.TAG, "Failed to import data", e)
            e.printStackTrace()
            false
        }
    }
}
