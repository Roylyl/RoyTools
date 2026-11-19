package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.AdapterLogMessageBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LogMessageAdapter(
    private val context: Context,
    private val copy: (LogMessage) -> Unit,
) :
    RecyclerView.Adapter<LogMessageAdapter.Holder>() {
    class Holder(val binding: AdapterLogMessageBinding) : RecyclerView.ViewHolder(binding.root)

    var messages: List<LogMessage> = emptyList()
        private set
    private val expandedMessages = mutableSetOf<LogMessage>()

    suspend fun submitMessages(next: List<LogMessage>) {
        val previous = messages
        val diff = withContext(Dispatchers.Default) {
            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = previous.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    previous[oldItemPosition] == next[newItemPosition]
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) = true
            }, false)
        }
        withContext(Dispatchers.Main) {
            messages = next
            expandedMessages.retainAll(next.toSet())
            diff.dispatchUpdatesTo(this@LogMessageAdapter)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterLogMessageBinding
                .inflate(context.layoutInflater, parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = messages[position]

        holder.binding.message = current
        val hasDetails = current.message.contains("\n原始记录：")
        fun renderDetails() {
            val expanded = current in expandedMessages
            holder.binding.displayMessage = if (expanded) current.message
                else current.message.substringBefore("\n原始记录：")
            holder.binding.detailsHint.visibility = if (hasDetails) View.VISIBLE else View.GONE
            holder.binding.detailsHint.setText(if (expanded) R.string.roytools_logs_collapse_details
                else R.string.roytools_logs_expand_details)
        }
        renderDetails()
        holder.binding.root.setOnClickListener(if (hasDetails) View.OnClickListener {
            if (!expandedMessages.add(current)) expandedMessages.remove(current)
            renderDetails()
        } else null)
        holder.binding.levelLabel.setText(when (current.level) {
            LogMessage.Level.Error -> R.string.roytools_logs_error
            LogMessage.Level.Warning -> R.string.roytools_logs_warning
            LogMessage.Level.Info -> R.string.roytools_logs_info
            LogMessage.Level.Debug -> R.string.roytools_logs_debug
            LogMessage.Level.Silent -> R.string.roytools_logs_silent
            LogMessage.Level.Unknown -> R.string.roytools_logs_unknown
        })
        holder.binding.levelLabel.setTextColor(context.resolveThemedColor(when (current.level) {
            LogMessage.Level.Error -> com.google.android.material.R.attr.colorError
            LogMessage.Level.Warning -> com.google.android.material.R.attr.colorOnSurface
            LogMessage.Level.Info -> androidx.appcompat.R.attr.colorPrimary
            else -> R.attr.glassTextSecondary
        }))
        holder.binding.root.setOnLongClickListener {
            copy(current)

            true
        }
    }

    override fun getItemCount(): Int {
        return messages.size
    }
}
