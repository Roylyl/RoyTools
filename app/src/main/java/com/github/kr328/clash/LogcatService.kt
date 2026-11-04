package com.github.kr328.clash

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.compat.startForegroundCompat
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.NotificationGroups
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.log.LogcatCache
import com.github.kr328.clash.log.UnifiedLogs
import kotlinx.coroutines.*

class LogcatService : Service(), CoroutineScope by CoroutineScope(Dispatchers.Default), IInterface {
    private var previousSnapshot: List<LogMessage>? = null

    override fun onCreate() {
        super.onCreate()

        running = true

        createNotificationChannel()

        showNotification()

        // Compatibility for an old direct activity intent. Collection is process-owned.
        UnifiedLogs.start(this)
    }

    override fun onDestroy() {
        cancel()

        stopForeground(true)
        NotificationGroups.refresh(this, NotificationGroups.RUNTIME)

        running = false

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder {
        return this.asBinder()
    }

    override fun asBinder(): IBinder {
        return object : Binder() {
            override fun queryLocalInterface(descriptor: String): IInterface {
                return this@LogcatService
            }
        }
    }

    suspend fun snapshot(full: Boolean): LogcatCache.Snapshot? {
        return withContext(Dispatchers.IO) {
            val messages = UnifiedLogs.read(this@LogcatService, UnifiedLogs.Module.Proxy).takeLast(CACHE_CAPACITY)
            if (!full && messages == previousSnapshot) return@withContext null
            previousSnapshot = messages
            LogcatCache.Snapshot(messages, 0, messages.size)
        }
    }

    private fun createNotificationChannel() {
        NotificationManagerCompat.from(this)
            .createNotificationChannel(
                NotificationChannelCompat.Builder(
                    CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT
                ).setName(getString(com.github.kr328.clash.design.R.string.clash_logcat)).build()
            )
    }

    private fun showNotification() {
        val notification = NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setGroup(NotificationGroups.RUNTIME)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSmallIcon(com.github.kr328.clash.service.R.drawable.ic_logo_service)
            .setColor(getColorCompat(com.github.kr328.clash.design.R.color.color_clash_light))
            .setContentTitle(getString(com.github.kr328.clash.design.R.string.clash_logcat))
            .setContentText(getString(com.github.kr328.clash.design.R.string.running))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    R.id.nf_logcat_status,
                    LogcatActivity::class.intent
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
                )
            )
            .build()

        startForegroundCompat(R.id.nf_logcat_status, notification)
        NotificationGroups.refresh(this, NotificationGroups.RUNTIME)
    }

    companion object {
        private const val CHANNEL_ID = "clash_logcat_channel"
        private const val CACHE_CAPACITY = 128

        var running: Boolean = false
    }
}
