package com.github.kr328.clash.music

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.github.kr328.clash.R
import com.github.kr328.clash.common.ui.TouchFeedback
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.design.util.resolveThemedColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** A sibling of both music page surfaces. Only its metadata moves during a swipe. */
internal class NativeMiniPlayer(context: Context, private val onAction: (String) -> Unit) : FrameLayout(context) {
    private fun dp(n: Int) = (resources.displayMetrics.density * n).roundToInt()
    private fun color(attr: Int) = context.resolveThemedColor(attr)
    private fun shape(fill: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
    }
    private val surface = color(DesignR.attr.glassSurface)
    private val ink = color(com.google.android.material.R.attr.colorOnSurface)
    private val muted = color(DesignR.attr.glassTextSecondary)
    private val accent = color(android.R.attr.colorAccent)
    private val touch = ColorStateList.valueOf((ink and 0x00ffffff) or 0x22000000)
    private val cover = ImageView(context).apply {
        background = shape(color(DesignR.attr.glassIconSurface), 12)
        clipToOutline = true
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    private fun label(size: Float, tint: Int) = TextView(context).apply {
        textSize = size
        setTextColor(tint)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }
    private val title = label(15f, ink).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val detail = label(12f, muted)
    private val info = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(cover, LinearLayout.LayoutParams(dp(44), dp(44)))
        val lines = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(detail, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(3) })
        }
        addView(lines, LinearLayout.LayoutParams(0,-1,1f).apply { marginStart = dp(10); marginEnd = dp(4) })
        isFocusable = true
        setOnClickListener { onAction("openPlayer") }
    }
    private val play = ImageButton(context)
    private var artJob: Job? = null
    private var artUrl: String? = null
    private var current: MusicPlayback.State? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var x0 = 0f
    private var y0 = 0f
    private var swiping = false
    private var ignoreSwipe = false

    init {
        visibility = GONE
        background = shape(surface, 32)
        elevation = dp(3).toFloat()
        clipToOutline = true
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(16), 0, dp(6), 0)
            addView(info, LinearLayout.LayoutParams(0,-1,1f))
        }
        fun button(view: ImageButton, action: String, desc: String, image: Int, tint: Int, fill: Int) {
            view.apply {
                contentDescription = desc
                foreground = null
                background = InsetDrawable(RippleDrawable(touch, shape(fill, 24), shape(Color.WHITE,24)), dp(4))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(image)
                imageTintList = ColorStateList.valueOf(tint)
                setOnClickListener { onAction(action) }
            }
            row.addView(view, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        button(play, "toggle", "播放", R.drawable.ic_music_play, Color.WHITE, accent)
        button(ImageButton(context), "openQueue", "播放队列", R.drawable.ic_music_queue, muted, Color.TRANSPARENT)
        addView(row, LayoutParams(-1,-1))
    }

    fun render(state: MusicPlayback.State?, visible: Boolean) {
        current = state
        visibility = if (visible && !state?.title.isNullOrBlank()) VISIBLE else GONE
        if (visibility != VISIBLE || state == null) return
        if (title.text.toString() != state.title) title.text = state.title
        val secondary = when {
            state.album.isBlank() -> state.artist
            state.artist.isBlank() -> state.album
            else -> state.artist + " · " + state.album
        }
        if (detail.text.toString() != secondary) detail.text = secondary
        val description = "打开正在播放：" + state.title + "，" + state.artist
        if (info.contentDescription != description) info.contentDescription = description
        val action = if (state.playing) "暂停" else "播放"
        if (play.contentDescription != action) {
            play.contentDescription = action
            play.setImageResource(if (state.playing) R.drawable.ic_music_pause else R.drawable.ic_music_play)
        }
        if (artUrl == state.artwork) return
        artUrl = state.artwork
        artJob?.cancel()
        cover.setImageDrawable(null)
        if (state.artwork.isBlank()) return
        val app = context.applicationContext
        val url = state.artwork
        artJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            val bitmap = withContext(Dispatchers.IO) { MusicArtwork.decode(app, url) }
            if (artUrl == url) cover.setImageBitmap(bitmap)
        }
    }

    override fun onDetachedFromWindow() {
        artJob?.cancel()
        artUrl = null
        super.onDetachedFromWindow()
    }

    // Intercept a horizontal drag even if it began over a child button. Plain taps
    // continue to the original Android click handler and retain accessibility semantics.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = ev.x; y0 = ev.y; swiping = false; ignoreSwipe = false }
            MotionEvent.ACTION_POINTER_DOWN -> ignoreSwipe = true
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(ev.x - x0)
                val dy = abs(ev.y - y0)
                if (dy > slop && dy > dx) ignoreSwipe = true
                if (!ignoreSwipe && dx > slop && dx > dy * 1.3f) {
                    swiping = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!swiping) return super.onTouchEvent(ev)
        val dx = ev.x - x0
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> info.translationX = (dx * .22f).coerceIn(-dp(18).toFloat(), dp(18).toFloat())
            MotionEvent.ACTION_POINTER_DOWN -> ignoreSwipe = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                info.translationX = 0f
                swiping = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (ev.actionMasked == MotionEvent.ACTION_UP && !ignoreSwipe && abs(dx) >= dp(36) &&
                    (if (dx < 0) current?.canNext == true else current?.canPrevious == true)) {
                    TouchFeedback.click(this)
                    onAction(if (dx < 0) "next" else "previous")
                }
            }
        }
        return true
    }
}
