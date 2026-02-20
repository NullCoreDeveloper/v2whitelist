package com.kiktor.v2whitelist.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.util.Log
import androidx.annotation.RequiresApi
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.AppConfig.LOOPBACK
import com.kiktor.v2whitelist.BuildConfig
import com.kiktor.v2whitelist.contracts.ServiceControl
import com.kiktor.v2whitelist.contracts.Tun2SocksControl
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.NotificationManager
import com.kiktor.v2whitelist.handler.SettingsManager
import com.kiktor.v2whitelist.handler.V2RayServiceManager
import com.kiktor.v2whitelist.util.MyContextWrapper
import com.kiktor.v2whitelist.util.Utils
import java.lang.ref.SoftReference

class V2RayVpnService : VpnService(), ServiceControl {
    private var mInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var tun2SocksService: Tun2SocksControl? = null

    /**destroy
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface: https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                // it's a good idea to refresh capabilities
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        V2RayServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        stopAllService()
    }

    override fun getVpnInterface(): ParcelFileDescriptor? {
        return mInterface
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

    override fun onDestroy() {
        super.onDestroy()
        NotificationManager.cancelNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(AppConfig.TAG, "onStartCommand: VPN service starting (pid=${android.os.Process.myPid()})")
        NotificationManager.showNotification(null)

        if (!setupVpnService()) {
            Log.e(AppConfig.TAG, "onStartCommand: setupVpnService failed, service will stop")
            return START_NOT_STICKY
        }

        try {
            startService()
        } catch (t: Throwable) {
            Log.e(AppConfig.TAG, "onStartCommand: startService() threw exception", t)
            stopAllService()
        }
        return START_STICKY
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        Log.i(AppConfig.TAG, "startService: entering method (pid=${android.os.Process.myPid()})")
        try {
            val vpnInterface = mInterface
            if (vpnInterface == null) {
                Log.e(AppConfig.TAG, "startService: VPN interface is null — VPN not established, stopping service")
                stopAllService()
                return
            }
            Log.i(AppConfig.TAG, "startService: mInterface is not null, about to access fd...")
            val fd = try { vpnInterface.fd } catch (e: Exception) {
                Log.e(AppConfig.TAG, "startService: vpnInterface.fd threw exception (fd already closed?)", e)
                stopAllService()
                return
            }
            Log.i(AppConfig.TAG, "startService: VPN interface OK (fd=$fd), calling startCoreLoop...")
            val result = V2RayServiceManager.startCoreLoop(vpnInterface)
            Log.i(AppConfig.TAG, "startService: startCoreLoop returned $result")
            if (!result) {
                Log.e(AppConfig.TAG, "startService: startCoreLoop returned false, stopping service")
                stopAllService()
                return
            }
            Log.i(AppConfig.TAG, "startService: core loop started, now starting tun2socks...")

            // tun2socks запускается DOPO старта V2Ray core, чтобы SOCKS5 порт уже слушал
            try {
                runTun2socks()
                Log.i(AppConfig.TAG, "startService: tun2socks started successfully")
            } catch (t: Throwable) {
                Log.e(AppConfig.TAG, "startService: runTun2socks() crashed", t)
                // Не останавливаем сервис: V2Ray core уже работает, tun2socks опционален
            }

            Log.i(AppConfig.TAG, "startService: fully started")
        } catch (t: Throwable) {
            Log.e(AppConfig.TAG, "startService: FATAL exception", t)
            stopAllService()
        }
    }

    override fun stopService() {
        stopAllService(true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }

    /**
     * Sets up the VPN service.
     * Prepares the VPN and configures it if preparation is successful.
     * @return True if setup was successful, false otherwise.
     */
    private fun setupVpnService(): Boolean {
        val prepare = prepare(this)
        if (prepare != null) {
            Log.e(AppConfig.TAG, "setupVpnService: VPN prepare() returned non-null (permission not granted), stopping")
            stopSelf()
            return false
        }
        Log.i(AppConfig.TAG, "setupVpnService: VPN permission OK, configuring interface...")

        if (configureVpnService() != true) {
            Log.e(AppConfig.TAG, "setupVpnService: configureVpnService failed, stopping")
            stopSelf()
            return false
        }
        Log.i(AppConfig.TAG, "setupVpnService: VPN interface configured, mInterface=${mInterface?.fd}")

        // ВАЖНО: runTun2socks() теперь вызывается в startService() ПОСЛЕ startCoreLoop,
        // чтобы V2Ray core был запущен и SOCKS5 порт слушал к моменту старта tun2socks.
        return true
    }

