package com.kiktor.v2whitelist.ui

import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.Window
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.databinding.ActivityMainBinding
import com.kiktor.v2whitelist.enums.EConfigType
import com.kiktor.v2whitelist.enums.PermissionType
import com.kiktor.v2whitelist.extension.toast
import com.kiktor.v2whitelist.extension.toastError
import com.kiktor.v2whitelist.handler.AngConfigManager
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.SettingsChangeManager
import com.kiktor.v2whitelist.handler.SettingsManager
import com.kiktor.v2whitelist.handler.SmartConnectManager
import com.kiktor.v2whitelist.handler.V2RayServiceManager
import com.kiktor.v2whitelist.util.Utils
import com.kiktor.v2whitelist.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()
    private var activeJob: Job? = null
    private var isTaskRunning = false

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
    }
    private val scannerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val scanResult = result.data?.getStringExtra("SCAN_RESULT") ?: return@registerForActivityResult
            val oldServers = MmkvManager.decodeServerList()
            val (count, _) = com.kiktor.v2whitelist.handler.AngConfigManager.importBatchConfig(scanResult, "", true)
            if (count > 0) {
                toast(getString(R.string.title_import_config_count, count))
                mainViewModel.reloadServerList()
                
                // Авто-подключение после сканирования
                val newServers = MmkvManager.decodeServerList()
                val addedGuid = newServers.firstOrNull { !oldServers.contains(it) }

                if (addedGuid != null) {
                    MmkvManager.setSelectServer(addedGuid)
                    MmkvManager.saveLastConnectedServer(addedGuid)
                    if (mainViewModel.isRunning.value == true) {
                        com.kiktor.v2whitelist.util.MessageUtil.sendMsg2Service(this, AppConfig.MSG_STATE_SWITCH_SERVER, "")
                    } else {
                        startV2Ray()
                    }
                } else {
                    handleConnectAction()
                }
                
                // Умный догруз подписок: если серверов почти нет, обновляем их через новый туннель
                val totalServers = MmkvManager.decodeServerList().size
                if (totalServers <= count + 1) { // Если были пустыми или только этот сервер
                    lifecycleScope.launch {
                        delay(5000) // Ждем 5 сек, пока VPN поднимется
                        if (mainViewModel.isRunning.value == true) {
                            com.kiktor.v2whitelist.handler.SmartConnectManager.updateSubscription(this@MainActivity)
                            mainViewModel.reloadServerList()
                        }
                    }
                }
            } else {
                toast(R.string.toast_incorrect_protocol)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.btnBigConnect.setOnClickListener { handleConnectAction() }
        binding.btnSwitchServer.setOnClickListener { handleSwitchServer() }
        binding.btnSettingsQuick.setOnClickListener { requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java)) }
        binding.btnLogcatQuick.setOnClickListener { startActivity(Intent(this, LogcatActivity::class.java)) }
        binding.btnUpdateSubQuick.setOnClickListener { handleUpdateSubscription() }
        binding.btnFilterQuick.setOnClickListener { startActivity(Intent(this, LocationFilterActivity::class.java)) }

        // QR-код текущего подключённого сервера
        binding.btnShowQr.setOnClickListener { showCurrentServerQr() }

        // Сканирование QR для добавления сервера (когда отключён)
        binding.btnScanAdd.setOnClickListener {
            scannerLauncher.launch(Intent(this, ScannerActivity::class.java))
        }

        binding.btnAboutQuick.setOnClickListener {
            val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
            val bottomSheetView = layoutInflater.inflate(R.layout.layout_about_bottom_sheet, null)
            
            // Находим время последнего обновления
            val subs = MmkvManager.decodeSubscriptions()
            val lastUpdateTime = subs.maxOfOrNull { it.subscription.lastUpdated } ?: 0L
            val tvLastUpdate = bottomSheetView.findViewById<android.widget.TextView>(R.id.tv_last_update)
            if (lastUpdateTime > 0) {
                val dateStr = Utils.formatTimestamp(lastUpdateTime)
                tvLastUpdate.text = getString(R.string.title_last_update, dateStr)
            } else {
                tvLastUpdate.text = getString(R.string.title_last_update_never)
            }

            bottomSheetView.findViewById<android.widget.TextView>(R.id.tv_developer_link)?.setOnClickListener {
                com.kiktor.v2whitelist.util.Utils.openUri(this, "https://github.com/NullCoreDeveloper/v2whitelist")
                bottomSheetDialog.dismiss()
            }
            bottomSheetDialog.setContentView(bottomSheetView)
            bottomSheetDialog.show()
        }

        setupViewModel()
        mainViewModel.reloadServerList()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
        
        checkBatteryOptimization()
        checkAppUpdate()
    }

    private fun checkAppUpdate() {
        val shouldCheck = com.kiktor.v2whitelist.handler.MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_CHECK_UPDATE, true)
        if (!shouldCheck) return
        
        lifecycleScope.launch {
            try {
                val result = com.kiktor.v2whitelist.handler.UpdateCheckerManager.checkForUpdate(false)
                if (result.hasUpdate && !result.downloadUrl.isNullOrEmpty()) {
                    showUpdateNotification(result)
                }
            } catch (e: Exception) {
                Log.d(AppConfig.TAG, "Auto update check failed: ${e.message}")
            }
        }
    }

    private fun showUpdateNotification(result: com.kiktor.v2whitelist.dto.CheckUpdateResult) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.downloadUrl))
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = androidx.core.app.NotificationCompat.Builder(this, AppConfig.RAY_NG_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(getString(R.string.update_new_version_found, result.latestVersion))
            .setContentText(getString(R.string.update_now))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val notificationManager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(1001, builder.build())
    }

    private fun handleUpdateSubscription() {
        if (isTaskRunning) {
            cancelActiveTask()
            return
        }
        activeJob = lifecycleScope.launch {
            setConnectingState(getString(R.string.status_updating_subscription))
            SmartConnectManager.updateSubscription(this@MainActivity)
            mainViewModel.reloadServerList()
            updateUIState(mainViewModel.isRunning.value == true)
        }
    }

    private fun setupViewModel() {
        mainViewModel.isRunning.observe(this) { isRunning ->
            updateUIState(isRunning)
        }
        mainViewModel.uiStatus.observe(this) { status ->
            if (isTaskRunning) {
                binding.tvStatusDetail.text = status
            }
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun handleConnectAction() {
        if (isTaskRunning) {
            cancelActiveTask()
            return
        }
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        } else {
            activeJob = lifecycleScope.launch {
                setConnectingState()
                SmartConnectManager.smartConnect(this@MainActivity)
            }
        }
    }

    private fun handleSwitchServer() {
        if (isTaskRunning) {
            cancelActiveTask()
            return
        }
        activeJob = lifecycleScope.launch {
            setConnectingState()
            SmartConnectManager.switchServer(this@MainActivity)
        }
    }

    private fun cancelActiveTask() {
        activeJob?.cancel()
        activeJob = null
        isTaskRunning = false
        updateUIState(mainViewModel.isRunning.value == true)
    }

    private fun setConnectingState(message: String? = null) {
        isTaskRunning = true
        binding.btnBigConnect.isEnabled = true
        binding.btnBigConnect.text = getString(R.string.btn_label_cancel)
        binding.btnBigConnect.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.holo_orange_light))
        binding.progressBar.isVisible = true
        binding.progressBarCircular.isVisible = true
        binding.tvStatus.text = getString(R.string.connection_test_testing)
        binding.tvStatusDetail.text = message ?: getString(R.string.connection_test_testing)
        binding.tvServerName.isVisible = false
        binding.ivStatusIcon.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_orange_light))
    }

    private fun showCurrentServerQr() {
        val guid = MmkvManager.getSelectServer() ?: run {
            toast(R.string.toast_none_data)
            return
        }
        val serverName = V2RayServiceManager.getRunningServerName()
        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap: Bitmap? = AngConfigManager.share2QRCode(guid)
            withContext(Dispatchers.Main) {
                if (bitmap == null) {
                    toast(R.string.toast_failure)
                    return@withContext
                }
                // Показываем диалог с QR-кодом
                val dialog = Dialog(this@MainActivity)
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                val imageView = ImageView(this@MainActivity).apply {
                    setImageBitmap(bitmap)
                    val pad = (16 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                    setBackgroundColor(Color.WHITE)
                }
                dialog.setContentView(imageView)
                dialog.setTitle(getString(R.string.title_qr_current_server))
                if (!serverName.isNullOrEmpty()) {
                    imageView.contentDescription = serverName
                }
                dialog.show()
            }
        }
    }

    private fun updateUIState(isRunning: Boolean) {
        isTaskRunning = false
        activeJob = null
        binding.btnBigConnect.isEnabled = true
        binding.progressBar.isVisible = false
        binding.progressBarCircular.isVisible = false
        if (isRunning) {
            binding.tvStatus.text = getString(R.string.tv_status_protected)
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            binding.tvStatusDetail.text = getString(R.string.tv_status_protected_detail)
            binding.btnBigConnect.text = getString(R.string.btn_label_stop)
            binding.btnBigConnect.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.holo_green_light))
            binding.btnSwitchServer.isVisible = true
            binding.ivStatusIcon.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_green_light))

            // Показываем имя текущего сервера
            val serverName = V2RayServiceManager.getRunningServerName()
            if (serverName.isNotEmpty()) {
                binding.tvServerName.text = getString(R.string.tv_server_name, serverName)
                binding.tvServerName.isVisible = true
            } else {
                binding.tvServerName.isVisible = false
            }

            // Подключён: показываем QR кнопку, скрываем кнопку сканирования
            binding.btnShowQr.isVisible = true
            binding.btnScanAdd.isVisible = false
        } else {
            binding.tvStatus.text = getString(R.string.connection_not_connected)
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            binding.tvStatusDetail.text = getString(R.string.tv_status_disconnected_detail)
            binding.btnBigConnect.text = getString(R.string.btn_label_start)
            binding.btnBigConnect.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.darker_gray))
            binding.btnSwitchServer.isVisible = false
            binding.tvServerName.isVisible = false
            binding.ivStatusIcon.setColorFilter(ContextCompat.getColor(this, android.R.color.darker_gray))

            // Отключён: скрываем QR кнопку, показываем кнопку сканирования
            binding.btnShowQr.isVisible = false
            binding.btnScanAdd.isVisible = true
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        else -> super.onOptionsItemSelected(item)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.per_app_proxy_settings -> requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            R.id.settings -> requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            R.id.check_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    fun startV2Ray() {
        if (SettingsManager.isVpnMode()) {
            val intent = android.net.VpnService.prepare(this)
            if (intent != null) {
                requestVpnPermission.launch(intent)
                return
            }
        }
        V2RayServiceManager.startVService(this)
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        }
        startV2Ray()
    }

    private fun checkBatteryOptimization() {
        if (MmkvManager.getBatteryOptimizationAsked()) return

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_battery_optimization_title)
                .setMessage(R.string.dialog_battery_optimization_message)
                .setPositiveButton(R.string.dialog_battery_optimization_ok) { _, _ ->
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        toast("Could not open battery settings")
                    }
                }
                .setNegativeButton(R.string.dialog_battery_optimization_no_remind) { _, _ ->
                    MmkvManager.setBatteryOptimizationAsked(true)
                }
                .setNeutralButton(R.string.btn_label_cancel, null)
                .show()
        }
    }
}
