package com.kiktor.v2whitelist.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.core.model.ProfileItem

class PersonalServerAdapter(
    private val context: Context,
    private val onSelect: (String) -> Unit,
    private val onPing: (String) -> Unit,
    private val onEdit: (String) -> Unit,
    private val onToggleExitNode: (String) -> Unit,
    private val onShare: (String) -> Unit,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<PersonalServerAdapter.ViewHolder>() {

    private var servers: List<Pair<String, ProfileItem>> = emptyList()
    private var selectedGuid: String? = null
    private var exitNodeGuid: String? = null
    private val latencyMap = mutableMapOf<String, Long>()

    fun setData(
        newServers: List<Pair<String, ProfileItem>>,
        currentSelected: String?,
        currentExitNode: String?
    ) {
        servers = newServers
        selectedGuid = currentSelected
        exitNodeGuid = currentExitNode
        notifyDataSetChanged()
    }

    fun setLatency(guid: String, latencyMs: Long) {
        latencyMap[guid] = latencyMs
        val index = servers.indexOfFirst { it.first == guid }
        if (index >= 0) {
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_personal_server, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = servers.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (guid, profile) = servers[position]
        val isSelected = guid == selectedGuid
        val isExitNode = guid == exitNodeGuid

        holder.rbSelected.isChecked = isSelected
        holder.tvRemarks.text = profile.remarks.ifBlank { "Personal Server" }

        val details = "${profile.configType.name} • ${profile.server ?: ""}:${profile.serverPort ?: ""}"
        holder.tvDetails.text = details

        holder.tvBadgeExitNode.visibility = if (isExitNode) View.VISIBLE else View.GONE

        val latency = latencyMap[guid]
        if (latency != null) {
            holder.tvLatency.visibility = View.VISIBLE
            if (latency < Long.MAX_VALUE && latency > 0) {
                holder.tvLatency.text = "${latency} ms"
                holder.tvLatency.setTextColor(context.getColor(android.R.color.holo_green_dark))
            } else {
                holder.tvLatency.text = "Timeout"
                holder.tvLatency.setTextColor(context.getColor(android.R.color.holo_red_dark))
            }
        } else {
            holder.tvLatency.visibility = View.GONE
        }

        holder.cardServer.strokeWidth = if (isSelected) 3 else 0

        holder.itemView.setOnClickListener {
            onSelect(guid)
        }
        holder.rbSelected.setOnClickListener {
            onSelect(guid)
        }

        holder.btnPing.setOnClickListener {
            onPing(guid)
        }

        holder.btnMenu.setOnClickListener { v ->
            showPopupMenu(v, guid, isExitNode)
        }
    }

    private fun showPopupMenu(anchor: View, guid: String, isExitNode: Boolean) {
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, 1, 0, R.string.menu_item_edit_config)
        if (isExitNode) {
            popup.menu.add(0, 2, 1, R.string.personal_btn_remove_exit_node)
        } else {
            popup.menu.add(0, 2, 1, R.string.personal_btn_make_exit_node)
        }
        popup.menu.add(0, 3, 2, R.string.btn_label_share)
        popup.menu.add(0, 4, 3, R.string.menu_item_del_config)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> onEdit(guid)
                2 -> onToggleExitNode(guid)
                3 -> onShare(guid)
                4 -> onDelete(guid)
            }
            true
        }
        popup.show()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardServer: MaterialCardView = itemView.findViewById(R.id.card_server)
        val rbSelected: RadioButton = itemView.findViewById(R.id.rb_selected)
        val tvRemarks: TextView = itemView.findViewById(R.id.tv_remarks)
        val tvBadgeExitNode: TextView = itemView.findViewById(R.id.tv_badge_exit_node)
        val tvDetails: TextView = itemView.findViewById(R.id.tv_details)
        val tvLatency: TextView = itemView.findViewById(R.id.tv_latency)
        val btnPing: MaterialButton = itemView.findViewById(R.id.btn_ping)
        val btnMenu: MaterialButton = itemView.findViewById(R.id.btn_menu)
    }
}