    /**
     * Configures the VPN service.
     * @return True if the VPN service was configured successfully, false otherwise.
     */
    private fun configureVpnService(): Boolean {
        val builder = Builder()

        // Configure network settings (addresses, routing and DNS)
        configureNetworkSettings(builder)

        // Configure app-specific settings (session name and per-app proxy)
        configurePerAppProxy(builder)

        // Close the old interface since the parameters have been changed
        try {
            mInterface?.close()
        } catch (ignored: Exception) {
            // ignored
        }

        // Configure platform-specific features
        configurePlatformFeatures(builder)

        // Create a new interface using the builder and save the parameters
        try {
            val vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(AppConfig.TAG, "configureVpnService: builder.establish() returned null (VPN revoked?)")
                return false
            }
            mInterface = vpnInterface
            isRunning = true
            Log.i(AppConfig.TAG, "configureVpnService: VPN interface established, fd=${vpnInterface.fd}")
            return true
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "configureVpnService: Failed to establish VPN interface", e)
            stopAllService()
        }
        return false
    }

    /**
     * Configures the basic network settings for the VPN.
     * This includes IP addresses, routing rules, and DNS servers.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configureNetworkSettings(builder: Builder) {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val bypassLan = SettingsManager.routingRulesetsBypassLan()

        // Configure IPv4 settings
        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 30)

        // Configure routing rules
        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        // Configure IPv6 if enabled
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6) == true) {
            builder.addAddress(vpnConfig.ipv6Client, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3) // Currently only 1/8 of total IPv6 is in use
                builder.addRoute("fc00::", 18) // Xray-core default FakeIPv6 Pool
            } else {
                builder.addRoute("::", 0)
            }
        }

        // Configure DNS servers
        //if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
        //  builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        //} else {
        SettingsManager.getVpnDnsServers().forEach {
            if (Utils.isPureIpAddress(it)) {
                builder.addDnsServer(it)
            }
        }

        builder.setSession(V2RayServiceManager.getRunningServerName())
    }

    /**
     * Configures platform-specific VPN features for different Android versions.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configurePlatformFeatures(builder: Builder) {
        // Android P (API 28) and above: Configure network callbacks
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to request default network", e)
            }
        }

        // Android Q (API 29) and above: Configure metering and HTTP proxy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            }
        }
    }

    /**
     * Configures per-app proxy rules for the VPN builder.
     *
     * - If per-app proxy is not enabled, disallow the VPN service's own package.
     * - If no apps are selected, disallow the VPN service's own package.
     * - If bypass mode is enabled, disallow all selected apps (including self).
     * - If proxy mode is enabled, only allow the selected apps (excluding self).
     *
     * @param builder The VPN Builder to configure.
     */
    private fun configurePerAppProxy(builder: Builder) {
        val selfPackageName = BuildConfig.APPLICATION_ID

        // If per-app proxy is not enabled, disallow the VPN service's own package and return
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == false) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        // If no apps are selected, disallow the VPN service's own package and return
        val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (apps.isNullOrEmpty()) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        // Handle the VPN service's own package according to the mode
        if (bypassApps) apps.add(selfPackageName) else apps.remove(selfPackageName)

        apps.forEach {
            try {
                if (bypassApps) {
                    // In bypass mode, disallow the selected apps
                    builder.addDisallowedApplication(it)
                } else {
                    // In proxy mode, only allow the selected apps
                    builder.addAllowedApplication(it)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(AppConfig.TAG, "Failed to configure app in VPN: ${e.localizedMessage}", e)
            }
        }
    }

    /**
     * Runs the tun2socks process.
     * Starts the tun2socks process with the appropriate parameters.
     */
    private fun runTun2socks() {
        if (SettingsManager.isUsingHevTun()) {
            val vpnInterface = mInterface
            if (vpnInterface != null) {
                tun2SocksService = TProxyService(
                    context = applicationContext,
                    vpnInterface = vpnInterface,
                    isRunningProvider = { isRunning },
                    restartCallback = { runTun2socks() }
                )
            } else {
                Log.v(AppConfig.TAG, "VPN interface is null, cannot start tun2socks")
                tun2SocksService = null
            }
        } else {
            tun2SocksService = null
        }

        tun2SocksService?.startTun2Socks()
    }

    private fun stopAllService(isForced: Boolean = true) {
//        val configName = defaultDPreference.getPrefString(PREF_CURR_CONFIG_GUID, "")
//        val emptyInfo = VpnNetworkInfo()
//        val info = loadVpnNetworkInfo(configName, emptyInfo)!! + (lastNetworkInfo ?: emptyInfo)
//        saveVpnNetworkInfo(configName, info)
        isRunning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.unregisterNetworkCallback(defaultNetworkCallback)
            } catch (ignored: Exception) {
                // ignored
            }
        }

        tun2SocksService?.stopTun2Socks()
        tun2SocksService = null

        V2RayServiceManager.stopCoreLoop()

        if (isForced) {
            //stopSelf has to be called ahead of mInterface.close(). otherwise v2ray core cannot be stooped
            //It's strage but true.
            //This can be verified by putting stopself() behind and call stopLoop and startLoop
            //in a row for several times. You will find that later created v2ray core report port in use
            //which means the first v2ray core somehow failed to stop and release the port.
            stopSelf()

            try {
                mInterface?.close()
                mInterface = null
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to close VPN interface", e)
            }
        }
    }
}

