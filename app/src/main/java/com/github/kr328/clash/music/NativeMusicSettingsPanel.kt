package com.github.kr328.clash.music

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder
import com.github.kr328.clash.design.util.resolveThemedColor
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import com.github.kr328.clash.design.R as DesignR

/** UI state only. The page remains the owner of cache policy, storage and asynchronous operations. */
internal data class MusicSettingsState(
    val budget: Long,
    val preload: Boolean,
    val audioStatus: String,
    val lyricStatus: String,
    val coverStatus: String,
    val audioBusy: Boolean,
    val lyricBusy: Boolean,
    val coverBusy: Boolean,
    val available: Boolean,
    val overlayOpen: Boolean,
    val hasCurrent: Boolean,
    val downloadBatch: MusicDownloadState,
    val allBusy: Boolean,
) {
    companion object {
        val budgets = longArrayOf(0, 256_000_000, 512_000_000, 1_000_000_000, 2_000_000_000,
            4_000_000_000, 8_000_000_000, -1)
        val budgetLabels = arrayOf("关闭", "256MB", "512MB", "1GB", "2GB", "4GB", "8GB", "无限")

        fun parse(payload: String): MusicSettingsState? = runCatching {
            val json = JSONObject(payload)
            MusicSettingsState(
                budget = json.optLong("budget", -1).takeIf { it in budgets } ?: -1,
                preload = json.optBoolean("preload", true),
                audioStatus = json.optString("audioStatus", "正在检查缓存…").take(512),
                lyricStatus = json.optString("lyricStatus", "正在检查缓存…").take(512),
                coverStatus = json.optString("coverStatus", "正在检查缓存…").take(512),
                audioBusy = json.optBoolean("audioBusy"),
                lyricBusy = json.optBoolean("lyricBusy"),
                coverBusy = json.optBoolean("coverBusy"),
                available = json.optBoolean("available", true),
                overlayOpen = json.optBoolean("overlayOpen"),
                hasCurrent = json.optBoolean("hasCurrent"),
                downloadBatch = MusicDownloadState.parse(json.optJSONObject("downloadBatch")),
                allBusy = json.optBoolean("allBusy"),
            )
        }.getOrNull()
    }
}

