package com.github.kr328.clash.music

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.doOnNextLayout
import com.github.kr328.clash.common.R as CommonR
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
    val saveWhileListening: Boolean,
    val audioBytes: Long?,
    val lyricBytes: Long?,
    val coverBytes: Long?,
    val audioStatus: String,
    val lyricStatus: String,
    val coverStatus: String,
    val audioBusy: Boolean,
    val lyricBusy: Boolean,
    val coverBusy: Boolean,
    val available: Boolean,
    val hasCurrent: Boolean,
    val downloadBatch: MusicDownloadState,
    val allBusy: Boolean,
    val catalogVersion: String,
    val catalogStatus: String,
    val catalogChecking: Boolean,
) {
    companion object {
        val budgets = longArrayOf(-1, 0, 256_000_000, 512_000_000, 1_000_000_000, 2_000_000_000,
            4_000_000_000, 8_000_000_000)
        val budgetLabels = arrayOf("无限", "关闭", "256MB", "512MB", "1GB", "2GB", "4GB", "8GB")

        fun parse(payload: String): MusicSettingsState? = runCatching {
            val json = JSONObject(payload)
            val bytes = json.optJSONObject("cacheBytes")
            fun size(key: String) = bytes?.optLong(key, -1)?.takeIf { it >= 0 }
            MusicSettingsState(
                budget = json.optLong("budget", -1).takeIf { it in budgets } ?: -1,
                preload = json.optBoolean("preload", true),
                saveWhileListening = json.optBoolean("saveWhileListening", true),
                audioBytes = size("audio"),
                lyricBytes = size("lyrics"),
                coverBytes = size("covers"),
                audioStatus = json.optString("audioStatus", "正在检查缓存…").take(512),
                lyricStatus = json.optString("lyricStatus", "正在检查缓存…").take(512),
                coverStatus = json.optString("coverStatus", "正在检查缓存…").take(512),
                audioBusy = json.optBoolean("audioBusy"),
                lyricBusy = json.optBoolean("lyricBusy"),
                coverBusy = json.optBoolean("coverBusy"),
                available = json.optBoolean("available", true),
                hasCurrent = json.optBoolean("hasCurrent"),
                downloadBatch = MusicDownloadState.parse(json.optJSONObject("downloadBatch")),
                allBusy = json.optBoolean("allBusy"),
                catalogVersion = json.optString("catalogVersion").take(160),
                catalogStatus = json.optString("catalogStatus", "仅手动同步曲库与歌单").take(512),
                catalogChecking = json.optBoolean("catalogChecking"),
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
    private val content = page()
    private val cacheContent = page()
    private var showingCache = false
    private var settingsScroll = 0
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
    private val saveSwitch = MaterialSwitch(ContextThemeWrapper(activity,
        DesignR.style.ThemeOverlay_RoyTools_MaterialSwitch)).apply {
        contentDescription = "边听边存"
        minimumHeight = dp(48)
        setOnCheckedChangeListener { _, checked -> if (!binding) command("saveWhileListening", checked) }
    }
    private val audioStatus = label("—", 14f, muted)
    private val lyricStatus = label("—", 14f, muted)
    private val coverStatus = label("—", 14f, muted)
    private val catalogVersion = label("—", 14f, muted)
    private val catalogStatus = label("仅手动同步曲库与歌单", 13f, muted)
    private val checkCatalog = button("检查曲库更新", "检查曲库更新") { command("checkCatalog", null) }
    private val queueStatus = label("", 14f, muted)
    private val cacheTotal = label("—", 14f, muted)
    private val activeDownloads = navigationRow("正在下载", queueStatus) { command("openActiveDownloads", null) }
    private val downloadedTracks = navigationRow("已下载曲目") { command("openDownloads", null) }
    private val manageCache = navigationRow("管理缓存", cacheTotal) { command("openCacheSettings", null) }
    private val deleteAll = button("删除所有下载与缓存", "删除所有下载与缓存") {
        confirmClear("删除所有下载与缓存", "会停止当前下载，并删除本机保存的歌曲、歌词和封面缓存。", "clearAll", "删除")
    }
    private val downloadLyrics = button("下载所有歌词", "下载所有歌词") {
        confirmClear("下载所有歌词？", "下载缺少的歌词，供离线查看。", "downloadLyrics", "确认下载")
    }
    private val downloadCovers = button("下载所有封面", "下载所有封面") {
        confirmClear("下载所有封面？", "下载缺少的专辑封面，供离线查看。", "downloadCovers", "确认下载")
    }
    private val clearAudio = button("清理音乐缓存", "清理音乐缓存") {
        confirmClear("清理音乐缓存", "清理已保存的歌曲缓存，下次播放时会重新下载。", "clearAudio")
    }
    private val clearLyrics = button("清理歌词缓存", "清理歌词缓存") {
        confirmClear("清理歌词缓存", "清理已保存的歌词，下次播放时会重新获取。", "clearLyrics")
    }
    private val clearCovers = button("清理专辑封面缓存", "清理专辑封面缓存") {
        confirmClear("清理专辑封面缓存", "清理已保存的专辑封面，下次浏览时会重新获取。", "clearCovers")
    }

    init {
        view.addView(object : LinearLayout(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = minOf(MeasureSpec.getSize(widthMeasureSpec), dp(752))
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            addView(content, LinearLayout.LayoutParams(-1, -2))
            addView(cacheContent, LinearLayout.LayoutParams(-1, -2))
        }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL))
        cacheContent.visibility = View.GONE
        card(content, "播放").apply {
            addView(row(label("全屏播放时保持亮屏", 16f, textColor), keepScreenOnSwitch))
            addView(row(label("预加载下一首", 16f, textColor), preloadSwitch))
            addView(row(label("边听边存", 16f, textColor), saveSwitch))
            addView(label("边听边存会把已完整缓存的当前曲目移入「已下载曲目」。", 13f, muted))
        }
        card(content, "下载与缓存").apply {
            addView(activeDownloads)
            addView(downloadedTracks)
            addView(manageCache)
        }
        card(content, "曲库").apply {
            addView(row(label("当前版本", 16f, textColor), catalogVersion))
            addView(checkCatalog, rowParams())
            addView(catalogStatus, rowParams())
        }
        card(cacheContent, "音乐缓存").apply {
            addView(row(label("缓存上限", 16f, textColor), budgetButton))
            addView(row(label("已缓存", 16f, textColor), audioStatus))
            addView(clearAudio, rowParams())
            addView(label("完整缓存的歌曲可离线播放。达到上限后暂停新增缓存。", 13f, muted))
        }
        card(cacheContent, "歌词").apply {
            addView(row(label("已缓存", 16f, textColor), lyricStatus))
            addView(downloadLyrics, rowParams())
            addView(clearLyrics, rowParams())
        }
        card(cacheContent, "专辑封面").apply {
            addView(row(label("已缓存", 16f, textColor), coverStatus))
            addView(downloadCovers, rowParams())
            addView(clearCovers, rowParams())
        }
        card(cacheContent).addView(deleteAll, rowParams())
        render(null)
    }

    fun showCachePage(showCache: Boolean) {
        if (showCache == showingCache) return
        if (showCache) settingsScroll = view.scrollY
        showingCache = showCache
        closeDialogs()
        content.visibility = if (showCache) View.GONE else View.VISIBLE
        cacheContent.visibility = if (showCache) View.VISIBLE else View.GONE
        view.scrollTo(0, 0)
        if (!showCache && settingsScroll > 0) {
            view.doOnNextLayout { if (!showingCache) view.scrollTo(0, settingsScroll) }
        }
    }

    fun render(next: MusicSettingsState?) {
        state = next
        binding = true
        preloadSwitch.isChecked = next?.preload ?: true
        saveSwitch.isChecked = next?.saveWhileListening ?: true
        binding = false
        preloadSwitch.isEnabled = next != null
        saveSwitch.isEnabled = next != null
        budgetButton.isEnabled = next != null && !next.downloadBatch.active && !next.allBusy
        budgetButton.text = next?.let {
            MusicSettingsState.budgetLabels[MusicSettingsState.budgets.indexOf(it.budget)]
        } ?: "读取中…"
        audioStatus.text = next?.audioBytes?.let(::sizeLabel) ?: next?.audioStatus?.takeIf { it.isNotBlank() } ?: "—"
        lyricStatus.text = next?.lyricBytes?.let(::sizeLabel) ?: next?.lyricStatus?.takeIf { it.isNotBlank() } ?: "—"
        coverStatus.text = next?.coverBytes?.let(::sizeLabel) ?: next?.coverStatus?.takeIf { it.isNotBlank() } ?: "—"
        val usage = listOfNotNull(next?.audioBytes, next?.lyricBytes, next?.coverBytes)
        cacheTotal.text = if (usage.size == 3) sizeLabel(usage.sum()) else "—"
        queueStatus.text = next?.downloadBatch?.takeIf { it.active }?.let {
            if (it.paused) "已暂停" else "${(it.total - it.processed).coerceAtLeast(0)}项"
        }.orEmpty()
        catalogVersion.text = next?.catalogVersion?.takeIf { it.isNotBlank() } ?: "—"
        catalogStatus.text = next?.catalogStatus?.takeIf { it.isNotBlank() }
            ?: "仅手动同步曲库与歌单"
        checkCatalog.text = if (next?.catalogChecking == true) "检查中…" else "检查曲库更新"
        checkCatalog.contentDescription = checkCatalog.text
        checkCatalog.isEnabled = next != null && !next.catalogChecking
        downloadedTracks.isEnabled = next?.available == true && !next.allBusy
        deleteAll.text = if (next?.allBusy == true) "删除中…" else "删除所有下载与缓存"
        deleteAll.isEnabled = next?.available == true && !next.audioBusy && !next.lyricBusy && !next.coverBusy && !next.allBusy
        downloadLyrics.isEnabled = next?.available == true && !next.lyricBusy && !next.allBusy
        downloadCovers.isEnabled = next?.available == true && !next.coverBusy && !next.allBusy
        val activeKind = next?.downloadBatch?.takeIf { it.active }?.kind
        updateClearButton(clearAudio, "清理音乐缓存", next?.audioBusy == true, next?.available == true && activeKind !in setOf("audio", "mixed"))
        updateClearButton(clearLyrics, "清理歌词缓存", next?.lyricBusy == true, next?.available == true && activeKind !in setOf("lyrics", "mixed"))
        updateClearButton(clearCovers, "清理专辑封面缓存", next?.coverBusy == true, next?.available == true && activeKind !in setOf("covers", "mixed"))
        if (next?.allBusy == true) clearAudio.isEnabled = false
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
        saveSwitch.setOnCheckedChangeListener(null)
        keepScreenOnSwitch.setOnCheckedChangeListener(null)
    }

    private fun chooseBudget() {
        val current = state ?: return
        closeDialogs()
        dialog = RoyToolsDialogBuilder(activity).setTitle("缓存上限")
            .setSingleChoiceItems(MusicSettingsState.budgetLabels, MusicSettingsState.budgets.indexOf(current.budget)) { selected, index ->
                if (current.budget != MusicSettingsState.budgets[index]) command("budget", MusicSettingsState.budgets[index])
                selected.dismiss()
            }.setNegativeButton("取消", null).create().apply {
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

    private fun updateClearButton(button: MaterialButton, text: String, busy: Boolean, available: Boolean) {
        button.text = if (busy) "清理中…" else text
        button.isEnabled = available && !busy
    }

    private fun page() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val margin = resources.getDimensionPixelSize(CommonR.dimen.roytools_page_margin)
        setPadding(margin, margin / 2, margin, margin)
    }

    private fun card(parent: LinearLayout, title: String? = null) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val padding = resources.getDimensionPixelSize(CommonR.dimen.roytools_card_padding)
        setPadding(padding, padding, padding, padding)
        setBackgroundResource(DesignR.drawable.roytools_card)
        title?.let { addView(label(it, 18f, textColor).apply { setTypeface(typeface, Typeface.BOLD) }) }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = resources.getDimensionPixelSize(CommonR.dimen.roytools_section_spacing)
        })
    }

    private fun navigationRow(title: String, value: TextView? = null, action: () -> Unit): View {
        val trailing = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            value?.let {
                it.includeFontPadding = false
                addView(it)
            }
            addView(ImageView(activity).apply {
                setImageResource(DesignR.drawable.ic_glass_chevron)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                if (value != null) marginStart = dp(4)
            })
        }
        return row(label(title, 16f, textColor), trailing).apply {
            val ripple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true) && ripple.resourceId != 0) {
                setBackgroundResource(ripple.resourceId)
            }
            isFocusable = true
            setOnClickListener { action() }
        }
    }

    private fun sizeLabel(bytes: Long) = android.text.format.Formatter.formatShortFileSize(activity, bytes)
        .filterNot { it.isWhitespace() || Character.isSpaceChar(it) }

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
