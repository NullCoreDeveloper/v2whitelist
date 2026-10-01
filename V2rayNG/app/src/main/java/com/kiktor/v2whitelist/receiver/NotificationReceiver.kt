package com.kiktor.v2whitelist.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.NotificationManager
import com.kiktor.v2whitelist.handler.V2RayServiceManager
import com.kiktor.v2whitelist.util.MessageUtil

class NotificationReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_NEXT = "com.kiktor.v2whitelist.action.notification.next"
        const val ACTION_RESUME = "com.kiktor.v2whitelist.action.notification.resume"
        const val ACTION_STOP = "com.kiktor.v2whitelist.action.notification.stop"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(AppConfig.TAG, "NotificationReceiver received action: $action")
        when (action) {
            ACTION_NEXT -> {
                val currentGuid = MmkvManager.getSelectServer()
                val nextGuid = com.kiktor.v2whitelist.handler.SmartFailoverManager.selectNextServer(currentGuid)
                if (nextGuid != null && nextGuid != currentGuid) {
                    MmkvManager.setSelectServer(nextGuid)
                    MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_SWITCH_SERVER, "")
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
                V2RayServiceManager.stopVService(context)
            }
        }
    }
}
