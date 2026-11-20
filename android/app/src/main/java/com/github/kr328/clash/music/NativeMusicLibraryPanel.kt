package com.github.kr328.clash.music

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Parcelable
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.R
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder
import com.github.kr328.clash.design.util.resolveThemedColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject

/** A snapshot of the filtered library. The playback engine still resolves all row actions. */
internal data class MusicLibraryState(
    val pageKey: String,
    val query: String,
    val canReset: Boolean,
    val loading: Boolean,
    val error: String,
    val count: Int,
    val collectionCount: Int,
    val updatedAt: String,
    val canPlayAll: Boolean,
    val canDownloadAll: Boolean,
    val canClearQueue: Boolean,
    val currentTrackId: String?,
    val playing: Boolean,
    val filters: List<Filter>,
    val rows: List<Track>,
    val albums: List<MusicAlbum> = emptyList(),
    val album: MusicAlbum? = null,
) {
    val isAlbumGrid: Boolean get() = pageKey == "albums" || pageKey.startsWith("artist:")
    val isAlbumDetail: Boolean get() = pageKey.startsWith("album:")

    data class Filter(val id: String, val title: String, val selected: String, val enabled: Boolean, val options: List<Option>)
    data class Option(val value: String, val label: String)
    data class Track(
        val id: String, val title: String, val artist: String, val album: String,
        val duration: String, val artwork: String, val favorite: Boolean, val playable: Boolean,
        val canOpenAlbum: Boolean, val artists: List<String>, val sourceUrl: String, val sourceStatus: String,
        val key: String?,
    )

    companion object {
        fun supports(pageKey: String?): Boolean =
            pageKey == "songs" || pageKey == "favorites" || pageKey == "albums" ||
                pageKey?.let { it.startsWith("playlist:") || it.startsWith("artist:") || it.startsWith("album:") } == true

        fun parse(payload: String): MusicLibraryState? = runCatching {
            val json = JSONObject(payload)
            val pageKey = json.getString("pageKey")
            if (!supports(pageKey)) return null
            val rows = json.optJSONArray("rows")
            val filters = json.optJSONArray("filters")
            val albums = json.optJSONArray("albums")
            MusicLibraryState(
                pageKey = pageKey,
                query = json.optString("query"),
                canReset = json.optBoolean("canReset"),
                loading = json.optBoolean("loading"),
                error = (json.opt("error") as? String).orEmpty(),
                count = json.optInt("count", rows?.length() ?: 0).coerceAtLeast(0),
                collectionCount = json.optInt("collectionCount", json.optInt("count", rows?.length() ?: 0)).coerceAtLeast(0),
                updatedAt = json.optString("updatedAt"),
                canPlayAll = json.optBoolean("canPlayAll"),
                canDownloadAll = json.optBoolean("canDownloadAll"),
                canClearQueue = json.optBoolean("canClearQueue"),
                currentTrackId = (json.opt("currentTrackId") as? String)?.takeIf { it.isNotBlank() },
                playing = json.optBoolean("playing"),
                filters = List(filters?.length() ?: 0) { index ->
                    val item = filters!!.getJSONObject(index)
                    val choices = item.optJSONArray("options")
                    Filter(item.getString("id"), item.getString("title"), item.getString("selected"),
                        item.optBoolean("enabled", true), List(choices?.length() ?: 0) { choice ->
                            val option = choices!!.getJSONObject(choice)
                            Option(option.getString("value"), option.getString("label"))
                        })
                },
                rows = List(rows?.length() ?: 0) { index ->
                    val row = rows!!.getJSONObject(index)
                    val artists = row.optJSONArray("artists")
                    Track(
                        row.getString("id"), row.getString("title"), row.optString("artist"), row.optString("album"),
                        row.optString("duration"), row.optString("artwork"), row.optBoolean("favorite"),
                        row.optBoolean("playable"), row.optBoolean("canOpenAlbum"),
                        List(artists?.length() ?: 0) { artist -> artists!!.getString(artist) },
                        row.optString("sourceUrl"), row.optString("sourceStatus"),
                        row.optString("key").takeIf { it.isNotBlank() },
                    )
                },
                albums = List(albums?.length() ?: 0) { index -> MusicAlbum.parse(albums!!.getJSONObject(index)) },
                album = json.optJSONObject("album")?.let { MusicAlbum.parse(it) },
            )
        }.getOrNull()
    }
}

