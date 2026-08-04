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
import com.kiktor.v2whitelist.handler.SmartConnectManager
import com.kiktor.v2whitelist.util.JsonUtil
import com.kiktor.v2whitelist.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class CustomSubscriptionsActivity : BaseActivity() {

    private lateinit var switchBuiltin: MaterialSwitch
    private lateinit var tvBuiltinLastUpdate: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var rvSubscriptions: RecyclerView
    private lateinit var adapter: CustomSubscriptionAdapter

    private var customSubs = mutableListOf<CustomSubItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(R.layout.activity_custom_subscriptions, showHomeAsUp = true, title = getString(R.string.title_custom_subscriptions))

        switchBuiltin = findViewById(R.id.switch_builtin)
        tvBuiltinLastUpdate = findViewById(R.id.tv_builtin_last_update)
        tvEmpty = findViewById(R.id.tv_empty)
        rvSubscriptions = findViewById(R.id.rv_subscriptions)

        setupBuiltinSwitch()
        setupAddButton()
        loadCustomSubs()
        setupRecyclerView()
        updateBuiltinLastUpdateTime()
    }

    private fun updateBuiltinLastUpdateTime() {
        val subscriptions = MmkvManager.decodeSubscriptions()
        val builtin = subscriptions.find { it.guid == SmartConnectManager.SUBSCRIPTION_ID }
        val lastUpdated = builtin?.subscription?.lastUpdated ?: 0L
        if (lastUpdated > 0) {
            tvBuiltinLastUpdate.text = getString(R.string.title_last_update, Utils.formatTimestamp(lastUpdated))
        } else {
            tvBuiltinLastUpdate.text = getString(R.string.title_last_update_never)
        }
    }

    private fun setupBuiltinSwitch() {
        switchBuiltin.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_USE_BUILTIN_SUB, true)
        switchBuiltin.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_USE_BUILTIN_SUB, isChecked)
            if (!isChecked) {
                // Полностью удаляем встроенную подписку и её серверы, если пользователь её выключил
                MmkvManager.removeSubscription(SmartConnectManager.SUBSCRIPTION_ID)
                com.kiktor.v2whitelist.util.MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_RELOAD_SERVER_LIST, "")
                updateBuiltinLastUpdateTime()
            } else {
                // Запускаем обновление подписки при включении
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    com.kiktor.v2whitelist.handler.SmartConnectManager.checkAndSetupSubscription(this@CustomSubscriptionsActivity)
                    com.kiktor.v2whitelist.util.MessageUtil.sendMsg2UI(this@CustomSubscriptionsActivity, AppConfig.MSG_STATE_RELOAD_SERVER_LIST, "")
                    updateBuiltinLastUpdateTime()
                }
            }
        }
    }

    private fun setupAddButton() {
        findViewById<View>(R.id.btn_add_sub).setOnClickListener {
            showAddDialog()
        }
    }

    private fun showAddDialog(existingItem: CustomSubItem? = null) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_subscription, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_sub_name)
        val etUrl = dialogView.findViewById<EditText>(R.id.et_sub_url)
        val etFilter = dialogView.findViewById<EditText>(R.id.et_sub_filter)
        val etGroupRegex = dialogView.findViewById<EditText>(R.id.et_sub_group_regex)

        if (existingItem != null) {
            etName.setText(existingItem.name)
            etUrl.setText(existingItem.url)
            etFilter.setText(existingItem.filter)
            etGroupRegex.setText(existingItem.groupRegex)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.custom_sub_add)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = etName.text.toString().trim()
                val url = etUrl.text.toString().trim()
                val filter = etFilter.text.toString().trim()
                val groupRegex = etGroupRegex.text.toString().trim()

                if (name.isEmpty()) {
                    toast(R.string.sub_setting_remarks)
                    return@setPositiveButton
                }
                if (url.isEmpty() || !Utils.isValidUrl(url)) {
                    toast(R.string.toast_invalid_url)
                    return@setPositiveButton
                }

                if (existingItem != null) {
                    existingItem.name = name
                    existingItem.url = url
                    existingItem.filter = filter
                    existingItem.groupRegex = groupRegex
                    adapter.notifyDataSetChanged()
                } else {
                    val sub = CustomSubItem(
                        id = System.currentTimeMillis().toString(),
                        name = name,
                        url = url,
                        filter = filter,
                        groupRegex = groupRegex,
                        enabled = true
                    )
                    customSubs.add(sub)
                    adapter.notifyItemInserted(customSubs.size - 1)
                    toast(R.string.custom_sub_added)
                }
                
                saveCustomSubs()
                updateEmptyState()
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
                    
                    // Обогащаем данными о последнем обновлении из реальных подписок
                    val allSubs = MmkvManager.decodeSubscriptions()
                    customSubs.forEach { sub ->
                        val realSub = allSubs.find { it.guid == "custom_sub_${sub.id}" }
                        sub.lastUpdated = realSub?.subscription?.lastUpdated ?: 0L
                    }
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
            },
            onEdit = { position ->
                showAddDialog(customSubs[position])
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
        var filter: String = "",
        var groupRegex: String = "",
        var enabled: Boolean = true,
        var lastUpdated: Long = 0L
    )
}
