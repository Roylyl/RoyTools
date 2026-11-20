package com.github.kr328.clash.music

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.github.kr328.clash.common.log.Log
import org.json.JSONObject

internal data class MusicDownloadState(
    val id: String = "", val active: Boolean = false, val kind: String = "audio", val paused: Boolean = false,
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
                kind = json.optString("kind", "audio").takeIf { it in setOf("audio", "lyrics", "covers", "mixed") } ?: "audio",
                paused = json.optBoolean("paused"), total = total, processed = count("processed").coerceAtMost(total),
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
        if (!next.active || next.paused) speedBytesPerSecond = 0.0
        if (next.active && !before.active) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, MusicDownloadService::class.java))
            } catch (error: RuntimeException) {
                Log.w("无法启动音乐后台下载服务", error)
                Toast.makeText(context, "无法启动后台下载，请保持应用在前台", Toast.LENGTH_LONG).show()
            }
        }
        service?.publishState(next, speedBytesPerSecond)
    }

    fun show(activity: Activity) {
        if (!activity.isFinishing && !activity.isDestroyed) {
            MusicPanel.musicDownloadCommand(activity, "openActiveDownloads")
        }
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
}
