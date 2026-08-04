package com.kiktor.v2whitelist.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.kiktor.v2whitelist.R

class CustomSubscriptionAdapter(
    private val items: List<CustomSubscriptionsActivity.CustomSubItem>,
    private val onToggle: (position: Int, isEnabled: Boolean) -> Unit,
    private val onDelete: (position: Int) -> Unit,
    private val onEdit: (position: Int) -> Unit
) : RecyclerView.Adapter<CustomSubscriptionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_sub_name)
        val tvUrl: TextView = view.findViewById(R.id.tv_sub_url)
        val tvLastUpdate: TextView = view.findViewById(R.id.tv_sub_last_update)
        val switchEnabled: MaterialSwitch = view.findViewById(R.id.switch_sub_enabled)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_custom_subscription, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name
        holder.tvUrl.text = item.url
        
        if (item.lastUpdated > 0) {
            val dateStr = com.kiktor.v2whitelist.util.Utils.formatTimestamp(item.lastUpdated)
            holder.tvLastUpdate.text = holder.itemView.context.getString(R.string.title_last_update, dateStr)
            holder.tvLastUpdate.visibility = View.VISIBLE
        } else {
            holder.tvLastUpdate.text = holder.itemView.context.getString(R.string.title_last_update_never)
            holder.tvLastUpdate.visibility = View.VISIBLE
        }

        holder.switchEnabled.setOnCheckedChangeListener(null)
        holder.switchEnabled.isChecked = item.enabled
        holder.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            onToggle(holder.adapterPosition, isChecked)
        }

        holder.btnDelete.setOnClickListener {
            onDelete(holder.adapterPosition)
        }

        holder.itemView.setOnClickListener {
            onEdit(holder.adapterPosition)
        }
    }

    override fun getItemCount() = items.size
}
