package com.kiktor.v2whitelist.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.btnBigConnect.setOnClickListener { handleConnectAction() }
        binding.btnSwitchServer.setOnClickListener { handleSwitchServer() }
        binding.btnSettingsQuick.setOnClickListener { requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java)) }
        binding.btnLogcatQuick.setOnClickListener { startActivity(Intent(this, LogcatActivity::class.java)) }
        binding.btnUpdateSubQuick.setOnClickListener { handleUpdateSubscription() }
        binding.btnAboutQuick.setOnClickListener {
            val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
            val bottomSheetView = layoutInflater.inflate(R.layout.layout_about_bottom_sheet, null)
            bottomSheetView.findViewById<android.widget.TextView>(R.id.tv_developer_link)?.setOnClickListener {
                com.kiktor.v2whitelist.util.Utils.openUri(this, "https://github.com/kiktor12358/v2whitelist")
                bottomSheetDialog.dismiss()
            }
            bottomSheetDialog.setContentView(bottomSheetView)
            bottomSheetDialog.show()
        }

        setupViewModel()
        mainViewModel.reloadServerList()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
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
        } else {
            binding.tvStatus.text = getString(R.string.connection_not_connected)
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            binding.tvStatusDetail.text = getString(R.string.tv_status_disconnected_detail)
            binding.btnBigConnect.text = getString(R.string.btn_label_start)
            binding.btnBigConnect.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.darker_gray))
            binding.btnSwitchServer.isVisible = false
            binding.tvServerName.isVisible = false
            binding.ivStatusIcon.setColorFilter(ContextCompat.getColor(this, android.R.color.darker_gray))
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
}
