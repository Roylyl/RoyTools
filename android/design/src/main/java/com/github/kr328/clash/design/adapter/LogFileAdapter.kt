package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.AdapterLogFileBinding
import com.github.kr328.clash.design.model.LogFile
import com.github.kr328.clash.design.util.format
import com.github.kr328.clash.design.util.layoutInflater

class LogFileAdapter(
    private val context: Context,
    private val open: (LogFile) -> Unit,
) : RecyclerView.Adapter<LogFileAdapter.Holder>() {
    class Holder(val binding: AdapterLogFileBinding) : RecyclerView.ViewHolder(binding.root)

    var logs: List<LogFile> = emptyList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(AdapterLogFileBinding.inflate(context.layoutInflater, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = logs[position]

        holder.binding.fileTitle.text = context.getString(
            R.string.roytools_logs_file_title, current.date.format(context).substringBeforeLast('.')
        )
        holder.binding.fileName.text = current.fileName
        holder.binding.root.setOnClickListener {
            open(current)
        }
    }

    override fun getItemCount(): Int {
        return logs.size
    }
}
