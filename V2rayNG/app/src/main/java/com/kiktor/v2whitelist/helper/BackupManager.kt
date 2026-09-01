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

    /**
     * Exports all settings, subscriptions, and servers to the specified URI.
     */
    fun exportData(context: Context, uri: Uri): Boolean {
        return try {
            val settings = MmkvManager.getAllSettings()?.filterKeys { 
                // Filter out keys we don't want to export, if any
                it != "ANG_CONFIGS" && it != "SUB_IDS" && it != "SELECTED_SERVER"
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

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { writer ->
                    gson.toJson(backupData, writer)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Imports all settings, subscriptions, and servers from the specified URI.
     */
    fun importData(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                InputStreamReader(inputStream, StandardCharsets.UTF_8).use { reader ->
                    val backupData = gson.fromJson(reader, BackupData::class.java)
                    
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
            e.printStackTrace()
            false
        }
    }
}
