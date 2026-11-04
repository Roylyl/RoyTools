package com.github.kr328.clash.music

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.github.kr328.clash.MainActivity
import com.github.kr328.clash.widget.WidgetMusicState
import org.json.JSONObject

/** Native controls mirror the retained WebView. HTMLAudio remains the only audio player. */
object MusicPlayback {
    internal data class State(
        val playing: Boolean = false,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val artwork: String = "",
        val position: Double = 0.0,
        val duration: Double = 0.0,
        val canPrevious: Boolean = false,
        val canNext: Boolean = false,
        val updatedAt: Long = SystemClock.elapsedRealtime()
    )

    private val main = Handler(Looper.getMainLooper())
    private var application: Context? = null
    // The host supplies a WebView backed by MutableContextWrapper, never an Activity closure.
    private var dispatcher: ((String, Double?) -> Unit)? = null
    @Volatile private var initialized = false
    private var starting = false
    private var lastStartAttempt = -15_000L
    private var lastSavedAt = 0L
    private var lastSavedIdentity = ""
    private var lastWidgetIdentity = ""
    @Volatile internal var state = State()
        private set
    internal var service: MusicPlaybackService? = null
        private set

    val isPlaying: Boolean get() = state.playing
    internal val hasDispatcher: Boolean get() = dispatcher != null

    internal fun snapshot(context: Context): State {
        val current = state
        return if (initialized && (current.title.isNotBlank() || current.artist.isNotBlank())) current
            else readSavedState(context)
    }

    fun attach(context: Context, dispatcher: (String, Double?) -> Unit) = onMain {
        initialize(context)
        this.dispatcher = dispatcher
    }

    /** May be called by JavascriptInterface on its worker thread. Values are in seconds. */
    fun update(context: Context, payloadJson: String) {
        val payload = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return
        fun seconds(name: String) = payload.optDouble(name, 0.0)
            .takeIf { it.isFinite() && it >= 0.0 }?.coerceAtMost(604_800.0) ?: 0.0
        val duration = seconds("duration")
        val position = seconds("position").let { if (duration > 0) it.coerceAtMost(duration) else it }
        val next = State(
            playing = payload.optBoolean("playing", false) && !payload.optBoolean("paused", false),
            title = payload.optString("title").take(500),
            artist = payload.optString("artist").take(500),
            album = payload.optString("album").take(500),
            artwork = payload.optString("artwork").take(2048),
            position = position,
            duration = duration,
            canPrevious = payload.optBoolean("canPrevious", false),
            canNext = payload.optBoolean("canNext", false)
        )
        onMain {
            initialize(context)
            state = next
            saveState()
            val active = service
            if (active != null) {
                active.publishState(next)
            } else if (next.playing && hasDispatcher && !starting) {
                // The first user play starts while the app is visible. Never blindly restart
                // an FGS from a background timer after Android has stopped it.
                val process = ActivityManager.RunningAppProcessInfo()
                ActivityManager.getMyMemoryState(process)
                val now = SystemClock.elapsedRealtime()
                if (process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    now - lastStartAttempt >= 15_000L) {
                    lastStartAttempt = now
                    starting = true
                    try {
                        context.applicationContext.startForegroundService(
                            Intent(context.applicationContext, MusicPlaybackService::class.java)
                                .putExtra("foreground_start", true)
                        )
                    } catch (e: IllegalStateException) {
                        starting = false
                        Log.w("RoyToolsMusic", "Media foreground service cannot start yet", e)
                    } catch (e: SecurityException) {
                        starting = false
                        Log.w("RoyToolsMusic", "Media foreground service permission unavailable", e)
                    }
                }
            }
        }
    }

    fun dispatch(action: String, position: Double? = null) = onMain {
        if (action !in commands) return@onMain
        val target = dispatcher
        if (target != null) {
            target(action, position?.takeIf { it.isFinite() }?.coerceAtLeast(0.0))
        } else if (action != "sync") {
            application?.let { it.startActivity(playerIntent(it, action, position)) }
        }
    }

    /** For a destroyed WebView/renderer or explicit shutdown, not Activity.onStop. */
    fun shutdown(context: Context) = onMain {
        dispatch("pause")
        dispatcher = null
        state = state.copy(playing = false)
        saveState(force = true)
        context.applicationContext.stopService(Intent(context, MusicPlaybackService::class.java))
    }

    internal fun connected(service: MusicPlaybackService) {
        initialize(service)
        starting = false
        this.service = service
    }

    internal fun disconnected(service: MusicPlaybackService) {
        if (this.service !== service) return
        this.service = null
        starting = false
        if (state.playing) {
            dispatch("pause")
            state = state.copy(playing = false)
        }
        saveState(force = true)
    }

    internal fun pauseUnresponsivePlayer() {
        dispatch("pause")
        state = state.copy(playing = false, updatedAt = SystemClock.elapsedRealtime())
        saveState(force = true)
        service?.publishState(state)
    }

    internal fun playerIntent(context: Context, action: String? = null, position: Double? = null): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("roytools_tab", "music")
            .putExtra("roytools_music_player", true)
            .apply {
                if (action != null) putExtra("roytools_music_action", action)
                if (position != null && position.isFinite()) putExtra("roytools_music_position", position)
            }

    private fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        application = context.applicationContext
        state = readSavedState(context)
    }

    private fun readSavedState(context: Context): State {
        val saved = runCatching {
            JSONObject(context.getSharedPreferences("roytools_music_native", Context.MODE_PRIVATE)
                .getString("last_track", "{}") ?: "{}")
        }.getOrNull() ?: return State()
        // A process restart never auto-plays. The page restores its own queue/cache.
        return State(
            title = saved.optString("title").take(500), artist = saved.optString("artist").take(500),
            album = saved.optString("album").take(500), artwork = saved.optString("artwork").take(2048),
            position = saved.optDouble("position", 0.0), duration = saved.optDouble("duration", 0.0)
        )
    }

    private fun saveState(force: Boolean = false) {
        val current = state
        val context = application ?: return
        // Queue edits can change previous/next availability without changing the song.
        // Publish controls immediately; the disk-write throttle only applies to history.
        val widget = snapshot(context)
        val widgetIdentity = "${widget.playing}\n${widget.title}\n${widget.artist}\n${widget.artwork}\n${widget.canPrevious}\n${widget.canNext}"
        if (widgetIdentity != lastWidgetIdentity) {
            lastWidgetIdentity = widgetIdentity
            WidgetMusicState.notifyChanged(context)
        }
        val identity = "${current.playing}\n${current.title}\n${current.artist}\n${current.album}\n${current.artwork}"
        val now = SystemClock.elapsedRealtime()
        if (!force && identity == lastSavedIdentity && now - lastSavedAt < 15_000L) return
        lastSavedAt = now
        lastSavedIdentity = identity
        val json = JSONObject().put("title", current.title).put("artist", current.artist)
            .put("album", current.album).put("artwork", current.artwork)
            .put("position", current.position).put("duration", current.duration)
        // Initial page hydration reports no track before the catalog restores its queue.
        if (current.title.isNotBlank() || current.artist.isNotBlank()) {
            context.getSharedPreferences("roytools_music_native", Context.MODE_PRIVATE)
                .edit().putString("last_track", json.toString()).apply()
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private val commands = setOf("play", "pause", "toggle", "next", "previous", "seek", "openPlayer", "openQueue", "sync")
}
