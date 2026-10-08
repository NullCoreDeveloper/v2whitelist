package com.kiktor.v2whitelist.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.handler.SettingsManager
import com.kiktor.v2whitelist.handler.SmartConnectManager
import com.kiktor.v2whitelist.handler.V2RayServiceManager
import com.kiktor.v2whitelist.ui.MainActivity
import com.kiktor.v2whitelist.util.MessageUtil
import com.kiktor.v2whitelist.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.ref.SoftReference

class QSTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main)

    /**
     * Sets the state of the tile.
     * @param state The state to set.
     */
    fun setState(state: Int) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(applicationContext, R.drawable.ic_qs_probel)
        when (state) {
            Tile.STATE_INACTIVE -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.app_tile_name)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.connection_not_connected)
                }
            }
            Tile.STATE_ACTIVE -> {
                tile.state = Tile.STATE_ACTIVE
                val serverName = V2RayServiceManager.getRunningServerName()
                tile.label = if (serverName.isNotBlank()) serverName else getString(R.string.app_tile_name)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.tv_status_protected)
                }
            }
            Tile.STATE_UNAVAILABLE -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = getString(R.string.app_tile_name)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.status_starting_smart_connect)
                }
            }
        }
        tile.updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()

        when {
            V2RayServiceManager.isRunning() -> setState(Tile.STATE_ACTIVE)
            SmartConnectManager.isScanning.get() -> setState(Tile.STATE_UNAVAILABLE)
            else -> setState(Tile.STATE_INACTIVE)
        }

        mMsgReceive = ReceiveMessageHandler(this)
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        ContextCompat.registerReceiver(applicationContext, mMsgReceive, mFilter, Utils.receiverFlags())
        MessageUtil.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    override fun onStopListening() {
        super.onStopListening()
        try {
            mMsgReceive?.let {
                applicationContext.unregisterReceiver(it)
                mMsgReceive = null
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to unregister receiver", e)
        }
    }

    override fun onClick() {
        super.onClick()

        // 1. Если VPN уже работает — выключаем
        if (V2RayServiceManager.isRunning()) {
            V2RayServiceManager.stopVService(this)
            setState(Tile.STATE_INACTIVE)
            return
        }

        // 2. Если уже идёт сканирование — отменяем
        if (SmartConnectManager.isScanning.get()) {
            SmartConnectManager.isScanning.set(false)
            setState(Tile.STATE_INACTIVE)
            return
        }

        // 3. Проверяем системное разрешение на VPN
        if (SettingsManager.isVpnMode()) {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(MainActivity.EXTRA_AUTO_CONNECT, true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pendingIntent = PendingIntent.getActivity(
                        this, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pendingIntent)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
                return
            }
        }

        // 4. Запускаем умное подключение ровно так же, как из приложения!
        setState(Tile.STATE_UNAVAILABLE)
        serviceScope.launch {
            try {
                val success = SmartConnectManager.smartConnect(applicationContext)
                if (!success && !V2RayServiceManager.isRunning()) {
                    setState(Tile.STATE_INACTIVE)
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "QSTileService: SmartConnect failed", e)
                if (!V2RayServiceManager.isRunning()) {
                    setState(Tile.STATE_INACTIVE)
                }
            }
        }
    }

    private var mMsgReceive: BroadcastReceiver? = null

    private class ReceiveMessageHandler(context: QSTileService) : BroadcastReceiver() {
        var mReference: SoftReference<QSTileService> = SoftReference(context)
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val context = mReference.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> {
                    context.setState(Tile.STATE_ACTIVE)
                }
                AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_START_FAILURE, AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    context.setState(Tile.STATE_INACTIVE)
                }
            }
        }
    }
}
