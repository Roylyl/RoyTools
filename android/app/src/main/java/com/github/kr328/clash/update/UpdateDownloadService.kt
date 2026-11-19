package com.github.kr328.clash.update

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.NotificationGroups
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Observes the existing DownloadManager task; it never owns a second APK transfer. */
class UpdateDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notifications: NotificationManager
    private var observer: Job? = null
    private var foreground = false
    @Volatile private var downloadId = -1L

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "应用更新下载", NotificationManager.IMPORTANCE_LOW)
            .apply { setSound(null, null); enableVibration(false) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedId = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        if (requestedId >= 0 && intent?.action != ACTION_CANCEL) downloadId = requestedId
        if (!foreground) {
            try {
                // A short download may already be complete, but the foreground deadline still applies.
                startForeground(ID, notification(null, DownloadProgress(null, 0.0)), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                foreground = true
                NotificationGroups.refresh(this, NotificationGroups.RUNTIME)
            } catch (error: RuntimeException) {
                Log.w("无法显示应用更新下载进度", error)
                scope.launch {
                    if (requestedId >= 0) runCatching { UpdateDownload.cancel(this@UpdateDownloadService, requestedId) }
                    stopSelf(startId)
                }
                return START_NOT_STICKY
            }
        }
        if (intent?.action == ACTION_CANCEL) {
            scope.launch {
                try {
                    if (requestedId >= 0) UpdateDownload.cancel(this@UpdateDownloadService, requestedId)
                } catch (error: Exception) {
                    Log.w("取消应用更新下载失败", error)
                } finally {
                    if (observer?.isActive != true) stopSelf(startId)
                }
            }
            return START_NOT_STICKY
        }
        observer?.cancel()
        observer = scope.launch {
            val meter = DownloadProgressMeter()
            var readFailureLogged = false
            var announced = false
            while (isActive) {
                val state = try { UpdateDownload.query(this@UpdateDownloadService) }
                catch (error: Exception) {
                    if (!readFailureLogged) { Log.w("暂时无法读取应用更新下载进度", error); readFailureLogged = true }
                    delay(2000)
                    continue
                }
                if (!isActive) return@launch
                readFailureLogged = false
                if (state == null || downloadId >= 0 && state.id != downloadId) {
                    stopSelf(startId)
                    return@launch
                }
                downloadId = state.id
                if (state.status == DownloadManager.STATUS_SUCCESSFUL || state.status == DownloadManager.STATUS_FAILED) {
                    // Reuse completion/installer handling, including transfers that finish before service startup.
                    sendBroadcast(Intent(this@UpdateDownloadService, UpdateDownloadReceiver::class.java)
                        .setAction(DownloadManager.ACTION_DOWNLOAD_COMPLETE).putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, state.id))
                    stopSelf(startId)
                    return@launch
                }
                val progress = meter.sample(state.downloadedBytes, state.totalBytes, SystemClock.elapsedRealtime())
                withContext(Dispatchers.Main) {
                    try { notifications.notify(ID, notification(state, progress)) }
                    catch (error: SecurityException) {
                        Log.w("无法显示应用更新下载进度通知", error)
                        stopSelf(startId)
                        return@withContext
                    }
                    if (!announced) { announced = true; NotificationGroups.refresh(this@UpdateDownloadService, NotificationGroups.RUNTIME) }
                }
                delay(1000)
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(state: DownloadState?, progress: DownloadProgress): Notification {
        val open = PendingIntent.getActivity(this, ID, Intent(this, UpdateActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), FLAGS)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(com.github.kr328.clash.service.R.drawable.ic_logo_service)
            .setGroup(NotificationGroups.RUNTIME).setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setContentTitle("应用更新下载${state?.tag?.let { "·$it" }.orEmpty()}")
            .setContentIntent(open).setCategory(Notification.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true).setShowWhen(false).setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, progress.percent ?: 0, progress.percent == null)
        val speed = if (state?.status == DownloadManager.STATUS_RUNNING) progress.megabytesPerSecond else 0.0
        val detail = if (state == null) "正在读取下载进度…" else String.format(Locale.ROOT, "%s·%.2fMB/s\n%.2fMB/%s",
            progress.percent?.let { "$it%" } ?: "等待获取文件大小", speed,
            state.downloadedBytes.coerceAtLeast(0) / 1_000_000.0,
            if (state.totalBytes > 0) String.format(Locale.ROOT, "%.2fMB", state.totalBytes / 1_000_000.0) else "未知")
        builder.setContentText(detail.substringBefore('\n')).setStyle(Notification.BigTextStyle().bigText(detail))
        if (downloadId >= 0) {
            val cancel = PendingIntent.getService(this, ID, Intent(this, UpdateDownloadService::class.java)
                .setAction(ACTION_CANCEL).putExtra(EXTRA_ID, downloadId), FLAGS)
            builder.addAction(Notification.Action.Builder(null, "取消下载", cancel).build())
        }
        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf(startId) }

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancel(ID)
        NotificationGroups.refresh(this, NotificationGroups.RUNTIME)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "roytools_update_progress"
        private const val ID = 1702003
        private const val ACTION_CANCEL = "com.roylyl.tools.update.CANCEL_DOWNLOAD"
        private const val EXTRA_ID = "update_download_id"
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        fun start(context: Context, id: Long) {
            ContextCompat.startForegroundService(context.applicationContext, Intent(context, UpdateDownloadService::class.java).putExtra(EXTRA_ID, id))
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(Intent(context, UpdateDownloadService::class.java))
        }
    }
}
