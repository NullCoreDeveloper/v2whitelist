package com.kiktor.v2whitelist.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.handler.ServiceCheckManager

class ServiceCheckActivity : BaseActivity() {

    private lateinit var rvServices: RecyclerView
    private lateinit var btnAddService: Button
    private lateinit var btnResetServices: Button
    private lateinit var adapter: ServiceCheckAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(
            R.layout.activity_service_check,
            showHomeAsUp = true,
            title = getString(R.string.title_service_check_activity)
        )

        rvServices = findViewById(R.id.rv_services)
        btnAddService = findViewById(R.id.btn_add_service)
        btnResetServices = findViewById(R.id.btn_reset_services)

        adapter = ServiceCheckAdapter(
            items = ServiceCheckManager.loadServices(),
            onToggle = { id, enabled ->
                ServiceCheckManager.toggleService(id, enabled)
            },
            onDelete = { id ->
                val updated = ServiceCheckManager.removeService(id)
                adapter.updateList(updated)
            }
        )

        rvServices.layoutManager = LinearLayoutManager(this)
        rvServices.adapter = adapter

        btnAddService.setOnClickListener {
            showAddServiceDialog()
        }

        btnResetServices.setOnClickListener {
            val defaults = ServiceCheckManager.resetToDefault()
            adapter.updateList(defaults)
            Toast.makeText(this, R.string.service_check_reset_btn, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAddServiceDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_custom_service, null, false)
        val etName = dialogView.findViewById<EditText>(R.id.et_service_name)
        val etUrl = dialogView.findViewById<EditText>(R.id.et_service_url)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.service_check_dialog_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = etName.text.toString().trim()
                val url = etUrl.text.toString().trim()
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    val updated = ServiceCheckManager.addCustomService(name, url)
                    adapter.updateList(updated)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

