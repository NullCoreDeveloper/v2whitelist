package com.kiktor.v2whitelist.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.core.domain.inspection.ServerListEntry
import com.kiktor.v2whitelist.core.model.inspection.countryCodeToFlagEmoji
import com.kiktor.v2whitelist.handler.SettingsManager
import com.kiktor.v2whitelist.viewmodel.SpeedTestRowState
import java.util.Locale

sealed class ServerListRow {
    data class Header(
        val id: String,
        val title: String,
        val isVipHeader: Boolean
    ) : ServerListRow()

    data class Server(
        val entry: ServerListEntry,
        val isExpanded: Boolean,
        val isConnected: Boolean,
        val endpointEnabled: Boolean,
        val speedTestState: SpeedTestRowState,
        val isReinspecting: Boolean,
        val fallbackFlagEmoji: String = ""
    ) : ServerListRow()

    data class LoadMore(
        val remainingCount: Int
    ) : ServerListRow()
}

class ServerListAdapter(
    private val onToggleExpand: (String) -> Unit,
    private val onConnect: (String) -> Unit,
    private val onSpeedTest: (String) -> Unit,
    private val onToggleVip: (String) -> Unit,
    private val onReinspect: (String) -> Unit,
    private val onLoadMore: () -> Unit
) : ListAdapter<ServerListRow, RecyclerView.ViewHolder>(DiffCallback) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_SERVER = 1
        private const val TYPE_LOAD_MORE = 2
        private val GOLD_COLOR = Color.parseColor("#D4AF37")
        private val GREEN_COLOR = Color.parseColor("#2E7D32")
        private val AMBER_COLOR = Color.parseColor("#F57C00")
        private val RED_COLOR = Color.parseColor("#D32F2F")

        fun getLocalizedCountryName(code: String): String {
            val norm = code.trim().uppercase()
            if (norm.isEmpty()) return ""
            return try {
                val locale = SettingsManager.getLocale()
                val name = Locale.Builder().setRegion(norm).build().getDisplayCountry(locale)
                if (name.isNotBlank() && !name.equals(norm, ignoreCase = true)) name else norm
            } catch (_: Exception) {
                norm
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<ServerListRow>() {
        override fun areItemsTheSame(oldItem: ServerListRow, newItem: ServerListRow): Boolean {
            return when {
                oldItem is ServerListRow.Header && newItem is ServerListRow.Header -> oldItem.id == newItem.id
                oldItem is ServerListRow.Server && newItem is ServerListRow.Server -> oldItem.entry.guid == newItem.entry.guid
                oldItem is ServerListRow.LoadMore && newItem is ServerListRow.LoadMore -> true
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: ServerListRow, newItem: ServerListRow): Boolean {
            return oldItem == newItem
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ServerListRow.Header -> TYPE_HEADER
            is ServerListRow.Server -> TYPE_SERVER
            is ServerListRow.LoadMore -> TYPE_LOAD_MORE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_server_section_header, parent, false))
            TYPE_LOAD_MORE -> {
                val btn = MaterialButton(parent.context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        val density = parent.context.resources.displayMetrics.density
                        setMargins((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (16 * density).toInt())
                    }
                }
                LoadMoreViewHolder(btn)
            }
            else -> ServerViewHolder(inflater.inflate(R.layout.item_server_card, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ServerListRow.Header -> (holder as HeaderViewHolder).bind(item)
            is ServerListRow.Server -> (holder as ServerViewHolder).bind(item)
            is ServerListRow.LoadMore -> (holder as LoadMoreViewHolder).bind(item)
        }
    }

    inner class LoadMoreViewHolder(private val btn: MaterialButton) : RecyclerView.ViewHolder(btn) {
        fun bind(item: ServerListRow.LoadMore) {
            btn.text = btn.context.getString(R.string.server_list_load_more, item.remainingCount)
            btn.setOnClickListener { onLoadMore() }
        }
    }

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_section_title)

        fun bind(item: ServerListRow.Header) {
            tvTitle.text = item.title
            if (item.isVipHeader) {
                tvTitle.setTextColor(GOLD_COLOR)
            } else {
                val defaultColor = MaterialColors.getColor(tvTitle, android.R.attr.textColorPrimary, Color.GRAY)
                tvTitle.setTextColor(defaultColor)
            }
        }
    }

    inner class ServerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView.findViewById(R.id.card_server)
        private val layoutSummary: LinearLayout = itemView.findViewById(R.id.layout_summary)
        private val tvFlag: TextView = itemView.findViewById(R.id.tv_flag)
        private val tvVipStar: TextView = itemView.findViewById(R.id.tv_vip_star)
        private val tvServerName: TextView = itemView.findViewById(R.id.tv_server_name)
        private val tvConnectedBadge: TextView = itemView.findViewById(R.id.tv_connected_badge)
        private val tvPing: TextView = itemView.findViewById(R.id.tv_ping)
        private val ivExpandArrow: ImageView = itemView.findViewById(R.id.iv_expand_arrow)
        private val tvProtocolBadge: TextView = itemView.findViewById(R.id.tv_protocol_badge)
        private val tvSubscription: TextView = itemView.findViewById(R.id.tv_subscription)
        private val tvStatusBadge: TextView = itemView.findViewById(R.id.tv_status_badge)

        private val layoutDetails: LinearLayout = itemView.findViewById(R.id.layout_details)
        private val tvExitIp: TextView = itemView.findViewById(R.id.tv_exit_ip)
        private val tvEndpointDetail: TextView = itemView.findViewById(R.id.tv_endpoint_detail)
        private val chipGroupServices: ChipGroup = itemView.findViewById(R.id.chip_group_services)
        private val tvErrorDetail: TextView = itemView.findViewById(R.id.tv_error_detail)
        private val tvSpeedResult: TextView = itemView.findViewById(R.id.tv_speed_result)
        private val btnConnect: MaterialButton = itemView.findViewById(R.id.btn_connect)
        private val btnSpeedTest: MaterialButton = itemView.findViewById(R.id.btn_speed_test)
        private val btnToggleVip: MaterialButton = itemView.findViewById(R.id.btn_toggle_vip)
        private val btnRetest: MaterialButton = itemView.findViewById(R.id.btn_retest)

        private val cachedOutlineColor = MaterialColors.getColor(
            card,
            com.google.android.material.R.attr.colorOutlineVariant,
            Color.LTGRAY
        )
        private val cachedSecondaryColor = MaterialColors.getColor(
            tvPing,
            android.R.attr.textColorSecondary,
            Color.GRAY
        )
        private val density = itemView.context.resources.displayMetrics.density

        fun bind(item: ServerListRow.Server) {
            val ctx = itemView.context
            val entry = item.entry
            val insp = entry.inspection

            card.alpha = if (entry.isDead) 0.65f else 1.0f

            if (entry.isVip) {
                card.strokeColor = GOLD_COLOR
                card.strokeWidth = (2f * density).toInt()
                tvVipStar.visibility = View.VISIBLE
            } else {
                card.strokeColor = cachedOutlineColor
                card.strokeWidth = (1f * density).toInt()
                tvVipStar.visibility = View.GONE
            }

            val exitFlag = countryCodeToFlagEmoji(entry.effectiveCountryCode)
            tvFlag.text = exitFlag.ifEmpty { item.fallbackFlagEmoji.ifEmpty { "🌐" } }

            tvServerName.text = entry.profile.remarks.ifBlank { entry.profile.server ?: entry.guid }
            tvConnectedBadge.visibility = if (item.isConnected) View.VISIBLE else View.GONE

            val ping = entry.effectivePingMs
            when {
                entry.isDead -> {
                    tvPing.text = "✖"
                    tvPing.setTextColor(RED_COLOR)
                }
                ping > 0L -> {
                    tvPing.text = "${ping}ms"
                    tvPing.setTextColor(
                        when {
                            ping < 250L -> GREEN_COLOR
                            ping < 600L -> AMBER_COLOR
                            else -> RED_COLOR
                        }
                    )
                }
                else -> {
                    tvPing.text = "—"
                    tvPing.setTextColor(cachedSecondaryColor)
                }
            }

            ivExpandArrow.rotation = if (item.isExpanded) 180f else 0f
            tvProtocolBadge.text = entry.profile.configType.name
            tvSubscription.text = "• ${entry.subscriptionName}"

            bindSummaryStatusBadge(ctx, item)

            layoutSummary.setOnClickListener {
                onToggleExpand(entry.guid)
            }

            if (item.isExpanded) {
                layoutDetails.visibility = View.VISIBLE
                bindExpandedDetails(ctx, item)
            } else {
                layoutDetails.visibility = View.GONE
            }
        }

        private fun bindSummaryStatusBadge(ctx: Context, item: ServerListRow.Server) {
            val insp = item.entry.inspection
            if (insp == null || !insp.alive) {
                tvStatusBadge.visibility = View.GONE
                return
            }

            if (item.endpointEnabled) {
                when (insp.endpointPassed) {
                    true -> {
                        tvStatusBadge.visibility = View.VISIBLE
                        tvStatusBadge.text = ctx.getString(R.string.server_list_endpoint_ok)
                        tvStatusBadge.setTextColor(GREEN_COLOR)
                    }
                    false -> {
                        tvStatusBadge.visibility = View.VISIBLE
                        tvStatusBadge.text = ctx.getString(R.string.server_list_endpoint_fail)
                        tvStatusBadge.setTextColor(RED_COLOR)
                    }
                    null -> {
                        tvStatusBadge.visibility = View.GONE
                    }
                }
            } else if (insp.totalServicesCount > 0) {
                tvStatusBadge.visibility = View.VISIBLE
                tvStatusBadge.text = ctx.getString(
                    R.string.server_list_services_badge,
                    insp.okServicesCount,
                    insp.totalServicesCount
                )
                tvStatusBadge.setTextColor(
                    when {
                        insp.okServicesCount == insp.totalServicesCount -> GREEN_COLOR
                        insp.okServicesCount > 0 -> AMBER_COLOR
                        else -> RED_COLOR
                    }
                )
            } else {
                tvStatusBadge.visibility = View.GONE
            }
        }

        private fun bindExpandedDetails(ctx: Context, item: ServerListRow.Server) {
            val entry = item.entry
            val insp = entry.inspection

            val ipText = insp?.exitIp.orEmpty()
            val cc = entry.effectiveCountryCode
            val fragSuffix = if (insp != null && insp.fragMode.isNotEmpty()) {
                " ⚡[FRAG:${insp.fragMode.uppercase()}]"
            } else ""

            if (ipText.isNotEmpty() || cc.isNotEmpty() || fragSuffix.isNotEmpty()) {
                val parts = mutableListOf<String>()
                if (cc.isNotEmpty()) {
                    val flag = countryCodeToFlagEmoji(cc)
                    val cName = getLocalizedCountryName(cc)
                    parts.add("$flag $cName".trim())
                }
                if (ipText.isNotEmpty()) {
                    parts.add(ipText)
                }
                val combined = parts.joinToString(" • ") + fragSuffix
                tvExitIp.visibility = View.VISIBLE
                tvExitIp.text = ctx.getString(R.string.server_list_exit_ip_label, combined.trim())
            } else {
                tvExitIp.visibility = View.GONE
            }

            if (item.endpointEnabled && insp != null && insp.alive) {
                tvEndpointDetail.visibility = View.VISIBLE
                when (insp.endpointPassed) {
                    true -> {
                        tvEndpointDetail.text = ctx.getString(R.string.server_list_endpoint_ok)
                        tvEndpointDetail.setTextColor(GREEN_COLOR)
                    }
                    false -> {
                        tvEndpointDetail.text = ctx.getString(R.string.server_list_endpoint_fail)
                        tvEndpointDetail.setTextColor(RED_COLOR)
                    }
                    null -> {
                        tvEndpointDetail.text = ctx.getString(R.string.server_list_endpoint_untested)
                        tvEndpointDetail.setTextColor(
                            MaterialColors.getColor(tvEndpointDetail, android.R.attr.textColorSecondary, Color.GRAY)
                        )
                    }
                }
            } else {
                tvEndpointDetail.visibility = View.GONE
            }

            chipGroupServices.removeAllViews()
            if (!item.endpointEnabled && insp != null && insp.services.isNotEmpty()) {
                chipGroupServices.visibility = View.VISIBLE
                for (svc in insp.services) {
                    val badge = createServiceBadge(ctx, svc.name, svc.success, svc.latencyMs)
                    chipGroupServices.addView(badge)
                }
            } else {
                chipGroupServices.visibility = View.GONE
            }

            if (entry.isDead && !insp?.error.isNullOrBlank()) {
                tvErrorDetail.visibility = View.VISIBLE
                tvErrorDetail.text = insp?.error
            } else {
                tvErrorDetail.visibility = View.GONE
            }

            when (val sp = item.speedTestState) {
                is SpeedTestRowState.Idle -> {
                    tvSpeedResult.visibility = View.GONE
                    btnSpeedTest.isEnabled = true
                    btnSpeedTest.text = ctx.getString(R.string.server_list_btn_speed_test)
                }
                is SpeedTestRowState.Testing -> {
                    tvSpeedResult.visibility = View.VISIBLE
                    tvSpeedResult.text = ctx.getString(R.string.server_list_speed_testing)
                    tvSpeedResult.setTextColor(
                        MaterialColors.getColor(tvSpeedResult, android.R.attr.textColorSecondary, Color.GRAY)
                    )
                    btnSpeedTest.isEnabled = false
                    btnSpeedTest.text = ctx.getString(R.string.server_list_speed_testing)
                }
                is SpeedTestRowState.Success -> {
                    tvSpeedResult.visibility = View.VISIBLE
                    tvSpeedResult.text = ctx.getString(R.string.server_list_speed_result, sp.mbps)
                    tvSpeedResult.setTextColor(GREEN_COLOR)
                    btnSpeedTest.isEnabled = true
                    btnSpeedTest.text = ctx.getString(R.string.server_list_btn_speed_test)
                }
                is SpeedTestRowState.Failed -> {
                    tvSpeedResult.visibility = View.VISIBLE
                    tvSpeedResult.text = ctx.getString(R.string.server_list_speed_failed)
                    tvSpeedResult.setTextColor(RED_COLOR)
                    btnSpeedTest.isEnabled = true
                    btnSpeedTest.text = ctx.getString(R.string.server_list_btn_speed_test)
                }
            }

            btnToggleVip.text = if (entry.isVip) {
                ctx.getString(R.string.server_list_btn_remove_vip)
            } else {
                ctx.getString(R.string.server_list_btn_add_vip)
            }

            if (item.isReinspecting) {
                btnRetest.isEnabled = false
                btnRetest.text = ctx.getString(R.string.server_list_retesting)
            } else {
                btnRetest.isEnabled = true
                btnRetest.text = ctx.getString(R.string.server_list_btn_retest)
            }

            btnConnect.setOnClickListener { onConnect(entry.guid) }
            btnSpeedTest.setOnClickListener { onSpeedTest(entry.guid) }
            btnToggleVip.setOnClickListener { onToggleVip(entry.guid) }
            btnRetest.setOnClickListener { onReinspect(entry.guid) }
        }

        private fun createServiceBadge(ctx: Context, name: String, success: Boolean, latencyMs: Long): TextView {
            val density = ctx.resources.displayMetrics.density
            val hPad = (8 * density).toInt()
            val vPad = (4 * density).toInt()
            val textColor = if (success) GREEN_COLOR else RED_COLOR
            val bgColor = if (success) Color.argb(28, 46, 125, 50) else Color.argb(28, 211, 47, 47)
            val strokeColor = if (success) Color.argb(90, 46, 125, 50) else Color.argb(90, 211, 47, 47)

            val bg = GradientDrawable().apply {
                cornerRadius = 8f * density
                setColor(bgColor)
                setStroke((1f * density).toInt(), strokeColor)
            }

            return TextView(ctx).apply {
                text = if (success && latencyMs >= 0L) "✔ $name ${latencyMs}ms" else if (success) "✔ $name" else "✖ $name"
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(hPad, vPad, hPad, vPad)
                background = bg
            }
        }
    }
}
