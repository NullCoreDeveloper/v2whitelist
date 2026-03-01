package com.kiktor.v2whitelist.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.kiktor.v2whitelist.R

class LocationFilterAdapter(
    private val locations: List<LocationFilterActivity.LocationItem>,
    private val onToggle: (emoji: String, isChecked: Boolean) -> Unit
) : RecyclerView.Adapter<LocationFilterAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvEmoji: TextView = view.findViewById(R.id.tv_emoji)
        val tvLocationName: TextView = view.findViewById(R.id.tv_location_name)
        val tvServerCount: TextView = view.findViewById(R.id.tv_server_count)
        val switchEnabled: MaterialSwitch = view.findViewById(R.id.switch_enabled)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_location_filter, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = locations[position]
        holder.tvEmoji.text = item.emoji
        holder.tvLocationName.text = item.emoji  // Эмодзи как название
        holder.tvServerCount.text = holder.itemView.context.getString(
            R.string.location_filter_server_count, item.serverCount
        )

        // Блокируем слушателя при установке значения
        holder.switchEnabled.setOnCheckedChangeListener(null)
        holder.switchEnabled.isChecked = item.isSelected
        holder.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            item.isSelected = isChecked
            onToggle(item.emoji, isChecked)
        }
    }

    override fun getItemCount() = locations.size
}
