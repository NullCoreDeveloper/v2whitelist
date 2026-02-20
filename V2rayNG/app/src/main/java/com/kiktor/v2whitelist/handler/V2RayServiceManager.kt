package com.kiktor.v2whitelist.handler

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.enums.EConfigType
import com.kiktor.v2whitelist.dto.ProfileItem
import com.kiktor.v2whitelist.extension.toast
import com.kiktor.v2whitelist.contracts.ServiceControl
import com.kiktor.v2whitelist.service.V2RayProxyOnlyService
import com.kiktor.v2whitelist.service.V2RayVpnService
import com.kiktor.v2whitelist.util.MessageUtil
import com.kiktor.v2whitelist.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import java.lang.ref.SoftReference

object V2RayServiceManager {

    private val coreController: CoreController = V2RayNativeManager.newCoreController(CoreCallback())
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            V2RayNativeManager.initCoreEnv(value?.get()?.getService())
        }

    /**
     * Starts the V2Ray service from a toggle action.
     * @param context The context from which the service is started.
     * @return True if the service was started successfully, false otherwise.
     */
    fun startVServiceFromToggle(context: Context): Boolean {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            context.toast(R.string.app_tile_first_use)
            return false
        }
        startContextService(context)
        return true
    }

    /**
     * Starts the V2Ray service.
     * @param context The context from which the service is started.
     * @param guid The GUID of the server configuration to use (optional).
     */
    fun startVService(context: Context, guid: String? = null) {
        if (guid != null) {
            MmkvManager.setSelectServer(guid)
        }
        startContextService(context)
    }

    /**
     * Stops the V2Ray service.
     * @param context The context from which the service is stopped.
     */
    fun stopVService(context: Context) {
        //context.toast(R.string.toast_services_stop)
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
    }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = coreController.isRunning

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Starts the context service for V2Ray.
     * Chooses between VPN service or Proxy-only service based on user settings.
     * @param context The context from which the service is started.
     */
    private fun startContextService(context: Context) {
        // Не делаем ранний выход при coreController.isRunning — startCoreLoop сам разберётся.
        // Ранний выход здесь был причиной бага: сервис стартовал, но startCoreLoop видел
        // isRunning==true и возвращал false, что вызывало мгновенную остановку VPN.
        Log.d(AppConfig.TAG, "startContextService: coreController.isRunning=${coreController.isRunning}")
        val guid = MmkvManager.getSelectServer() ?: run {
            Log.w(AppConfig.TAG, "startContextService: no selected server, aborting")
            return
        }
        val config = MmkvManager.decodeServerConfig(guid) ?: run {
            Log.w(AppConfig.TAG, "startContextService: failed to decode server config for guid=$guid")
            return
        }
        if (config.configType != EConfigType.CUSTOM
            && config.configType != EConfigType.POLICYGROUP
            && !Utils.isValidUrl(config.server)
            && !Utils.isPureIpAddress(config.server.orEmpty())
        ) {
            Log.w(AppConfig.TAG, "startContextService: invalid server address '${config.server}', aborting")
            return
        }

        GlobalScope.launch(Dispatchers.Main) {
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) {
                context.toast(R.string.toast_warning_pref_proxysharing_short)
            } else {
                context.toast(R.string.toast_services_start)
            }
        }
        val intent = if (SettingsManager.isVpnMode()) {
            Intent(context.applicationContext, V2RayVpnService::class.java)
        } else {
            Intent(context.applicationContext, V2RayProxyOnlyService::class.java)
        }
        Log.i(AppConfig.TAG, "startContextService: launching foreground service for guid=$guid")
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     * Starts the V2Ray core service.
     */
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        Log.i(AppConfig.TAG, "startCoreLoop: called, coreController.isRunning=${coreController.isRunning}")

        // Исправление бага: если ядро считает себя запущенным — принудительно останавливаем его
        // перед новым стартом. Раньше здесь был `return false`, что вызывало мгновенную остановку
        // VPN т.к. startService() → stopAllService() при false.
        if (coreController.isRunning) {
            Log.w(AppConfig.TAG, "startCoreLoop: core is already running, stopping it first...")
            try {
                coreController.stopLoop()
                Thread.sleep(300L)
                Log.i(AppConfig.TAG, "startCoreLoop: old core stopped successfully")
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "startCoreLoop: failed to stop existing core", e)
                // Продолжаем попытку запуска даже если не смогли остановить
            }
        }

        val service = getService() ?: run {
            Log.e(AppConfig.TAG, "startCoreLoop: service is null, aborting")
            return false
        }
        val guid = MmkvManager.getSelectServer() ?: run {
            Log.e(AppConfig.TAG, "startCoreLoop: no selected server, aborting")
            return false
        }
        val config = MmkvManager.decodeServerConfig(guid) ?: run {
            Log.e(AppConfig.TAG, "startCoreLoop: failed to decode config for guid=$guid")
            return false
        }
        Log.d(AppConfig.TAG, "startCoreLoop: building config for '${config.remarks}' (guid=$guid)")
        val result = V2rayConfigManager.getV2rayConfig(service, guid)
        if (!result.status) {
            Log.e(AppConfig.TAG, "startCoreLoop: V2rayConfigManager returned invalid config for guid=$guid")
            return false
        }

        try {
            val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
            mFilter.addAction(Intent.ACTION_SCREEN_ON)
            mFilter.addAction(Intent.ACTION_SCREEN_OFF)
            mFilter.addAction(Intent.ACTION_USER_PRESENT)
            ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())
        } catch (e: Exception) {
            Log.d(AppConfig.TAG, "startCoreLoop: Failed to register broadcast receiver (may already be registered)", e)
            // Не возвращаем false — ресивер мог уже быть зарегистрирован (при switch server)
        }

        currentConfig = config
        var tunFd = vpnInterface?.fd ?: 0
        Log.d(AppConfig.TAG, "startCoreLoop: tunFd=$tunFd, isUsingHevTun=${SettingsManager.isUsingHevTun()}")
        if (SettingsManager.isUsingHevTun()) {
            tunFd = 0
        }

        try {
            Log.i(AppConfig.TAG, "startCoreLoop: calling coreController.startLoop()")
            NotificationManager.showNotification(currentConfig)
            coreController.startLoop(result.content, tunFd)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "startCoreLoop: Failed to start Core loop", e)
            return false
        }

        if (coreController.isRunning == false) {
            Log.e(AppConfig.TAG, "startCoreLoop: core did not start (isRunning=false after startLoop)")
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            NotificationManager.cancelNotification()
            return false
        }

        Log.i(AppConfig.TAG, "startCoreLoop: core started successfully for '${config.remarks}'")
        try {
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
            NotificationManager.startSpeedNotification(currentConfig)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "startCoreLoop: Failed to send startup notifications", e)
            return false
        }
        return true
    }

    /**
     * Stops the V2Ray core service.
     * Unregisters broadcast receivers, stops notifications, and shuts down plugins.
     * @return True if the core was stopped successfully, false otherwise.
     */
    fun stopCoreLoop(): Boolean {
        Log.i(AppConfig.TAG, "stopCoreLoop: called, coreController.isRunning=${coreController.isRunning}")
        val service = getService() ?: run {
            Log.w(AppConfig.TAG, "stopCoreLoop: service is null")
            return false
        }

        if (coreController.isRunning) {
            Log.i(AppConfig.TAG, "stopCoreLoop: stopping V2Ray core loop...")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    coreController.stopLoop()
                    Log.i(AppConfig.TAG, "stopCoreLoop: core stopped successfully")
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "stopCoreLoop: Failed to stop V2Ray loop", e)
                }
            }
        } else {
            Log.d(AppConfig.TAG, "stopCoreLoop: core was not running, skipping stopLoop")
        }

        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        NotificationManager.cancelNotification()

        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            Log.d(AppConfig.TAG, "stopCoreLoop: Failed to unregister broadcast receiver (may already be unregistered)", e)
        }

        return true
    }

    /**
     * Queries the statistics for a given tag and link.
     * @param tag The tag to query.
     * @param link The link to query.
     * @return The statistics value.
     */
    fun queryStats(tag: String, link: String): Long {
        return coreController.queryStats(tag, link)
    }

    /**
     * Measures the connection delay for the current V2Ray configuration.
     * Tests with primary URL first, then falls back to alternative URL if needed.
     * Also fetches remote IP information if the delay test was successful.
     */
    private fun measureV2rayDelay() {
        if (coreController.isRunning == false) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val service = getService() ?: return@launch
            var time = -1L
            var errorStr = ""

            try {
                time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to measure delay with primary URL", e)
                errorStr = e.message?.substringAfter("\":") ?: "empty message"
            }
            if (time == -1L) {
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                } catch (e: Exception) {
                    Log.v(AppConfig.TAG, "Failed to measure delay with alternative URL", e)
                    errorStr = e.message?.substringAfter("\":") ?: "empty message"
                }
            }

            val result = if (time >= 0) {
                service.getString(R.string.connection_test_available, time)
            } else {
                service.getString(R.string.connection_test_error, errorStr)
            }
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, result)

            // Only fetch IP info if the delay test was successful
            if (time >= 0) {
                SpeedtestManager.getRemoteIPInfo()?.let { ip ->
                    MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, "$result\n$ip")
                }
            }
        }
    }

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         * @return 0 for success, any other value for failure.
         */
        override fun shutdown(): Long {
            val serviceControl = serviceControl?.get() ?: return -1
            return try {
                serviceControl.stopService()
                0
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to stop service in callback", e)
                -1
            }
        }

        /**
         * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            return 0
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (coreController.isRunning) {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    Log.i(AppConfig.TAG, "Stop Service")
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    Log.i(AppConfig.TAG, "Restart Service")
                    serviceControl.stopService()
                    Thread.sleep(500L)
                    startVService(serviceControl.getService())
                }

                AppConfig.MSG_STATE_SWITCH_SERVER -> {
                    Log.i(AppConfig.TAG, "MSG_STATE_SWITCH_SERVER: switching server, coreRunning=${coreController.isRunning}")
                    val vpnInterface = serviceControl.getVpnInterface()
                    Log.d(AppConfig.TAG, "MSG_STATE_SWITCH_SERVER: vpnInterface=${vpnInterface?.fd}")
                    val guid = MmkvManager.getSelectServer()
                    Log.i(AppConfig.TAG, "MSG_STATE_SWITCH_SERVER: target guid=$guid")
                    stopCoreLoop()
                    Thread.sleep(1000L) // Wait for core to fully release resources
                    Log.i(AppConfig.TAG, "MSG_STATE_SWITCH_SERVER: starting core loop after switch")
                    val success = startCoreLoop(vpnInterface)
                    Log.i(AppConfig.TAG, "MSG_STATE_SWITCH_SERVER: startCoreLoop result=$success")
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    measureV2rayDelay()
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(AppConfig.TAG, "SCREEN_OFF, stop querying stats")
                    NotificationManager.stopSpeedNotification(currentConfig)
                }

                Intent.ACTION_SCREEN_ON -> {
                    Log.i(AppConfig.TAG, "SCREEN_ON, start querying stats")
                    NotificationManager.startSpeedNotification(currentConfig)
                }
            }
        }
    }
}