/** Recreated with its Activity; no native View or dialog is retained by the shared WebView engine. */
internal class NativeMusicSettingsPanel(
    private val activity: Activity,
    private val command: (String, Any?) -> Unit,
    private val onPlaybackSettingsChanged: () -> Unit,
) {
    private val context: Context get() = activity
    private val textColor get() = context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
    private val muted get() = context.resolveThemedColor(DesignR.attr.glassTextSecondary)
    private var state: MusicSettingsState? = null
    private var binding = false
    private var dialog: AlertDialog? = null
    private val playbackPreferences = activity.getSharedPreferences("roytools_music_native", Context.MODE_PRIVATE)

    val view = ScrollView(activity).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        setBackgroundColor(context.resolveThemedColor(DesignR.attr.glassCanvasStart))
    }
    private val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(16))
    }
    private val budgetButton = button("读取中…", "缓存上限") { chooseBudget() }
    private val keepScreenOnSwitch = MaterialSwitch(ContextThemeWrapper(activity,
        DesignR.style.ThemeOverlay_RoyTools_MaterialSwitch)).apply {
        contentDescription = "全屏播放时保持亮屏"
        minimumHeight = dp(48)
        isChecked = playbackPreferences.getBoolean("keep_screen_on_full", true)
        setOnCheckedChangeListener { _, checked ->
            playbackPreferences.edit().putBoolean("keep_screen_on_full", checked).apply()
            onPlaybackSettingsChanged()
        }
    }
    private val preloadSwitch = MaterialSwitch(ContextThemeWrapper(activity,
        DesignR.style.ThemeOverlay_RoyTools_MaterialSwitch)).apply {
        contentDescription = "预加载下一首"
        minimumHeight = dp(48)
        setOnCheckedChangeListener { _, checked -> if (!binding) command("preload", checked) }
    }
    private val audioStatus = label("正在检查缓存…", 13f, muted)
    private val lyricStatus = label("正在检查缓存…", 13f, muted)
    private val coverStatus = label("正在检查缓存…", 13f, muted)
    private val downloadAll = button("下载全部歌曲", "下载全部歌曲") {
        if (state?.downloadBatch?.active == true) MusicDownloadCoordinator.show(activity)
        else command("downloadAll", null)
    }.apply {
        setIconResource(DesignR.drawable.ic_baseline_cloud_download)
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        iconSize = dp(18)
    }
    private val downloadedTracks = button("已下载曲目", "管理已下载曲目") { command("openDownloads", null) }
    private val deleteAll = button("删除所有下载与缓存", "删除所有下载与缓存") {
        confirmClear("删除所有下载与缓存", "会停止当前下载，并删除本机保存的歌曲、歌词和封面缓存。", "clearAll", "删除")
    }
    private val downloadLyrics = button("下载所有歌词", "下载所有歌词") { command("downloadLyrics", null) }
    private val downloadCovers = button("下载所有封面", "下载所有封面") { command("downloadCovers", null) }
    private val clearAudio = button("清理", "清理音乐缓存") {
        confirmClear("清理音乐缓存", "清理已保存的歌曲缓存，下次播放时会重新下载。", "clearAudio")
    }
    private val clearLyrics = button("清理", "清理歌词缓存") {
        confirmClear("清理歌词缓存", "清理已保存的歌词，下次播放时会重新获取。", "clearLyrics")
    }
    private val clearCovers = button("清理", "清理专辑封面缓存") {
        confirmClear("清理专辑封面缓存", "清理已保存的专辑封面，下次浏览时会重新获取。", "clearCovers")
    }

    init {
        view.addView(content, ViewGroup.LayoutParams(-1, -2))
        card("播放设置").addView(row(label("全屏播放时保持亮屏", 16f, textColor), keepScreenOnSwitch), rowParams())
        card("音乐下载").apply {
            addView(downloadAll, rowParams())
            addView(downloadedTracks, rowParams())
            addView(deleteAll, rowParams())
        }
        card("音乐缓存", "完整保存的歌曲可离线播放。").apply {
            addView(row(label("缓存上限", 16f, textColor), budgetButton), rowParams())
            val preloadCopy = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("预加载下一首", 16f, textColor))
                addView(label("提前下载下一首完整音频", 13f, muted).apply { setPadding(0, dp(4), 0, 0) })
            }
            addView(row(preloadCopy, preloadSwitch), rowParams())
            addView(row(audioStatus, clearAudio), rowParams())
        }
        card("歌词缓存", "单独保存，更新后重新验证。").apply {
            addView(downloadLyrics, rowParams())
            addView(row(lyricStatus, clearLyrics), rowParams())
        }
        card("专辑封面缓存").apply {
            addView(downloadCovers, rowParams())
            addView(row(coverStatus, clearCovers), rowParams())
        }
        render(null)
    }

    fun render(next: MusicSettingsState?) {
        state = next
        binding = true
        preloadSwitch.isChecked = next?.preload ?: true
        binding = false
        preloadSwitch.isEnabled = next != null
        budgetButton.isEnabled = next != null && !next.downloadBatch.active && !next.allBusy
        budgetButton.text = next?.let {
            MusicSettingsState.budgetLabels[MusicSettingsState.budgets.indexOf(it.budget)]
        } ?: "读取中…"
        audioStatus.text = next?.audioStatus?.takeIf { it.isNotBlank() } ?: "正在检查缓存…"
        lyricStatus.text = next?.lyricStatus?.takeIf { it.isNotBlank() } ?: "正在检查缓存…"
        coverStatus.text = next?.coverStatus?.takeIf { it.isNotBlank() } ?: "正在检查缓存…"
        downloadAll.text = if (next?.downloadBatch?.active == true) "查看下载进度" else "下载全部歌曲"
        downloadAll.contentDescription = downloadAll.text
        downloadAll.isEnabled = next?.available == true && !next.audioBusy && !next.allBusy
        downloadedTracks.isEnabled = next?.available == true && !next.allBusy
        deleteAll.text = if (next?.allBusy == true) "删除中…" else "删除所有下载与缓存"
        deleteAll.isEnabled = next?.available == true && !next.audioBusy && !next.lyricBusy && !next.coverBusy && !next.allBusy
        downloadLyrics.isEnabled = next?.available == true && !next.lyricBusy && !next.allBusy
        downloadCovers.isEnabled = next?.available == true && !next.coverBusy && !next.allBusy
        updateClearButton(clearAudio, next?.audioBusy == true, next?.available == true)
        updateClearButton(clearLyrics, next?.lyricBusy == true, next?.available == true)
        updateClearButton(clearCovers, next?.coverBusy == true, next?.available == true)
        if (next?.downloadBatch?.active == true || next?.allBusy == true) clearAudio.isEnabled = false
        if (next?.downloadBatch?.active == true && next.downloadBatch.kind == "lyrics") clearLyrics.isEnabled = false
        if (next?.downloadBatch?.active == true && next.downloadBatch.kind == "covers") clearCovers.isEnabled = false
        if (next?.allBusy == true) { clearLyrics.isEnabled = false; clearCovers.isEnabled = false }
    }

    fun setVisible(visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) closeDialogs()
    }

    fun closeDialogs() { dialog?.dismiss(); dialog = null }

    fun dispose() {
        closeDialogs()
        preloadSwitch.setOnCheckedChangeListener(null)
        keepScreenOnSwitch.setOnCheckedChangeListener(null)
    }

    private fun chooseBudget() {
        val current = state ?: return
        closeDialogs()
        dialog = RoyToolsDialogBuilder(activity).setTitle("缓存上限")
            .setSingleChoiceItems(MusicSettingsState.budgetLabels, MusicSettingsState.budgets.indexOf(current.budget)) { selected, index ->
                if (current.budget != MusicSettingsState.budgets[index]) command("budget", MusicSettingsState.budgets[index])
                selected.dismiss()
            }.setNegativeButton("关闭", null).create().apply {
                setOnDismissListener { dialog = null }
                show()
            }
    }

    private fun confirmClear(title: String, message: String, action: String, confirmLabel: String = "清理") {
        closeDialogs()
        dialog = RoyToolsDialogBuilder(activity).setTitle(title).setMessage(message)
            .setNegativeButton("取消", null).setPositiveButton(confirmLabel) { _, _ -> command(action, null) }
            .create().apply { setOnDismissListener { dialog = null }; show() }
    }

    private fun updateClearButton(button: MaterialButton, busy: Boolean, available: Boolean) {
        button.text = if (busy) "清理中…" else "清理"
        button.isEnabled = available && !busy
    }

    private fun card(title: String, description: String? = null) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        setBackgroundResource(DesignR.drawable.roytools_card)
        addView(label(title, 18f, textColor).apply { setTypeface(typeface, Typeface.BOLD) })
        description?.let { addView(label(it, 13f, muted).apply { setPadding(0, dp(6), 0, 0) }) }
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    private fun row(copy: View, control: View) = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        minimumHeight = dp(56)
        addView(copy, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        addView(control, LinearLayout.LayoutParams(-2, -2))
    }

    private fun rowParams() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }

    private fun button(text: String, description: String, action: () -> Unit) = MaterialButton(activity).apply {
        this.text = text
        contentDescription = description
        setOnClickListener { action() }
    }

    private fun label(text: String, size: Float, color: Int) = TextView(activity).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun dp(value: Int) = (activity.resources.displayMetrics.density * value + 0.5f).toInt()
}
