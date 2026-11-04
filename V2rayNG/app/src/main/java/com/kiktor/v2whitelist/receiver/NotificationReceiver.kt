package com.kiktor.v2whitelist.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.NotificationManager
import com.kiktor.v2whitelist.handler.V2RayServiceManager
import com.kiktor.v2whitelist.util.MessageUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_NEXT = "com.kiktor.v2whitelist.action.notification.next"
        const val ACTION_RESUME = "com.kiktor.v2whitelist.action.notification.resume"
        const val ACTION_STOP = "com.kiktor.v2whitelist.action.notification.stop"
        const val ACTION_RESTART = "com.kiktor.v2whitelist.action.notification.restart"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(AppConfig.TAG, "NotificationReceiver received action: $action")
        when (action) {
            ACTION_NEXT -> {
                // Та же логика, что у кнопки «Следующий профиль» в UI: SmartConnectManager.switchServer.
                // ВАЖНО: флаг isScanning здесь НЕ выставляем — switchServer сам захватывает его через
                // compareAndSet(false, true); раньше предварительный set(true) приводил к тому, что
                // switchServer считал, что поиск уже идёт, и молча выходил (переключение из шторки не работало).
                if (com.kiktor.v2whitelist.handler.SmartConnectManager.isScanning.get()) {
                    Log.i(AppConfig.TAG, "NotificationReceiver: switch already in progress, ignoring")
                    return
                }
                MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE, context.getString(R.string.status_switching_server))
                // goAsync() не используем: переключение может идти дольше 10 секунд (лимит ресивера),
                // процесс при этом живёт, т.к. работает foreground-сервис VPN.
                val appContext = context.applicationContext
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        com.kiktor.v2whitelist.handler.SmartConnectManager.switchServer(appContext)
                    } catch (e: Exception) {
                        Log.e(AppConfig.TAG, "NotificationReceiver: switchServer failed", e)
                        com.kiktor.v2whitelist.handler.SmartConnectManager.isScanning.set(false)
                    }
                }
            }
            ACTION_RESUME -> {
                NotificationManager.cancelPausedNotification(context)
                MmkvManager.encodeSettings(AppConfig.PREF_IS_PAUSED, false)
                val pausedGuid = MmkvManager.decodeSettingsString(AppConfig.PREF_PAUSED_SERVER_GUID)
                if (!pausedGuid.isNullOrBlank()) {
                    MmkvManager.setSelectServer(pausedGuid)
                }
                V2RayServiceManager.startVService(context)
            }
            ACTION_STOP -> {
                NotificationManager.cancelPausedNotification(context)
                MmkvManager.encodeSettings(AppConfig.PREF_IS_PAUSED, false)
                V2RayServiceManager.stopVService(context.applicationContext)
            }
            ACTION_RESTART -> {
                NotificationManager.cancelPausedNotification(context)
                MmkvManager.encodeSettings(AppConfig.PREF_IS_PAUSED, false)
                V2RayServiceManager.stopVService(context.applicationContext)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    V2RayServiceManager.startVService(context.applicationContext)
                }, 500L)
            }
        }
    }
}
