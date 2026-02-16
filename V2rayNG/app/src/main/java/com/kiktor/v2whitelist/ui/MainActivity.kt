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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()

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

        setupViewModel()
        mainViewModel.reloadServerList()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
    }

    private fun handleUpdateSubscription() {
        lifecycleScope.launch {
            setConnectingState("Updating Subscription...")
            SmartConnectManager.updateSubscription(this@MainActivity)
            mainViewModel.reloadServerList()
            updateUIState(mainViewModel.isRunning.value == true)
        }
    }

    private fun setupViewModel() {
        mainViewModel.isRunning.observe(this) { isRunning ->
            updateUIState(isRunning)
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun handleConnectAction() {
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        } else {
            lifecycleScope.launch {
                setConnectingState()
                SmartConnectManager.smartConnect(this@MainActivity)
            }
        }
    }

    private fun handleSwitchServer() {
        lifecycleScope.launch {
            setConnectingState()
            SmartConnectManager.switchServer(this@MainActivity)
        }
    }

    private fun setConnectingState(message: String = "Updating & Testing...") {
        binding.btnBigConnect.isEnabled = false
        binding.btnBigConnect.text = "..."
        binding.btnBigConnect.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.holo_orange_light))
        binding.progressBar.isVisible = true
        binding.progressBarCircular.isVisible = true
        binding.tvStatus.text = "Please wait"
        binding.tvStatusDetail.text = message
    }

    private fun updateUIState(isRunning: Boolean) {
        binding.btnBigConnect.isEnabled = true
        binding.progressBar.isVisible = false
        binding.progressBarCircular.isVisible = false
        if (isRunning) {
            binding.tvStatus.text = "Protected"
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            binding.tvStatusDetail.text = "Connection is active and secure"
            binding.btnBigConnect.text = "Stop"
            binding.btnBigConnect.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.holo_green_light))
            binding.btnSwitchServer.isVisible = true
        } else {
            binding.tvStatus.text = "Disconnected"
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            binding.tvStatusDetail.text = "Select a server or press start"
            binding.btnBigConnect.text = "Start"
            binding.btnBigConnect.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.darker_gray))
            binding.btnSwitchServer.isVisible = false
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
