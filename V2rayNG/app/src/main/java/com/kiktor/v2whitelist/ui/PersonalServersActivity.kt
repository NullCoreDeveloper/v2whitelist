package com.kiktor.v2whitelist.ui

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.core.config.UniversalConfigParser
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.NetworkBindingRule
import com.kiktor.v2whitelist.core.model.PersonalServersConfig
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.extension.toast
import com.kiktor.v2whitelist.handler.AngConfigManager
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.NetworkManager
import com.kiktor.v2whitelist.handler.PersonalServersManager
import com.kiktor.v2whitelist.handler.SmartConnectManager
import com.kiktor.v2whitelist.handler.V2RayServiceManager
import com.kiktor.v2whitelist.util.QRCodeDecoder
import com.kiktor.v2whitelist.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PersonalServersActivity : BaseActivity() {

    private lateinit var swEnablePersonal: MaterialSwitch
    private lateinit var tvConnectionStatus: TextView
    private lateinit var btnToggleConnect: MaterialButton

    private lateinit var swExitNodeEnabled: MaterialSwitch
    private lateinit var tvExitNodeStatus: TextView

    private lateinit var swIgnoreExitNode: MaterialSwitch
    private lateinit var swAutoConnect: MaterialSwitch
    private lateinit var swFallbackPublic: MaterialSwitch

    private lateinit var spWifiRule: Spinner
    private lateinit var spCellularRule: Spinner

    private lateinit var cardAutoSelect: MaterialCardView
    private lateinit var rbAutoSelect: RadioButton
    private lateinit var rvPersonalServers: RecyclerView
    private lateinit var tvEmpty: TextView

    private lateinit var adapter: PersonalServerAdapter

    private val scannerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val scanResult = result.data?.getStringExtra("SCAN_RESULT")
            if (!scanResult.isNullOrBlank()) {
                handleImportLink(scanResult)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(
            R.layout.activity_personal_servers,
            showHomeAsUp = true,
            title = getString(R.string.title_personal_servers)
        )

        initViews()
        setupListeners()
        setupSpinners()
        setupRecyclerView()
        loadData()
    }

    private val mMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING,
                AppConfig.MSG_STATE_NOT_RUNNING,
                AppConfig.MSG_STATE_START_SUCCESS,
                AppConfig.MSG_STATE_STOP_SUCCESS,
                AppConfig.MSG_STATE_SWITCH_SERVER -> {
                    updateConnectionStatus()
                    loadData()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        ContextCompat.registerReceiver(this, mMsgReceiver, filter, Utils.receiverFlags())
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(mMsgReceiver)
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        updateConnectionStatus()
        loadData()
    }

    private fun initViews() {
        swEnablePersonal = findViewById(R.id.sw_enable_personal)
        tvConnectionStatus = findViewById(R.id.tv_connection_status)
        btnToggleConnect = findViewById(R.id.btn_toggle_connect)

        swExitNodeEnabled = findViewById(R.id.sw_exit_node_enabled)
        tvExitNodeStatus = findViewById(R.id.tv_exit_node_status)

        swIgnoreExitNode = findViewById(R.id.sw_ignore_exit_node)
        swAutoConnect = findViewById(R.id.sw_auto_connect)
        swFallbackPublic = findViewById(R.id.sw_fallback_public)

        spWifiRule = findViewById(R.id.sp_wifi_rule)
        spCellularRule = findViewById(R.id.sp_cellular_rule)

        cardAutoSelect = findViewById(R.id.card_auto_select)
        rbAutoSelect = findViewById(R.id.rb_auto_select)
        rvPersonalServers = findViewById(R.id.rv_personal_servers)
        tvEmpty = findViewById(R.id.tv_empty)
    }

    private fun setupListeners() {
        swEnablePersonal.setOnCheckedChangeListener { _, isChecked ->
            PersonalServersManager.setEnabled(isChecked)
            updateConnectionStatus()
            if (V2RayServiceManager.isRunning()) {
                lifecycleScope.launch {
                    SmartConnectManager.smartConnect(this@PersonalServersActivity)
                    updateConnectionStatus()
                    loadData()
                }
            }
        }

        swExitNodeEnabled.setOnCheckedChangeListener { _, isChecked ->
            val cfg = PersonalServersManager.getConfig()
            if (cfg.exitNodeEnabled != isChecked) {
                PersonalServersManager.saveConfig(cfg.copy(exitNodeEnabled = isChecked))
                MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_ENDPOINT_ENABLED, isChecked)
                if (V2RayServiceManager.isRunning()) {
                    com.kiktor.v2whitelist.util.MessageUtil.sendMsg2Service(this, AppConfig.MSG_STATE_SWITCH_SERVER, "")
                }
            }
        }

        swIgnoreExitNode.setOnCheckedChangeListener { _, isChecked ->
            val cfg = PersonalServersManager.getConfig()
            PersonalServersManager.saveConfig(cfg.copy(ignoreExitNodeForPersonal = isChecked))
        }

        swAutoConnect.setOnCheckedChangeListener { _, isChecked ->
            val cfg = PersonalServersManager.getConfig()
            PersonalServersManager.saveConfig(cfg.copy(autoConnectOnStartup = isChecked))
        }

        swFallbackPublic.setOnCheckedChangeListener { _, isChecked ->
            val cfg = PersonalServersManager.getConfig()
            PersonalServersManager.saveConfig(cfg.copy(fallbackToPublicOnFailure = isChecked))
        }

        btnToggleConnect.setOnClickListener {
            handleToggleConnect()
        }

        findViewById<View>(R.id.btn_add_server).setOnClickListener {
            showAddServerOptionsDialog()
        }

        val triggerAutoSelect = {
            PersonalServersManager.setSelectedServer(null)
            val cfg = PersonalServersManager.getConfig()
            val isWifi = NetworkManager.isWifiActive(this)
            val updatedCfg = if (isWifi) cfg.copy(wifiRule = NetworkBindingRule.USE_PERSONAL_AUTO)
            else cfg.copy(cellularRule = NetworkBindingRule.USE_PERSONAL_AUTO)
            PersonalServersManager.saveConfig(updatedCfg)
            loadData()
            if (V2RayServiceManager.isRunning()) {
                lifecycleScope.launch {
                    SmartConnectManager.smartConnect(this@PersonalServersActivity)
                    updateConnectionStatus()
                    loadData()
                }
            }
        }
        cardAutoSelect.setOnClickListener { triggerAutoSelect() }
        rbAutoSelect.setOnClickListener { triggerAutoSelect() }
    }

    private fun setupSpinners() {
        val ruleTitles = listOf(
            getString(R.string.personal_rule_auto),
            getString(R.string.personal_rule_selected),
            getString(R.string.personal_rule_public),
            getString(R.string.personal_rule_direct)
        )
        val ruleValues = listOf(
            NetworkBindingRule.USE_PERSONAL_AUTO,
            NetworkBindingRule.USE_PERSONAL_SELECTED,
            NetworkBindingRule.USE_PUBLIC_SUBS,
            NetworkBindingRule.DIRECT_NO_VPN
        )

        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ruleTitles)
        spWifiRule.adapter = spinnerAdapter
        spCellularRule.adapter = spinnerAdapter

        spWifiRule.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                val cfg = PersonalServersManager.getConfig()
                val selectedRule = ruleValues.getOrElse(position) { NetworkBindingRule.USE_PERSONAL_AUTO }
                if (cfg.wifiRule != selectedRule) {
                    PersonalServersManager.saveConfig(cfg.copy(wifiRule = selectedRule))
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        spCellularRule.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                val cfg = PersonalServersManager.getConfig()
                val selectedRule = ruleValues.getOrElse(position) { NetworkBindingRule.USE_PUBLIC_SUBS }
                if (cfg.cellularRule != selectedRule) {
                    PersonalServersManager.saveConfig(cfg.copy(cellularRule = selectedRule))
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun setupRecyclerView() {
        rvPersonalServers.layoutManager = LinearLayoutManager(this)
        adapter = PersonalServerAdapter(
            context = this,
            onSelect = { guid ->
                PersonalServersManager.setSelectedServer(guid)
                val cfg = PersonalServersManager.getConfig()
                val isWifi = NetworkManager.isWifiActive(this)
                val currentRule = if (isWifi) cfg.wifiRule else cfg.cellularRule
                if (currentRule == NetworkBindingRule.USE_PERSONAL_AUTO) {
                    val updatedCfg = if (isWifi) cfg.copy(wifiRule = NetworkBindingRule.USE_PERSONAL_SELECTED)
                    else cfg.copy(cellularRule = NetworkBindingRule.USE_PERSONAL_SELECTED)
                    PersonalServersManager.saveConfig(updatedCfg)
                }
                loadData()
                if (V2RayServiceManager.isRunning()) {
                    lifecycleScope.launch {
                        SmartConnectManager.connectDirectlyTo(this@PersonalServersActivity, guid)
                        updateConnectionStatus()
                        loadData()
                    }
                }
            },
            onPing = { guid ->
                testServerPing(guid)
            },
            onEdit = { guid ->
                val profile = MmkvManager.decodeServerConfig(guid)
                if (profile != null) {
                    val intent = Intent().putExtra("guid", guid)
                        .putExtra("isRunning", V2RayServiceManager.isRunning() && guid == MmkvManager.getSelectServer())
                        .putExtra("createConfigType", profile.configType.value)
                    when (profile.configType) {
                        EConfigType.CUSTOM -> startActivity(intent.setClass(this, ServerCustomConfigActivity::class.java))
                        EConfigType.POLICYGROUP -> startActivity(intent.setClass(this, ServerGroupActivity::class.java))
                        else -> startActivity(intent.setClass(this, ServerActivity::class.java))
                    }
                }
            },
            onToggleExitNode = { guid ->
                val cfg = PersonalServersManager.getConfig()
                if (cfg.exitNodeServerGuid == guid) {
                    PersonalServersManager.setExitNode(null)
                    toast(R.string.toast_exit_node_cleared)
                } else {
                    PersonalServersManager.setExitNode(guid)
                    val name = MmkvManager.decodeServerConfig(guid)?.remarks ?: "Server"
                    toast(getString(R.string.toast_custom_endpoint_selected, name))
                }
                loadData()
                if (V2RayServiceManager.isRunning()) {
                    com.kiktor.v2whitelist.util.MessageUtil.sendMsg2Service(this, AppConfig.MSG_STATE_SWITCH_SERVER, "")
                }
            },
            onShare = { guid ->
                shareServer(guid)
            },
            onDeleteServer = { guid ->
                showDeleteConfirmDialog(guid)
            },
            onUpdateSub = { subId ->
                updateSinglePersonalSubscription(subId)
            },
            onDeleteSub = { subId ->
                showDeleteSubConfirmDialog(subId)
            }
        )
        rvPersonalServers.adapter = adapter
    }

    private fun loadData() {
        val config = PersonalServersManager.getConfig()
        swEnablePersonal.isChecked = config.enabled
        swIgnoreExitNode.isChecked = config.ignoreExitNodeForPersonal
        swAutoConnect.isChecked = config.autoConnectOnStartup
        swFallbackPublic.isChecked = config.fallbackToPublicOnFailure

        val exitGuid = config.exitNodeServerGuid
        val exitProfile = exitGuid?.let { MmkvManager.decodeServerConfig(it) }
        if (exitProfile != null) {
            tvExitNodeStatus.text = getString(R.string.personal_exit_node_assigned, exitProfile.remarks)
            swExitNodeEnabled.isEnabled = true
            swExitNodeEnabled.isChecked = config.exitNodeEnabled
        } else {
            tvExitNodeStatus.text = getString(R.string.personal_exit_node_none)
            swExitNodeEnabled.isEnabled = false
            swExitNodeEnabled.isChecked = false
        }

        val ruleValues = listOf(
            NetworkBindingRule.USE_PERSONAL_AUTO,
            NetworkBindingRule.USE_PERSONAL_SELECTED,
            NetworkBindingRule.USE_PUBLIC_SUBS,
            NetworkBindingRule.DIRECT_NO_VPN
        )
        spWifiRule.setSelection(ruleValues.indexOf(config.wifiRule).coerceAtLeast(0))
        spCellularRule.setSelection(ruleValues.indexOf(config.cellularRule).coerceAtLeast(0))

        val isAuto = config.selectedServerGuid.isNullOrBlank()
        rbAutoSelect.isChecked = isAuto
        cardAutoSelect.strokeWidth = if (isAuto) 3 else 0

        val activeGuid = if (V2RayServiceManager.isRunning()) MmkvManager.getSelectServer() else null
        val subs = PersonalServersManager.getPersonalSubscriptions()
        val servers = PersonalServersManager.getPersonalServers()
        adapter.setData(subs, servers, config.selectedServerGuid, config.exitNodeServerGuid, activeGuid)

        tvEmpty.visibility = if (subs.isEmpty() && servers.isEmpty()) View.VISIBLE else View.GONE
        updateConnectionStatus()
    }

    private fun updateConnectionStatus() {
        val isRunning = V2RayServiceManager.isRunning()
        val currentGuid = MmkvManager.getSelectServer()
        val currentProfile = if (currentGuid != null) MmkvManager.decodeServerConfig(currentGuid) else null

        if (isRunning && currentProfile != null) {
            tvConnectionStatus.text = getString(R.string.personal_status_connected, currentProfile.remarks)
            tvConnectionStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            btnToggleConnect.text = getString(R.string.personal_action_disconnect)
        } else {
            tvConnectionStatus.text = getString(R.string.personal_status_disconnected)
            tvConnectionStatus.setTextColor(getColor(android.R.color.darker_gray))
            btnToggleConnect.text = getString(R.string.personal_action_connect)
        }
    }

    private fun handleToggleConnect() {
        if (V2RayServiceManager.isRunning()) {
            V2RayServiceManager.stopVService(this)
            updateConnectionStatus()
        } else {
            val config = PersonalServersManager.getConfig()
            if (!config.enabled) {
                PersonalServersManager.setEnabled(true)
                swEnablePersonal.isChecked = true
            }

            lifecycleScope.launch {
                btnToggleConnect.isEnabled = false
                val selGuid = config.selectedServerGuid
                if (!selGuid.isNullOrBlank()) {
                    SmartConnectManager.connectDirectlyTo(this@PersonalServersActivity, selGuid)
                } else {
                    SmartConnectManager.smartConnect(this@PersonalServersActivity)
                }
                btnToggleConnect.isEnabled = true
                updateConnectionStatus()
            }
        }
    }

    private fun testServerPing(guid: String) {
        lifecycleScope.launch {
            val latency = withContext(Dispatchers.IO) {
                val profile = MmkvManager.decodeServerConfig(guid)
                if (profile != null) {
                    val res = com.kiktor.v2whitelist.handler.NodeTesterManager.testServers(
                        this@PersonalServersActivity,
                        listOf(guid to profile)
                    )
                    res.firstOrNull()?.third ?: -1L
                } else {
                    -1L
                }
            }
            adapter.setLatency(guid, latency)
        }
    }

    private fun showAddServerOptionsDialog() {
        val options = arrayOf(
            getString(R.string.personal_add_from_clipboard),
            getString(R.string.personal_add_scan_qr),
            getString(R.string.personal_add_subscription),
            getString(R.string.personal_refresh_subs),
            getString(R.string.personal_add_manual)
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.custom_sub_add)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> importFromClipboard()
                    1 -> scannerLauncher.launch(Intent(this, ScannerActivity::class.java))
                    2 -> showAddSubscriptionDialog()
                    3 -> refreshPersonalSubscriptions()
                    4 -> showManualAddProtocolDialog()
                }
            }
            .show()
    }

    private fun refreshPersonalSubscriptions() {
        val subs = PersonalServersManager.getPersonalSubscriptions()
        if (subs.isEmpty()) {
            toast(R.string.custom_sub_empty)
            return
        }
        lifecycleScope.launch {
            toast(R.string.personal_sub_updating)
            val count = PersonalServersManager.updateAllPersonalSubscriptions()
            toast(getString(R.string.personal_sub_updated, count))
            loadData()
        }
    }

    private fun importFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        if (clip.isNullOrBlank()) {
            toast(R.string.personal_server_invalid)
            return
        }
        handleImportLink(clip)
    }

    private fun handleImportLink(link: String) {
        val trimmed = link.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            lifecycleScope.launch {
                toast(R.string.personal_sub_downloading)
                val (_, count) = PersonalServersManager.addAndFetchPersonalSubscription(trimmed, "Доверенная подписка")
                if (count > 0) {
                    toast(getString(R.string.personal_sub_imported, count))
                } else {
                    toast(R.string.personal_sub_fetch_failed)
                }
                loadData()
            }
            return
        }

        val count = PersonalServersManager.importBatchPersonalServers(trimmed)
        if (count > 0) {
            toast(getString(R.string.personal_servers_imported, count))
            loadData()
        } else {
            toast(R.string.personal_server_invalid)
        }
    }

    private fun showAddSubscriptionDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_personal_subscription, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_sub_name)
        val etUrl = dialogView.findViewById<EditText>(R.id.et_sub_url)

        AlertDialog.Builder(this)
            .setTitle(R.string.personal_dialog_sub_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = etName.text.toString().trim()
                val url = etUrl.text.toString().trim()
                if (url.isBlank() || !Utils.isValidUrl(url)) {
                    toast(R.string.toast_invalid_url)
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    toast(R.string.personal_sub_downloading)
                    val (_, count) = PersonalServersManager.addAndFetchPersonalSubscription(url, name)
                    if (count > 0) {
                        toast(getString(R.string.personal_sub_imported, count))
                    } else {
                        toast(R.string.personal_sub_fetch_failed)
                    }
                    loadData()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showManualAddProtocolDialog() {
        val protocols = arrayOf("VLESS", "VMess", "Trojan", "Shadowsocks", "Hysteria 2")
        val types = arrayOf(
            EConfigType.VLESS,
            EConfigType.VMESS,
            EConfigType.TROJAN,
            EConfigType.SHADOWSOCKS,
            EConfigType.HYSTERIA2
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.personal_add_manual)
            .setItems(protocols) { _, which ->
                val profile = ProfileItem.create(types[which])
                profile.subscriptionId = PersonalServersConfig.SUB_ID_PERSONAL_DIRECT
                val guid = PersonalServersManager.addPersonalServer(profile)
                startActivity(Intent(this, ServerActivity::class.java).apply {
                    putExtra("guid", guid)
                })
            }
            .show()
    }

    private fun shareServer(guid: String) {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return
        val isCustom = profile.configType == EConfigType.CUSTOM || profile.configType == EConfigType.POLICYGROUP

        if (isCustom) {
            lifecycleScope.launch(Dispatchers.IO) {
                val result = AngConfigManager.shareFullContent2Clipboard(this@PersonalServersActivity, guid)
                withContext(Dispatchers.Main) {
                    if (result == 0) {
                        toast(R.string.toast_success)
                    } else {
                        toast(R.string.toast_failure)
                    }
                }
            }
        } else {
            val qrBitmap = AngConfigManager.share2QRCode(guid)
            val copyResult = AngConfigManager.share2Clipboard(this, guid)
            if (copyResult == 0) {
                toast(R.string.toast_link_copied)
            }
            if (qrBitmap != null) {
                val iv = ImageView(this).apply {
                    setImageBitmap(qrBitmap)
                    setPadding(32, 32, 32, 32)
                }
                AlertDialog.Builder(this)
                    .setTitle(profile.remarks)
                    .setView(iv)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } else if (copyResult != 0) {
                toast(R.string.toast_failure)
            }
        }
    }

    private fun showDeleteConfirmDialog(guid: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_item_del_config)
            .setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { dialog, which ->
                PersonalServersManager.removePersonalServer(guid)
                toast(R.string.personal_server_deleted)
                loadData()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateSinglePersonalSubscription(subId: String) {
        val sub = PersonalServersManager.getPersonalSubscriptions().firstOrNull { it.guid == subId }
        val name = sub?.subscription?.remarks ?: getString(R.string.personal_dialog_sub_title)
        lifecycleScope.launch {
            toast(R.string.personal_sub_updating)
            val count = PersonalServersManager.fetchSubscription(subId)
            toast(getString(R.string.personal_sub_single_updated, name, count))
            loadData()
        }
    }

    private fun showDeleteSubConfirmDialog(subId: String) {
        val sub = PersonalServersManager.getPersonalSubscriptions().firstOrNull { it.guid == subId }
        val name = sub?.subscription?.remarks ?: getString(R.string.personal_dialog_sub_title)
        AlertDialog.Builder(this)
            .setTitle(R.string.personal_sub_delete_title)
            .setMessage(getString(R.string.personal_sub_delete_confirm, name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                PersonalServersManager.removePersonalSubscription(subId)
                toast(R.string.personal_server_deleted)
                loadData()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
