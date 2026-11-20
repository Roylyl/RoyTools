package com.github.kr328.clash.music

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.R
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.design.util.resolveThemedColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class MusicPlayerPageState(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val artwork: String,
    val canOpenAlbum: Boolean,
    val favorite: Boolean,
    val playing: Boolean,
    val buffering: Boolean,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val position: Double,
    val duration: Double,
    val mode: String,
    val modeLabel: String,
    val tab: String,
    val lyrics: Lyrics,
    val playLabel: String,
    val canShowDetails: Boolean,
    val canDownload: Boolean,
    val artists: List<String>,
    val canClearQueue: Boolean,
    val sleepActive: Boolean,
) {
    data class Lyrics(
        val status: String = "loading",
        val message: String = "",
        val retry: Boolean = false,
        val metadata: List<String> = emptyList(),
        val groups: List<List<String>> = emptyList(),
        val activeIndex: Int = -1,
        val hasTimeline: Boolean = false,
    )

    companion object {
        fun parse(payload: String): MusicPlayerPageState? = runCatching {
            val json = JSONObject(payload)
            val trackId = json.optString("trackId").takeIf { it.isNotBlank() } ?: return null
            val lyric = json.optJSONObject("lyrics")
            val groups = lyric?.optJSONArray("groups")
            MusicPlayerPageState(
                trackId = trackId,
                title = json.optString("title"),
                artist = json.optString("artist"),
                album = json.optString("album"),
                artwork = json.optString("artwork"),
                canOpenAlbum = json.optBoolean("canOpenAlbum"),
                favorite = json.optBoolean("favorite"),
                playing = json.optBoolean("playing"),
                buffering = json.optBoolean("buffering"),
                canPrevious = json.optBoolean("canPrevious", true),
                canNext = json.optBoolean("canNext", true),
                position = json.optDouble("position", 0.0).takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0,
                duration = json.optDouble("duration", 0.0).takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0,
                mode = json.optString("mode", "all"),
                modeLabel = json.optString("modeLabel"),
                tab = json.optString("tab", "cover"),
                lyrics = Lyrics(
                    lyric?.optString("status", "loading") ?: "loading",
                    lyric?.optString("message").orEmpty(),
                    lyric?.optBoolean("retry") == true,
                    strings(lyric?.optJSONArray("metadata")),
                    List(groups?.length() ?: 0) { index -> strings(groups?.optJSONObject(index)?.optJSONArray("lines")) },
                    lyric?.optInt("activeIndex", -1) ?: -1,
                    lyric?.optBoolean("hasTimeline") == true,
                ),
                playLabel = json.optString("playLabel"),
                canShowDetails = json.optBoolean("canShowDetails"),
                canDownload = json.optBoolean("canDownload"),
                artists = strings(json.optJSONArray("artists")),
                canClearQueue = json.optBoolean("canClearQueue"),
                sleepActive = json.optBoolean("sleepActive"),
            )
        }.getOrNull()

        private fun strings(array: JSONArray?): List<String> = List(array?.length() ?: 0) { index ->
            array?.optString(index).orEmpty()
        }
    }
}

