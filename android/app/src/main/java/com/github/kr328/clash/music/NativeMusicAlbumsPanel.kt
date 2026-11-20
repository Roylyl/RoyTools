package com.github.kr328.clash.music

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.R
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class MusicAlbum(
    val key: String,
    val title: String,
    val artist: String,
    val meta: String,
    val artwork: String,
    val artists: List<String>,
) {
    companion object {
        fun parse(json: JSONObject): MusicAlbum {
            val artists = json.optJSONArray("artists")
            return MusicAlbum(
                json.getString("key"), json.getString("title"), json.optString("artist"),
                json.optString("meta"), json.optString("artwork"),
                List(artists?.length() ?: 0) { index -> artists!!.getString(index) },
            )
        }
    }
}

/** Shares album artwork with the player, track list and disk cache. */
internal class MusicArtworkBinding(
    private val image: ImageView,
    private val scope: CoroutineScope,
    private val artworkIO: CoroutineDispatcher,
) {
    private var identity: String? = null
    private var loaded = false
    private var job: Job? = null

    fun bind(url: String) {
        val key = MusicArtwork.assetPath(url)
        if (identity == key && (loaded || job?.isActive == true)) return
        val replaced = identity != key
        identity = key
        job?.cancel()
        loaded = false
        val decoded = MusicArtwork.peek(key)
        if (decoded != null) {
            image.setImageBitmap(decoded)
            loaded = true
            return
        }
        if (replaced || key == null) image.setImageResource(R.drawable.widget_music_placeholder)
        if (key == null) return
        val context = image.context.applicationContext
        job = scope.launch {
            val bitmap = withContext(artworkIO) { MusicArtwork.decode(context, url) }
            if (key != identity || bitmap == null) return@launch
            image.setImageBitmap(bitmap)
            loaded = true
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

internal class AlbumGridAdapter(
    private val theme: NativeAlbumCardTheme,
    private val scope: CoroutineScope,
    private val artworkIO: CoroutineDispatcher,
    private val action: (String, String?, View?) -> Unit,
    private val showOptions: (View, MusicAlbum) -> Unit,
) : ListAdapter<MusicAlbum, AlbumGridAdapter.Holder>(object : DiffUtil.ItemCallback<MusicAlbum>() {
    override fun areItemsTheSame(old: MusicAlbum, new: MusicAlbum) = old.key == new.key
    override fun areContentsTheSame(old: MusicAlbum, new: MusicAlbum) = old == new
}) {
    private val stableIds = HashMap<String, Long>()

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = stableIds.getOrPut(getItem(position).key) { stableIds.size.toLong() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder()
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    override fun onViewRecycled(holder: Holder) = holder.recycle()

    inner class Holder : RecyclerView.ViewHolder(LinearLayout(theme.context)) {
        private val card = itemView as LinearLayout
        private var album: MusicAlbum? = null
        private val cover = theme.image(12).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener { album?.let { action("open-album", it.key, this) } }
            setOnLongClickListener {
                album?.let { showOptions(this, it) } != null
            }
            foreground = theme.ripple(12)
            isFocusable = true
        }
        private val artwork = MusicArtworkBinding(cover, scope, artworkIO)
        private val title = theme.label(14f, theme.textColor).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setOnClickListener { album?.let { action("open-album", it.key, this) } }
            setOnLongClickListener {
                album?.let { showOptions(this, it) } != null
            }
            isFocusable = true
        }
        private val artist = theme.label(12f, theme.muted)
        private val meta = theme.label(12f, theme.muted)
        private val play = ImageButton(theme.context).apply {
            setImageResource(R.drawable.ic_music_play)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = theme.ripple(20, theme.accent)
            setPadding(theme.dp(10), theme.dp(10), theme.dp(10), theme.dp(10))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { album?.let { action("play-album", it.key, this) } }
        }

        init {
            card.layoutParams = RecyclerView.LayoutParams(-1, -2)
            card.orientation = LinearLayout.VERTICAL
            card.background = theme.rounded(theme.surface, 20)
            val padding = theme.dp(12)
            card.setPadding(padding, padding, padding, padding)
            card.addView(object : FrameLayout(theme.context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY))
                }
            }.apply {
                addView(cover, FrameLayout.LayoutParams(-1, -1))
                addView(play, FrameLayout.LayoutParams(theme.dp(40), theme.dp(40), Gravity.BOTTOM or Gravity.END).apply {
                    marginEnd = theme.dp(10)
                    bottomMargin = theme.dp(10)
                })
            }, theme.fullWidth())
            card.addView(title, theme.fullWidth(12))
            card.addView(artist, theme.fullWidth(8))
            card.addView(meta, theme.fullWidth(4))
        }

        fun bind(next: MusicAlbum) {
            album = next
            title.text = next.title
            artist.text = next.artist
            meta.text = next.meta
            cover.contentDescription = "查看${next.title}"
            play.contentDescription = "播放专辑${next.title}"
            artwork.bind(next.artwork)
        }

        fun recycle() {
            album = null
            artwork.cancel()
        }
    }
}

