package com.kiktor.v2whitelist.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import com.kiktor.v2whitelist.handler.MmkvManager
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

    override fun onResume() {
        super.onResume()
        updateConnectionStatus()
        loadData()
    }

    private fun initViews() {
        swEnablePersonal = findViewById(R.id.sw_enable_personal)
        tvConnectionStatus = findViewById(R.id.tv_connection_status)
        btnToggleConnect = findViewById(R.id.btn_toggle_connect)

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

        cardAutoSelect.setOnClickListener {
            PersonalServersManager.setSelectedServer(null)
            loadData()
        }
        rbAutoSelect.setOnClickListener {
            PersonalServersManager.setSelectedServer(null)
            loadData()
        }
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
                loadData()
            },
            onPing = { guid ->
                testServerPing(guid)
            },
            onEdit = { guid ->
                startActivity(Intent(this, ServerActivity::class.java).apply {
                    putExtra("guid", guid)
                })
            },
            onToggleExitNode = { guid ->
                val cfg = PersonalServersManager.getConfig()
                val isCurrentExit = cfg.exitNodeServerGuid == guid
                PersonalServersManager.setExitNode(if (isCurrentExit) null else guid)
                val profile = MmkvManager.decodeServerConfig(guid)
                if (!isCurrentExit && profile != null) {
                    toast(getString(R.string.personal_exit_node_set, profile.remarks))
                }
                loadData()
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

        val subs = PersonalServersManager.getPersonalSubscriptions()
        val servers = PersonalServersManager.getPersonalServers()
        adapter.setData(subs, servers, config.selectedServerGuid, config.exitNodeServerGuid)

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
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_subscription, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_sub_name)
        val etUrl = dialogView.findViewById<EditText>(R.id.et_sub_url)

        dialogView.findViewById<View>(R.id.et_sub_filter)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.et_sub_group_regex)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.et_sub_share_percent)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.cb_scenario_whitelist)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.cb_scenario_blacklist)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.cb_scenario_youtube)?.visibility = View.GONE

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
        val uri = UniversalConfigParser.toUri(profile) ?: return

        Utils.setClipboard(this, uri)
        toast(R.string.toast_link_copied)

        val qrBitmap = QRCodeDecoder.createQRCode(uri)
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
