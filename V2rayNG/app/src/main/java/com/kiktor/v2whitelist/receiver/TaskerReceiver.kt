package com.kiktor.v2whitelist.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.handler.FullScanManager
import com.kiktor.v2whitelist.handler.GeekModeLogger
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.NotificationManager
import com.kiktor.v2whitelist.handler.SettingsManager
import com.kiktor.v2whitelist.handler.SmartConnectManager
import com.kiktor.v2whitelist.handler.V2RayServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class TaskerReceiver : BroadcastReceiver() {

    private sealed class ExternalCommand {
        data object Connect : ExternalCommand()
        data object Disconnect : ExternalCommand()
        data object Toggle : ExternalCommand()
        data object SwitchServer : ExternalCommand()
        data class ConnectServer(val guid: String) : ExternalCommand()
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private const val RECEIVER_HOLD_TIMEOUT_MS = 8000L
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        try {
            if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_EXTERNAL_CONTROL_ENABLED, false)) {
                GeekModeLogger.log("ExternalControl", "Ignored external intent (${intent?.action}): disabled in settings")
                return
            }

            val cmd = resolveCommand(intent) ?: return
            GeekModeLogger.log("ExternalControl", "Received command: $cmd (action=${intent?.action})")

            when (cmd) {
                is ExternalCommand.Disconnect -> performDisconnect(appContext)
                is ExternalCommand.Toggle -> handleToggle(appContext)
                is ExternalCommand.Connect,
                is ExternalCommand.SwitchServer,
                is ExternalCommand.ConnectServer -> launchAsyncCommand(appContext, cmd)
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Error processing external control broadcast", e)
        }
    }

    private fun resolveCommand(intent: Intent?): ExternalCommand? {
        if (intent == null) return null
        return when (intent.action) {
            AppConfig.ACTION_EXTERNAL_CONNECT -> {
                val guid = intent.getStringExtra("guid").orEmpty().trim()
                if (guid.isNotEmpty() && MmkvManager.decodeServerConfig(guid) != null) {
                    ExternalCommand.ConnectServer(guid)
                } else {
                    ExternalCommand.Connect
                }
            }
            AppConfig.ACTION_EXTERNAL_DISCONNECT -> ExternalCommand.Disconnect
            AppConfig.ACTION_EXTERNAL_TOGGLE -> ExternalCommand.Toggle
            AppConfig.ACTION_EXTERNAL_SWITCH_SERVER -> ExternalCommand.SwitchServer
            "com.twofortyfouram.locale.intent.action.FIRE_SETTING" -> resolveTaskerBundle(intent)
            else -> null
        }
    }

    private fun resolveTaskerBundle(intent: Intent): ExternalCommand? {
        val bundle = intent.getBundleExtra(AppConfig.TASKER_EXTRA_BUNDLE) ?: return null
        val action = bundle.getString(AppConfig.TASKER_EXTRA_BUNDLE_ACTION)
        val guid = bundle.getString(AppConfig.TASKER_EXTRA_BUNDLE_GUID).orEmpty().trim()

        if (!action.isNullOrBlank()) {
            return when (action) {
                AppConfig.TASKER_ACTION_CONNECT -> ExternalCommand.Connect
                AppConfig.TASKER_ACTION_DISCONNECT -> ExternalCommand.Disconnect
                AppConfig.TASKER_ACTION_TOGGLE -> ExternalCommand.Toggle
                AppConfig.TASKER_ACTION_SWITCH -> ExternalCommand.SwitchServer
                AppConfig.TASKER_ACTION_CONNECT_SERVER -> {
                    if (guid.isNotEmpty() && guid != AppConfig.TASKER_DEFAULT_GUID && MmkvManager.decodeServerConfig(guid) != null) {
                        ExternalCommand.ConnectServer(guid)
                    } else {
                        ExternalCommand.Connect
                    }
                }
                else -> ExternalCommand.Connect
            }
        }

        if (!bundle.containsKey(AppConfig.TASKER_EXTRA_BUNDLE_SWITCH)) {
            return null
        }
        val legacySwitch = bundle.getBoolean(AppConfig.TASKER_EXTRA_BUNDLE_SWITCH, false)
        if (!legacySwitch) {
            return ExternalCommand.Disconnect
        }
        return if (guid.isNotEmpty() && guid != AppConfig.TASKER_DEFAULT_GUID && MmkvManager.decodeServerConfig(guid) != null) {
            ExternalCommand.ConnectServer(guid)
        } else {
            ExternalCommand.Connect
        }
    }

    private fun performDisconnect(appContext: Context) {
        FullScanManager.stopFullScan()
        SmartConnectManager.isScanning.set(false)
        NotificationManager.cancelScanningNotification(appContext)
        V2RayServiceManager.stopVService(appContext)
    }

    private fun handleToggle(appContext: Context) {
        when {
            V2RayServiceManager.isRunning() -> performDisconnect(appContext)
            SmartConnectManager.isScanning.get() -> {
                SmartConnectManager.isScanning.set(false)
                NotificationManager.cancelScanningNotification(appContext)
            }
            else -> launchAsyncCommand(appContext, ExternalCommand.Connect)
        }
    }

    private fun launchAsyncCommand(appContext: Context, cmd: ExternalCommand) {
        if (SettingsManager.isVpnMode() && VpnService.prepare(appContext) != null) {
            GeekModeLogger.log("ExternalControl", "Cannot start VPN from background: VPN permission not granted")
            return
        }

        FullScanManager.stopFullScan()

        val pendingResult = goAsync()
        val finished = AtomicBoolean(false)
        val finishOnce = {
            if (finished.compareAndSet(false, true)) {
                try {
                    pendingResult.finish()
                } catch (_: Exception) {}
            }
        }

        Handler(Looper.getMainLooper()).postDelayed(finishOnce, RECEIVER_HOLD_TIMEOUT_MS)

        scope.launch {
            try {
                when (cmd) {
                    is ExternalCommand.Connect -> SmartConnectManager.smartConnect(appContext)
                    is ExternalCommand.SwitchServer -> SmartConnectManager.switchServer(appContext)
                    is ExternalCommand.ConnectServer -> {
                        if (MmkvManager.decodeServerConfig(cmd.guid) != null) {
                            SmartConnectManager.connectDirectlyTo(appContext, cmd.guid)
                        } else {
                            SmartConnectManager.smartConnect(appContext)
                        }
                    }
                    is ExternalCommand.Disconnect,
                    is ExternalCommand.Toggle -> Unit
                }
            } catch (e: Exception) {
                GeekModeLogger.log("ExternalControl", "Command $cmd failed: ${e.message}")
            } finally {
                finishOnce()
            }
        }
    }
}
