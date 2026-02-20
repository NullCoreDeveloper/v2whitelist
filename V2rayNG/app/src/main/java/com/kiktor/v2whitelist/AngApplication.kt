package com.kiktor.v2whitelist

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log
import androidx.multidex.MultiDexApplication
import androidx.work.Configuration
import androidx.work.WorkManager
import com.tencent.mmkv.MMKV
import com.kiktor.v2whitelist.AppConfig.ANG_PACKAGE
import com.kiktor.v2whitelist.handler.SettingsManager
import com.kiktor.v2whitelist.handler.SmartConnectManager
import com.kiktor.v2whitelist.handler.V2RayNativeManager
import com.kiktor.v2whitelist.service.SubscriptionUpdaterWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AngApplication : MultiDexApplication() {
    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    private val workManagerConfiguration: Configuration = Configuration.Builder()
        .setDefaultProcessName("${ANG_PACKAGE}:bg")
        .build()

    /**
     * Checks if the current process is the main application process.
     */
    private fun isMainProcess(): Boolean {
        val pid = Process.myPid()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.runningAppProcesses?.any { it.pid == pid && it.processName == packageName } == true
    }

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()

        val isMain = isMainProcess()
        Log.i(AppConfig.TAG, "AngApplication.onCreate: pid=${Process.myPid()}, isMainProcess=$isMain")

        MMKV.initialize(this)

        // Ensure critical preference defaults are present in MMKV early
        SettingsManager.ensureDefaultSettings()

        // Initialize V2Ray core environment globally (needed in all processes)
        V2RayNativeManager.initCoreEnv(this)

        // The rest only runs in the main process
        if (isMain) {
            SettingsManager.setNightMode()
            // Initialize WorkManager with the custom configuration
            WorkManager.initialize(this, workManagerConfiguration)

            SettingsManager.initRoutingRulesets(this)
            SettingsManager.migrateHysteria2PinSHA256()

            es.dmoral.toasty.Toasty.Config.getInstance()
                .setGravity(android.view.Gravity.BOTTOM, 0, 200)
                .apply()

            CoroutineScope(Dispatchers.Main).launch {
                SmartConnectManager.checkAndSetupSubscription(this@AngApplication)
            }

            // Регистрируем фоновое обновление подписки раз в час
            SubscriptionUpdaterWorker.schedule(this)
        } else {
            Log.i(AppConfig.TAG, "AngApplication.onCreate: service process, skipping UI/SmartConnect init")
        }
    }
}