internal class AlbumDetailCard(
    private val theme: NativeAlbumCardTheme,
    scope: CoroutineScope,
    artworkIO: CoroutineDispatcher,
    private val action: (String, String?, View?) -> Unit,
) : LinearLayout(theme.context) {
    private val cover = theme.image(10).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val artwork = MusicArtworkBinding(cover, scope, artworkIO)
    private val title = theme.label(22f, theme.textColor).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        isSingleLine = false
        maxLines = 3
    }
    private val artist = theme.label(14f, theme.accent).apply {
        isSingleLine = false
        maxLines = 3
        movementMethod = LinkMovementMethod.getInstance()
        highlightColor = Color.TRANSPARENT
    }
    private val meta = theme.label(12f, theme.muted).apply {
        isSingleLine = false
        maxLines = 3
    }
    private val play = actionButton("播放专辑", R.drawable.ic_music_play, "playAll")
    private val download = actionButton("下载全部", R.drawable.ic_album_download, "downloadAll")
    private var displayed: MusicAlbum? = null
    private var artworkWidth = 0

    init {
        orientation = VERTICAL
        background = theme.rounded(theme.surface, 20)
        val inset = theme.dp(16)
        setPadding(inset, inset, inset, inset)
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.TOP
            addView(cover, LayoutParams(theme.dp(96), theme.dp(96)).apply { marginEnd = theme.dp(16) })
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(title, theme.fullWidth())
                addView(artist, theme.fullWidth(6))
                addView(meta, theme.fullWidth(5))
            }, LayoutParams(0, -2, 1f))
        }, theme.fullWidth())
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(play, LayoutParams(0, theme.dp(44), 1f))
            addView(download, LayoutParams(0, theme.dp(44), 1f).apply { marginStart = theme.dp(8) })
        }, theme.fullWidth(12))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val large = MeasureSpec.getSize(widthMeasureSpec) > theme.dp(668)
        val size = theme.dp(if (large) 144 else 96)
        if (artworkWidth != size) {
            artworkWidth = size
            cover.layoutParams.width = size
            cover.layoutParams.height = size
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun bind(album: MusicAlbum?, canPlay: Boolean, canDownload: Boolean) {
        visibility = if (album == null) GONE else VISIBLE
        if (album == null) {
            artwork.cancel()
            return
        }
        play.isEnabled = canPlay
        play.alpha = if (canPlay) 1f else .46f
        download.isEnabled = canDownload
        download.alpha = if (canDownload) 1f else .46f
        artwork.bind(album.artwork)
        if (displayed == album) return
        displayed = album
        title.text = album.title
        meta.text = album.meta
        artist.text = if (album.artists.isEmpty()) album.artist else SpannableStringBuilder().apply {
            album.artists.forEachIndexed { index, name ->
                if (index > 0) append(" & ")
                val start = length
                append(name)
                setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) = action("artist", name, widget)
                    override fun updateDrawState(paint: TextPaint) {
                        paint.color = theme.accent
                        paint.isUnderlineText = false
                    }
                }, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    fun cancelArtwork() = artwork.cancel()

    private fun actionButton(title: String, @DrawableRes drawable: Int, name: String) =
        MaterialButton(theme.context).apply {
            this.text = title
            textSize = 13f
            setTextColor(Color.WHITE)
            setAllCaps(false)
            includeFontPadding = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minWidth = 0
            minimumWidth = 0
            minimumHeight = 0
            insetTop = 0
            insetBottom = 0
            setPaddingRelative(theme.dp(6), 0, theme.dp(6), 0)
            backgroundTintList = ColorStateList.valueOf(theme.accent)
            cornerRadius = theme.dp(24)
            strokeWidth = 0
            icon = context.getDrawable(drawable)?.mutate()
            iconSize = theme.dp(17)
            iconPadding = theme.dp(4)
            iconTint = ColorStateList.valueOf(Color.WHITE)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            setOnClickListener { action(name, null, this) }
        }
}

internal class NativeAlbumCardTheme(
    val context: android.content.Context,
    val surface: Int,
    val textColor: Int,
    val muted: Int,
    val accent: Int,
    private val coverBackground: Int,
    private val rippleColor: ColorStateList,
) {
    fun dp(value: Int) = (context.resources.displayMetrics.density * value + .5f).toInt()
    fun fullWidth(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    fun ripple(radius: Int, color: Int? = null) =
        RippleDrawable(rippleColor, color?.let { rounded(it, radius) }, rounded(Color.WHITE, radius))
    fun image(radius: Int) = ImageView(context).apply {
        background = rounded(coverBackground, radius)
        clipToOutline = true
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    fun label(size: Float, color: Int) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
}
