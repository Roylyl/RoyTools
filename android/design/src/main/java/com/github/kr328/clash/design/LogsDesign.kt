package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.adapter.LogFileAdapter
import com.github.kr328.clash.design.databinding.DesignLogsBinding
import com.github.kr328.clash.design.model.LogFile
import com.github.kr328.clash.design.util.*
import com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class LogsDesign(context: Context) : Design<LogsDesign.Request>(context) {
    sealed class Request {
        object StartLogcat : Request()
        object DeleteAll : Request()

        data class OpenFile(val file: LogFile) : Request()
    }

    private val binding = DesignLogsBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = LogFileAdapter(context) {
        requests.trySend(Request.OpenFile(it))
    }

    override val root: View
        get() = binding.root

    suspend fun patchLogs(logs: List<LogFile>) {
        adapter.patchDataSet(adapter::logs, logs.sortedByDescending { it.date.time }, false, LogFile::fileName)
        withContext(Dispatchers.Main) {
            binding.historyCount.text = context.getString(R.string.roytools_logs_history_count, logs.size)
            binding.emptyView.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
            binding.deleteView.isEnabled = logs.isNotEmpty() && !recording
            binding.deleteView.alpha = if (binding.deleteView.isEnabled) 1f else 0.4f
        }
    }

    private var recording = false

    fun setRecording(value: Boolean) {
        recording = value
        binding.captureStatus.setText(if (value) R.string.roytools_logs_recording else R.string.roytools_logs_idle)
        binding.captureStatus.setTextColor(context.resolveThemedColor(
            if (value) R.attr.glassSuccess else R.attr.glassTextSecondary
        ))
        binding.captureDescription.setText(if (value) R.string.roytools_logs_active_intro else R.string.roytools_logs_intro)
        binding.startLogcat.setText(if (value) R.string.roytools_logs_open_live else R.string.roytools_logs_start)
        binding.historyHint.setText(if (value) R.string.roytools_logs_clear_active else R.string.roytools_logs_history_hint)
        binding.deleteView.isEnabled = adapter.logs.isNotEmpty() && !value
        binding.deleteView.alpha = if (binding.deleteView.isEnabled) 1f else 0.4f
    }

    suspend fun requestDeleteAll(): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { ctx ->
                RoyToolsDialogBuilder(context)
                    .setTitle(R.string.delete_all_logs)
                    .setMessage(R.string.delete_all_logs_warn)
                    .setPositiveButton(R.string.ok) { _, _ -> ctx.resume(true) }
                    .setNegativeButton(R.string.cancel) { _, _ -> }
                    .show()
                    .setOnDismissListener { if (!ctx.isCompleted) ctx.resume(false) }
            }
        }
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.recyclerList.applyLinearAdapter(context, adapter)
    }
}
