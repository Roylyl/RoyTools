package com.github.kr328.clash.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.github.kr328.clash.R
import com.github.kr328.clash.widget.WidgetMusicState
import java.util.concurrent.Executors

/** Accompanies Chromium HTMLAudio; deliberately owns neither a player nor audio focus. */
class MusicPlaybackService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val artworkLoader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RoyToolsMusicArtwork").apply { isDaemon = true }
    }
    private lateinit var session: MediaSession
    private lateinit var notifications: NotificationManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private var wakeAcquiredAt = 0L
    private var foreground = false
    private var noisyRegistered = false
    private var destroyed = false
    private var artworkPath = ""
    private var artwork: Bitmap? = null
    private var artworkRetryAt = 0L
    private var metadataIdentity = ""
    private var notificationIdentity = ""

    private val demoteAfterPause = Runnable {
        if (!destroyed && foreground && !MusicPlayback.isPlaying) {
            stopForeground(STOP_FOREGROUND_DETACH)
            foreground = false
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && MusicPlayback.isPlaying) {
                MusicPlayback.dispatch("pause")
            }
        }
    }

    private val pulse = object : Runnable {
        override fun run() {
            if (destroyed || !MusicPlayback.isPlaying) return
            if (!MusicPlayback.hasDispatcher || SystemClock.elapsedRealtime() - MusicPlayback.state.updatedAt > 60_000L) {
                MusicPlayback.pauseUnresponsivePlayer()
                return
            }
            // Also runs the original page's sleep timer when Chromium throttles page timers.
            MusicPlayback.dispatch("sync")
            main.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        MusicPlayback.connected(this)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL, getString(R.string.music_notification_channel), NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.music_notification_channel_description) })
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:music").apply {
                setReferenceCounted(false)
            }
        session = MediaSession(this, "RoyToolsMusic").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setPlaybackToLocal(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setSessionActivity(playerPendingIntent())
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = MusicPlayback.dispatch("play")
                override fun onPause() = MusicPlayback.dispatch("pause")
                override fun onStop() { MusicPlayback.dispatch("pause"); stopSelf() }
                override fun onSkipToNext() = MusicPlayback.dispatch("next")
                override fun onSkipToPrevious() = MusicPlayback.dispatch("previous")
                override fun onSeekTo(pos: Long) = MusicPlayback.dispatch("seek", pos.coerceAtLeast(0) / 1000.0)
            }, main)
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val widgetCommand = intent?.action == ACTION_COMMAND && intent.getBooleanExtra(EXTRA_WIDGET_COMMAND, false)
        if (intent?.action == ACTION_DISMISS) {
            MusicPlayback.dispatch("pause")
            stopSelf()
            return START_NOT_STICKY
        }
        // A very quick play/pause can reach here already paused. A requested FGS must
        // still enter the foreground before the pause grace period can demote it.
        if (intent?.getBooleanExtra("foreground_start", false) == true && !foreground) {
            try {
                startForeground(NOTIFICATION_ID, buildNotification(MusicPlayback.state),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } catch (error: RuntimeException) {
                if (!widgetCommand) throw error
                WidgetMusicState.showUnavailable(this)
                stopSelf()
                return START_NOT_STICKY
            }
            foreground = true
        }
        if (widgetCommand) {
            try { MusicPanel.ensurePlaybackEngine(this) }
            catch (_: RuntimeException) {
                WidgetMusicState.showUnavailable(this)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        publishState(MusicPlayback.state, force = true)
        if (intent?.action == ACTION_COMMAND) {
            intent.getStringExtra("command")?.let { MusicPlayback.dispatch(it) }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    internal fun publishState(state: MusicPlayback.State, force: Boolean = false) {
        if (destroyed) return
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        if (state.duration > 0) actions = actions or PlaybackState.ACTION_SEEK_TO
        if (state.canPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (state.canNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        session.setPlaybackState(PlaybackState.Builder().setActions(actions).setState(
            if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
            (state.position * 1000).toLong(), if (state.playing) 1f else 0f, state.updatedAt
        ).build())
        val identity = "${state.title}\n${state.artist}\n${state.album}\n${state.duration}\n${state.artwork}"
        if (force || identity != metadataIdentity) {
            metadataIdentity = identity
            if (artworkPath != state.artwork) {
                artworkPath = state.artwork
                artwork = null
                artworkRetryAt = 0L
                loadArtwork(state.artwork)
            }
            publishMetadata(state)
        }
        if (artwork == null && artworkPath.isNotBlank() && SystemClock.elapsedRealtime() >= artworkRetryAt) {
            loadArtwork(artworkPath)
        }
        if (state.playing) {
            main.removeCallbacks(demoteAfterPause)
            if (!wakeLock.isHeld || SystemClock.elapsedRealtime() - wakeAcquiredAt > 30_000L) {
                if (wakeLock.isHeld) wakeLock.release()
                // Expiry protects against a hung renderer; only fresh page state renews it.
                wakeLock.acquire(60_000L)
                wakeAcquiredAt = SystemClock.elapsedRealtime()
            }
            if (!noisyRegistered) {
                ContextCompat.registerReceiver(this, noisyReceiver,
                    IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
                noisyRegistered = true
            }
            main.removeCallbacks(pulse)
            main.postDelayed(pulse, 10_000L)
        } else {
            releasePlaybackResources()
            // ended/advance and buffering can briefly report paused while the app is
            // backgrounded. Retain the existing FGS grant across that transition, but
            // release playback resources immediately and do not extend idle time on sync.
            if (foreground && !main.hasCallbacks(demoteAfterPause)) {
                main.postDelayed(demoteAfterPause, PAUSE_FOREGROUND_GRACE)
            }
        }
        val displayIdentity = "$identity\n${state.playing}\n${state.canPrevious}\n${state.canNext}"
        if (!force && notificationIdentity == displayIdentity) return
        notificationIdentity = displayIdentity
        val notification = buildNotification(state)
        if (state.playing) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            foreground = true
        } else {
            notifications.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun publishMetadata(state: MusicPlayback.State) {
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, state.title.ifBlank { getString(R.string.music_notification_title) })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, state.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, state.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, (state.duration * 1000).toLong())
            .apply { artwork?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
            .build())
    }

    private fun buildNotification(state: MusicPlayback.State): Notification {
        fun action(icon: Int, label: Int, command: String, enabled: Boolean = true): Notification.Action =
            Notification.Action.Builder(Icon.createWithResource(this, icon), getString(label),
                if (enabled) commandPendingIntent(command, state.playing) else null).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_music_notification)
            .setContentTitle(state.title.ifBlank { getString(R.string.music_notification_title) })
            .setContentText(state.artist.ifBlank { getString(R.string.music_notification_open) })
            .setSubText(state.album.takeIf { it.isNotBlank() })
            .setContentIntent(playerPendingIntent())
            .setDeleteIntent(PendingIntent.getService(this, 50,
                Intent(this, MusicPlaybackService::class.java).setAction(ACTION_DISMISS), PENDING_FLAGS))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(state.playing)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setLargeIcon(artwork)
            .addAction(action(R.drawable.ic_music_previous, R.string.music_notification_previous, "previous", state.canPrevious))
            .addAction(if (state.playing) action(R.drawable.ic_music_pause, R.string.music_notification_pause, "pause")
                else action(R.drawable.ic_music_play, R.string.music_notification_play, "play"))
            .addAction(action(R.drawable.ic_music_next, R.string.music_notification_next, "next", state.canNext))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun playerPendingIntent(action: String? = null): PendingIntent = PendingIntent.getActivity(
        this, when (action) { "play" -> 1; "previous" -> 2; "next" -> 3; "pause" -> 4; else -> 0 },
        MusicPlayback.playerIntent(this, action), PENDING_FLAGS
    )

    private fun commandPendingIntent(command: String, playing: Boolean): PendingIntent {
        // A paused service can be reclaimed. Direct Activity PendingIntents still restore the
        // page after process death and avoid Android 12's notification trampoline restriction.
        if (!playing || !MusicPlayback.hasDispatcher) return playerPendingIntent(command)
        return PendingIntent.getService(this, when (command) { "pause" -> 11; "previous" -> 12; else -> 13 },
            Intent(this, MusicPlaybackService::class.java).setAction(ACTION_COMMAND).putExtra("command", command), PENDING_FLAGS)
    }

    private fun loadArtwork(url: String) {
        if (MusicArtwork.assetPath(url) == null) return
        val now = SystemClock.elapsedRealtime()
        if (now < artworkRetryAt) return
        artworkRetryAt = now + 30_000L
        artworkLoader.execute {
            val bitmap = MusicArtwork.decode(this, url, 320)
            main.post {
                if (destroyed || artworkPath != url) return@post
                artwork = bitmap
                publishMetadata(MusicPlayback.state)
                notifications.notify(NOTIFICATION_ID, buildNotification(MusicPlayback.state))
            }
        }
    }

    private fun releasePlaybackResources() {
        main.removeCallbacks(pulse)
        if (wakeLock.isHeld) wakeLock.release()
        if (noisyRegistered) {
            unregisterReceiver(noisyReceiver)
            noisyRegistered = false
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!MusicPlayback.isPlaying) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacks(demoteAfterPause)
        releasePlaybackResources()
        artworkLoader.shutdownNow()
        session.isActive = false
        session.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancel(NOTIFICATION_ID)
        MusicPlayback.disconnected(this)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "roytools_music_playback"
        private const val NOTIFICATION_ID = 416
        internal const val ACTION_COMMAND = "com.roylyl.tools.music.COMMAND"
        internal const val EXTRA_WIDGET_COMMAND = "from_music_widget"
        private const val ACTION_DISMISS = "com.roylyl.tools.music.DISMISS"
        private const val PAUSE_FOREGROUND_GRACE = 25_000L
        private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
