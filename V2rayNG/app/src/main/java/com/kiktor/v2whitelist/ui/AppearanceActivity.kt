package com.kiktor.v2whitelist.ui

import android.os.Bundle
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.google.android.material.color.DynamicColors
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.SettingsManager
import com.kiktor.v2whitelist.helper.MmkvPreferenceDataStore
import com.kiktor.v2whitelist.util.Utils
import com.kiktor.v2whitelist.extension.toast

class AppearanceActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(
            R.layout.activity_appearance,
            showHomeAsUp = true,
            title = getString(R.string.title_appearance_settings)
        )
    }

    class AppearanceFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = MmkvPreferenceDataStore()
            addPreferencesFromResource(R.xml.pref_appearance)

            val nightModePref = findPreference<ListPreference>(AppConfig.PREF_UI_MODE_NIGHT)
            val palettePref = findPreference<ListPreference>(AppConfig.PREF_THEME_PALETTE)
            val amoledPref = findPreference<SwitchPreference>(AppConfig.PREF_AMOLED_BLACK)
            val languagePref = findPreference<ListPreference>(AppConfig.PREF_LANGUAGE)

            // Если DynamicColors не поддерживается системой (< Android 12), отключаем выбор
            if (!DynamicColors.isDynamicColorAvailable()) {
                palettePref?.isEnabled = false
                palettePref?.summary = getString(R.string.summary_pref_dynamic_color)
            }

            nightModePref?.setOnPreferenceChangeListener { pref, newValue ->
                val str = newValue.toString()
                (pref as? ListPreference)?.let { lp ->
                    val idx = lp.findIndexOfValue(str)
                    lp.summary = if (idx >= 0) lp.entries[idx] else str
                }
                MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, str)
                SettingsManager.setNightMode()
                activity?.recreate()
                true
            }

            palettePref?.setOnPreferenceChangeListener { pref, newValue ->
                val str = newValue.toString()
                (pref as? ListPreference)?.let { lp ->
                    val idx = lp.findIndexOfValue(str)
                    lp.summary = if (idx >= 0) lp.entries[idx] else str
                }
                MmkvManager.encodeSettings(AppConfig.PREF_THEME_PALETTE, str)
                activity?.recreate()
                true
            }

            amoledPref?.setOnPreferenceChangeListener { _, newValue ->
                MmkvManager.encodeSettings(AppConfig.PREF_AMOLED_BLACK, newValue as Boolean)
                activity?.recreate()
                true
            }

            languagePref?.setOnPreferenceChangeListener { pref, newValue ->
                val str = newValue.toString()
                (pref as? ListPreference)?.let { lp ->
                    val idx = lp.findIndexOfValue(str)
                    lp.summary = if (idx >= 0) lp.entries[idx] else str
                }
                MmkvManager.encodeSettings(AppConfig.PREF_LANGUAGE, str)
                activity?.recreate()
                true
            }

            val premiumListener = androidx.preference.Preference.OnPreferenceChangeListener { _, _ ->
                requireContext().toast(getString(R.string.toast_restart_required_premium_ui))
                true
            }
            findPreference<SwitchPreference>(AppConfig.PREF_PREMIUM_GRADIENTS)?.onPreferenceChangeListener = premiumListener
            findPreference<androidx.preference.ListPreference>(AppConfig.PREF_PREMIUM_GRADIENT_ANIM)?.onPreferenceChangeListener = premiumListener
            findPreference<SwitchPreference>(AppConfig.PREF_PREMIUM_ANIMATIONS)?.onPreferenceChangeListener = premiumListener
            findPreference<SwitchPreference>(AppConfig.PREF_PREMIUM_GLASS)?.onPreferenceChangeListener = premiumListener
            findPreference<SwitchPreference>(AppConfig.PREF_PREMIUM_HAPTIC)?.onPreferenceChangeListener = premiumListener
            findPreference<SwitchPreference>(AppConfig.PREF_PREMIUM_PULSE)?.onPreferenceChangeListener = premiumListener

            val showStalenessPref = findPreference<SwitchPreference>(AppConfig.PREF_SUB_SHOW_STALENESS)
            val staleModePref = findPreference<ListPreference>(AppConfig.PREF_SUB_STALE_MODE)
            val staleTimeoutPref = findPreference<androidx.preference.EditTextPreference>(AppConfig.PREF_SUB_STALE_TIMEOUT_MIN)

            staleTimeoutPref?.setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }

            fun updateSubStatusDependencies(showStale: Boolean, modeVal: String?) {
                staleModePref?.isEnabled = showStale
                val isRelative = modeVal.equals("RELATIVE", ignoreCase = true)
                staleTimeoutPref?.isEnabled = showStale && isRelative
            }

            val initialShowStale = MmkvManager.decodeSettingsBool(AppConfig.PREF_SUB_SHOW_STALENESS, false)
            val initialStaleMode = MmkvManager.decodeSettingsString(AppConfig.PREF_SUB_STALE_MODE, "CALENDAR_HOUR")
            val initialTimeout = (MmkvManager.decodeSettingsString(AppConfig.PREF_SUB_STALE_TIMEOUT_MIN, "60")?.toIntOrNull() ?: 60)
                .coerceIn(1, 10080)
            staleTimeoutPref?.summary = getString(R.string.summary_pref_sub_stale_timeout_min, initialTimeout)
            updateSubStatusDependencies(initialShowStale, initialStaleMode)

            showStalenessPref?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as? Boolean ?: false
                updateSubStatusDependencies(enabled, staleModePref?.value)
                true
            }

            staleModePref?.setOnPreferenceChangeListener { pref, newValue ->
                val str = newValue.toString()
                (pref as? ListPreference)?.let { lp ->
                    val idx = lp.findIndexOfValue(str)
                    lp.summary = if (idx >= 0) lp.entries[idx] else str
                }
                updateSubStatusDependencies(showStalenessPref?.isChecked ?: false, str)
                true
            }

            staleTimeoutPref?.setOnPreferenceChangeListener { pref, newValue ->
                val parsed = newValue.toString().trim().toIntOrNull()?.coerceIn(1, 10080) ?: 60
                MmkvManager.encodeSettings(AppConfig.PREF_SUB_STALE_TIMEOUT_MIN, parsed.toString())
                (pref as? androidx.preference.EditTextPreference)?.text = parsed.toString()
                pref.summary = getString(R.string.summary_pref_sub_stale_timeout_min, parsed)
                false
            }

            findPreference<androidx.preference.Preference>("pref_add_qs_tile")?.setOnPreferenceClickListener {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
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

            initSummaries()
        }

        private fun initSummaries() {
            findPreference<ListPreference>(AppConfig.PREF_UI_MODE_NIGHT)?.let { lp ->
                val idx = lp.findIndexOfValue(lp.value)
                if (idx >= 0) lp.summary = lp.entries[idx]
            }
            findPreference<ListPreference>(AppConfig.PREF_THEME_PALETTE)?.let { lp ->
                val idx = lp.findIndexOfValue(lp.value)
                if (idx >= 0) lp.summary = lp.entries[idx]
            }
            findPreference<ListPreference>(AppConfig.PREF_LANGUAGE)?.let { lp ->
                val idx = lp.findIndexOfValue(lp.value)
                if (idx >= 0) lp.summary = lp.entries[idx]
            }
        }
    }
}
