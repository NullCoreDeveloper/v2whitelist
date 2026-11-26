package com.kiktor.v2whitelist.ui

import android.app.Activity
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.core.model.AppScenario
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.ServiceCheckManager
import com.kiktor.v2whitelist.handler.SubscriptionHelper
import com.kiktor.v2whitelist.util.Utils
import java.util.Locale

/**
 * Контроллер окна настройки режима сети, порога скорости и проверки сервисов.
 */
class ModeSettingsBottomSheet(
    private val activity: Activity,
    private val onOpenServiceCheck: (Intent) -> Unit,
    private val onStateChanged: () -> Unit
) {
    private var rootView: View? = null
    private var switchCheckServices: MaterialSwitch? = null
    private var btnOpenServicesConfig: MaterialButton? = null
    private var tvEndpointNotice: TextView? = null

    fun show() {
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_mode_settings, null)
        rootView = view

        val toggleNetworkMode = view.findViewById<MaterialButtonToggleGroup>(R.id.toggle_network_mode)
        val btnAuto = view.findViewById<MaterialButton>(R.id.btn_mode_auto)
        val btnWl = view.findViewById<MaterialButton>(R.id.btn_mode_wl)
        val btnBl = view.findViewById<MaterialButton>(R.id.btn_mode_bl)
        val tvModeDesc = view.findViewById<TextView>(R.id.tv_network_mode_desc)

        val btnToggleCustomSpeed = view.findViewById<MaterialButton>(R.id.btn_toggle_custom_speed)
        val toggleSpeedPresets = view.findViewById<MaterialButtonToggleGroup>(R.id.toggle_speed_presets)
        val tilCustomSpeed = view.findViewById<TextInputLayout>(R.id.til_custom_speed)
        val etCustomSpeed = view.findViewById<TextInputEditText>(R.id.et_custom_speed)
        val tvSpeedDesc = view.findViewById<TextView>(R.id.tv_speed_desc)

        switchCheckServices = view.findViewById(R.id.switch_check_services)
        btnOpenServicesConfig = view.findViewById(R.id.btn_open_services_config)
        tvEndpointNotice = view.findViewById(R.id.tv_services_endpoint_notice)

        // --- 1. Режим сети ---
        fun updateModeDescription(scenario: AppScenario) {
            tvModeDesc.text = when (scenario) {
                AppScenario.WHITELIST -> activity.getString(R.string.mode_desc_wl)
                AppScenario.VPN_BLACKLIST -> activity.getString(R.string.mode_desc_bl)
                else -> activity.getString(R.string.mode_desc_auto)
            }
        }

        val currentScenario = SubscriptionHelper.getCurrentScenario()
        val initialModeBtnId = when (currentScenario) {
            AppScenario.WHITELIST -> R.id.btn_mode_wl
            AppScenario.VPN_BLACKLIST -> R.id.btn_mode_bl
            else -> R.id.btn_mode_auto
        }
        toggleNetworkMode.check(initialModeBtnId)
        updateModeDescription(currentScenario)

        toggleNetworkMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val scenario = when (checkedId) {
                R.id.btn_mode_wl -> AppScenario.WHITELIST
                R.id.btn_mode_bl -> AppScenario.VPN_BLACKLIST
                else -> AppScenario.AUTO
            }
            SubscriptionHelper.setScenario(scenario)
            updateModeDescription(scenario)
            onStateChanged()
        }

        // --- 2. Скорость ---
        val savedSpeedEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROFILE_SPEED_CHECK_ENABLED, true)
        val savedSpeedMbps = if (!savedSpeedEnabled) {
            0.0
        } else {
            MmkvManager.decodeSettingsString(AppConfig.PREF_PROFILE_MIN_SPEED_MBPS, "1.0")
                ?.trim()?.replace(',', '.')?.toDoubleOrNull()
                ?.coerceAtLeast(0.0) ?: 1.0
        }

        var isCustomSpeedMode = !savedSpeedEnabled ||
            (savedSpeedMbps != 1.0 && savedSpeedMbps != 10.0 && savedSpeedMbps != 30.0)
        var ignoreSpeedWatcher = false

        fun formatSpeedText(mbps: Double): String {
            return if (mbps % 1.0 == 0.0) {
                mbps.toInt().toString()
            } else {
                String.format(Locale.US, "%.1f", mbps)
            }
        }

        fun saveSpeed(mbps: Double) {
            val clean = mbps.coerceAtLeast(0.0)
            val enabled = clean > 0.0
            val str = if (clean % 1.0 == 0.0) "${clean.toInt()}.0" else clean.toString()
            MmkvManager.encodeSettings(AppConfig.PREF_PROFILE_SPEED_CHECK_ENABLED, enabled)
            MmkvManager.encodeSettings(AppConfig.PREF_PROFILE_MIN_SPEED_MBPS, str)
            onStateChanged()
        }

        fun updateSpeedDescForPreset(mbps: Double) {
            tvSpeedDesc.text = when (mbps) {
                1.0 -> activity.getString(R.string.mode_speed_desc_min)
                30.0 -> activity.getString(R.string.mode_speed_desc_max)
                else -> activity.getString(R.string.mode_speed_desc_balance)
            }
        }

        fun updateSpeedDescForCustom(mbps: Double) {
            tvSpeedDesc.text = if (mbps <= 0.0) {
                activity.getString(R.string.mode_speed_desc_custom_disabled)
            } else {
                activity.getString(R.string.mode_speed_desc_custom_value, formatSpeedText(mbps))
            }
        }

        fun applySpeedUiMode(customMode: Boolean, currentMbps: Double) {
            isCustomSpeedMode = customMode
            if (customMode) {
                toggleSpeedPresets.visibility = View.GONE
                tilCustomSpeed.visibility = View.VISIBLE
                btnToggleCustomSpeed.text = activity.getString(R.string.mode_speed_to_presets)
                ignoreSpeedWatcher = true
                etCustomSpeed.setText(formatSpeedText(currentMbps))
                etCustomSpeed.setSelection(etCustomSpeed.text?.length ?: 0)
                ignoreSpeedWatcher = false
                updateSpeedDescForCustom(currentMbps)
            } else {
                toggleSpeedPresets.visibility = View.VISIBLE
                tilCustomSpeed.visibility = View.GONE
                btnToggleCustomSpeed.text = activity.getString(R.string.mode_speed_to_custom)
                val presetMbps = when (currentMbps) {
                    1.0, 10.0, 30.0 -> currentMbps
                    else -> 10.0
                }
                val btnId = when (presetMbps) {
                    1.0 -> R.id.btn_speed_min
                    30.0 -> R.id.btn_speed_max
                    else -> R.id.btn_speed_balance
                }
                toggleSpeedPresets.check(btnId)
                updateSpeedDescForPreset(presetMbps)
            }
        }

        applySpeedUiMode(isCustomSpeedMode, savedSpeedMbps)

        toggleSpeedPresets.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || isCustomSpeedMode) return@addOnButtonCheckedListener
            val preset = when (checkedId) {
                R.id.btn_speed_min -> 1.0
                R.id.btn_speed_max -> 30.0
                else -> 10.0
            }
            saveSpeed(preset)
            updateSpeedDescForPreset(preset)
        }

        etCustomSpeed.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (ignoreSpeedWatcher || !isCustomSpeedMode) return
                val raw = s?.toString()?.trim()?.replace(',', '.') ?: ""
                val parsed = raw.toDoubleOrNull()
                if (parsed != null && parsed >= 0.0) {
                    tilCustomSpeed.error = null
                    saveSpeed(parsed)
                    updateSpeedDescForCustom(parsed)
                }
            }
        })

        btnToggleCustomSpeed.setOnClickListener {
            val currentEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROFILE_SPEED_CHECK_ENABLED, true)
            val currentVal = if (!currentEnabled) {
                0.0
            } else {
                MmkvManager.decodeSettingsString(AppConfig.PREF_PROFILE_MIN_SPEED_MBPS, "1.0")
                    ?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: 1.0
            }
            if (isCustomSpeedMode) {
                val targetPreset = when (currentVal) {
                    1.0, 10.0, 30.0 -> currentVal
                    else -> 10.0
                }
                saveSpeed(targetPreset)
                applySpeedUiMode(false, targetPreset)
            } else {
                applySpeedUiMode(true, currentVal)
            }
        }

        // --- 3. Проверка сервисов ---
        refreshServicesState()

        switchCheckServices?.setOnCheckedChangeListener { _, isChecked ->
            if (!isExitNodeActive()) {
                ServiceCheckManager.setServiceCheckEnabled(isChecked)
                onStateChanged()
            }
        }

        btnOpenServicesConfig?.setOnClickListener {
            onOpenServiceCheck(Intent(activity, ServiceCheckActivity::class.java))
        }

        // --- Отображение ---
        val isTvMode = Utils.isTv(activity)
        if (isTvMode) {
            view.findViewById<View>(R.id.drag_handle)?.visibility = View.GONE
            listOfNotNull(btnAuto, btnWl, btnBl).forEach {
                it.isFocusable = true
                it.isFocusableInTouchMode = true
            }
            val dialog = MaterialAlertDialogBuilder(activity)
                .setView(view)
                .setCancelable(true)
                .setPositiveButton(android.R.string.ok, null)
                .create()
            dialog.setOnDismissListener {
                clearRefs()
            }
            dialog.show()
            dialog.window?.let { window ->
                val dm = activity.resources.displayMetrics
                val targetWidth = (dm.widthPixels * 0.72).toInt().coerceAtMost(Utils.dp2px(activity, 640))
                window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            view.post { btnAuto?.requestFocus() }
        } else {
            val bottomSheetDialog = BottomSheetDialog(activity)
            bottomSheetDialog.setCancelable(true)
            bottomSheetDialog.setCanceledOnTouchOutside(true)
            bottomSheetDialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            bottomSheetDialog.behavior.skipCollapsed = true
            bottomSheetDialog.setOnDismissListener {
                clearRefs()
            }
            bottomSheetDialog.setContentView(view)
            bottomSheetDialog.show()
        }
    }

    fun refreshServicesState() {
        if (rootView == null) return
        val exitNodeActive = isExitNodeActive()
        val services = ServiceCheckManager.loadServices()
        val enabledCount = services.count { it.enabled }

        btnOpenServicesConfig?.text = activity.getString(
            R.string.mode_services_configure_btn,
            enabledCount,
            services.size
        )

        val isCheckEnabled = ServiceCheckManager.isServiceCheckEnabled()
        switchCheckServices?.apply {
            setOnCheckedChangeListener(null)
            isChecked = isCheckEnabled && !exitNodeActive
            isEnabled = !exitNodeActive
            alpha = if (exitNodeActive) 0.5f else 1.0f
            setOnCheckedChangeListener { _, checked ->
                if (!isExitNodeActive()) {
                    ServiceCheckManager.setServiceCheckEnabled(checked)
                    onStateChanged()
                }
            }
        }

        btnOpenServicesConfig?.apply {
            isEnabled = true
            alpha = 1.0f
        }

        tvEndpointNotice?.visibility = if (exitNodeActive) View.VISIBLE else View.GONE
    }

    private fun isExitNodeActive(): Boolean {
        val enabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_CUSTOM_ENDPOINT_ENABLED, false)
        val url = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_ENDPOINT_URL).orEmpty().trim()
        return enabled && url.isNotBlank()
    }

    private fun clearRefs() {
        rootView = null
        switchCheckServices = null
        btnOpenServicesConfig = null
        tvEndpointNotice = null
    }
}
