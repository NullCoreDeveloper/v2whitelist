package com.kiktor.v2whitelist.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.extension.toast
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.util.JsonUtil
import com.kiktor.v2whitelist.util.Utils

class CustomSubscriptionsActivity : BaseActivity() {

    private lateinit var switchBuiltin: MaterialSwitch
    private lateinit var tvEmpty: TextView
    private lateinit var rvSubscriptions: RecyclerView
    private lateinit var adapter: CustomSubscriptionAdapter

    private var customSubs = mutableListOf<CustomSubItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(R.layout.activity_custom_subscriptions, showHomeAsUp = true, title = getString(R.string.title_custom_subscriptions))

        switchBuiltin = findViewById(R.id.switch_builtin)
        tvEmpty = findViewById(R.id.tv_empty)
        rvSubscriptions = findViewById(R.id.rv_subscriptions)

        setupBuiltinSwitch()
        setupAddButton()
        loadCustomSubs()
        setupRecyclerView()
    }

    private fun setupBuiltinSwitch() {
        switchBuiltin.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_USE_BUILTIN_SUB, true)
        switchBuiltin.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_USE_BUILTIN_SUB, isChecked)
        }
    }

    private fun setupAddButton() {
        findViewById<View>(R.id.btn_add_sub).setOnClickListener {
            showAddDialog()
        }
    }

    private fun showAddDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_subscription, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_sub_name)
        val etUrl = dialogView.findViewById<EditText>(R.id.et_sub_url)

        AlertDialog.Builder(this)
            .setTitle(R.string.custom_sub_add)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = etName.text.toString().trim()
                val url = etUrl.text.toString().trim()

                if (name.isEmpty()) {
                    toast(R.string.sub_setting_remarks)
                    return@setPositiveButton
                }
                if (url.isEmpty() || !Utils.isValidUrl(url)) {
                    toast(R.string.toast_invalid_url)
                    return@setPositiveButton
                }

                val sub = CustomSubItem(
                    id = System.currentTimeMillis().toString(),
                    name = name,
                    url = url,
                    enabled = true
                )
                customSubs.add(sub)
                saveCustomSubs()
                adapter.notifyItemInserted(customSubs.size - 1)
                updateEmptyState()
                toast(R.string.custom_sub_added)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadCustomSubs() {
        val json = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_SUB_URLS)
        if (!json.isNullOrEmpty()) {
            try {
                val items = JsonUtil.fromJson(json, Array<CustomSubItem>::class.java)
                if (items != null) {
                    customSubs = items.toMutableList()
                }
            } catch (e: Exception) {
                customSubs = mutableListOf()
            }
        }
    }

    private fun saveCustomSubs() {
        val json = JsonUtil.toJson(customSubs)
        MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_SUB_URLS, json)
    }

    private fun setupRecyclerView() {
        adapter = CustomSubscriptionAdapter(
            items = customSubs,
            onToggle = { position, isEnabled ->
                customSubs[position].enabled = isEnabled
                saveCustomSubs()
            },
            onDelete = { position ->
                customSubs.removeAt(position)
                saveCustomSubs()
                adapter.notifyItemRemoved(position)
                updateEmptyState()
                toast(R.string.custom_sub_removed)
            }
        )

        rvSubscriptions.layoutManager = LinearLayoutManager(this)
        rvSubscriptions.adapter = adapter
        updateEmptyState()
    }

    private fun updateEmptyState() {
        if (customSubs.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rvSubscriptions.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rvSubscriptions.visibility = View.VISIBLE
        }
    }

    data class CustomSubItem(
        val id: String,
        var name: String,
        var url: String,
        var enabled: Boolean = true
    )
}
