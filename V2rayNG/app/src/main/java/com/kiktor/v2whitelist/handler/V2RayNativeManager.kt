package com.kiktor.v2whitelist.handler

import android.content.Context
import android.util.Log
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.util.Utils
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V2Ray Native Library Manager
 *
 * Thread-safe singleton wrapper for Libv2ray native methods.
 * Provides initialization protection and unified API for V2Ray core operations.
 */
object V2RayNativeManager {
    private val initialized = AtomicBoolean(false)

    /**
     * Initialize V2Ray core environment.
     * This method is thread-safe and ensures initialization happens only once.
     * Subsequent calls will be ignored silently.
     *
     */
    fun initCoreEnv(context: Context?) {
        if (initialized.compareAndSet(false, true)) {
            try {
                val appContext = context?.applicationContext
                Seq.setContext(appContext)
                val assetPath = Utils.userAssetPath(appContext)

                // Ensure assets are copied in the current process if missing
                if (appContext != null) {
                    SettingsManager.initAssets(appContext, appContext.assets)
                }

                // Explicitly set asset location for Xray/V2Ray core via environment variables
                try {
                    android.system.Os.setenv("V2RAY_LOCATION_ASSET", assetPath, true)
                    android.system.Os.setenv("XRAY_LOCATION_ASSET", assetPath, true)
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Failed to set asset path environment variables", e)
                }

                val deviceId = Utils.getDeviceIdForXUDPBaseKey()
                Libv2ray.initCoreEnv(assetPath, deviceId)
                Log.d(AppConfig.TAG, "V2Ray core environment initialized successfully at $assetPath")

                // Verification check
                val geosite = java.io.File(assetPath, "geosite.dat")
                if (!geosite.exists()) {
                    Log.e(AppConfig.TAG, "CRITICAL: geosite.dat MISSING at ${geosite.absolutePath}")
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to initialize V2Ray core environment", e)
                initialized.set(false)
                throw e
            }
        } else {
            Log.d(AppConfig.TAG, "V2Ray core environment already initialized, skipping")
        }
    }


    /**
     * Get V2Ray core version.
     *
     * @return Version string of the V2Ray core
     */
    fun getLibVersion(): String {
        return try {
            Libv2ray.checkVersionX()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to check V2Ray version", e)
            "Unknown"
        }
    }

    /**
     * Measure outbound connection delay.
     *
     * @param config The configuration JSON string
     * @param testUrl The URL to test against
     * @return Delay in milliseconds, or -1 if test failed
     */
    fun measureOutboundDelay(config: String, testUrl: String): Long {
        return try {
            Libv2ray.measureOutboundDelay(config, testUrl)
        } catch (e: Exception) {
            Log.d(AppConfig.TAG, "Failed to measure outbound delay: ${e.message}")
            -1L
        }
    }

    /**
     * Create a new core controller instance.
     *
     * @param handler The callback handler for core events
     * @return A new CoreController instance
     */
    fun newCoreController(handler: CoreCallbackHandler): CoreController {
        return try {
            Libv2ray.newCoreController(handler)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to create core controller", e)
            throw e
        }
    }
}
