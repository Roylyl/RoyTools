package com.github.kr328.clash.common.util

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.github.kr328.clash.common.log.Log

/** Explicit notification groups keep wireless replies separate from ongoing services. */
object NotificationGroups {
    const val WIRELESS = "com.roylyl.tools.notifications.WIRELESS"
    const val RUNTIME = "com.roylyl.tools.notifications.RUNTIME"
    private const val WIRELESS_SUMMARY_ID = 0x52540001
    private const val RUNTIME_SUMMARY_ID = 0x52540002
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val pending = mutableMapOf<String, Runnable>()

    /** Coalesce changes, allowing notification posts/removals to reach system_server. */
    fun refresh(context: Context, group: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || group !in setOf(WIRELESS, RUNTIME)) return
        val app = context.applicationContext
        if (group == WIRELESS) {
            // The single wireless notification must expose its reply action directly.
            app.getSystemService(NotificationManager::class.java).cancel(WIRELESS_SUMMARY_ID)
            return
        }
        main.post {
            pending.remove(group)?.let(main::removeCallbacks)
            val update = Runnable {
                pending.remove(group)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    runCatching { updateSummary(app, group) }
                        .onFailure { Log.w("无法更新通知分组", it) }
                }
            }
            pending[group] = update
            main.postDelayed(update, 150)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updateSummary(context: Context, group: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val summaryId = if (group == WIRELESS) WIRELESS_SUMMARY_ID else RUNTIME_SUMMARY_ID
        if (group == WIRELESS) {
            manager.cancel(summaryId)
            return
        }
        val children = manager.activeNotifications.filter {
            it.notification.group == group && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
        }.sortedBy { it.id }
        if (children.size < 2) {
            manager.cancel(summaryId)
            return
        }
        val first = children.first().notification
        val title = if (group == WIRELESS) "无线调试" else "RoyTools运行服务"
        val lines = children.mapNotNull { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) }
        val style = Notification.InboxStyle().setBigContentTitle(title)
        lines.forEach { style.addLine(it) }
        val summary = Notification.Builder(context, first.channelId)
            .setSmallIcon(first.smallIcon)
            .setContentTitle(title)
            .setContentText(lines.joinToString(" · "))
            .setContentIntent(first.contentIntent)
            .setStyle(style)
            .setGroup(group)
            .setGroupSummary(true)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(children.any { it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0 })
            .build()
        manager.notify(summaryId, summary)
    }
}
