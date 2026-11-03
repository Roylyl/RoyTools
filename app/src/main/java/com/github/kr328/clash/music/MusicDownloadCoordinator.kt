package com.github.kr328.clash.music

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder
import com.github.kr328.clash.design.util.resolveThemedColor
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Locale

internal data class MusicDownloadState(
    val id: String = "", val active: Boolean = false, val kind: String = "audio",
    val total: Int = 0, val processed: Int = 0,
    val downloaded: Int = 0, val cached: Int = 0, val failed: Int = 0, val skipped: Int = 0,
    val scope: String = "", val reason: String = "", val status: String = "",
    val downloadedBytes: Long = 0, val totalBytes: Long = 0, val totalBytesKnown: Boolean = false,
    val current: String = "", val currentBytes: Long = 0, val currentTotalBytes: Long = 0,
    val currentTotalBytesKnown: Boolean = false,
) {
    val percent: Int get() {
        if (total <= 0) return 0
        val partial = if (active && currentTotalBytesKnown && currentTotalBytes > 0 && processed < total)
            (currentBytes.toDouble() / currentTotalBytes).coerceIn(0.0, 1.0) else 0.0
        return ((processed + partial) / total * 100).toInt().coerceIn(0, if (active) 99 else 100)
    }

    companion object {
        fun parse(json: JSONObject?): MusicDownloadState {
            if (json == null) return MusicDownloadState()
            fun count(key: String) = json.optInt(key).coerceIn(0, 1_000_000)
            fun bytes(key: String) = json.optLong(key).coerceAtLeast(0)
            val total = count("total")
            return MusicDownloadState(
                id = json.optString("id").take(160), active = json.optBoolean("active"),
                kind = json.optString("kind", "audio").takeIf { it in setOf("audio", "lyrics", "covers") } ?: "audio",
                total = total, processed = count("processed").coerceAtMost(total),
                downloaded = count("downloaded"), cached = count("cached"), failed = count("failed"), skipped = count("skipped"),
                scope = json.optString("scope").take(300), reason = json.optString("reason").take(1000),
                status = json.optString("status").take(40), downloadedBytes = bytes("downloadedBytes"),
                totalBytes = bytes("totalBytes"), totalBytesKnown = json.optBoolean("totalBytesKnown"),
                current = json.optString("current").take(300), currentBytes = bytes("currentBytes"),
                currentTotalBytes = bytes("currentTotalBytes"), currentTotalBytesKnown = json.optBoolean("currentTotalBytesKnown"),
            )
        }
    }
}

/** One download job belongs to the retained music engine, independently of the visible page. */
internal object MusicDownloadCoordinator {
    var state = MusicDownloadState()
        private set
    var speedBytesPerSecond = 0.0
        private set
    private var previousBytes = 0L
    private var previousTime = 0L
    private var failedId: String? = null
    private var service: MusicDownloadService? = null
    private var host = WeakReference<Activity>(null)
    private var dialog: AlertDialog? = null
    private var progress: ProgressBar? = null
    private var scope: TextView? = null
    private var transfer: TextView? = null
    private var detail: TextView? = null

    fun update(context: Context, next: MusicDownloadState) {
        if (failedId != null && (next.id == failedId || next.id.isEmpty())) return
        if (next.id.isNotEmpty() && next.id != failedId) failedId = null
        val before = state
        val now = SystemClock.elapsedRealtime()
        if (next.id != before.id || previousTime == 0L || next.downloadedBytes < previousBytes) {
            speedBytesPerSecond = 0.0
            previousTime = now
            previousBytes = next.downloadedBytes
        } else if (now - previousTime >= 400) {
            speedBytesPerSecond = (next.downloadedBytes - previousBytes).toDouble() * 1000 / (now - previousTime)
            previousTime = now
            previousBytes = next.downloadedBytes
        }
        state = next
        if (!next.active) speedBytesPerSecond = 0.0
        if (next.active && !before.active) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, MusicDownloadService::class.java))
            } catch (error: RuntimeException) {
                Log.w("无法启动音乐后台下载服务", error)
                Toast.makeText(context, "无法启动后台下载，请保持应用在前台", Toast.LENGTH_LONG).show()
            }
        }
        service?.publishState(next, speedBytesPerSecond)
        render()
    }

    fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (host.get() === activity && dialog?.isShowing == true) { render(); return }
        dialog?.dismiss()
        host = WeakReference(activity)
        val padding = (24 * activity.resources.displayMetrics.density).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }
        fun label(size: Float) = TextView(activity).apply {
            textSize = size
            setTextColor(activity.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(0, padding / 4, 0, padding / 4)
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        scope = label(15f)
        progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        transfer = label(14f)
        detail = label(13f)
        val created = RoyToolsDialogBuilder(activity).setTitle("音乐下载").setView(content)
            .setNegativeButton("取消下载") { _, _ -> cancel(activity) }
            .setNeutralButton("后台下载", null)
            .setPositiveButton("关闭", null).create()
        dialog = created
        created.setOnDismissListener {
            if (dialog === created) {
                dialog = null; progress = null; scope = null; transfer = null; detail = null; host.clear()
            }
        }
        created.show()
        render()
    }

    fun dismiss(activity: Activity) {
        if (host.get() === activity) dialog?.dismiss()
    }

    fun cancel(context: Context) {
        if (state.active) MusicPanel.cancelDownloads(context)
    }

    fun fail(context: Context, reason: String) {
        val current = state
        if (!current.active) return
        runCatching { MusicPanel.cancelDownloads(context) }
        update(context, current.copy(active = false, status = "error", reason = reason))
        failedId = current.id
    }

    fun onServiceAvailable(service: MusicDownloadService) {
        this.service = service
        service.publishState(state, speedBytesPerSecond)
    }

    fun onServiceUnavailable(service: MusicDownloadService) {
        if (this.service === service) this.service = null
    }

    private fun render() {
        val current = state
        val window = dialog ?: return
        val unit = when (current.kind) { "lyrics" -> "份"; "covers" -> "张"; else -> "首" }
        scope?.text = current.scope.ifBlank { "音乐离线下载" }
        progress?.isIndeterminate = current.active && current.total == 0
        progress?.progress = current.percent
        transfer?.text = if (current.active) String.format(Locale.ROOT, "%d%% · %d/%d%s · %.2fMB/s\n已下载%.1fMB",
            current.percent, current.processed, current.total, unit, speedBytesPerSecond / 1_000_000, current.downloadedBytes / 1_000_000.0)
        else when (current.status) {
            "completed" -> if (current.failed > 0) "下载结束，部分项目失败" else "下载完成"
            "cancelled" -> "已取消下载"
            "budget" -> "缓存空间不足，下载已停止"
            "error" -> "下载失败"
            else -> "正在准备下载…"
        }
        detail?.text = buildString {
            append("已下载${current.downloaded}${unit} · 已缓存${current.cached}${unit} · 失败${current.failed}${unit} · 跳过${current.skipped}${unit}")
            if (current.active && current.current.isNotBlank()) append("\n${current.current}")
            if (current.reason.isNotBlank()) append("\n${current.reason}")
        }
        window.getButton(AlertDialog.BUTTON_NEGATIVE)?.visibility = if (current.active) View.VISIBLE else View.GONE
        window.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility = if (current.active) View.VISIBLE else View.GONE
        window.getButton(AlertDialog.BUTTON_POSITIVE)?.visibility = if (current.active) View.GONE else View.VISIBLE
    }
}
