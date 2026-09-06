package com.kiktor.v2whitelist.ui

import android.graphics.Color
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.contracts.BaseAdapterListener
import com.kiktor.v2whitelist.databinding.ItemRecyclerSubSettingBinding
import com.kiktor.v2whitelist.helper.ItemTouchHelperAdapter
import com.kiktor.v2whitelist.helper.ItemTouchHelperViewHolder
import com.kiktor.v2whitelist.util.Utils
import com.kiktor.v2whitelist.viewmodel.SubscriptionsViewModel

class SubSettingRecyclerAdapter(
    private val viewModel: SubscriptionsViewModel,
    private val adapterListener: BaseAdapterListener?
) : RecyclerView.Adapter<SubSettingRecyclerAdapter.MainViewHolder>(), ItemTouchHelperAdapter {

    override fun getItemCount() = viewModel.getAll().size

    override fun onBindViewHolder(holder: MainViewHolder, position: Int) {
        val subscriptions = viewModel.getAll()
        val subId = subscriptions[position].guid
        val subItem = subscriptions[position].subscription
        holder.itemSubSettingBinding.tvName.text = subItem.remarks
        holder.itemSubSettingBinding.tvUrl.text = subItem.url
        holder.itemSubSettingBinding.chkEnable.isChecked = subItem.enabled

        if (subItem.lastUpdateFailed && subItem.enabled) {
            val redColor = androidx.core.content.ContextCompat.getColor(holder.itemView.context, android.R.color.holo_red_light)
            holder.itemSubSettingBinding.tvLastUpdated.text = holder.itemView.context.getString(R.string.sub_update_failed)
            holder.itemSubSettingBinding.tvLastUpdated.setTextColor(redColor)
            holder.itemSubSettingBinding.tvName.setTextColor(redColor)
        } else {
            holder.itemSubSettingBinding.tvLastUpdated.setTextColor(holder.defaultUpdatedColors)
            holder.itemSubSettingBinding.tvName.setTextColor(holder.defaultNameColors)
            holder.itemSubSettingBinding.tvLastUpdated.text = Utils.formatTimestamp(subItem.lastUpdated)
        }

        holder.itemView.setBackgroundColor(Color.TRANSPARENT)

        holder.itemSubSettingBinding.layoutEdit.setOnClickListener {
            adapterListener?.onEdit(subId, position)
        }

        holder.itemSubSettingBinding.layoutRemove.setOnClickListener {
            adapterListener?.onRemove(subId, position)
        }

        holder.itemSubSettingBinding.chkEnable.setOnCheckedChangeListener { it, isChecked ->
            if (!it.isPressed) return@setOnCheckedChangeListener
            subItem.enabled = isChecked
            viewModel.update(subId, subItem)
        }

        if (TextUtils.isEmpty(subItem.url)) {
            holder.itemSubSettingBinding.layoutUrl.visibility = View.GONE
            holder.itemSubSettingBinding.layoutShare.visibility = View.INVISIBLE
            holder.itemSubSettingBinding.chkEnable.visibility = View.INVISIBLE
            holder.itemSubSettingBinding.layoutLastUpdated.visibility = View.INVISIBLE
        } else {
            holder.itemSubSettingBinding.layoutUrl.visibility = View.VISIBLE
            holder.itemSubSettingBinding.layoutShare.visibility = View.VISIBLE
            holder.itemSubSettingBinding.chkEnable.visibility = View.VISIBLE
            holder.itemSubSettingBinding.layoutLastUpdated.visibility = View.VISIBLE
            holder.itemSubSettingBinding.layoutShare.setOnClickListener {
                adapterListener?.onShare(subItem.url)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MainViewHolder {
        return MainViewHolder(
            ItemRecyclerSubSettingBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    class MainViewHolder(val itemSubSettingBinding: ItemRecyclerSubSettingBinding) :
        BaseViewHolder(itemSubSettingBinding.root), ItemTouchHelperViewHolder {
        val defaultNameColors = itemSubSettingBinding.tvName.textColors
        val defaultUpdatedColors = itemSubSettingBinding.tvLastUpdated.textColors
    }

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun onItemSelected() {
            itemView.setBackgroundColor(Color.LTGRAY)
        }

        fun onItemClear() {
            itemView.setBackgroundColor(0)
        }
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        viewModel.swap(fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onItemMoveCompleted() {
        adapterListener?.onRefreshData()
    }

    override fun onItemDismiss(position: Int) {
    }
}
