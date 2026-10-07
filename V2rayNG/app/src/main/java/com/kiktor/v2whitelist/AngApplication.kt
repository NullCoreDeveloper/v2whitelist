package com.kiktor.v2whitelist

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
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
import com.kiktor.v2whitelist.handler.SubscriptionHelper

class AngApplication : Application(), Configuration.Provider {
    companion object {
        lateinit var application: AngApplication
        private var currentActivityRef: java.lang.ref.WeakReference<android.app.Activity>? = null

        fun getCurrentActivity(): android.app.Activity? = currentActivityRef?.get()
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    /**
     * Checks if the current process is the main application process.
     */
    private fun isMainProcess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return getProcessName() == packageName
        }
        val pid = Process.myPid()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.runningAppProcesses?.any { it.pid == pid && it.processName == packageName } == true
    }

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityResumed(activity: android.app.Activity) {
                currentActivityRef = java.lang.ref.WeakReference(activity)
            }
            override fun onActivityPaused(activity: android.app.Activity) {
                if (currentActivityRef?.get() == activity) {
                    currentActivityRef = null
                }
            }
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {
                if (currentActivityRef?.get() == activity) {
                    currentActivityRef = null
                }
            }
        })

        val isMain = isMainProcess()
        Log.i(AppConfig.TAG, "AngApplication.onCreate: pid=${Process.myPid()}, isMainProcess=$isMain")

        MMKV.initialize(this)

        // Ensure critical preference defaults are present in MMKV early
        SettingsManager.ensureDefaultSettings()

        // Initialize V2Ray core environment globally (needed in all processes)
        V2RayNativeManager.initCoreEnv(this)
        

        // The rest only runs in the main process
        if (isMain) {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val daemonProcName = "$packageName:RunSoLibV2RayDaemon"
            val daemonAlive = am?.runningAppProcesses?.any { it.processName == daemonProcName } == true
            if (!daemonAlive) {
                com.kiktor.v2whitelist.handler.MmkvManager.encodeSettings(AppConfig.PREF_IS_SERVICE_RUNNING, false)
            }

            SettingsManager.setNightMode()

            if (com.google.android.material.color.DynamicColors.isDynamicColorAvailable()) {
                com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(
                    this,
                    com.google.android.material.color.DynamicColorsOptions.Builder()
                        .setPrecondition { _, _ -> SettingsManager.isDynamicColorEnabled() }
                        .build()
                )
            }

            SettingsManager.initRoutingRulesets(this)
            SettingsManager.migrateHysteria2PinSHA256()
            SettingsManager.migrateV2wCoreDefaults()
            SettingsManager.migrateSpeedCheckTimeoutDefaults()

            es.dmoral.toasty.Toasty.Config.getInstance()
                .setGravity(android.view.Gravity.BOTTOM, 0, 200)
                .apply()

            CoroutineScope(Dispatchers.Main).launch {
                SubscriptionHelper.checkAndSetupSubscription(this@AngApplication)
            }

            // Регистрируем фоновое обновление подписки раз в час
            SubscriptionUpdaterWorker.schedule(this)
        } else {
            Log.i(AppConfig.TAG, "AngApplication.onCreate: service process, skipping UI/SmartConnect init")
        }
    }
}
