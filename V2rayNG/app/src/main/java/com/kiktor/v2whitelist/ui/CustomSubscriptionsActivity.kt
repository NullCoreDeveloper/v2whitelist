package com.kiktor.v2whitelist.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.EditText
import android.widget.ImageView
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

    private lateinit var tvEmpty: TextView
    private lateinit var rvSubscriptions: RecyclerView
    private lateinit var adapter: CustomSubscriptionAdapter

    private var customSubs = mutableListOf<CustomSubItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(R.layout.activity_custom_subscriptions, showHomeAsUp = true, title = getString(R.string.title_custom_subscriptions))

        tvEmpty = findViewById(R.id.tv_empty)
        rvSubscriptions = findViewById(R.id.rv_subscriptions)

        setupAddButton()
        loadCustomSubs()
        setupRecyclerView()
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

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.custom_sub_add)
            .setView(dialogView)
            
        if (existingItem != null) {
            builder.setNeutralButton("Поделиться") { _, _ ->
                val sharedSub = com.kiktor.v2whitelist.handler.DeepLinkManager.SharedSubscription(
                    name = existingItem.name,
                    url = existingItem.url,
                    filter = existingItem.filter,
                    groupRegex = existingItem.groupRegex
                )
                val base64 = com.kiktor.v2whitelist.handler.DeepLinkManager.encodeToDeepLinkData(sharedSub)
                val link = "${com.kiktor.v2whitelist.handler.DeepLinkManager.SCHEME_SUB}://?data=$base64"
                
                // Show QR and Link
                com.kiktor.v2whitelist.util.Utils.setClipboard(this, link)
                toast("Ссылка скопирована в буфер!")
                
                // Optionally we could show a QR code dialog, but for now we copy to clipboard.
                val qrBitmap = com.kiktor.v2whitelist.util.QRCodeDecoder.createQRCode(link)
                if (qrBitmap != null) {
                    val iv = ImageView(this).apply {
                        setImageBitmap(qrBitmap)
                        setPadding(32, 32, 32, 32)
                    }
                    AlertDialog.Builder(this)
                        .setTitle("QR код подписки")
                        .setView(iv)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }

        builder.setPositiveButton(android.R.string.ok) { _, _ ->
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

                    // Авто-очистка призрачных подписок (удаленных до фикса бага)
                    allSubs.forEach { sub ->
                        if (sub.guid.startsWith("custom_sub_")) {
                            val id = sub.guid.removePrefix("custom_sub_")
                            if (customSubs.none { it.id == id }) {
                                MmkvManager.removeSubscription(sub.guid)
                            }
                        }
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
                if (!isEnabled) {
                    val subId = customSubs[position].id
                    val guid = "custom_sub_$subId"
                    MmkvManager.removeServerViaSubid(guid)
                    customSubs[position].lastUpdated = 0L
                    
                    val allSubs = MmkvManager.decodeSubscriptions()
                    allSubs.find { it.guid == guid }?.let {
                        it.subscription.lastUpdated = 0L
                        MmkvManager.encodeSubscription(it.guid, it.subscription)
                    }
                    
                    // Обновляем UI, чтобы сразу сбросить "Обновлено N минут назад"
                    rvSubscriptions.post {
                        adapter.notifyItemChanged(position)
                    }
                }
                saveCustomSubs()
            },
            onDelete = { position ->
                val subId = customSubs[position].id
                MmkvManager.removeSubscription("custom_sub_$subId")
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
