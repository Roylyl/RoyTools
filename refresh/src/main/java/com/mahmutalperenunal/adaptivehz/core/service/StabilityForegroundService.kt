package com.mahmutalperenunal.adaptivehz.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.github.kr328.clash.common.util.NotificationGroups
import com.mahmutalperenunal.adaptivehz.OneSetup
import com.mahmutalperenunal.adaptivehz.R
import com.mahmutalperenunal.adaptivehz.core.engine.AdaptiveHzRuntimeState
import com.mahmutalperenunal.adaptivehz.core.engine.model.AdaptiveHzMode
import com.mahmutalperenunal.adaptivehz.core.health.AccessibilityHealthMonitor
import com.mahmutalperenunal.adaptivehz.core.prefs.AdaptiveHzPrefs

/**
 * Keeps the app process alive more reliably on aggressive OEM ROMs (e.g. HyperOS)
 * by running a minimal foreground service with a persistent notification.
 *
 * This does NOT perform any heavy work — it's purely for stability.
 */
class StabilityForegroundService : Service() {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val healthMonitorRunnable = object : Runnable {
        override fun run() {
            if (!OneSetup.isKeepAliveEnabled(this@StabilityForegroundService)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            if (AdaptiveHzPrefs.isAppEnabled(this@StabilityForegroundService)) {
                OneSetup.recoverAccessibility(this@StabilityForegroundService)
                AccessibilityHealthMonitor.check(
                    context = this@StabilityForegroundService,
                    reason = "stability_foreground_service"
                )
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification())
            mainHandler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        NotificationGroups.refresh(this, NotificationGroups.RUNTIME)
    }

    /**
     * Keeps the foreground lifecycle aligned with the global keep-alive setting.
     * Notification actions are handled by RoyToolsShortcutActivity.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!OneSetup.isKeepAliveEnabled(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startHealthMonitor()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Builds the persistent foreground notification with quick mode actions.
     */
    private fun buildNotification(): Notification {
        ensureChannel()
        val open = Intent().setClassName(packageName, "com.github.kr328.clash.MainActivity")
            .putExtra("roytools_tab", "refresh")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(this, 1001, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val mode = AdaptiveHzActionHandler.getCurrentMode(this)
        val modeText = when (mode) {
            AdaptiveHzMode.OFF -> "关闭"
            AdaptiveHzMode.FORCE_MIN -> "60Hz"
            AdaptiveHzMode.FORCE_MAX -> "120Hz"
            AdaptiveHzMode.ADAPTIVE -> "自适应刷新率"
        }
        val needsRecovery = mode != AdaptiveHzMode.OFF &&
            AdaptiveHzRuntimeState.getAccessibilityState(this) == AdaptiveHzRuntimeState.AccessibilityState.BROKEN
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setGroup(NotificationGroups.RUNTIME)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSmallIcon(R.drawable.ic_qs_refresh_phone)
            .setContentTitle("刷新率控制")
            .setSubText("RoyTools")
            .setContentText(if (needsRecovery) "辅助服务待恢复，请打开RoyTools配置" else modeText)
            .setContentIntent(contentIntent)
            .setCustomBigContentView(controlView(mode, needsRecovery))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private fun controlView(mode: AdaptiveHzMode, needsRecovery: Boolean): android.widget.RemoteViews {
        val views = android.widget.RemoteViews(packageName, R.layout.roytools_refresh_notification)
        views.setViewVisibility(R.id.refresh_notification_warning, if (needsRecovery) android.view.View.VISIBLE else android.view.View.GONE)
        val entries = listOf(
            Triple(R.id.refresh_notification_off, AdaptiveHzMode.OFF, "REFRESH_DEFAULT"),
            Triple(R.id.refresh_notification_60, AdaptiveHzMode.FORCE_MIN, "REFRESH_60"),
            Triple(R.id.refresh_notification_120, AdaptiveHzMode.FORCE_MAX, "REFRESH_120"),
            Triple(R.id.refresh_notification_adaptive, AdaptiveHzMode.ADAPTIVE, "REFRESH_ADAPTIVE"))
        entries.forEachIndexed { index, (id, option, action) ->
            val intent = Intent().setClassName(packageName, "com.github.kr328.clash.RoyToolsShortcutActivity")
                .setAction("com.roylyl.tools.shortcut." + action)
                .putExtra("roytools_notification_action", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            views.setOnClickPendingIntent(id, PendingIntent.getActivity(this, 2100 + index, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            views.setInt(id, "setBackgroundResource", if (mode == option) R.drawable.roytools_notification_selected else R.drawable.roytools_notification_button)
            views.setTextColor(id, if (mode == option) android.graphics.Color.WHITE else android.graphics.Color.rgb(0, 122, 255))
        }
        return views
    }

    /**
     * Creates the notification channel on first launch.
     */
    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.stability_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun startHealthMonitor() {
        mainHandler.removeCallbacks(healthMonitorRunnable)
        mainHandler.postDelayed(healthMonitorRunnable, HEALTH_CHECK_INTERVAL_MS)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationGroups.refresh(this, NotificationGroups.RUNTIME)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "adaptive_hz_stability"
        private const val NOTIF_ID = 1001

        private const val HEALTH_CHECK_INTERVAL_MS = 5_000L

        fun start(context: Context) {
            if (!OneSetup.isKeepAliveEnabled(context)) return
            val appContext = context.applicationContext
            val intent = Intent(appContext, StabilityForegroundService::class.java)
            ContextCompat.startForegroundService(appContext, intent)
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            appContext.stopService(Intent(appContext, StabilityForegroundService::class.java))
        }

        /**
         * Refreshes notification state without restarting engine logic.
         */
        fun refreshNotification(context: Context) {
            try {
                start(context)
            } catch (_: Throwable) { }
        }
    }
}


