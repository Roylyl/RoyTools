package com.github.kr328.clash.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.github.kr328.clash.music.MusicArtwork
import com.github.kr328.clash.music.MusicPlayback
import com.github.kr328.clash.music.MusicPlaybackService

/** A compact view of the existing player. No widget-specific queue, player or network cache. */
object WidgetMusicState {
    const val ACTION_CHANGED = "com.roylyl.tools.widget.MUSIC_CHANGED"
    private val commands = setOf("previous", "toggle", "next")
    private data class Cover(val path: String, val bitmap: Bitmap?, val loadedAt: Long = SystemClock.elapsedRealtime())
    @Volatile private var lastCover: Cover? = null

    data class Snapshot(
        val title: String,
        val artist: String,
        val cover: Bitmap?,
        val playing: Boolean,
        val hasTrack: Boolean,
        val canPrevious: Boolean,
        val canNext: Boolean,
    )

    /** Provider's existing worker calls this; decoding is explicitly skipped on the main thread. */
    fun read(context: Context): Snapshot {
        val state = MusicPlayback.snapshot(context)
        val path = MusicArtwork.assetPath(state.artwork)
        val cached = lastCover
        val bitmap = when {
            path == null -> null
            cached != null && cached.path == path &&
                (cached.bitmap != null || SystemClock.elapsedRealtime() - cached.loadedAt < 30_000L) -> cached.bitmap
            Looper.myLooper() == Looper.getMainLooper() -> null
            else -> MusicArtwork.decode(context, state.artwork, 96).also { lastCover = Cover(path, it) }
        }
        return Snapshot(state.title, state.artist, bitmap, state.playing,
            state.title.isNotBlank() || state.artist.isNotBlank(), state.canPrevious, state.canNext)
    }

    /** A widget click is user initiated. Start the same media service and never open an Activity. */
    fun action(context: Context, action: String): Boolean {
        if (action !in commands) return false
        return try {
            context.applicationContext.startForegroundService(
                Intent(context, MusicPlaybackService::class.java)
                    .setAction(MusicPlaybackService.ACTION_COMMAND)
                    .putExtra("command", action)
                    .putExtra("foreground_start", true)
                    .putExtra(MusicPlaybackService.EXTRA_WIDGET_COMMAND, true)
            )
            true
        } catch (_: RuntimeException) {
            showUnavailable(context)
            false
        }
    }

    internal fun notifyChanged(context: Context) {
        context.sendBroadcast(Intent(ACTION_CHANGED).setComponent(
            ComponentName(context.packageName, "com.github.kr328.clash.widget.ProxyWidgetProvider")))
    }

    internal fun showUnavailable(context: Context) {
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(app, "暂时无法控制音乐，请打开RoyTools后重试", Toast.LENGTH_SHORT).show()
        }
    }
}
