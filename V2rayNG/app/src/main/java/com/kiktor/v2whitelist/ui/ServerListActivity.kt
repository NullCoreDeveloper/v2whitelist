package com.kiktor.v2whitelist.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.core.domain.inspection.EndpointFilterMode
import com.kiktor.v2whitelist.core.domain.inspection.ServerListFilter
import com.kiktor.v2whitelist.core.domain.inspection.ServerSortOrder
import com.kiktor.v2whitelist.core.domain.inspection.ServerStatusFilter
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.inspection.ScanNetworkCondition
import com.kiktor.v2whitelist.core.model.inspection.countryCodeToFlagEmoji
import com.kiktor.v2whitelist.extension.toast
import com.kiktor.v2whitelist.handler.FullScanManager
import com.kiktor.v2whitelist.handler.SettingsManager
import com.kiktor.v2whitelist.handler.SmartConnectManager
import com.kiktor.v2whitelist.util.Utils
import com.kiktor.v2whitelist.viewmodel.ServerListScreenState
import com.kiktor.v2whitelist.viewmodel.ServerListViewModel
import com.kiktor.v2whitelist.viewmodel.SpeedTestRowState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ServerListActivity : BaseActivity() {

    private val viewModel: ServerListViewModel by viewModels()

    private lateinit var tvStatsSummary: TextView
    private lateinit var tvScanMeta: TextView
    private lateinit var progressFullScan: LinearProgressIndicator
    private lateinit var btnFullScan: MaterialButton
    private lateinit var btnFilters: MaterialButton
    private lateinit var btnFindMoreVip: MaterialButton
    private lateinit var btnPingVip: MaterialButton
    private lateinit var etSearch: TextInputEditText
    private lateinit var rvServers: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var btnResetFiltersEmpty: MaterialButton

    private lateinit var adapter: ServerListAdapter
    private var latestState: ServerListScreenState = ServerListScreenState()
    private var pendingConnectGuid: String? = null

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val guid = pendingConnectGuid
        pendingConnectGuid = null
        if (result.resultCode == RESULT_OK && !guid.isNullOrBlank()) {
            connectToGuid(guid)
        }
    }

    private val mMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING,
                AppConfig.MSG_STATE_NOT_RUNNING,
                AppConfig.MSG_STATE_START_SUCCESS,
                AppConfig.MSG_STATE_STOP_SUCCESS,
                AppConfig.MSG_STATE_SWITCH_SERVER -> {
                    viewModel.reload()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(
            R.layout.activity_server_list,
            showHomeAsUp = true,
            title = getString(R.string.title_server_list)
        )

        initViews()
        setupRecyclerView()
        setupListeners()
        observeViewModel()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        ContextCompat.registerReceiver(this, mMsgReceiver, filter, Utils.receiverFlags())
        viewModel.reload()
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(mMsgReceiver)
        } catch (_: Exception) {}
    }

    private fun initViews() {
        tvStatsSummary = findViewById(R.id.tv_stats_summary)
        tvScanMeta = findViewById(R.id.tv_scan_meta)
        progressFullScan = findViewById(R.id.progress_full_scan)
        btnFullScan = findViewById(R.id.btn_full_scan)
        btnFilters = findViewById(R.id.btn_filters)
        btnFindMoreVip = findViewById(R.id.btn_find_more_vip)
        btnPingVip = findViewById(R.id.btn_ping_vip)
        etSearch = findViewById(R.id.et_search)
        rvServers = findViewById(R.id.rv_servers)
        layoutEmpty = findViewById(R.id.layout_empty)
        btnResetFiltersEmpty = findViewById(R.id.btn_reset_filters_empty)
    }

    private fun setupRecyclerView() {
        adapter = ServerListAdapter(
            onToggleExpand = { guid -> viewModel.toggleExpand(guid) },
            onConnect = { guid -> handleConnectClick(guid) },
            onSpeedTest = { guid -> viewModel.runSpeedTest(guid) },
            onToggleVip = { guid -> viewModel.toggleVip(guid) },
            onReinspect = { guid -> viewModel.reinspectServer(guid) }
        )
        rvServers.layoutManager = LinearLayoutManager(this)
        rvServers.adapter = adapter
        (rvServers.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    private fun setupListeners() {
        btnFullScan.setOnClickListener {
            val startedOrStopped = viewModel.startOrStopFullScan()
            if (!startedOrStopped) {
                toast(R.string.server_list_scan_busy)
            }
        }

        btnFilters.setOnClickListener {
            openFiltersBottomSheet(latestState)
        }

        btnFindMoreVip.setOnClickListener {
            viewModel.findMoreVipServers()
            toast(R.string.server_list_finding_vip)
        }

        btnPingVip.setOnClickListener {
            if (latestState.viewResult.stats.vipCount == 0) {
                toast(R.string.server_list_no_vip)
            } else {
                viewModel.pingVipServers()
                toast(R.string.server_list_pinging_vip)
            }
        }

        btnResetFiltersEmpty.setOnClickListener {
            etSearch.setText("")
            viewModel.resetFilters()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.setSearchQuery(s?.toString().orEmpty())
            }
        })
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    latestState = state
                    renderState(state)
                }
            }
        }
    }

    private fun renderState(state: ServerListScreenState) {
        val stats = state.viewResult.stats
        tvStatsSummary.text = getString(
            R.string.server_list_stats_summary,
            stats.totalServers,
            stats.aliveCount,
            stats.vipCount,
            stats.removedDuplicatesCount
        )

        val scan = state.scanState
        if (scan.isScanning) {
            btnFullScan.text = getString(R.string.server_list_btn_stop_scan)
            btnFullScan.setIconResource(R.drawable.ic_stop_24dp)
            progressFullScan.visibility = View.VISIBLE
            if (scan.totalCount > 0) {
                progressFullScan.isIndeterminate = false
                progressFullScan.max = scan.totalCount
                progressFullScan.progress = scan.completedCount.coerceAtMost(scan.totalCount)
            } else {
                progressFullScan.isIndeterminate = true
            }
            tvScanMeta.text = getString(
                R.string.server_list_scan_progress,
                scan.completedCount,
                scan.totalCount,
                scan.aliveCount
            )
        } else {
            btnFullScan.text = getString(R.string.server_list_btn_full_scan)
            btnFullScan.setIconResource(R.drawable.ic_check_update_24dp)
            progressFullScan.visibility = View.GONE
            tvScanMeta.text = formatScanMetaText(state)
        }

        val activeFilters = state.filter.activeFilterCount(state.endpointEnabled)
        btnFilters.text = if (activeFilters > 0) {
            getString(R.string.server_list_btn_filters_count, activeFilters)
        } else {
            getString(R.string.server_list_btn_filters)
        }

        btnFindMoreVip.isEnabled = !state.isFindingVip
        btnFindMoreVip.text = if (state.isFindingVip) {
            getString(R.string.server_list_finding_vip)
        } else {
            getString(R.string.geek_mode_btn_find_vip)
        }

        btnPingVip.isEnabled = !state.isPingingVip
        btnPingVip.text = if (state.isPingingVip) {
            getString(R.string.server_list_pinging_vip)
        } else {
            getString(R.string.geek_mode_btn_ping_vip)
        }

        val rows = mutableListOf<ServerListRow>()
        val vipList = state.viewResult.vipServers
        val regularList = state.viewResult.regularServers

        if (vipList.isNotEmpty()) {
            rows.add(
                ServerListRow.Header(
                    id = "header_vip",
                    title = "★ ${getString(R.string.server_list_vip_section)} (${vipList.size})",
                    isVipHeader = true
                )
            )
            for (entry in vipList) {
                rows.add(
                    ServerListRow.Server(
                        entry = entry,
                        isExpanded = entry.guid in state.expandedGuids,
                        isConnected = state.isVpnRunning && state.connectedGuid == entry.guid,
                        endpointEnabled = state.endpointEnabled,
                        speedTestState = state.speedTestStates[entry.guid] ?: SpeedTestRowState.Idle,
                        isReinspecting = entry.guid in state.reinspectingGuids
                    )
                )
            }
        }

        if (regularList.isNotEmpty()) {
            rows.add(
                ServerListRow.Header(
                    id = "header_regular",
                    title = "${getString(R.string.server_list_all_section)} (${regularList.size})",
                    isVipHeader = false
                )
            )
            for (entry in regularList) {
                rows.add(
                    ServerListRow.Server(
                        entry = entry,
                        isExpanded = entry.guid in state.expandedGuids,
                        isConnected = state.isVpnRunning && state.connectedGuid == entry.guid,
                        endpointEnabled = state.endpointEnabled,
                        speedTestState = state.speedTestStates[entry.guid] ?: SpeedTestRowState.Idle,
                        isReinspecting = entry.guid in state.reinspectingGuids
                    )
                )
            }
        }

        adapter.submitList(rows)
        val isEmpty = rows.isEmpty()
        layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        rvServers.visibility = if (isEmpty) View.GONE else View.VISIBLE
        btnResetFiltersEmpty.visibility = if (state.filter.isDefault(state.endpointEnabled)) View.GONE else View.VISIBLE
    }

    private fun formatScanMetaText(state: ServerListScreenState): String {
        val meta = state.lastScanMeta
        if (meta == null || meta.finishedAtMs <= 0L) {
            return getString(R.string.server_list_no_scan_yet)
        }
        val timeText = formatTimestamp(meta.finishedAtMs)
        val showCondition = state.networkCheckEnabled &&
            meta.networkCheckEnabled &&
            meta.networkCondition != ScanNetworkCondition.UNKNOWN
        if (!showCondition) {
            return getString(R.string.server_list_last_scan_time, timeText)
        }
        val condStr = when (meta.networkCondition) {
            ScanNetworkCondition.BLACKLIST -> getString(R.string.server_list_cond_blacklist)
            ScanNetworkCondition.WHITELIST -> getString(R.string.server_list_cond_whitelist)
            ScanNetworkCondition.NO_INTERNET -> getString(R.string.server_list_cond_no_internet)
            ScanNetworkCondition.UNKNOWN -> ""
        }
        return if (condStr.isNotEmpty()) {
            getString(R.string.server_list_last_scan_with_cond, timeText, condStr)
        } else {
            getString(R.string.server_list_last_scan_time, timeText)
        }
    }

    private fun formatTimestamp(ms: Long): String {
        val nowCal = Calendar.getInstance()
        val targetCal = Calendar.getInstance().apply { timeInMillis = ms }
        val sameDay = nowCal.get(Calendar.YEAR) == targetCal.get(Calendar.YEAR) &&
            nowCal.get(Calendar.DAY_OF_YEAR) == targetCal.get(Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm" else "dd.MM HH:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
    }

    private fun handleConnectClick(guid: String) {
        if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                pendingConnectGuid = guid
                requestVpnPermission.launch(intent)
                return
            }
        }
        connectToGuid(guid)
    }

    private fun connectToGuid(guid: String) {
        FullScanManager.stopFullScan()
        lifecycleScope.launch {
            SmartConnectManager.connectDirectlyTo(this@ServerListActivity, guid)
            viewModel.reload()
        }
    }

    private fun openFiltersBottomSheet(state: ServerListScreenState) {
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_server_filters, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetView)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val current = state.filter
        val cgSort = sheetView.findViewById<ChipGroup>(R.id.cg_sort)
        val cgStatus = sheetView.findViewById<ChipGroup>(R.id.cg_status)
        val sectionEndpoint = sheetView.findViewById<LinearLayout>(R.id.section_endpoint)
        val cgEndpoint = sheetView.findViewById<ChipGroup>(R.id.cg_endpoint)
        val sectionServices = sheetView.findViewById<LinearLayout>(R.id.section_services)
        val cgServices = sheetView.findViewById<ChipGroup>(R.id.cg_services)
        val tvCountriesEmpty = sheetView.findViewById<TextView>(R.id.tv_countries_empty)
        val cgCountries = sheetView.findViewById<ChipGroup>(R.id.cg_countries)
        val cgSubscriptions = sheetView.findViewById<ChipGroup>(R.id.cg_subscriptions)
        val cgProtocols = sheetView.findViewById<ChipGroup>(R.id.cg_protocols)
        val btnReset = sheetView.findViewById<MaterialButton>(R.id.btn_filter_reset)
        val btnApply = sheetView.findViewById<MaterialButton>(R.id.btn_filter_apply)

        // Sort
        val sortChipId = when (current.sortOrder) {
            ServerSortOrder.SMART -> R.id.chip_sort_smart
            ServerSortOrder.PING_ASC -> R.id.chip_sort_ping
            ServerSortOrder.SERVICES_DESC -> R.id.chip_sort_services
            ServerSortOrder.COUNTRY_ASC -> R.id.chip_sort_country
            ServerSortOrder.NAME_ASC -> R.id.chip_sort_name
        }
        cgSort.check(sortChipId)

        // Status
        val statusChipId = when (current.statusFilter) {
            ServerStatusFilter.ALL -> R.id.chip_status_all
            ServerStatusFilter.ALIVE_ONLY -> R.id.chip_status_alive
            ServerStatusFilter.HIDE_DEAD -> R.id.chip_status_hide_dead
        }
        cgStatus.check(statusChipId)

        // Endpoint vs Services (mutually exclusive)
        if (state.endpointEnabled) {
            sectionEndpoint.visibility = View.VISIBLE
            sectionServices.visibility = View.GONE
            val epChipId = when (current.endpointFilter) {
                EndpointFilterMode.ANY -> R.id.chip_ep_any
                EndpointFilterMode.PASSED_ONLY -> R.id.chip_ep_passed
                EndpointFilterMode.FAILED_ONLY -> R.id.chip_ep_failed
            }
            cgEndpoint.check(epChipId)
        } else {
            sectionEndpoint.visibility = View.GONE
            val availableServices = state.viewResult.availableServices
            if (availableServices.isNotEmpty()) {
                sectionServices.visibility = View.VISIBLE
                for (svc in availableServices) {
                    val chip = createFilterChip("${svc.name} (${svc.aliveServerCount})", svc.id in current.requiredServiceIds)
                    chip.tag = svc.id
                    cgServices.addView(chip)
                }
            } else {
                sectionServices.visibility = View.GONE
            }
        }

        // Countries
        val countryCounts = state.viewResult.availableCountryCounts
        if (countryCounts.isEmpty()) {
            tvCountriesEmpty.visibility = View.VISIBLE
            cgCountries.visibility = View.GONE
        } else {
            tvCountriesEmpty.visibility = View.GONE
            cgCountries.visibility = View.VISIBLE
            for ((code, count) in countryCounts) {
                val flag = countryCodeToFlagEmoji(code)
                val name = ServerListAdapter.getLocalizedCountryName(code)
                val label = "$flag $name ($count)".trim()
                val chip = createFilterChip(label, code in current.selectedCountryCodes)
                chip.tag = code
                cgCountries.addView(chip)
            }
        }

        // Subscriptions
        for (sub in state.availableSubscriptions) {
            val chip = createFilterChip("${sub.name} (${sub.count})", sub.id in current.selectedSubscriptionIds)
            chip.tag = sub.id
            cgSubscriptions.addView(chip)
        }

        // Protocols
        for (proto in state.availableProtocols) {
            val chip = createFilterChip(proto.name, proto in current.selectedProtocols)
            chip.tag = proto
            cgProtocols.addView(chip)
        }

        btnReset.setOnClickListener {
            viewModel.updateFilter(ServerListFilter(searchQuery = current.searchQuery))
            dialog.dismiss()
        }

        btnApply.setOnClickListener {
            val selectedSort = when (cgSort.checkedChipId) {
                R.id.chip_sort_ping -> ServerSortOrder.PING_ASC
                R.id.chip_sort_services -> ServerSortOrder.SERVICES_DESC
                R.id.chip_sort_country -> ServerSortOrder.COUNTRY_ASC
                R.id.chip_sort_name -> ServerSortOrder.NAME_ASC
                else -> ServerSortOrder.SMART
            }

            val selectedStatus = when (cgStatus.checkedChipId) {
                R.id.chip_status_alive -> ServerStatusFilter.ALIVE_ONLY
                R.id.chip_status_hide_dead -> ServerStatusFilter.HIDE_DEAD
                else -> ServerStatusFilter.ALL
            }

            val selectedEp = if (state.endpointEnabled) {
                when (cgEndpoint.checkedChipId) {
                    R.id.chip_ep_passed -> EndpointFilterMode.PASSED_ONLY
                    R.id.chip_ep_failed -> EndpointFilterMode.FAILED_ONLY
                    else -> EndpointFilterMode.ANY
                }
            } else {
                EndpointFilterMode.ANY
            }

            val selectedServices = collectCheckedTags<String>(cgServices)
            val selectedCountries = collectCheckedTags<String>(cgCountries)
            val selectedSubs = collectCheckedTags<String>(cgSubscriptions)
            val selectedProtocols = collectCheckedTags<EConfigType>(cgProtocols)

            viewModel.updateFilter(
                ServerListFilter(
                    searchQuery = current.searchQuery,
                    statusFilter = selectedStatus,
                    selectedCountryCodes = selectedCountries,
                    selectedSubscriptionIds = selectedSubs,
                    selectedProtocols = selectedProtocols,
                    requiredServiceIds = if (state.endpointEnabled) emptySet() else selectedServices,
                    endpointFilter = selectedEp,
                    sortOrder = selectedSort
                )
            )
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun createFilterChip(label: String, checked: Boolean): Chip {
        return Chip(this).apply {
            text = label
            isCheckable = true
            isChecked = checked
        }
    }

    private inline fun <reified T> collectCheckedTags(group: ChipGroup): Set<T> {
        val result = LinkedHashSet<T>()
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) as? Chip ?: continue
            if (child.isChecked) {
                val tag = child.tag as? T
                if (tag != null) {
                    result.add(tag)
                }
            }
        }
        return result
    }
}
