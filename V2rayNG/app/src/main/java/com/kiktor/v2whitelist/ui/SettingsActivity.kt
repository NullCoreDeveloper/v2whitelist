package com.kiktor.v2whitelist.ui

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import com.kiktor.v2whitelist.AngApplication
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.AppConfig.VPN
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.extension.toLongEx
import com.kiktor.v2whitelist.extension.toast
import androidx.activity.result.contract.ActivityResultContracts
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.SettingsManager
import com.kiktor.v2whitelist.helper.BackupManager
import com.kiktor.v2whitelist.helper.MmkvPreferenceDataStore
import com.kiktor.v2whitelist.util.Utils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.subscription.endpoint.CustomEndpointManager
import com.kiktor.v2whitelist.handler.AngConfigManager

class SettingsActivity : BaseActivity(), PreferenceFragmentCompat.OnPreferenceStartScreenCallback {
    private var lastNightMode: String? = null
    private var lastAmoled: Boolean = false
    private var lastPalette: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastNightMode = MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT)
        lastAmoled = SettingsManager.isAmoledBlackEnabled()
        lastPalette = MmkvManager.decodeSettingsString(AppConfig.PREF_THEME_PALETTE)
        setContentViewWithToolbar(R.layout.activity_settings, showHomeAsUp = true, title = getString(R.string.title_settings))

        supportFragmentManager.addOnBackStackChangedListener {
            syncToolbarTitle()
        }
    }

    private fun syncToolbarTitle() {
        val count = supportFragmentManager.backStackEntryCount
        val title: CharSequence = if (count == 0) {
            getString(R.string.title_settings)
        } else {
            val key = supportFragmentManager.getBackStackEntryAt(count - 1).name
            val frag = key?.let { supportFragmentManager.findFragmentByTag(it) } as? PreferenceFragmentCompat
            frag?.preferenceScreen?.title ?: getString(R.string.title_settings)
        }
        supportActionBar?.title = title
        supportActionBar?.subtitle = null
    }

    override fun onPreferenceStartScreen(caller: PreferenceFragmentCompat, pref: androidx.preference.PreferenceScreen): Boolean {
        val fragment = SettingsFragment().apply {
            arguments = Bundle().apply {
                putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, pref.key)
            }
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_settings, fragment, pref.key)
            .addToBackStack(pref.key)
            .commit()
        supportActionBar?.title = pref.title
        supportActionBar?.subtitle = null
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
                return true
            }
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        val currentNightMode = MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT)
        val currentAmoled = SettingsManager.isAmoledBlackEnabled()
        val currentPalette = MmkvManager.decodeSettingsString(AppConfig.PREF_THEME_PALETTE)
        if (lastNightMode != null && (
            lastNightMode != currentNightMode ||
            lastAmoled != currentAmoled ||
            lastPalette != currentPalette
        )) {
            recreate()
            return
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val localDns get() = findPreference<CheckBoxPreference>(AppConfig.PREF_LOCAL_DNS_ENABLED)
        private val fakeDns get() = findPreference<CheckBoxPreference>(AppConfig.PREF_FAKE_DNS_ENABLED)
        private val appendHttpProxy get() = findPreference<CheckBoxPreference>(AppConfig.PREF_APPEND_HTTP_PROXY)

        private val exportDataLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                val success = BackupManager.exportData(requireContext(), uri)
                if (success) {
                    requireContext().toast(getString(R.string.msg_export_success))
                } else {
                    requireContext().toast(getString(R.string.msg_export_fail))
                }
            }
        }

        private val importDataLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val success = BackupManager.importData(requireContext(), uri)
                if (success) {
                    requireContext().toast(getString(R.string.msg_import_success))
                    activity?.recreate()
                } else {
                    requireContext().toast(getString(R.string.msg_import_fail))
                }
            }
        }


        //        private val localDnsPort by lazy { findPreference<EditTextPreference>(AppConfig.PREF_LOCAL_DNS_PORT) }
        private val vpnDns get() = findPreference<EditTextPreference>(AppConfig.PREF_VPN_DNS)
        private val vpnBypassLan get() = findPreference<ListPreference>(AppConfig.PREF_VPN_BYPASS_LAN)
        private val vpnInterfaceAddress get() = findPreference<ListPreference>(AppConfig.PREF_VPN_INTERFACE_ADDRESS_CONFIG_INDEX)
        private val vpnMtu get() = findPreference<EditTextPreference>(AppConfig.PREF_VPN_MTU)

        private val mux get() = findPreference<CheckBoxPreference>(AppConfig.PREF_MUX_ENABLED)
        private val muxConcurrency get() = findPreference<EditTextPreference>(AppConfig.PREF_MUX_CONCURRENCY)
        private val muxXudpConcurrency get() = findPreference<EditTextPreference>(AppConfig.PREF_MUX_XUDP_CONCURRENCY)
        private val muxXudpQuic get() = findPreference<ListPreference>(AppConfig.PREF_MUX_XUDP_QUIC)

        private val fragment get() = findPreference<CheckBoxPreference>(AppConfig.PREF_FRAGMENT_ENABLED)
        private val fragmentPackets get() = findPreference<ListPreference>(AppConfig.PREF_FRAGMENT_PACKETS)
        private val fragmentLength get() = findPreference<EditTextPreference>(AppConfig.PREF_FRAGMENT_LENGTH)
        private val fragmentInterval get() = findPreference<EditTextPreference>(AppConfig.PREF_FRAGMENT_INTERVAL)

        private val autoUpdateCheck get() = findPreference<CheckBoxPreference>(AppConfig.SUBSCRIPTION_AUTO_UPDATE)
        private val autoUpdateInterval get() = findPreference<EditTextPreference>(AppConfig.SUBSCRIPTION_AUTO_UPDATE_INTERVAL)
        private val mode get() = findPreference<ListPreference>(AppConfig.PREF_MODE)

        override fun onResume() {
            super.onResume()
            updateLanSharingSummary()
            updatePersonalServersSummary()
        }

        private fun updatePersonalServersSummary() {
            findPreference<androidx.preference.Preference>("pref_personal_servers_screen")?.let {
                val cfg = com.kiktor.v2whitelist.handler.PersonalServersManager.getConfig()
                if (cfg.enabled) {
                    val count = com.kiktor.v2whitelist.handler.PersonalServersManager.getPersonalServers().size
                    val selGuid = cfg.selectedServerGuid
                    it.summary = if (!selGuid.isNullOrBlank()) {
                        val name = MmkvManager.decodeServerConfig(selGuid)?.remarks ?: "Выбран сервер"
                        "Включено: $name"
                    } else {
                        "Включено: Автовыбор ($count узлов)"
                    }
                } else {
                    it.summary = getString(R.string.summary_pref_personal_servers)
                }
            }
        }

        private fun updateLanSharingSummary() {
            findPreference<androidx.preference.Preference>("pref_lan_proxy_sharing_screen")?.let {
                val isSharing = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING, false)
                it.summary = if (isSharing) {
                    getString(R.string.lan_sharing_summary_active, SettingsManager.getSocksPort())
                } else {
                    getString(R.string.summary_lan_sharing)
                }
            }
        }

        override fun onCreatePreferences(bundle: Bundle?, s: String?) {
            // Use MMKV as the storage backend for all Preferences
            // This prevents inconsistencies between SharedPreferences and MMKV
            preferenceManager.preferenceDataStore = MmkvPreferenceDataStore()

            setPreferencesFromResource(R.xml.pref_settings, s)

            initPreferenceSummaries()
            updateLanSharingSummary()

            localDns?.setOnPreferenceChangeListener { _, any ->
                updateLocalDns(any as Boolean)
                true
            }

            mux?.setOnPreferenceChangeListener { _, newValue ->
                updateMux(newValue as Boolean)
                true
            }
            muxConcurrency?.setOnPreferenceChangeListener { _, newValue ->
                updateMuxConcurrency(newValue as String)
                true
            }
            muxXudpConcurrency?.setOnPreferenceChangeListener { _, newValue ->
                updateMuxXudpConcurrency(newValue as String)
                true
            }

            fragment?.setOnPreferenceChangeListener { _, newValue ->
                updateFragment(newValue as Boolean)
                true
            }

            autoUpdateCheck?.setOnPreferenceChangeListener { _, newValue ->
                val value = newValue as Boolean
                autoUpdateCheck?.isChecked = value
                autoUpdateInterval?.isEnabled = value
                // Пересоздаём задачу с новыми настройками
                view?.post {
                    com.kiktor.v2whitelist.service.SubscriptionUpdaterWorker.reschedule(requireContext())
                }
                true
            }
            autoUpdateInterval?.setOnPreferenceChangeListener { _, newValue ->
                autoUpdateInterval?.summary = newValue as String
                view?.post {
                    com.kiktor.v2whitelist.service.SubscriptionUpdaterWorker.reschedule(requireContext())
                }
                true
            }
            findPreference<CheckBoxPreference>(AppConfig.PREF_UPDATE_SUB_ONLY_VIA_VPN)?.setOnPreferenceChangeListener { _, newValue ->
                val onlyViaVpn = newValue as Boolean
                updateYandexUpdatePreferences(onlyViaVpn)
                true
            }
            findPreference<androidx.preference.Preference>("pref_lan_proxy_sharing_screen")?.setOnPreferenceClickListener {
                startActivity(android.content.Intent(requireContext(), LanProxySharingActivity::class.java))
                true
            }
            findPreference<androidx.preference.Preference>("pref_personal_servers_screen")?.setOnPreferenceClickListener {
                startActivity(android.content.Intent(requireContext(), PersonalServersActivity::class.java))
                true
            }
            findPreference<androidx.preference.Preference>("pref_service_check_config")?.setOnPreferenceClickListener {
                startActivity(android.content.Intent(activity, ServiceCheckActivity::class.java))
                true
            }
            findPreference<androidx.preference.Preference>("pref_add_qs_tile")?.setOnPreferenceClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val sbm = requireContext().getSystemService(android.app.StatusBarManager::class.java)
                    sbm?.requestAddTileService(
                        android.content.ComponentName(requireContext(), com.kiktor.v2whitelist.service.QSTileService::class.java),
                        getString(R.string.app_tile_name),
                        android.graphics.drawable.Icon.createWithResource(requireContext(), R.drawable.ic_qs_probel),
                        requireContext().mainExecutor
                    ) { _ -> }
                } else {
                    requireContext().toast(getString(R.string.msg_qs_tile_manual_hint))
                }
                true
            }
            findPreference<androidx.preference.Preference>("pref_check_update_now")?.setOnPreferenceClickListener {
                com.kiktor.v2whitelist.service.SubscriptionUpdaterWorker.runOnce(requireContext())
                requireContext().toast(getString(R.string.status_updating_subscription))
                true
            }
            findPreference<androidx.preference.Preference>("pref_test_update_via_yandex")?.setOnPreferenceClickListener {
                val intent = android.content.Intent(requireContext(), MainActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_START_YANDEX_UPDATE, true)
                }
                startActivity(intent)
                activity?.finish()
                true
            }
            findPreference<androidx.preference.Preference>("pref_clear_yandex_cookies")?.setOnPreferenceClickListener {
                com.kiktor.v2whitelist.handler.YandexCaptchaSolver.clearCookies()
                requireContext().toast(getString(R.string.toast_yandex_cookies_cleared))
                true
            }
            findPreference<androidx.preference.Preference>("pref_appearance_settings")?.setOnPreferenceClickListener {
                startActivity(android.content.Intent(requireContext(), AppearanceActivity::class.java))
                true
            }
            findPreference<androidx.preference.Preference>("pref_per_app_proxy_settings")?.setOnPreferenceClickListener {
                startActivity(android.content.Intent(requireContext(), PerAppProxyActivity::class.java))
                true
            }
            findPreference<androidx.preference.Preference>("pref_domain_split_tunneling_settings")?.setOnPreferenceClickListener {
                val currentDomains = MmkvManager.decodeSettingsString(AppConfig.PREF_DIRECT_DOMAIN_LIST) ?: ""
                val editText = android.widget.EditText(requireContext()).apply {
                    setText(currentDomains)
                    hint = getString(R.string.hint_pref_domain_split_tunneling_settings)
                    minLines = 4
                    gravity = android.view.Gravity.TOP
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                }
                val padding = (16 * resources.displayMetrics.density).toInt()
                val editorHeight = (resources.displayMetrics.heightPixels / resources.displayMetrics.density * 0.42f)
                    .toInt()
                    .coerceAtMost((320 * resources.displayMetrics.density).toInt())
                val editorScrollView = android.widget.ScrollView(requireContext()).apply {
                    isFillViewport = true
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        editorHeight
                    )
                    addView(editText.also {
                        it.setPadding(padding, padding / 2, padding, padding / 2)
                    })
                }
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.title_pref_domain_split_tunneling_settings))
                    .setMessage(getString(R.string.summary_pref_domain_split_tunneling_settings))
                    .setView(editorScrollView)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val value = editText.text.toString().trim()
                        MmkvManager.encodeSettings(AppConfig.PREF_DIRECT_DOMAIN_LIST, value)
                        updateDomainSummaries()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
            findPreference<androidx.preference.Preference>("pref_proxy_domain_list_settings")?.setOnPreferenceClickListener {
                val currentDomains = MmkvManager.decodeSettingsString(AppConfig.PREF_PROXY_DOMAIN_LIST) ?: ""
                val editText = android.widget.EditText(requireContext()).apply {
                    setText(currentDomains)
                    hint = getString(R.string.hint_pref_proxy_domain_list_settings)
                    minLines = 4
                    gravity = android.view.Gravity.TOP
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                }
                val padding = (16 * resources.displayMetrics.density).toInt()
                val editorHeight = (resources.displayMetrics.heightPixels / resources.displayMetrics.density * 0.42f)
                    .toInt()
                    .coerceAtMost((320 * resources.displayMetrics.density).toInt())
                val editorScrollView = android.widget.ScrollView(requireContext()).apply {
                    isFillViewport = true
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        editorHeight
                    )
                    addView(editText.also {
                        it.setPadding(padding, padding / 2, padding, padding / 2)
                    })
                }
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.title_pref_proxy_domain_list_settings))
                    .setMessage(getString(R.string.summary_pref_proxy_domain_list_settings))
                    .setView(editorScrollView)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val value = editText.text.toString().trim()
                        MmkvManager.encodeSettings(AppConfig.PREF_PROXY_DOMAIN_LIST, value)
                        updateDomainSummaries()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
            findPreference<androidx.preference.Preference>("pref_domain_geo_files")?.setOnPreferenceClickListener {
                startActivity(android.content.Intent(requireContext(), UserAssetActivity::class.java))
                true
            }

            mode?.setOnPreferenceChangeListener { pref, newValue ->
                val valueStr = newValue.toString()
                (pref as? ListPreference)?.let { lp ->
                    val idx = lp.findIndexOfValue(valueStr)
                    lp.summary = if (idx >= 0) lp.entries[idx] else valueStr
                }
                updateMode(valueStr)
                true
            }
            mode?.dialogLayoutResource = R.layout.preference_with_help_link

            // Управление кастомными подписками
            findPreference<androidx.preference.Preference>("pref_manage_custom_subs")?.setOnPreferenceClickListener {
                startActivity(android.content.Intent(requireContext(), CustomSubscriptionsActivity::class.java))
                true
            }

            // Мастер настройки подписок (пресеты)
            findPreference<androidx.preference.Preference>("pref_subscription_setup_wizard")?.setOnPreferenceClickListener {
                com.kiktor.v2whitelist.handler.SubscriptionHelper.showSetupWizard(requireActivity())
                true
            }

            // Логи
            findPreference<androidx.preference.Preference>("pref_logcat")?.setOnPreferenceClickListener {
                startActivity(android.content.Intent(requireContext(), LogcatActivity::class.java))
                true
            }

            // Backup & Restore
            findPreference<androidx.preference.Preference>("pref_export_data")?.setOnPreferenceClickListener {
                val dateStr = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                exportDataLauncher.launch("ProBel_Backup_$dateStr.json")
                true
            }
            findPreference<androidx.preference.Preference>("pref_import_data")?.setOnPreferenceClickListener {
                importDataLauncher.launch(arrayOf("application/json", "application/zip", "application/octet-stream", "*/*"))
                true
            }
        }

        private fun initPreferenceSummaries() {
            fun updateSummary(pref: androidx.preference.Preference) {
                when (pref) {
                    is EditTextPreference -> {
                        pref.summary = pref.text.orEmpty()
                        pref.setOnPreferenceChangeListener { p, newValue ->
                            p.summary = (newValue as? String).orEmpty()
                            true
                        }
                    }

                    is ListPreference -> {
                        pref.summary = pref.entry ?: ""
                        pref.setOnPreferenceChangeListener { p, newValue ->
                            val lp = p as ListPreference
                            val idx = lp.findIndexOfValue(newValue as? String)
                            lp.summary = (if (idx >= 0) lp.entries[idx] else newValue) as CharSequence?
                            true
                        }
                    }

                    is CheckBoxPreference, is androidx.preference.SwitchPreferenceCompat -> {
                    }
                }
            }

            fun traverse(group: androidx.preference.PreferenceGroup) {
                for (i in 0 until group.preferenceCount) {
                    when (val p = group.getPreference(i)) {
                        is androidx.preference.PreferenceGroup -> traverse(p)
                        else -> updateSummary(p)
                    }
                }
            }

            preferenceScreen?.let { traverse(it) }
        }

        override fun onStart() {
            super.onStart()

            // Initialize mode-dependent UI states
            updateMode(MmkvManager.decodeSettingsString(AppConfig.PREF_MODE, VPN))

            // Initialize mux-dependent UI states
            updateMux(MmkvManager.decodeSettingsBool(AppConfig.PREF_MUX_ENABLED, false))

            // Initialize fragment-dependent UI states
            updateFragment(MmkvManager.decodeSettingsBool(AppConfig.PREF_FRAGMENT_ENABLED, false))

            // Initialize auto-update interval state
            autoUpdateInterval?.isEnabled = MmkvManager.decodeSettingsBool(AppConfig.SUBSCRIPTION_AUTO_UPDATE, false)

            // Initialize Yandex update preferences state
            val onlyViaVpn = MmkvManager.decodeSettingsBool(AppConfig.PREF_UPDATE_SUB_ONLY_VIA_VPN, false)
            updateYandexUpdatePreferences(onlyViaVpn)

            // Update domain split tunneling summaries
            updateDomainSummaries()
        }

        private fun updateDomainSummaries() {
            val proxyDomains = MmkvManager.decodeSettingsString(AppConfig.PREF_PROXY_DOMAIN_LIST).orEmpty()
            val directDomains = MmkvManager.decodeSettingsString(AppConfig.PREF_DIRECT_DOMAIN_LIST).orEmpty()

            val proxyCount = proxyDomains.split(Regex("[,\\r\\n]+")).map { it.trim() }.filter { it.isNotEmpty() }.size
            val directCount = directDomains.split(Regex("[,\\r\\n]+")).map { it.trim() }.filter { it.isNotEmpty() }.size

            findPreference<androidx.preference.Preference>("pref_proxy_domain_list_settings")?.summary =
                if (proxyCount > 0) "Настроено доменов: $proxyCount"
                else getString(R.string.summary_pref_proxy_domain_list_settings)

            findPreference<androidx.preference.Preference>("pref_domain_split_tunneling_settings")?.summary =
                if (directCount > 0) "Настроено доменов: $directCount"
                else getString(R.string.summary_pref_domain_split_tunneling_settings)
        }

        private fun updateYandexUpdatePreferences(onlyViaVpn: Boolean) {
            val yandexCheck = findPreference<CheckBoxPreference>(AppConfig.PREF_UPDATE_VIA_YANDEX_ON_BS)
            val testYandex = findPreference<androidx.preference.Preference>("pref_test_update_via_yandex")
            val clearYandexCookies = findPreference<androidx.preference.Preference>("pref_clear_yandex_cookies")

            yandexCheck?.isEnabled = !onlyViaVpn
            testYandex?.isEnabled = !onlyViaVpn
            clearYandexCookies?.isEnabled = !onlyViaVpn
        }

        private fun updateMode(value: String?) {
            val vpn = value == VPN
            localDns?.isEnabled = vpn
            fakeDns?.isEnabled = vpn
            appendHttpProxy?.isEnabled = vpn
//            localDnsPort?.isEnabled = vpn
            vpnDns?.isEnabled = vpn
            vpnBypassLan?.isEnabled = vpn
            vpnInterfaceAddress?.isEnabled = vpn
            vpnMtu?.isEnabled = vpn
            if (vpn) {
                updateLocalDns(
                    MmkvManager.decodeSettingsBool(
                        AppConfig.PREF_LOCAL_DNS_ENABLED,
                        false
                    )
                )
            }
        }

        private fun updateLocalDns(enabled: Boolean) {
            fakeDns?.isEnabled = enabled
//            localDnsPort?.isEnabled = enabled
            vpnDns?.isEnabled = !enabled
        }

        private fun updateMux(enabled: Boolean) {
            muxConcurrency?.isEnabled = enabled
            muxXudpConcurrency?.isEnabled = enabled
            muxXudpQuic?.isEnabled = enabled
            if (enabled) {
                updateMuxConcurrency(MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_CONCURRENCY, "8"))
                updateMuxXudpConcurrency(MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_XUDP_CONCURRENCY, "8"))
            }
        }

        private fun updateMuxConcurrency(value: String?) {
            val concurrency = value?.toIntOrNull() ?: 8
            muxConcurrency?.summary = concurrency.toString()
        }


        private fun updateMuxXudpConcurrency(value: String?) {
            if (value == null) {
                muxXudpQuic?.isEnabled = true
            } else {
                val concurrency = value.toIntOrNull() ?: 8
                muxXudpConcurrency?.summary = concurrency.toString()
                muxXudpQuic?.isEnabled = concurrency >= 0
            }
        }

        private fun updateFragment(enabled: Boolean) {
            fragmentPackets?.isEnabled = enabled
            fragmentLength?.isEnabled = enabled
            fragmentInterval?.isEnabled = enabled
        }

        private fun showServerSelectionDialog(servers: List<ProfileItem>, onSelected: (ProfileItem) -> Unit) {
            val names = servers.map { CustomEndpointManager.getDisplayName(it) }.toTypedArray()
            val currentUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_ENDPOINT_URL)
            var selectedIndex = -1
            if (!currentUrl.isNullOrBlank()) {
                val currentProfile = AngConfigManager.identifyConfigType(currentUrl)
                if (currentProfile != null) {
                    selectedIndex = servers.indexOfFirst { 
                        it.remarks == currentProfile.remarks && it.server == currentProfile.server && it.serverPort == currentProfile.serverPort 
                    }
                }
            }

            android.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.title_pref_custom_endpoint_select_server))
                .setSingleChoiceItems(names, selectedIndex) { dialog, which ->
                    dialog.dismiss()
                    if (which in servers.indices) {
                        onSelected(servers[which])
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    fun onModeHelpClicked(view: View) {
        Utils.openUri(this, AppConfig.APP_WIKI_MODE)
    }
}
