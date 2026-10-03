package com.kiktor.v2whitelist.handler

import android.content.Context
import android.util.Log
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.dto.ProfileItem
import com.kiktor.v2whitelist.util.MessageUtil
import com.kiktor.v2whitelist.extension.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WarpManager {

    const val WARP_SUB_ID = "warp_auto_sub"
    const val WARP_SERVER_REMARKS = "Cloudflare WARP (Auto)"

    private fun sendStatus(context: Context, status: String) {
        MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE, status)
    }

    /**
     * Checks if WARP is enabled in preferences.
     */
    fun isWarpEnabled(context: Context? = null): Boolean {
        return MmkvManager.decodeSettingsBool(AppConfig.PREF_WARP_ENABLED, false)
    }

    /**
     * Checks if WARP is currently in cooldown after repeated failures.
     */
    fun isWarpInCooldown(): Boolean {
        val cooldownUntil = MmkvManager.decodeSettingsLong(AppConfig.PREF_WARP_COOLDOWN_UNTIL, 0L)
        val inCooldown = System.currentTimeMillis() < cooldownUntil
        if (inCooldown) {
            val remainMin = ((cooldownUntil - System.currentTimeMillis()) / 60000L).coerceAtLeast(1L)
            GeekModeLogger.log("WarpManager", "WARP is in cooldown for $remainMin more minutes")
        }
        return inCooldown
    }

    /**
     * Triggers cooldown after failed attempts with exponential backoff (e.g., 15m, 60m, 360m).
     */
    fun triggerCooldown() {
        val currentStep = MmkvManager.decodeSettingsInt(AppConfig.PREF_WARP_COOLDOWN_STEP, 0)
        val baseMinutes = MmkvManager.decodeSettingsString(AppConfig.PREF_WARP_COOLDOWN_MINUTES, "15")?.toLongOrNull() ?: 15L
        
        val multiplier = when (currentStep) {
            0 -> 1L
            1 -> 4L
            else -> 24L
        }
        val cooldownMinutes = baseMinutes * multiplier
        val cooldownUntil = System.currentTimeMillis() + (cooldownMinutes * 60 * 1000L)

        MmkvManager.encodeSettings(AppConfig.PREF_WARP_COOLDOWN_UNTIL, cooldownUntil)
        MmkvManager.encodeSettings(AppConfig.PREF_WARP_COOLDOWN_STEP, currentStep + 1)
        GeekModeLogger.log("WarpManager", "WARP cooldown activated for $cooldownMinutes minutes (step ${currentStep + 1})")
    }

    /**
     * Resets the cooldown timer and step upon a successful connection or manual disable.
     */
    fun resetCooldown() {
        MmkvManager.encodeSettings(AppConfig.PREF_WARP_COOLDOWN_UNTIL, 0L)
        MmkvManager.encodeSettings(AppConfig.PREF_WARP_COOLDOWN_STEP, 0)
    }

    /**
     * Retrieves the stored WARP profile GUID if available.
     */
    fun getWarpProfileGuid(): String? {
        val guid = MmkvManager.decodeSettingsString(AppConfig.PREF_WARP_PROFILE_GUID)
        if (guid.isNullOrBlank()) return null
        val profile = MmkvManager.decodeServerConfig(guid)
        return if (profile != null) guid else null
    }

    /**
     * Saves or replaces the WARP profile in storage.
     */
    fun saveWarpProfile(profile: ProfileItem): String {
        val oldGuid = MmkvManager.decodeSettingsString(AppConfig.PREF_WARP_PROFILE_GUID)
        if (!oldGuid.isNullOrBlank()) {
            MmkvManager.removeServer(oldGuid)
        }
        val newGuid = MmkvManager.encodeServerConfig("", profile)
        MmkvManager.encodeSettings(AppConfig.PREF_WARP_PROFILE_GUID, newGuid)
        return newGuid
    }

    /**
     * Clears the current WARP profile and resets cooldown.
     */
    fun clearWarpProfile() {
        val guid = MmkvManager.decodeSettingsString(AppConfig.PREF_WARP_PROFILE_GUID)
        if (!guid.isNullOrBlank()) {
            MmkvManager.removeServer(guid)
            MmkvManager.encodeSettings(AppConfig.PREF_WARP_PROFILE_GUID, "")
        }
        resetCooldown()
    }

    /**
     * Checks if the given server GUID belongs to the WARP profile.
     */
    fun isWarpGuid(guid: String?): Boolean {
        if (guid.isNullOrBlank()) return false
        val warpGuid = MmkvManager.decodeSettingsString(AppConfig.PREF_WARP_PROFILE_GUID)
        return guid == warpGuid
    }

    /**
     * Core orchestrator method: Attempts to connect to Cloudflare WARP with auto-regeneration and health checks.
     * @return true if successfully connected to WARP, false if should fallback to standard servers.
     */
    suspend fun tryConnectWarp(context: Context): Boolean = withContext(Dispatchers.IO) {
        val warpEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_WARP_ENABLED, false)
        if (!warpEnabled) {
            return@withContext false
        }

        // Check network status (whitelists vs normal)
        val internetStatus = NetworkManager.checkInternetStatus()
        if (internetStatus == 1) {
            GeekModeLogger.log("WarpManager", "Network check: Jamming / Whitelists detected -> Bypassing WARP, using servers")
            return@withContext false
        }
        if (internetStatus == 2) {
            GeekModeLogger.log("WarpManager", "Network check: No internet connection")
            return@withContext false
        }

        if (isWarpInCooldown()) {
            sendStatus(context, context.getString(R.string.status_warp_cooldown_fallback))
            return@withContext false
        }

        val maxAttempts = MmkvManager.decodeSettingsString(AppConfig.PREF_WARP_MAX_ATTEMPTS, "3")?.toIntOrNull()?.coerceAtLeast(1) ?: 3
        val minSpeedMbps = MmkvManager.decodeSettingsString(AppConfig.PREF_WARP_MIN_SPEED_MBPS, "2.0")?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: 2.0
        val bytes = MmkvManager.decodeSettingsString(AppConfig.PREF_WARP_SPEED_CHECK_BYTES, "2000000")?.toLongOrNull() ?: 2_000_000L
        val timeoutMs = MmkvManager.decodeSettingsString(AppConfig.PREF_WARP_SPEED_CHECK_TIMEOUT_MS, "5000")?.toIntOrNull() ?: 5000

        // 1. Try existing cached WARP profile if available
        var currentGuid = getWarpProfileGuid()
        if (currentGuid != null) {
            sendStatus(context, context.getString(R.string.status_checking_warp))
            GeekModeLogger.log("WarpManager", "Testing existing WARP profile: $currentGuid")
            
            val valid = NodeTesterManager.verifyProfile(
                context = context,
                guid = currentGuid,
                showStatus = true,
                customMinMbps = minSpeedMbps,
                customBytes = bytes,
                customTimeoutMs = timeoutMs
            )

            if (valid) {
                GeekModeLogger.log("WarpManager", "Existing WARP profile passed verification. Connecting...")
                resetCooldown()
                connectToWarp(context, currentGuid)
                return@withContext true
            } else {
                GeekModeLogger.log("WarpManager", "Existing WARP profile degraded/failed. Clearing...")
                clearWarpProfile()
            }
        }

        // 2. Если профиля нет (или он устарел): НЕ зависаем с таймаутами на голом интернете!
        // Передаем управление основным серверам, чтобы сначала подключиться к VPN,
        // а затем сгенерировать WARP-профиль через активный туннель.
        GeekModeLogger.log("WarpManager", "No cached WARP profile available. Deferring to servers to generate via VPN.")
        return@withContext false
    }

    /**
     * Генерирует новый WARP-профиль через активное VPN-соединение (локальный прокси)
     * и бесшовно переключает клиента на WARP при наличии чистого интернета.
     */
    suspend fun generateAndMaybeSwitchToWarp(context: Context, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val warpEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_WARP_ENABLED, false)
        if (!warpEnabled && !force) return@withContext false
        if (!force && isWarpInCooldown()) return@withContext false
        if (!force && getWarpProfileGuid() != null) return@withContext false

        val isRunning = V2RayServiceManager.isRunning()
        val socksPort = if (isRunning) SettingsManager.getSocksPort() else 0
        val httpPort = if (isRunning) SettingsManager.getHttpPort() else 0

        val maxAttempts = MmkvManager.decodeSettingsString(AppConfig.PREF_WARP_MAX_ATTEMPTS, "3")?.toIntOrNull()?.coerceAtLeast(1) ?: 3
        val minSpeedMbps = MmkvManager.decodeSettingsString(AppConfig.PREF_WARP_MIN_SPEED_MBPS, "2.0")?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: 2.0
        val bytes = MmkvManager.decodeSettingsString(AppConfig.PREF_WARP_SPEED_CHECK_BYTES, "2000000")?.toLongOrNull() ?: 2_000_000L
        val timeoutMs = MmkvManager.decodeSettingsString(AppConfig.PREF_WARP_SPEED_CHECK_TIMEOUT_MS, "5000")?.toIntOrNull() ?: 5000

        GeekModeLogger.log("WarpManager", "Generating WARP profile (vpnRunning=$isRunning, socks=$socksPort, http=$httpPort, force=$force)...")

        var newProfile: ProfileItem? = null
        for (attempt in 1..maxAttempts) {
            newProfile = WarpGenerator.generateWarpProfile(socksPort, httpPort)
            if (newProfile != null) {
                break
            }
            kotlinx.coroutines.delay(1000)
        }

        if (newProfile == null) {
            GeekModeLogger.log("WarpManager", "Failed to generate WARP profile after $maxAttempts attempts")
            if (!force) triggerCooldown()
            return@withContext false
        }

        val guid = saveWarpProfile(newProfile)
        GeekModeLogger.log("WarpManager", "WARP profile generated successfully: $guid")
        resetCooldown()

        val internetStatus = NetworkManager.checkInternetStatus()
        if (internetStatus == 1) {
            GeekModeLogger.log("WarpManager", "Network is jammed. Keeping WARP profile for clean networks, staying on server.")
            withContext<Unit>(Dispatchers.Main) {
                context.toast(R.string.status_warp_ready_switching)
            }
            return@withContext true
        }

        // Проверяем скорость сгенерированного WARP-профиля
        val valid = NodeTesterManager.verifyProfile(
            context = context,
            guid = guid,
            showStatus = false,
            customMinMbps = minSpeedMbps,
            customBytes = bytes,
            customTimeoutMs = timeoutMs
        )

        if (valid) {
            GeekModeLogger.log("WarpManager", "New WARP profile $guid verified successfully! Switching connection to WARP...")
            sendStatus(context, context.getString(R.string.status_warp_ready_switching))
            withContext<Unit>(Dispatchers.Main) {
                context.toast(R.string.status_warp_ready_switching)
            }
            connectToWarp(context, guid)
            return@withContext true
        } else {
            GeekModeLogger.log("WarpManager", "Generated WARP profile failed speed verification")
            if (!force) {
                clearWarpProfile()
                triggerCooldown()
                return@withContext false
            } else {
                withContext<Unit>(Dispatchers.Main) {
                    context.toast(R.string.status_warp_ready_switching)
                }
                connectToWarp(context, guid)
                return@withContext true
            }
        }
    }

    private suspend fun connectToWarp(context: Context, guid: String) {
        val profile = MmkvManager.decodeServerConfig(guid)
        val remarks = profile?.remarks ?: WARP_SERVER_REMARKS
        sendStatus(context, context.getString(R.string.status_connecting_to, remarks))
        
        MmkvManager.setSelectServer(guid)
        MmkvManager.saveLastConnectedServer(guid)

        val isRunning = V2RayServiceManager.isRunning()
        if (isRunning) {
            MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_SWITCH_SERVER, "")
        } else {
            withContext(Dispatchers.Main) {
                V2RayServiceManager.startVService(context.applicationContext)
            }
        }
    }
}
