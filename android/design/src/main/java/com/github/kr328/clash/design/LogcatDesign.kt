package com.github.kr328.clash.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.adapter.LogMessageAdapter
import com.github.kr328.clash.design.databinding.DesignLogcatBinding
import com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder
import com.github.kr328.clash.design.model.LogFile
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class LogcatDesign(
    context: Context,
    private val streaming: Boolean,
    private val moduleName: String? = null,
) : Design<LogcatDesign.Request>(context) {
    enum class Request {
        Close, Delete, Export, History
    }

    private val binding = DesignLogcatBinding.inflate(context.layoutInflater, context.root, false)
    private val adapter = LogMessageAdapter(context) {
        launch {
            context.getSystemService<ClipboardManager>()?.setPrimaryClip(
                ClipData.newPlainText("log_message", it.message)
            )
            showToast(R.string.copied, ToastDuration.Short)
        }
    }
    private var latestMessages: List<LogMessage> = emptyList()
    private var displayedMessages: List<LogMessage> = emptyList()
    private var paused = false
    private var recording = streaming
    private var renderJob: Job? = null

    override val root: View
        get() = binding.root

    suspend fun patchMessages(messages: List<LogMessage>) {
        withContext(Dispatchers.Main) {
            if (latestMessages == messages) return@withContext
            latestMessages = messages
            if (!paused) {
                displayedMessages = messages
                renderMessages()
            }
        }
    }

    fun setFile(file: LogFile) {
        binding.fileDescription.text = file.date.format(context).substringBeforeLast('.')
    }

    fun setRecording(value: Boolean) {
        if (recording == value) return
        recording = value
        updateStatus()
    }

    suspend fun requestDelete(): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            RoyToolsDialogBuilder(context)
                .setTitle(R.string.roytools_logs_delete_title)
                .setMessage(R.string.roytools_logs_delete_message)
                .setPositiveButton(R.string.delete) { _, _ -> continuation.resume(true) }
                .setNegativeButton(R.string.cancel) { _, _ -> }
                .show()
                .setOnDismissListener {
                    if (!continuation.isCompleted) continuation.resume(false)
                }
        }
    }

    private fun renderMessages(resetScroll: Boolean = false) {
        renderJob?.cancel()
        renderJob = launch(Dispatchers.Main) {
            val source = displayedMessages
            val query = binding.searchInput.text.toString().trim()
            val level = binding.levelFilters.checkedChipId
            val followLatest = streaming && !paused && !binding.recyclerList.canScrollVertically(-1)
            val filtered = withContext(Dispatchers.Default) {
                source.asReversed().filter { message ->
                    val matchesLevel = when (level) {
                        R.id.level_error -> message.level == LogMessage.Level.Error
                        R.id.level_warning -> message.level == LogMessage.Level.Warning
                        R.id.level_info -> message.level == LogMessage.Level.Info
                        R.id.level_debug -> message.level == LogMessage.Level.Debug
                        R.id.level_other -> message.level == LogMessage.Level.Unknown ||
                            message.level == LogMessage.Level.Silent
                        else -> true
                    }
                    matchesLevel && (query.isEmpty() || message.message.contains(query, true) ||
                        message.time.format(context, includeDate = false).contains(query, true))
                }
            }
            adapter.submitMessages(filtered)
            binding.resultCount.text = context.getString(
                R.string.roytools_logs_result_count, filtered.size, source.size
            )
            binding.emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            val noMatch = source.isNotEmpty()
            binding.emptyTitle.setText(when {
                noMatch -> R.string.roytools_logs_no_match
                streaming && recording -> R.string.roytools_logs_waiting
                else -> R.string.roytools_logs_empty_file
            })
            binding.emptyHint.setText(when {
                noMatch -> R.string.roytools_logs_no_match_hint
                streaming && recording -> R.string.roytools_logs_waiting_hint
                else -> R.string.roytools_logs_empty_file_hint
            })
            if (!noMatch && moduleName != null) {
                binding.emptyTitle.text = "暂无${moduleName}日志"
                binding.emptyHint.text = when (moduleName) {
                    "音乐" -> "播放音乐后，播放事件与错误会自动显示在这里。"
                    "代理" -> "代理运行后，连接与规则事件会自动显示在这里。"
                    "刷新率" -> "调整刷新率或配置辅助服务后，运行记录会自动显示在这里。"
                    else -> "应用运行时会自动记录事件，无需手动开始采集。"
                }
            }
            if ((resetScroll || followLatest) && filtered.isNotEmpty()) {
                binding.recyclerList.scrollToPosition(0)
            }
            updateLatestButton()
        }
    }

    private fun updateStatus() {
        binding.captureStatus.setText(when {
            !streaming -> R.string.roytools_logs_read_only
            !recording -> R.string.roytools_logs_stopped
            paused -> R.string.roytools_logs_paused_status
            else -> R.string.roytools_logs_live_status
        })
        binding.statusDot.alpha = if (recording && !paused) 1f else 0.35f
        binding.pauseButton.setText(if (paused) R.string.roytools_logs_resume else R.string.roytools_logs_pause)
        binding.stopButton.setText(if (recording) R.string.roytools_logs_stop else R.string.roytools_logs_back_history)
    }

    private fun updateLatestButton() {
        binding.latestButton.visibility = if (streaming &&
            (paused || binding.recyclerList.canScrollVertically(-1))) View.VISIBLE else View.GONE
    }

    init {
        binding.self = this
        binding.streaming = streaming
        binding.unified = moduleName != null
        binding.activityBarLayout.applyFrom(context)
        binding.activityBarLayout.findViewById<TextView>(R.id.activity_bar_title_view)?.setText(
            if (streaming) R.string.roytools_logs_live else R.string.roytools_logs_history
        )
        binding.recyclerList.layoutManager = LinearLayoutManager(context)
        if (moduleName != null) {
            binding.activityBarLayout.findViewById<TextView>(R.id.activity_bar_title_view)?.text = "${moduleName}日志"
            binding.fileDescription.text = "默认自动采集·仅保存在本机"
        }
        binding.recyclerList.adapter = adapter
        binding.recyclerList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateLatestButton()
        })
        binding.searchInput.doAfterTextChanged {
            binding.clearSearch.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE
            renderMessages(resetScroll = true)
        }
        binding.clearSearch.setOnClickListener { binding.searchInput.text.clear() }
        binding.levelFilters.setOnCheckedChangeListener { _, _ -> renderMessages(resetScroll = true) }
        binding.pauseButton.setOnClickListener {
            paused = !paused
            if (!paused) displayedMessages = latestMessages
            updateStatus()
            renderMessages(resetScroll = !paused)
        }
        binding.latestButton.setOnClickListener {
            paused = false
            displayedMessages = latestMessages
            updateStatus()
            renderMessages(resetScroll = true)
        }
        binding.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = renderMessages()
            override fun onViewDetachedFromWindow(view: View) {
                renderJob?.cancel()
            }
        })
        updateStatus()
        renderMessages()
    }
}
