package com.kiktor.v2whitelist.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckItem

class ServiceCheckAdapter(
    private var items: List<ServiceCheckItem>,
    private val onToggle: (id: String, enabled: Boolean) -> Unit,
    private val onDelete: (id: String) -> Unit
) : RecyclerView.Adapter<ServiceCheckAdapter.ViewHolder>() {

    fun updateList(newItems: List<ServiceCheckItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_service_check, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name
        holder.tvUrl.text = item.url
        holder.switchService.setOnCheckedChangeListener(null)
        holder.switchService.isChecked = item.enabled
        holder.switchService.setOnCheckedChangeListener { _, isChecked ->
            onToggle(item.id, isChecked)
        }

        if (item.isCustom) {
            holder.btnDelete.visibility = View.VISIBLE
            holder.btnDelete.setOnClickListener {
                onDelete(item.id)
            }
        } else {
            holder.btnDelete.visibility = View.GONE
            holder.btnDelete.setOnClickListener(null)
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_service_name)
        val tvUrl: TextView = view.findViewById(R.id.tv_service_url)
        val switchService: MaterialSwitch = view.findViewById(R.id.switch_service)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)
    }
}

