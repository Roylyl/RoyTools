package com.github.kr328.clash.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.github.kr328.clash.MainActivity
import com.github.kr328.clash.R
import com.github.kr328.clash.common.log.Log
import java.util.Locale

/** Keeps the existing WebView download queue foregrounded without owning a second downloader. */
class MusicDownloadService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var notifications: NotificationManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private var started = false
    private var foreground = false
    private var destroyed = false
    private var lastPublishedAt = 0L
    private var lastStateAt = 0L
    private var wakeAcquiredAt = 0L
    private var pendingState: MusicDownloadState? = null
    private var pendingSpeed = 0.0
    private val publishPending = Runnable {
        renderPending()
    }
    private val checkHeartbeat = object : Runnable {
        override fun run() {
            if (destroyed || !started || pendingState?.active != true) return
            val remaining = STATE_TIMEOUT - (SystemClock.elapsedRealtime() - lastStateAt)
            if (remaining > 0) main.postDelayed(this, remaining)
            else failDownload("下载引擎长时间未响应，请重新开始下载")
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL, getString(R.string.music_download_channel), NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.music_download_channel_description)
            setSound(null, null)
            enableVibration(false)
        })
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:music-download").apply {
                setReferenceCounted(false)
            }
        MusicDownloadCoordinator.onServiceAvailable(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        started = true
        val current = MusicDownloadCoordinator.state
        // A short queue can finish before the foreground-start intent is delivered.
        // Enter foreground first even then, so Android's start deadline is satisfied.
        if (!foreground) {
            try {
                startForeground(NOTIFICATION_ID,
                    buildNotification(current, MusicDownloadCoordinator.speedBytesPerSecond),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                foreground = true
                lastPublishedAt = SystemClock.elapsedRealtime()
            } catch (error: RuntimeException) {
                Log.w("无法启动音乐下载前台服务", error)
                failDownload("无法启动后台下载服务，请保持应用在前台后重试")
                return START_NOT_STICKY
            }
        }
        if (intent?.action == ACTION_CANCEL &&
            intent.getStringExtra(EXTRA_DOWNLOAD_ID) == current.id && current.active) {
            MusicDownloadCoordinator.cancel(this)
        }
        publishState(MusicDownloadCoordinator.state, MusicDownloadCoordinator.speedBytesPerSecond)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    internal fun publishState(state: MusicDownloadState, speedBytesPerSecond: Double) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { publishState(state, speedBytesPerSecond) }
            return
        }
        if (destroyed) return
        lastStateAt = SystemClock.elapsedRealtime()
        pendingState = state
        pendingSpeed = speedBytesPerSecond
        if (!started) return
        main.removeCallbacks(checkHeartbeat)
        if (state.active) {
            renewWakeLease()
            main.postDelayed(checkHeartbeat, STATE_TIMEOUT)
        } else {
            releaseWakeLock()
        }
        renderPending()
    }

    private fun renderPending() {
        if (destroyed || !started) return
        val state = pendingState ?: return
        main.removeCallbacks(publishPending)
        if (!state.active) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf()
            return
        }
        val remaining = PUBLISH_INTERVAL - (SystemClock.elapsedRealtime() - lastPublishedAt)
        if (remaining > 0) {
            main.postDelayed(publishPending, remaining)
            return
        }
        try {
            notifications.notify(NOTIFICATION_ID, buildNotification(state, pendingSpeed))
            lastPublishedAt = SystemClock.elapsedRealtime()
        } catch (error: SecurityException) {
            // A denied notification permission must not interrupt a user-started transfer.
            Log.w("无法显示音乐下载进度通知", error)
        }
    }

    private fun renewWakeLease() {
        val now = SystemClock.elapsedRealtime()
        if (!wakeLock.isHeld || now - wakeAcquiredAt >= 30_000L) {
            if (wakeLock.isHeld) wakeLock.release()
            // Fresh engine state is the only renewal source; a stalled renderer cannot
            // keep the CPU awake indefinitely even if Android skips service destruction.
            wakeLock.acquire(WAKE_LEASE)
            wakeAcquiredAt = now
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun buildNotification(state: MusicDownloadState, speedBytesPerSecond: Double): Notification {
        val total = state.total.coerceAtLeast(0)
        val processed = state.processed.coerceIn(0, total)
        val percent = state.percent
        val speed = speedBytesPerSecond.takeIf { it.isFinite() && it > 0 } ?: 0.0
        val rate = String.format(Locale.ROOT, "%.2fMB/s", speed / 1_000_000.0)
        val unit = when (state.kind) { "lyrics" -> "份"; "covers" -> "张"; else -> "首" }
        val summary = getString(R.string.music_download_notification_progress, percent, processed, total, rate, unit)
        val transferred = String.format(Locale.ROOT, "%.2fMB", state.downloadedBytes.coerceAtLeast(0) / 1_000_000.0)
        val bytes = if (state.totalBytesKnown && state.totalBytes > 0) {
            transferred + "/" + String.format(Locale.ROOT, "%.2fMB", state.totalBytes / 1_000_000.0)
        } else transferred
        val detail = getString(R.string.music_download_notification_detail,
            state.downloaded, state.cached, state.failed, state.skipped, unit)
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID,
            Intent(this, MainActivity::class.java)
                .putExtra("roytools_tab", "music")
                .putExtra("roytools_music_download", true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PENDING_FLAGS)
        val cancel = PendingIntent.getService(this, NOTIFICATION_ID,
            Intent(this, MusicDownloadService::class.java).setAction(ACTION_CANCEL)
                .putExtra(EXTRA_DOWNLOAD_ID, state.id), PENDING_FLAGS)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_music_notification)
            .setContentTitle(getString(R.string.music_download_title))
            .setContentText(summary)
            .setSubText("RoyTools")
            .setStyle(Notification.BigTextStyle().bigText("$summary\n$bytes\n$detail"))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setProgress(100, percent, total <= 0)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, getString(R.string.music_download_cancel), cancel).build())
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w("音乐下载已达到系统后台运行时限")
        failDownload("已达到系统后台下载时限，请重新开始下载")
    }

    private fun failDownload(reason: String) {
        try {
            MusicDownloadCoordinator.fail(this, reason)
        } finally {
            main.removeCallbacks(checkHeartbeat)
            main.removeCallbacks(publishPending)
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf()
        }
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        try {
            MusicDownloadCoordinator.onServiceUnavailable(this)
        } finally {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            notifications.cancel(NOTIFICATION_ID)
            super.onDestroy()
        }
    }

    companion object {
        private const val CHANNEL = "roytools_music_downloads"
        private const val NOTIFICATION_ID = 417
        private const val ACTION_CANCEL = "com.roylyl.tools.music.CANCEL_DOWNLOAD"
        private const val EXTRA_DOWNLOAD_ID = "music_download_id"
        private const val PUBLISH_INTERVAL = 750L
        private const val WAKE_LEASE = 150_000L
        private const val STATE_TIMEOUT = 120_000L
        private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