/** The full-screen player presents native controls while the existing engine owns playback and lyrics. */
internal class NativeMusicPlayerPanel(
    private val activity: Activity,
    private val onAction: (String, String?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val canvas = color(DesignR.attr.glassCanvasStart)
    private val surface = color(com.google.android.material.R.attr.colorSurface)
    private val coverPlaceholder = color(DesignR.attr.glassIconSurface)
    private val textColor = color(com.google.android.material.R.attr.colorOnSurface)
    private val muted = color(DesignR.attr.glassTextSecondary)
    private val accent = color(android.R.attr.colorAccent)
    private val dark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val compactHeight = activity.resources.configuration.screenHeightDp in 1..580
    private val positionTrackColor = Color.parseColor(if (dark) "#48484A" else "#D1D1D6")
    private val touch = ColorStateList.valueOf((textColor and 0x00FFFFFF) or 0x22000000)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private var state: MusicPlayerPageState? = null
    private var artworkUrl: String? = null
    private var artworkJob: Job? = null
    private var popup: PopupMenu? = null
    private var touchingSeek = false
    private var seekRequest: SeekRequest? = null
    private var manualLyricsUntil = 0L

    private data class SeekRequest(val trackId: String, val seconds: Double, val expiresAt: Long)
    private val restoreLyricFollow = Runnable { followLyrics(smooth = true) }

    val view = FrameLayout(activity).apply {
        setBackgroundColor(canvas)
        visibility = View.GONE
    }

    private val coverTab = textButton("封面") { onAction("tab", "cover") }
    private val lyricsTab = textButton("歌词") { onAction("tab", "lyrics") }
    private val coverInfo = TrackInfo(compact = false)
    private val lyricsInfo = TrackInfo(compact = true)
    private val largeCover = object : ImageView(activity) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = minOf(dp(286), MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
            setMeasuredDimension(size, size)
        }
    }.apply { configureCover(20) }
    private val smallCover = ImageView(activity).apply { configureCover(9) }
    private val coverBody = FrameLayout(activity).apply {
        val edge = dp(if (compactHeight) 4 else 12)
        setPadding(0, edge, 0, edge)
        addView(largeCover, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    }

    private val lyricAdapter = LyricAdapter()
    private val lyricLayout = LinearLayoutManager(activity)
    private val lyricList = object : RecyclerView(activity) {
        override fun getTopFadingEdgeStrength(): Float = 0f
    }.apply {
        layoutManager = lyricLayout
        adapter = lyricAdapter
        itemAnimator = null
        clipToPadding = false
        isVerticalScrollBarEnabled = false
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(22))
        setPadding(dp(10), dp(8), dp(10), dp(16))
        contentDescription = "滚动歌词"
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> holdLyricFollow()
            }
            false
        }
        addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (bottom - top != oldBottom - oldTop || right - left != oldRight - oldLeft) {
                post { followLyrics(smooth = false) }
            }
        }
    }
    private val lyricsMessage = label(12f, muted).apply { gravity = Gravity.CENTER }
    private val lyricsRetry = textButton("重试") { onAction("lyricsRetry", null) }.apply {
        setTextColor(accent)
        background = ripple(surface, 22)
        visibility = View.GONE
    }
    private val lyricPlaceholder = column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(10), dp(34), dp(10), 0)
        addView(lyricsMessage, LinearLayout.LayoutParams(-1, -2))
        addView(lyricsRetry, LinearLayout.LayoutParams(dp(88), dp(44)).apply { topMargin = dp(14) })
    }
    private val lyricsBody = column().apply {
        visibility = View.GONE
        setPadding(0, dp(if (compactHeight) 4 else 12), 0, 0)
        addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(smallCover, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(lyricsInfo.view, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        }, LinearLayout.LayoutParams(-1, -2))
        addView(FrameLayout(activity).apply {
            addView(lyricList, FrameLayout.LayoutParams(-1, -1))
            addView(lyricPlaceholder, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
    }

    private val elapsed = label(11f, muted).apply { fontFeatureSettings = "tnum" }
    private val duration = label(11f, muted).apply {
        fontFeatureSettings = "tnum"
        gravity = Gravity.END
    }
    private val seekThumbRadius = dp(9)
    private val seek = AppCompatSeekBar(activity).apply {
        max = SeekSteps
        contentDescription = "播放进度"
        setPaddingRelative(seekThumbRadius, 0, seekThumbRadius, 0)
        splitTrack = false
        progressDrawable = positionTrack()
        thumb = rounded(accent, 9).apply { setSize(seekThumbRadius * 2, seekThumbRadius * 2) }
        thumbOffset = seekThumbRadius
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) { touchingSeek = true }
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val player = state ?: return
                if (player.duration <= 0.0) return
                val selected = player.duration * progress / SeekSteps
                elapsed.updateText(time(selected))
                if (!touchingSeek) seekTo(player, selected)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                touchingSeek = false
                val player = state ?: return
                if (player.duration > 0.0) seekTo(player, player.duration * seekBar.progress / SeekSteps)
            }
        })
    }
    private val repeat = iconButton(R.drawable.ic_music_player_repeat, "播放模式", "repeat")
    private val previous = iconButton(R.drawable.ic_music_player_previous, "上一首", "previous")
    private val play = iconButton(R.drawable.ic_music_play, "播放", "toggle", primary = true)
    private val next = iconButton(R.drawable.ic_music_player_next, "下一首", "next")
    private val queue = iconButton(R.drawable.ic_music_queue, "播放队列", "queue")
    private val buffering = LinearLayout(activity).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(3), 0, 0)
        visibility = View.INVISIBLE
        addView(ProgressBar(activity, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(accent)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { gravity = Gravity.CENTER_VERTICAL })
        addView(label(12f, muted).apply {
            text = "正在缓冲"
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(-2, dp(16)).apply { marginStart = dp(6) })
    }

    init {
        val tabs = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(surface, 28)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(coverTab, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(4) })
            addView(lyricsTab, LinearLayout.LayoutParams(0, -1, 1f))
        }
        val tabBar = FrameLayout(activity).apply {
            val height = if (compactHeight) 44 else 52
            addView(tabs, FrameLayout.LayoutParams(dp(176), dp(height), Gravity.CENTER))
        }
        val playbackBody = FrameLayout(activity).apply {
            addView(coverBody, FrameLayout.LayoutParams(-1, -1))
            addView(lyricsBody, FrameLayout.LayoutParams(-1, -1))
        }
        val timeline = column().apply {
            addView(seek, LinearLayout.LayoutParams(-1, dp(if (compactHeight) 36 else 44)))
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(18)
                setPaddingRelative(seekThumbRadius, 0, seekThumbRadius, 0)
                addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f))
                addView(duration, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        val controls = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            for (button in listOf(repeat, previous, play, next, queue)) {
                val size = dp(if (button === play) 52 else 44)
                addView(FrameLayout(activity).apply {
                    addView(button, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
                }, LinearLayout.LayoutParams(0, -1, 1f))
            }
        }
        val contentMargin = dp(20)
        fun contentRow(height: Int, weight: Float = 0f) = LinearLayout.LayoutParams(-1, height, weight).apply {
            marginStart = contentMargin
            marginEnd = contentMargin
        }
        val shell = column().apply {
            addView(tabBar, LinearLayout.LayoutParams(-1, dp(if (compactHeight) 44 else 52)))
            addView(playbackBody, contentRow(0, 1f))
            addView(coverInfo.view, contentRow(-2))
            addView(timeline, LinearLayout.LayoutParams(-1, -2).apply {
                // Align the rail and time labels with the metadata while keeping the full thumb inside the view.
                marginStart = contentMargin - seekThumbRadius
                marginEnd = contentMargin - seekThumbRadius
                topMargin = dp(4)
            })
            addView(controls, contentRow(dp(52)))
            addView(buffering, LinearLayout.LayoutParams(-1, dp(if (compactHeight) 20 else 38)))
        }
        view.addView(shell, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER).apply {
            val width = activity.resources.configuration.screenWidthDp
            if (width > 480) this.width = dp(480)
        })
        setTab(lyrics = false)
    }

    fun render(nextState: MusicPlayerPageState) {
        val previousState = state
        if (previousState == nextState) return
        state = nextState
        val changedTrack = previousState?.trackId != nextState.trackId
        val changedTab = previousState?.tab != nextState.tab
        val lyricPage = nextState.tab == "lyrics"

        if (changedTrack) {
            seekRequest = null
            touchingSeek = false
            manualLyricsUntil = 0
            lyricList.removeCallbacks(restoreLyricFollow)
            lyricList.stopScroll()
            lyricLayout.scrollToPositionWithOffset(0, 0)
        }
        if (changedTab) setTab(lyricPage)
        if (changedTrack || previousState?.artwork != nextState.artwork) bindArtwork(nextState.artwork)
        if (previousState == null || changedTrack || previousState.title != nextState.title ||
            previousState.artist != nextState.artist || previousState.album != nextState.album ||
            previousState.canOpenAlbum != nextState.canOpenAlbum || previousState.favorite != nextState.favorite) {
            coverInfo.bind(nextState)
            lyricsInfo.bind(nextState)
        }

        val modeLabel = nextState.modeLabel.ifBlank { when (nextState.mode) {
            "one" -> "单曲循环"
            "shuffle" -> "随机播放"
            else -> "列表循环"
        } }
        val modeDescription = if (modeLabel.startsWith("播放模式")) modeLabel else "播放模式：$modeLabel"
        if (repeat.contentDescription != modeDescription) repeat.contentDescription = modeDescription
        if (previousState?.mode != nextState.mode) {
            repeat.setImageResource(when (nextState.mode) {
                "one" -> R.drawable.ic_music_player_repeat_one
                "shuffle" -> R.drawable.ic_music_player_shuffle
                else -> R.drawable.ic_music_player_repeat
            })
            repeat.imageTintList = ColorStateList.valueOf(if (nextState.mode == "all") muted else accent)
        }
        repeat.isSelected = nextState.mode != "all"
        val playbackDescription = nextState.playLabel.ifBlank { if (nextState.playing) "暂停" else "播放" }
        if (play.contentDescription != playbackDescription) play.contentDescription = playbackDescription
        if (previousState?.playing != nextState.playing) {
            play.setImageResource(if (nextState.playing) R.drawable.ic_music_pause else R.drawable.ic_music_play)
        }
        setEnabled(previous, nextState.canPrevious)
        setEnabled(next, nextState.canNext)
        buffering.visibility = if (nextState.buffering) View.VISIBLE else View.INVISIBLE
        updateProgress(nextState)
        if (previousState?.lyrics != nextState.lyrics || changedTrack) {
            bindLyrics(nextState.lyrics, previousState?.lyrics, changedTrack)
        }
        if (lyricPage && (changedTab || changedTrack || previousState?.lyrics?.activeIndex != nextState.lyrics.activeIndex)) {
            lyricList.post { followLyrics(smooth = !changedTrack && !changedTab) }
        }
    }

    fun setVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        if (view.visibility == visibility) return
        view.visibility = visibility
        if (visible) {
            lyricList.post { followLyrics(smooth = false) }
        } else {
            closeDialogs()
            lyricList.stopScroll()
            lyricList.removeCallbacks(restoreLyricFollow)
        }
    }

    fun closeDialogs() {
        popup?.dismiss()
        popup = null
    }

    fun handleBack(): Boolean {
        if (popup == null) return false
        closeDialogs()
        return true
    }

    fun showMore(anchor: View) {
        val snapshot = state ?: return
        if (!anchor.isAttachedToWindow || activity.isFinishing || activity.isDestroyed) return
        closeDialogs()
        val menu = PopupMenu(activity, anchor, Gravity.END, 0, DesignR.style.RoyTools_AnchoredMenu)
        fun item(title: String, action: String) {
            menu.menu.add(title).setOnMenuItemClickListener playerMenuAction@{
                if (state?.trackId != snapshot.trackId) return@playerMenuAction true
                onAction(action, null)
                true
            }
        }
        if (snapshot.canShowDetails) item("歌曲详情", "details")
        item("分享", "share")
        if (snapshot.canDownload) item("下载", "download")
        item("定时关闭", "sleep")
        if (snapshot.canOpenAlbum) item("查看专辑", "album")
        val artists = snapshot.artists
        if (artists.size == 1) {
            menu.menu.add("查看歌手").setOnMenuItemClickListener {
                if (state?.trackId == snapshot.trackId) onAction("artist", artists[0])
                true
            }
        } else if (artists.isNotEmpty()) {
            val submenu = menu.menu.addSubMenu("查看歌手")
            artists.forEach { artist -> submenu.add(artist).setOnMenuItemClickListener {
                if (state?.trackId == snapshot.trackId) onAction("artist", artist)
                true
            } }
        }
        if (snapshot.canClearQueue) item("清空待插队", "clearQueue")
        popup = menu
        menu.setOnDismissListener { if (popup === it) popup = null }
        menu.show()
    }

    fun dispose() {
        closeDialogs()
        lyricList.removeCallbacks(restoreLyricFollow)
        lyricList.adapter = null
        scope.cancel()
    }

    private fun setTab(lyrics: Boolean) {
        coverTab.isSelected = !lyrics
        lyricsTab.isSelected = lyrics
        coverTab.background = ripple(if (lyrics) Color.TRANSPARENT else accent, 24)
        lyricsTab.background = ripple(if (lyrics) accent else Color.TRANSPARENT, 24)
        coverTab.setTextColor(if (lyrics) muted else Color.WHITE)
        lyricsTab.setTextColor(if (lyrics) Color.WHITE else muted)
        coverBody.visibility = if (lyrics) View.GONE else View.VISIBLE
        coverInfo.view.visibility = coverBody.visibility
        lyricsBody.visibility = if (lyrics) View.VISIBLE else View.GONE
    }

    private fun bindArtwork(url: String) {
        if (artworkUrl == url) return
        artworkUrl = url
        artworkJob?.cancel()
        val cached = MusicArtwork.peek(MusicArtwork.assetPath(url))
        largeCover.setImageBitmap(cached)
        smallCover.setImageBitmap(cached)
        if (cached != null || url.isBlank()) return
        artworkJob = scope.launch {
            val bitmap = withContext(Dispatchers.IO) { MusicArtwork.decode(activity.applicationContext, url) }
            if (artworkUrl == url && bitmap != null) {
                largeCover.setImageBitmap(bitmap)
                smallCover.setImageBitmap(bitmap)
            }
        }
    }

    private fun seekTo(player: MusicPlayerPageState, seconds: Double) {
        seekRequest = SeekRequest(player.trackId, seconds, SystemClock.uptimeMillis() + 850)
        onAction("seek", seconds.toString())
    }

    private fun updateProgress(player: MusicPlayerPageState) {
        duration.updateText(time(player.duration))
        seek.isEnabled = player.duration > 0.0
        if (touchingSeek) return
        val request = seekRequest
        if (request != null) {
            val waiting = request.trackId == player.trackId && SystemClock.uptimeMillis() < request.expiresAt &&
                abs(player.position - request.seconds) > 0.6
            if (waiting) return
            seekRequest = null
        }
        val position = player.position.coerceAtMost(player.duration.takeIf { it > 0.0 } ?: player.position)
        elapsed.updateText(time(position))
        val progress = if (player.duration > 0) (position / player.duration * SeekSteps).roundToInt() else 0
        if (seek.progress != progress) seek.progress = progress
    }

    private fun positionTrack(): LayerDrawable {
        val height = dp(3)
        val base = rounded(positionTrackColor, 2).apply { setSize(0, height) }
        val progress = ClipDrawable(rounded(accent, 2), Gravity.START, ClipDrawable.HORIZONTAL)
        return LayerDrawable(arrayOf(base, progress)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
            for (index in 0..1) {
                setLayerHeight(index, height)
                setLayerGravity(index, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
            }
        }
    }

    private fun bindLyrics(
        lyrics: MusicPlayerPageState.Lyrics,
        previous: MusicPlayerPageState.Lyrics?,
        changedTrack: Boolean,
    ) {
        val displayed = lyrics.status == "ready" && (lyrics.groups.isNotEmpty() || lyrics.metadata.isNotEmpty())
        lyricList.visibility = if (displayed) View.VISIBLE else View.INVISIBLE
        lyricPlaceholder.visibility = if (displayed) View.GONE else View.VISIBLE
        lyricsMessage.updateText(lyrics.message.ifBlank { when (lyrics.status) {
            "error" -> "歌词加载失败"
            "not-found", "ready" -> "暂无歌词"
            else -> "正在加载歌词…"
        } })
        lyricsRetry.visibility = if (!displayed && (lyrics.retry || lyrics.status == "error")) View.VISIBLE else View.GONE
        if (changedTrack || previous?.metadata != lyrics.metadata || previous?.groups != lyrics.groups ||
            previous?.hasTimeline != lyrics.hasTimeline) {
            lyricAdapter.replace(lyrics)
        } else {
            lyricAdapter.setActive(lyrics.activeIndex)
        }
    }

    private fun holdLyricFollow() {
        manualLyricsUntil = SystemClock.uptimeMillis() + 8_000
        lyricList.removeCallbacks(restoreLyricFollow)
        lyricList.postDelayed(restoreLyricFollow, 8_050)
    }

    private fun followLyrics(smooth: Boolean) {
        val player = state ?: return
        if (view.visibility != View.VISIBLE || player.tab != "lyrics" || lyricList.visibility != View.VISIBLE ||
            lyricList.height == 0 || SystemClock.uptimeMillis() < manualLyricsUntil) return
        val position = lyricAdapter.activePosition ?: return
        val offset = (lyricList.height * .4f).roundToInt()
        if (!smooth) {
            lyricLayout.scrollToPositionWithOffset(position, offset)
            return
        }
        val scroller = object : LinearSmoothScroller(activity) {
            override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int {
                return boxStart + offset - viewStart
            }
        }
        scroller.targetPosition = position
        lyricLayout.startSmoothScroll(scroller)
    }

    private inner class TrackInfo(compact: Boolean) {
        private val title = label(if (compact) 17f else if (compactHeight) 20f else 23f, textColor).apply {
            setTypeface(null, Typeface.BOLD)
            maxLines = if (compact) 1 else 2
            ellipsize = TextUtils.TruncateAt.END
        }
        private val artist = label(15f, muted).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        private val album = textButton("") { onAction("album", null) }.apply {
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setTextColor(muted)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            minHeight = dp(22)
        }
        private val favorite = iconButton(R.drawable.ic_music_favorite_off, "Apple Music收藏", "favorite")
        val view = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(column().apply {
                addView(title, LinearLayout.LayoutParams(-1, -2))
                addView(artist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
                addView(album, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
            addView(favorite, LinearLayout.LayoutParams(dp(44), dp(44)))
        }

        fun bind(player: MusicPlayerPageState) {
            title.updateText(player.title)
            artist.updateText(player.artist)
            album.updateText(player.album)
            album.visibility = if (player.album.isBlank()) View.GONE else View.VISIBLE
            album.isEnabled = player.canOpenAlbum
            album.isClickable = player.canOpenAlbum
            favorite.isSelected = player.favorite
            favorite.contentDescription = if (player.favorite) "Apple Music已喜欢" else "Apple Music未喜欢"
            favorite.setImageResource(if (player.favorite) R.drawable.ic_music_favorite_on else R.drawable.ic_music_favorite_off)
            favorite.imageTintList = ColorStateList.valueOf(if (player.favorite) accent else muted)
        }
    }

    private inner class LyricAdapter : RecyclerView.Adapter<LyricHolder>() {
        private var metadata: List<String> = emptyList()
        private var groups: List<List<String>> = emptyList()
        private var active = -1
        private var unsynced = false

        val activePosition: Int? get() = active.takeIf { it in groups.indices }?.let { metadata.size + it }

        fun replace(lyrics: MusicPlayerPageState.Lyrics) {
            metadata = lyrics.metadata
            groups = lyrics.groups
            active = lyrics.activeIndex
            unsynced = !lyrics.hasTimeline && (metadata.isNotEmpty() || groups.isNotEmpty())
            notifyDataSetChanged()
        }

        fun setActive(index: Int) {
            if (index == active) return
            val previous = activePosition
            active = index
            previous?.let { notifyItemChanged(it) }
            activePosition?.let { notifyItemChanged(it) }
        }

        override fun getItemCount(): Int = metadata.size + groups.size + if (unsynced) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = LyricHolder(label(19f, muted).apply {
            gravity = Gravity.CENTER
            layoutParams = RecyclerView.LayoutParams(-1, -2)
        })

        override fun onBindViewHolder(holder: LyricHolder, position: Int) {
            val note = position >= metadata.size + groups.size
            val active = activePosition == position
            val label = holder.label
            label.text = when {
                note -> "无时间轴"
                position < metadata.size -> metadata[position]
                else -> groups[position - metadata.size].joinToString("\n")
            }
            label.textSize = if (note) 12f else 19f
            label.setTextColor(if (!active) muted else if (dark) textColor else accent)
            label.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            label.setLineSpacing(0f, 1.26f)
            label.setPadding(dp(4), dp(if (note) 7 else 12), dp(4), dp(if (note) 7 else 12))
        }
    }

    private class LyricHolder(val label: TextView) : RecyclerView.ViewHolder(label)

    private fun ImageView.configureCover(radius: Int) {
        background = rounded(coverPlaceholder, radius)
        clipToOutline = true
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = "当前专辑封面"
    }

    private fun textButton(title: String, action: () -> Unit) = Button(activity).apply {
        text = title
        textSize = 14f
        typeface = medium
        isAllCaps = false
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
        includeFontPadding = false
        setPadding(0, 0, 0, 0)
        background = ripple(Color.TRANSPARENT, 22)
        setOnClickListener { action() }
    }

    private fun iconButton(@DrawableRes image: Int, description: String, action: String, primary: Boolean = false) =
        ImageButton(activity).apply {
            contentDescription = description
            background = ripple(if (primary) accent else Color.TRANSPARENT, if (primary) 26 else 22)
            val padding = dp(if (primary) 12 else 11)
            setPadding(padding, padding, padding, padding)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(image)
            imageTintList = ColorStateList.valueOf(if (primary) Color.WHITE else muted)
            setOnClickListener { onAction(action, null) }
        }

    private fun label(size: Float, tint: Int) = TextView(activity).apply {
        textSize = size
        setTextColor(tint)
        includeFontPadding = false
    }

    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else .46f
    }

    private fun TextView.updateText(value: String) {
        if (text.toString() != value) text = value
    }

    private fun rounded(fill: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
    }

    private fun ripple(fill: Int, radius: Int) = RippleDrawable(touch, rounded(fill, radius), rounded(Color.WHITE, radius))
    private fun color(attributeId: Int) = activity.resolveThemedColor(attributeId)
    private fun dp(value: Int) = (activity.resources.displayMetrics.density * value).roundToInt()

    private fun time(value: Double): String {
        val seconds = value.toLong().coerceAtLeast(0)
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    companion object {
        private const val SeekSteps = 1000
    }
}
