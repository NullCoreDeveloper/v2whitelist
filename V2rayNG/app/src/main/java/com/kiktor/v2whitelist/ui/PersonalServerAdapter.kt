package com.kiktor.v2whitelist.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.dto.SubscriptionCache

class PersonalServerAdapter(
    private val context: Context,
    private val onSelect: (String) -> Unit,
    private val onPing: (String) -> Unit,
    private val onEdit: (String) -> Unit,
    private val onToggleExitNode: (String) -> Unit,
    private val onShare: (String) -> Unit,
    private val onDeleteServer: (String) -> Unit,
    private val onUpdateSub: (String) -> Unit,
    private val onDeleteSub: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class ListItem {
        data class SubHeader(
            val subId: String,
            val title: String,
            val serverCount: Int,
            val isExpanded: Boolean
        ) : ListItem()

        data class DirectHeader(
            val serverCount: Int,
            val isExpanded: Boolean
        ) : ListItem()

        data class Server(
            val guid: String,
            val profile: ProfileItem
        ) : ListItem()
    }

    private val collapsedIds = mutableSetOf<String>()
    private var isDirectCollapsed = false

    private var rawSubscriptions: List<SubscriptionCache> = emptyList()
    private var rawServers: List<Pair<String, ProfileItem>> = emptyList()
    private var displayItems: List<ListItem> = emptyList()

    private var selectedGuid: String? = null
    private var exitNodeGuid: String? = null
    private val latencyMap = mutableMapOf<String, Long>()

    companion object {
        private const val TYPE_SUB_HEADER = 0
        private const val TYPE_DIRECT_HEADER = 1
        private const val TYPE_SERVER = 2
    }

    fun setData(
        subscriptions: List<SubscriptionCache>,
        servers: List<Pair<String, ProfileItem>>,
        currentSelected: String?,
        currentExitNode: String?
    ) {
        rawSubscriptions = subscriptions
        rawServers = servers
        selectedGuid = currentSelected
        exitNodeGuid = currentExitNode
        rebuildDisplayList()
        notifyDataSetChanged()
    }

    private fun rebuildDisplayList() {
        val list = mutableListOf<ListItem>()
        val knownSubIds = rawSubscriptions.map { it.guid }.toSet()

        // 1. Доверенные подписки
        for (sub in rawSubscriptions) {
            val subServers = rawServers.filter { it.second.subscriptionId == sub.guid }
            val isExpanded = !collapsedIds.contains(sub.guid)
            list.add(
                ListItem.SubHeader(
                    subId = sub.guid,
                    title = sub.subscription.remarks.ifBlank { context.getString(R.string.personal_dialog_sub_title) },
                    serverCount = subServers.size,
                    isExpanded = isExpanded
                )
            )
            if (isExpanded) {
                for ((guid, profile) in subServers) {
                    list.add(ListItem.Server(guid, profile))
                }
            }
        }

        // 2. Отдельные серверы (не привязанные к подпискам)
        val directServers = rawServers.filter { it.second.subscriptionId !in knownSubIds }
        if (directServers.isNotEmpty()) {
            if (rawSubscriptions.isNotEmpty()) {
                val isExpanded = !isDirectCollapsed
                list.add(
                    ListItem.DirectHeader(
                        serverCount = directServers.size,
                        isExpanded = isExpanded
                    )
                )
                if (isExpanded) {
                    for ((guid, profile) in directServers) {
                        list.add(ListItem.Server(guid, profile))
                    }
                }
            } else {
                for ((guid, profile) in directServers) {
                    list.add(ListItem.Server(guid, profile))
                }
            }
        }

        displayItems = list
    }

    private fun toggleSub(subId: String) {
        if (collapsedIds.contains(subId)) {
            collapsedIds.remove(subId)
        } else {
            collapsedIds.add(subId)
        }
        rebuildDisplayList()
        notifyDataSetChanged()
    }

    private fun toggleDirect() {
        isDirectCollapsed = !isDirectCollapsed
        rebuildDisplayList()
        notifyDataSetChanged()
    }

    fun setLatency(guid: String, latencyMs: Long) {
        latencyMap[guid] = latencyMs
        val index = displayItems.indexOfFirst { it is ListItem.Server && it.guid == guid }
        if (index >= 0) {
            notifyItemChanged(index)
        }
    }

    override fun getItemCount(): Int = displayItems.size

    override fun getItemViewType(position: Int): Int {
        return when (displayItems[position]) {
            is ListItem.SubHeader -> TYPE_SUB_HEADER
            is ListItem.DirectHeader -> TYPE_DIRECT_HEADER
            is ListItem.Server -> TYPE_SERVER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SUB_HEADER, TYPE_DIRECT_HEADER -> {
                val view = inflater.inflate(R.layout.item_personal_sub_header, parent, false)
                SubHeaderViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_personal_server, parent, false)
                ServerViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = displayItems[position]) {
            is ListItem.SubHeader -> {
                (holder as SubHeaderViewHolder).bindSub(item)
            }
            is ListItem.DirectHeader -> {
                (holder as SubHeaderViewHolder).bindDirect(item)
            }
            is ListItem.Server -> {
                (holder as ServerViewHolder).bind(item)
            }
        }
    }

    inner class SubHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardSubHeader: MaterialCardView = itemView.findViewById(R.id.card_sub_header)
        private val ivSubIcon: ImageView = itemView.findViewById(R.id.iv_sub_icon)
        private val tvSubTitle: TextView = itemView.findViewById(R.id.tv_sub_title)
        private val tvSubCount: TextView = itemView.findViewById(R.id.tv_sub_count)
        private val btnUpdateSub: MaterialButton = itemView.findViewById(R.id.btn_update_sub)
        private val btnSubMenu: MaterialButton = itemView.findViewById(R.id.btn_sub_menu)
        private val ivExpandArrow: ImageView = itemView.findViewById(R.id.iv_expand_arrow)

        fun bindSub(item: ListItem.SubHeader) {
            ivSubIcon.setImageResource(R.drawable.ic_subscriptions_24dp)
            tvSubTitle.text = item.title
            tvSubCount.text = context.getString(R.string.personal_sub_servers_count, item.serverCount)
            btnUpdateSub.visibility = View.VISIBLE
            btnSubMenu.visibility = View.VISIBLE
            ivExpandArrow.rotation = if (item.isExpanded) 180f else 0f

            cardSubHeader.setOnClickListener {
                toggleSub(item.subId)
            }

            btnUpdateSub.setOnClickListener {
                onUpdateSub(item.subId)
            }

            btnSubMenu.setOnClickListener { v ->
                showSubMenu(v, item.subId)
            }
        }

        fun bindDirect(item: ListItem.DirectHeader) {
            ivSubIcon.setImageResource(R.drawable.ic_routing_24dp)
            tvSubTitle.text = context.getString(R.string.personal_direct_servers_title)
            tvSubCount.text = context.getString(R.string.personal_sub_servers_count, item.serverCount)
            btnUpdateSub.visibility = View.GONE
            btnSubMenu.visibility = View.GONE
            ivExpandArrow.rotation = if (item.isExpanded) 180f else 0f

            cardSubHeader.setOnClickListener {
                toggleDirect()
            }
        }

        private fun showSubMenu(anchor: View, subId: String) {
            val popup = PopupMenu(context, anchor)
            popup.menu.add(0, 1, 0, R.string.personal_refresh_subs)
            popup.menu.add(0, 2, 1, R.string.personal_sub_delete_title)
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> onUpdateSub(subId)
                    2 -> onDeleteSub(subId)
                }
                true
            }
            popup.show()
        }
    }

    inner class ServerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardServer: MaterialCardView = itemView.findViewById(R.id.card_server)
        val rbSelected: RadioButton = itemView.findViewById(R.id.rb_selected)
        val tvRemarks: TextView = itemView.findViewById(R.id.tv_remarks)
        val tvBadgeExitNode: TextView = itemView.findViewById(R.id.tv_badge_exit_node)
        val tvDetails: TextView = itemView.findViewById(R.id.tv_details)
        val tvLatency: TextView = itemView.findViewById(R.id.tv_latency)
        val btnPing: MaterialButton = itemView.findViewById(R.id.btn_ping)
        val btnMenu: MaterialButton = itemView.findViewById(R.id.btn_menu)

        fun bind(item: ListItem.Server) {
            val guid = item.guid
            val profile = item.profile
            val isSelected = guid == selectedGuid
            val isExitNode = guid == exitNodeGuid

            rbSelected.isChecked = isSelected
            tvRemarks.text = profile.remarks.ifBlank { "Trusted Server" }

            val details = "${profile.configType.name} • ${profile.server ?: ""}:${profile.serverPort ?: ""}"
            tvDetails.text = details

            tvBadgeExitNode.visibility = if (isExitNode) View.VISIBLE else View.GONE

            val latency = latencyMap[guid]
            if (latency != null) {
                tvLatency.visibility = View.VISIBLE
                if (latency < Long.MAX_VALUE && latency > 0) {
                    tvLatency.text = "${latency} ms"
                    tvLatency.setTextColor(context.getColor(android.R.color.holo_green_dark))
                } else {
                    tvLatency.text = "Timeout"
                    tvLatency.setTextColor(context.getColor(android.R.color.holo_red_dark))
                }
            } else {
                tvLatency.visibility = View.GONE
            }

            cardServer.strokeWidth = if (isSelected) 3 else 0

            itemView.setOnClickListener {
                onSelect(guid)
            }
            rbSelected.setOnClickListener {
                onSelect(guid)
            }

            btnPing.setOnClickListener {
                onPing(guid)
            }

            btnMenu.setOnClickListener { v ->
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

            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> onEdit(guid)
                    2 -> onToggleExitNode(guid)
                    3 -> onShare(guid)
                    4 -> onDeleteServer(guid)
                }
                true
            }
            popup.show()
        }
    }
}