/** Native library pages that reuse the shared playback and cache policy. */
internal class NativeMusicLibraryPanel(
    private val activity: Activity,
    private val onAction: (String, String?, View?) -> Unit,
) {
    private val canvas = color(DesignR.attr.glassCanvasStart)
    private val surface = color(com.google.android.material.R.attr.colorSurface)
    private val textColor = color(com.google.android.material.R.attr.colorOnSurface)
    private val muted = color(DesignR.attr.glassTextSecondary)
    private val accent = color(android.R.attr.colorAccent)
    private val coverBackground = color(DesignR.attr.glassIconSurface)
    private val rippleColor = ColorStateList.valueOf((textColor and 0x00ffffff) or 0x18000000)
    private val albumTheme = NativeAlbumCardTheme(activity, surface, textColor, muted, accent, coverBackground, rippleColor)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val artworkIO = Dispatchers.IO.limitedParallelism(4)
    private val layout = GridLayoutManager(activity, 2)
    private val positions = LruCache<String, Parcelable>(12)
    private val header = LibraryHeaderAdapter()
    private val tracks = TrackAdapter()
    private val albums = AlbumGridAdapter(albumTheme, scope, artworkIO, onAction, ::showAlbumOptions)
    private var renderGeneration = 0L
    private var state: MusicLibraryState? = null
    private var restorePage: String? = null
    private var pendingLocation: Pair<String, String>? = null
    private var pendingQuery: String? = null
    private var popup: PopupMenu? = null
    private var dialog: AlertDialog? = null
    private var listFocusedSearch: EditText? = null
    private val deliverQuery = Runnable { pendingQuery?.let { onAction("search", it, null) } }

    val view: RecyclerView = object : RecyclerView(activity) {
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w != oldw) {
                updatePadding()
                layout.spanCount = when {
                    w <= dp(700) -> 2
                    w <= dp(850) -> 3
                    else -> 4
                }
            }
        }
    }.apply {
        setBackgroundColor(canvas)
        layout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (position in 1..albums.itemCount) 1 else layout.spanCount
        }
        layoutManager = layout
        itemAnimator = null
        clipToPadding = false
        isVerticalScrollBarEnabled = false
        isFocusableInTouchMode = true
        adapter = ConcatAdapter(
            ConcatAdapter.Config.Builder().setStableIdMode(ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS).build(),
            header, albums, tracks,
        )
        addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val cell = view.layoutParams as? GridLayoutManager.LayoutParams ?: return
                val count = layout.spanCount
                if (cell.spanSize != 1 || cell.spanIndex !in 0 until count) return
                val gap = dp(if (count == 2) 12 else 14)
                val start = gap * cell.spanIndex / count
                val end = gap - gap * (cell.spanIndex + 1) / count
                val rtl = parent.layoutDirection == View.LAYOUT_DIRECTION_RTL
                outRect.set(if (rtl) end else start, 0, if (rtl) start else end, dp(if (count == 2) 14 else 18))
            }
        })
    }

    fun render(next: MusicLibraryState) {
        val prior = state
        if (prior == next) return
        val newPage = prior?.pageKey != next.pageKey
        if (newPage) {
            if (prior != null && restorePage == null) {
                layout.onSaveInstanceState()?.let { positions.put(prior.pageKey, it) }
            }
            closeDialogs()
            clearKeyboard()
            cancelQuery()
            pendingLocation = null
            restorePage = next.pageKey
        } else if (pendingQuery == next.query) {
            pendingQuery = null
        }
        state = next
        if (prior == null || HeaderState(prior) != HeaderState(next)) header.notifyItemChanged(0)
        val occurrences = HashMap<String, Int>()
        val current = next.rows.indexOfFirst { it.id == next.currentTrackId }
        val items = next.rows.mapIndexed { index, track ->
            val occurrence = occurrences.getOrDefault(track.id, 0)
            occurrences[track.id] = occurrence + 1
            TrackItem(track, track.key ?: "${track.id}#$occurrence", index + 1, index == next.rows.lastIndex,
                index == current, next.playing && index == current)
        }
        val generation = ++renderGeneration
        var pendingAdapters = 2
        fun restorePosition() {
            if (--pendingAdapters != 0 || renderGeneration != generation) return
            if (restorePage == next.pageKey && !next.loading) {
                restorePage = null
                val saved = positions.get(next.pageKey)
                if (saved == null) layout.scrollToPositionWithOffset(0, 0) else layout.onRestoreInstanceState(saved)
            }
            pendingLocation?.takeIf { it.first == next.pageKey }?.let { scrollToTrack(it.second) }
        }
        albums.submitList(next.albums.takeIf { next.isAlbumGrid && it.isNotEmpty() }, ::restorePosition)
        tracks.submitList(items.takeIf { !next.isAlbumGrid && it.isNotEmpty() }, ::restorePosition)
    }

    fun setVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        if (view.visibility == visibility) return
        if (!visible) {
            closeDialogs()
            clearKeyboard()
            if (pendingQuery != null) {
                cancelQuery()
                header.notifyItemChanged(0)
            }
        }
        view.visibility = visibility
    }

    fun closeDialogs() {
        popup?.dismiss()
        dialog?.dismiss()
        popup = null
        dialog = null
    }

    fun handleBack(): Boolean {
        if (dialog != null || popup != null) {
            closeDialogs()
            return true
        }
        if (listFocusedSearch?.hasFocus() == true &&
            ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
            clearKeyboard()
            return true
        }
        return false
    }

    fun scrollToTop() = view.smoothScrollToPosition(0)

    fun locateCurrent(trackId: String) {
        val page = state?.pageKey ?: return
        if (!scrollToTrack(trackId)) pendingLocation = page to trackId
    }

    fun dispose() {
        closeDialogs()
        cancelQuery()
        clearKeyboard()
        scope.cancel()
        view.adapter = null
    }

    private fun scrollToTrack(trackId: String): Boolean {
        val index = tracks.currentList.indexOfFirst { it.track.id == trackId }
        if (index < 0) return false
        pendingLocation = null
        layout.scrollToPositionWithOffset(index + albums.itemCount + 1, dp(32))
        return true
    }

    private fun clearKeyboard() {
        val focused = listFocusedSearch?.takeIf { it.hasFocus() } ?: return
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(focused.windowToken, 0)
        focused.clearFocus()
    }

    private fun cancelQuery() {
        pendingQuery = null
        view.removeCallbacks(deliverQuery)
    }

    private fun updatePadding() {
        val horizontal = maxOf(dp(16), (view.width - dp(760)) / 2)
        if (view.paddingLeft != horizontal || view.paddingTop != dp(6)) {
            view.setPadding(horizontal, dp(6), horizontal, view.paddingBottom)
        }
    }

    private data class HeaderState(
        val page: String, val query: String, val reset: Boolean, val loading: Boolean, val error: String,
        val count: Int, val collectionCount: Int, val updated: String, val play: Boolean, val download: Boolean,
        val filters: List<MusicLibraryState.Filter>, val album: MusicAlbum?,
    ) {
        constructor(state: MusicLibraryState) : this(state.pageKey, state.query, state.canReset, state.loading, state.error,
            state.count, state.collectionCount, state.updatedAt, state.canPlayAll, state.canDownloadAll, state.filters, state.album)
    }

    private inner class LibraryHeaderAdapter : RecyclerView.Adapter<HeaderHolder>() {
        init { setHasStableIds(true) }
        override fun getItemCount() = 1
        override fun getItemId(position: Int) = 0L
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = HeaderHolder()
        override fun onBindViewHolder(holder: HeaderHolder, position: Int) { state?.let(holder::bind) }
        override fun onViewRecycled(holder: HeaderHolder) = holder.recycle()
    }

    private inner class HeaderHolder : RecyclerView.ViewHolder(LinearLayout(activity)) {
        private val root = itemView as LinearLayout
        private val album = AlbumDetailCard(albumTheme, scope, artworkIO, onAction)
        private val collectionCount = label(13f, muted)
        private val updated = label(12f, muted)
        private val playAll = pill("顺序播放", true) { onAction("playAll", null, null) }
        private val shuffle = pill("随机播放", true) { onAction("shuffleAll", null, null) }
        private val download = pill("下载全部", true) { onAction("downloadAll", null, null) }
        private val collection = column().apply {
            background = rounded(surface, 20)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(collectionCount)
            addView(horizontal().apply {
                addView(playAll, weight())
                addView(shuffle, weight(6))
                addView(download, weight(6))
            }, fullWidth(7))
            addView(updated, fullWidth(6))
        }
        private val result = label(12f, muted).apply { gravity = Gravity.END }
        private val message = label(15f, muted).apply { isSingleLine = false; maxLines = 4; gravity = Gravity.CENTER }
        private val retry = pill("重试", false) { onAction("retry", null, null) }
        private val empty = column().apply {
            background = rounded(surface, 20)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(20), dp(12), dp(20))
            addView(message, fullWidth())
            addView(retry, LinearLayout.LayoutParams(dp(116), dp(44)).apply { topMargin = dp(8) })
        }
        private var bindingSearch = false
        private val search = EditText(activity).apply {
            hint = "搜索歌曲、歌手、专辑"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(textColor)
            setHintTextColor(muted)
            background = rounded(surface, 24)
            setPaddingRelative(dp(14), 0, dp(12), 0)
            val icon = activity.getDrawable(DesignR.drawable.ic_baseline_search)?.mutate()
            icon?.setTint(muted)
            icon?.setBounds(0, 0, dp(20), dp(20))
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = dp(9)
            setOnFocusChangeListener { _, focused -> if (focused) listFocusedSearch = this }
            doAfterTextChanged {
                if (bindingSearch) return@doAfterTextChanged
                pendingQuery = it?.toString().orEmpty()
                view.removeCallbacks(deliverQuery)
                view.postDelayed(deliverQuery, 120)
            }
            setOnEditorActionListener { _, action, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER || event?.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                if (action != EditorInfo.IME_ACTION_SEARCH && !enter) return@setOnEditorActionListener false
                if (event == null || event.action == KeyEvent.ACTION_UP) {
                    view.removeCallbacks(deliverQuery)
                    deliverQuery.run()
                    clearKeyboard()
                }
                true
            }
        }
        private val reset = pill("清空筛选", true) {
            cancelQuery()
            showQuery("")
            onAction("reset", null, null)
        }
        private val choices = listOf("artist", "sort-direction", "sort-primary", "sort-secondary")
            .associateWith { FilterCell(it) }
        private val filterRows = choices.values.chunked(2).map { pair ->
            horizontal().apply {
                pair.forEachIndexed { index, choice -> addView(choice.view, weight(if (index == 0) 0 else 12)) }
            }
        }

        init {
            root.orientation = LinearLayout.VERTICAL
            root.layoutParams = RecyclerView.LayoutParams(-1, -2)
            root.addView(collection, fullWidth())
            root.addView(album, fullWidth().apply { bottomMargin = dp(8) })
            root.addView(horizontal().apply {
                addView(reset, LinearLayout.LayoutParams(-2, dp(46)))
                addView(search, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(9) })
            }, fullWidth(10))
            filterRows.forEach { root.addView(it, fullWidth(10)) }
            root.addView(result, fullWidth(9))
            root.addView(empty, fullWidth(10))
        }

        fun bind(next: MusicLibraryState) {
            collection.visibility = if (next.pageKey == "favorites" || next.pageKey.startsWith("playlist:")) View.VISIBLE else View.GONE
            album.bind(next.album, next.canPlayAll, next.canDownloadAll)
            collectionCount.text = "${next.collectionCount}首歌曲"
            updated.text = next.updatedAt
            updated.visibility = if (!next.updatedAt.isBlank()) View.VISIBLE else View.GONE
            setEnabled(playAll, next.canPlayAll)
            setEnabled(shuffle, next.canPlayAll)
            setEnabled(download, next.canDownloadAll)
            setEnabled(reset, next.canReset)
            showQuery(pendingQuery ?: next.query)
            filterRows.forEach { it.visibility = if (next.isAlbumDetail) View.GONE else View.VISIBLE }
            choices.forEach { (id, cell) -> cell.bind(next.filters.firstOrNull { it.id == id }) }
            result.text = if (next.isAlbumGrid) "${next.count}张专辑" else "${next.count}首歌曲"
            result.visibility = if (next.loading || next.error.isNotBlank() || next.isAlbumDetail) View.GONE else View.VISIBLE
            val hasItems = if (next.isAlbumGrid) next.albums.isNotEmpty() else next.rows.isNotEmpty()
            empty.visibility = if (next.loading || next.error.isNotBlank() || !hasItems) View.VISIBLE else View.GONE
            retry.visibility = if (next.error.isNotBlank()) View.VISIBLE else View.GONE
            message.text = when {
                next.error.isNotBlank() -> next.error
                next.loading -> if (next.isAlbumGrid) "正在读取专辑…" else "正在读取歌曲…"
                next.canReset -> "没有找到匹配项"
                next.isAlbumGrid -> "暂无专辑"
                else -> "暂无歌曲"
            }
            root.setPadding(0, 0, 0, dp(12))
        }

        fun recycle() = album.cancelArtwork()

        private fun showQuery(query: String) {
            if (search.text.toString() == query) return
            bindingSearch = true
            search.setText(query)
            if (search.hasFocus()) search.setSelection(query.length)
            bindingSearch = false
        }
    }

    private inner class FilterCell(id: String) {
        private val title = label(12f, muted)
        private val button = pill("", false) { chooseFilter(id) }.apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            val icon = activity.getDrawable(R.drawable.ic_music_filter_down)?.mutate()
            icon?.setTint(muted)
            icon?.setBounds(0, 0, dp(16), dp(16))
            setCompoundDrawablesRelative(null, null, icon, null)
            compoundDrawablePadding = dp(6)
        }
        val view = column().apply {
            addView(title, fullWidth())
            addView(button, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(3) })
        }
        fun bind(filter: MusicLibraryState.Filter?) {
            view.visibility = if (filter == null) View.INVISIBLE else View.VISIBLE
            if (filter == null) return
            title.text = filter.title
            button.text = filter.options.firstOrNull { it.value == filter.selected }?.label ?: filter.selected
            button.contentDescription = "${filter.title}：${button.text}"
            setEnabled(button, filter.enabled && filter.options.isNotEmpty())
        }
    }

    private fun chooseFilter(id: String) {
        val filter = state?.filters?.firstOrNull { it.id == id && it.enabled } ?: return
        val options = filter.options
        if (options.isEmpty() || activity.isFinishing || activity.isDestroyed) return
        closeDialogs()
        clearKeyboard()
        dialog = RoyToolsDialogBuilder(activity).setTitle(filter.title)
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), options.indexOfFirst { it.value == filter.selected }) { selected, index ->
                selected.dismiss()
                options.getOrNull(index)?.let {
                    if (it.value != filter.selected) onAction("filter:$id", it.value, null)
                }
            }.setNegativeButton("取消", null).create().apply {
                setOnDismissListener { if (dialog === it) dialog = null }
                show()
            }
    }

    private data class TrackItem(
        val track: MusicLibraryState.Track, val key: String, val index: Int, val last: Boolean,
        val current: Boolean, val playing: Boolean,
    )

    private inner class TrackAdapter : ListAdapter<TrackItem, TrackHolder>(object : DiffUtil.ItemCallback<TrackItem>() {
        override fun areItemsTheSame(old: TrackItem, new: TrackItem) = old.key == new.key
        override fun areContentsTheSame(old: TrackItem, new: TrackItem) = old == new
    }) {
        private val stableIds = HashMap<String, Long>()
        init { setHasStableIds(true) }
        override fun getItemId(position: Int): Long = stableIds.getOrPut(getItem(position).key) { stableIds.size.toLong() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = TrackHolder()
        override fun onBindViewHolder(holder: TrackHolder, position: Int) = holder.bind(getItem(position))
        override fun onViewRecycled(holder: TrackHolder) { holder.recycle() }
    }

    private inner class TrackHolder : RecyclerView.ViewHolder(LinearLayout(activity)) {
        private val row = itemView as LinearLayout
        private val number = label(13f, muted).apply { gravity = Gravity.CENTER }
        private val currentNote = ImageView(activity).apply {
            setImageResource(DesignR.drawable.roytools_nav_music)
            imageTintList = ColorStateList.valueOf(accent)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        private val cover = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(coverBackground, 8)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val artwork = MusicArtworkBinding(cover, scope, artworkIO)
        private val title = label(16f, textColor).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        private val detail = label(13f, muted)
        private val main = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(activity).apply {
                addView(number, FrameLayout.LayoutParams(-1, -1))
                addView(currentNote, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(26), dp(24)))
            addView(cover, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(4); marginEnd = dp(10) })
            addView(column().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(title, fullWidth())
                addView(detail, fullWidth(3))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            background = RippleDrawable(rippleColor, null, rounded(Color.WHITE, 8))
            isFocusable = true
            setOnClickListener { item?.track?.takeIf { it.playable }?.let { onAction("play", it.id, this) } }
        }
        private var item: TrackItem? = null
        private val next = iconButton(R.drawable.ic_music_queue_add) {
            item?.track?.let { onAction("next", it.id, this) }
        }
        private val favorite = iconButton(R.drawable.ic_music_favorite_off) {
            item?.track?.let { onAction("favorite", it.id, this) }
        }
        private val more = iconButton(DesignR.drawable.ic_baseline_more_vert) {
            item?.track?.let { showMore(this, it) }
        }
        private var paintKey: Triple<Boolean, Boolean, Boolean>? = null

        init {
            row.layoutParams = RecyclerView.LayoutParams(-1, dp(76))
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPaddingRelative(dp(8), 0, dp(3), 0)
            row.addView(main, LinearLayout.LayoutParams(0, -1, 1f))
            row.addView(next, LinearLayout.LayoutParams(dp(44), dp(48)))
            row.addView(favorite, LinearLayout.LayoutParams(dp(44), dp(48)))
            row.addView(more, LinearLayout.LayoutParams(dp(44), dp(48)))
        }

        fun bind(value: TrackItem) {
            item = value
            val track = value.track
            number.text = value.index.toString().padStart(2, '0')
            number.visibility = if (value.current) View.INVISIBLE else View.VISIBLE
            currentNote.visibility = if (value.current) View.VISIBLE else View.INVISIBLE
            currentNote.contentDescription = if (value.playing) "正在播放" else "当前曲目"
            title.text = track.title
            detail.text = if (!track.playable) track.artist.ifBlank {
                if (track.sourceStatus == "purchasable") "待购入" else "暂无音源"
            } else listOf(track.artist, track.duration).filter { it.isNotBlank() }.joinToString(" · ")
            main.isEnabled = track.playable
            next.visibility = if (track.playable) View.VISIBLE else View.GONE
            favorite.visibility = if (track.playable) View.VISIBLE else View.GONE
            more.visibility = if (track.playable || track.sourceUrl.isNotBlank()) View.VISIBLE else View.GONE
            next.contentDescription = "下一首播放：${track.title}"
            favorite.contentDescription = "Apple Music${if (track.favorite) "已喜欢" else "未喜欢"}：${track.title}"
            favorite.setImageResource(if (track.favorite) R.drawable.ic_music_favorite_on else R.drawable.ic_music_favorite_off)
            favorite.imageTintList = ColorStateList.valueOf(if (track.favorite) accent else muted)
            more.contentDescription = "${track.title}的更多选项"
            val paint = Triple(value.index == 1, value.last, value.playing)
            if (paintKey != paint) {
                paintKey = paint
                val top = if (paint.first) dp(20).toFloat() else 0f
                val bottom = if (paint.second) dp(20).toFloat() else 0f
                row.background = GradientDrawable().apply {
                    setColor(if (paint.third) blend(surface, accent) else surface)
                    cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
                }
            }
            artwork.bind(track.artwork)
        }

        fun recycle() {
            item = null
            artwork.cancel()
        }
    }

    private fun showAlbumOptions(anchor: View, album: MusicAlbum) {
        closeDialogs()
        val menu = PopupMenu(activity, anchor, Gravity.END, 0, DesignR.style.RoyTools_AnchoredMenu)
        menu.menu.add("播放专辑").setOnMenuItemClickListener { onAction("play-album", album.key, anchor); true }
        menu.menu.add("下载全部").setOnMenuItemClickListener { onAction("download-album", album.key, anchor); true }
        popup = menu
        menu.setOnDismissListener { if (popup === it) popup = null }
        menu.show()
    }

    private fun showMore(anchor: View, track: MusicLibraryState.Track) {
        closeDialogs()
        val menu = PopupMenu(activity, anchor, Gravity.END, 0, DesignR.style.RoyTools_AnchoredMenu)
        fun action(title: String, name: String, value: String = track.id) {
            menu.menu.add(title).setOnMenuItemClickListener { onAction(name, value, anchor); true }
        }
        if (track.playable) {
            action("分享歌曲", "share")
            action("下载", "download")
            if (track.canOpenAlbum) action("查看专辑", "album")
            if (track.artists.size == 1) action("查看歌手", "artist", track.artists[0])
            else if (track.artists.isNotEmpty()) {
                val artists = menu.menu.addSubMenu("查看歌手")
                track.artists.forEach { artist ->
                    artists.add(artist).setOnMenuItemClickListener { onAction("artist", artist, anchor); true }
                }
            }
            if (state?.canClearQueue == true) {
                menu.menu.add("清空待插队").setOnMenuItemClickListener { onAction("clearQueue", null, anchor); true }
            }
        } else if (track.sourceUrl.isNotBlank()) {
            action(if (track.sourceStatus == "purchasable") "待购入" else "外部收听", "source")
        }
        popup = menu
        menu.setOnDismissListener { if (popup === it) popup = null }
        menu.show()
    }

    private fun iconButton(@androidx.annotation.DrawableRes drawable: Int, action: ImageButton.() -> Unit) =
        ImageButton(activity).apply {
            background = RippleDrawable(rippleColor, null, rounded(Color.WHITE, 24))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(11), dp(13), dp(11), dp(13))
            setImageResource(drawable)
            imageTintList = ColorStateList.valueOf(muted)
            setOnClickListener { action() }
        }

    private fun pill(label: String, primary: Boolean, action: () -> Unit) = label(13f, if (primary) Color.WHITE else textColor).apply {
        text = label
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minimumHeight = dp(44)
        setPaddingRelative(dp(12), 0, dp(12), 0)
        gravity = Gravity.CENTER
        background = RippleDrawable(rippleColor, rounded(if (primary) accent else surface, 24), null)
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else .46f
    }

    private fun horizontal() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun label(size: Float, tint: Int) = TextView(activity).apply {
        textSize = size
        setTextColor(tint)
        includeFontPadding = false
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
    private fun weight(start: Int = 0) = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(start) }
    private fun fullWidth(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun color(attr: Int) = activity.resolveThemedColor(attr)
    private fun rounded(tint: Int, radius: Int) = GradientDrawable().apply {
        setColor(tint)
        cornerRadius = dp(radius).toFloat()
    }
    private fun blend(base: Int, tint: Int) = androidx.core.graphics.ColorUtils.blendARGB(base, tint, .10f)
    private fun dp(value: Int) = (activity.resources.displayMetrics.density * value + .5f).toInt()
}
