package com.kiktor.v2whitelist.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.databinding.ActivityTaskerBinding
import com.kiktor.v2whitelist.handler.ExternalControlAuth
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.PersonalServersManager

class TaskerActivity : BaseActivity() {
    private val binding by lazy { ActivityTaskerBinding.inflate(layoutInflater) }
    private val personalButtonIds = LinkedHashMap<String, Int>()
    private val personalNames = LinkedHashMap<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.tasker_title))

        setupExternalControlBanner()
        populatePersonalServers()
        restoreSelection()

        binding.btnSaveTasker.setOnClickListener {
            confirmFinish()
        }
    }

    private fun setupExternalControlBanner() {
        val enabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_EXTERNAL_CONTROL_ENABLED, false)
        binding.cardExternalDisabled.visibility = if (enabled) View.GONE else View.VISIBLE

        binding.btnEnableExternalControl.setOnClickListener {
            MmkvManager.encodeSettings(AppConfig.PREF_EXTERNAL_CONTROL_ENABLED, true)
            binding.cardExternalDisabled.visibility = View.GONE
        }
    }

    private fun populatePersonalServers() {
        val personalServers = PersonalServersManager.getPersonalServers()
        if (personalServers.isEmpty()) {
            binding.tvPersonalSection.visibility = View.GONE
            return
        }

        binding.tvPersonalSection.visibility = View.VISIBLE
        val minHeightPx = (48 * resources.displayMetrics.density).toInt()
        for ((guid, profile) in personalServers) {
            val label = profile.remarks.ifBlank { profile.server ?: guid }
            val rbId = View.generateViewId()
            val rb = RadioButton(this).apply {
                id = rbId
                text = label
                tag = guid
                minHeight = minHeightPx
                layoutParams = RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT
                )
            }
            binding.rgTaskerAction.addView(rb)
            personalButtonIds[guid] = rbId
            personalNames[guid] = label
        }
    }

    private fun restoreSelection() {
        try {
            val bundle = intent?.getBundleExtra(AppConfig.TASKER_EXTRA_BUNDLE)
            val action = bundle?.getString(AppConfig.TASKER_EXTRA_BUNDLE_ACTION)
            val guid = bundle?.getString(AppConfig.TASKER_EXTRA_BUNDLE_GUID).orEmpty()

            if (!action.isNullOrBlank()) {
                when (action) {
                    AppConfig.TASKER_ACTION_DISCONNECT -> binding.rgTaskerAction.check(R.id.rb_action_disconnect)
                    AppConfig.TASKER_ACTION_TOGGLE -> binding.rgTaskerAction.check(R.id.rb_action_toggle)
                    AppConfig.TASKER_ACTION_SWITCH -> binding.rgTaskerAction.check(R.id.rb_action_switch)
                    AppConfig.TASKER_ACTION_CONNECT_SERVER -> {
                        val personalId = personalButtonIds[guid]
                        if (personalId != null) {
                            binding.rgTaskerAction.check(personalId)
                        } else {
                            binding.rgTaskerAction.check(R.id.rb_action_connect)
                        }
                    }
                    else -> binding.rgTaskerAction.check(R.id.rb_action_connect)
                }
                return
            }

            if (bundle != null && bundle.containsKey(AppConfig.TASKER_EXTRA_BUNDLE_SWITCH)) {
                val legacySwitch = bundle.getBoolean(AppConfig.TASKER_EXTRA_BUNDLE_SWITCH, true)
                if (!legacySwitch) {
                    binding.rgTaskerAction.check(R.id.rb_action_disconnect)
                } else {
                    val personalId = personalButtonIds[guid]
                    if (personalId != null) {
                        binding.rgTaskerAction.check(personalId)
                    } else {
                        binding.rgTaskerAction.check(R.id.rb_action_connect)
                    }
                }
                return
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to initialize Tasker settings", e)
        }

        binding.rgTaskerAction.check(R.id.rb_action_connect)
    }

    private fun confirmFinish() {
        val checkedId = binding.rgTaskerAction.checkedRadioButtonId
        val extraBundle = Bundle()
        var blurb: String

        when (checkedId) {
            R.id.rb_action_disconnect -> {
                extraBundle.putString(AppConfig.TASKER_EXTRA_BUNDLE_ACTION, AppConfig.TASKER_ACTION_DISCONNECT)
                extraBundle.putString(AppConfig.TASKER_EXTRA_BUNDLE_GUID, AppConfig.TASKER_DEFAULT_GUID)
                extraBundle.putBoolean(AppConfig.TASKER_EXTRA_BUNDLE_SWITCH, false)
                blurb = getString(R.string.tasker_action_disconnect)
            }
            R.id.rb_action_toggle -> {
                extraBundle.putString(AppConfig.TASKER_EXTRA_BUNDLE_ACTION, AppConfig.TASKER_ACTION_TOGGLE)
                extraBundle.putString(AppConfig.TASKER_EXTRA_BUNDLE_GUID, AppConfig.TASKER_DEFAULT_GUID)
                extraBundle.putBoolean(AppConfig.TASKER_EXTRA_BUNDLE_SWITCH, true)
                blurb = getString(R.string.tasker_action_toggle)
            }
            R.id.rb_action_switch -> {
                extraBundle.putString(AppConfig.TASKER_EXTRA_BUNDLE_ACTION, AppConfig.TASKER_ACTION_SWITCH)
                extraBundle.putString(AppConfig.TASKER_EXTRA_BUNDLE_GUID, AppConfig.TASKER_DEFAULT_GUID)
                extraBundle.putBoolean(AppConfig.TASKER_EXTRA_BUNDLE_SWITCH, true)
                blurb = getString(R.string.tasker_action_switch)
            }
            else -> {
                val checkedView = binding.rgTaskerAction.findViewById<RadioButton>(checkedId)
                val selectedGuid = checkedView?.tag as? String
                if (!selectedGuid.isNullOrBlank() && personalButtonIds.containsKey(selectedGuid)) {
                    val serverName = personalNames[selectedGuid] ?: selectedGuid
                    extraBundle.putString(AppConfig.TASKER_EXTRA_BUNDLE_ACTION, AppConfig.TASKER_ACTION_CONNECT_SERVER)
                    extraBundle.putString(AppConfig.TASKER_EXTRA_BUNDLE_GUID, selectedGuid)
                    extraBundle.putBoolean(AppConfig.TASKER_EXTRA_BUNDLE_SWITCH, true)
                    blurb = serverName
                } else {
                    extraBundle.putString(AppConfig.TASKER_EXTRA_BUNDLE_ACTION, AppConfig.TASKER_ACTION_CONNECT)
                    extraBundle.putString(AppConfig.TASKER_EXTRA_BUNDLE_GUID, AppConfig.TASKER_DEFAULT_GUID)
                    extraBundle.putBoolean(AppConfig.TASKER_EXTRA_BUNDLE_SWITCH, true)
                    blurb = getString(R.string.tasker_action_connect)
                }
            }
        }

        val incomingBundle = intent?.getBundleExtra(AppConfig.TASKER_EXTRA_BUNDLE)
        val existingToken = incomingBundle?.getString(AppConfig.TASKER_EXTRA_BUNDLE_TOKEN)
        val authToken = if (ExternalControlAuth.verify(this, existingToken)) {
            existingToken!!
        } else {
            ExternalControlAuth.issue(this)
        }
        extraBundle.putString(AppConfig.TASKER_EXTRA_BUNDLE_TOKEN, authToken)

        val resultIntent = Intent().apply {
            putExtra(AppConfig.TASKER_EXTRA_BUNDLE, extraBundle)
            putExtra(AppConfig.TASKER_EXTRA_STRING_BLURB, blurb)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.action_server, menu)
        menu.findItem(R.id.del_config)?.isVisible = false
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.save_config -> {
            confirmFinish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
