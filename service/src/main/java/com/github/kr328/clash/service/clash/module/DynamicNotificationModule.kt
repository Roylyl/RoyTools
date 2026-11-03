package com.github.kr328.clash.service.clash.module

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.core.util.trafficDownloadRate
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.core.util.trafficUpload
import com.github.kr328.clash.core.util.trafficUploadRate
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.util.concurrent.TimeUnit

class DynamicNotificationModule(service: Service) : Module<Unit>(service) {
    private val builder = NotificationCompat.Builder(service, StaticNotificationModule.CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_logo_service)
        .setOngoing(true)
        .setColor(service.getColorCompat(R.color.color_clash))
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setSubText("RoyTools")
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(
            PendingIntent.getActivity(
                service,
                R.id.nf_clash_status,
                Intent().setComponent(Components.MAIN_ACTIVITY)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )
        )

    private val notificationManager = NotificationManagerCompat.from(service)
    private val store = ServiceStore(service)

    private fun update(showTraffic: Boolean) {
        val profile = StatusProvider.currentProfile
        builder.setContentTitle(profile?.let { "代理运行中·$it" } ?: "代理正在加载")
        if (showTraffic) {
            val now = Clash.queryTrafficNow()
            val total = Clash.queryTrafficTotal()
            val uploading = now.trafficUploadRate()
            val downloading = now.trafficDownloadRate()
            val uploaded = total.trafficUpload().replace(" ", "")
            val downloaded = total.trafficDownload().replace(" ", "")
            val allTraffic = total.trafficTotal().replace(" ", "")
            builder.setContentText("↑$uploading↓$downloading")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "上传速率：$uploading\n下载速率：$downloading\n" +
                        "累计上传：$uploaded\n累计下载：$downloaded\n" +
                        "累计流量：$allTraffic\n配置：${profile ?: "正在加载"}"
                ))
        } else {
            // Clearing the style also removes a previously expanded traffic snapshot.
            builder.setContentText("代理服务运行中").setStyle(null)
        }
        notificationManager.notify(R.id.nf_clash_status, builder.build())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun run() = coroutineScope {
        var shouldUpdate = service.getSystemService<PowerManager>()?.isInteractive ?: true
        var showTraffic = store.dynamicNotification

        val screenToggle = receiveBroadcast(false, Channel.CONFLATED) {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        val profileLoaded = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_PROFILE_LOADED)
        }

        val notificationChanged = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_NOTIFICATION_CHANGED)
        }

        update(showTraffic)

        while (true) {
            select<Unit> {
                screenToggle.onReceive {
                    when (it.action) {
                        Intent.ACTION_SCREEN_ON -> {
                            shouldUpdate = true
                            showTraffic = store.dynamicNotification
                            update(showTraffic)
                        }
                        Intent.ACTION_SCREEN_OFF ->
                            shouldUpdate = false
                    }
                }
                profileLoaded.onReceive {
                    showTraffic = store.dynamicNotification
                    update(showTraffic)
                }
                notificationChanged.onReceive {
                    showTraffic = store.dynamicNotification
                    update(showTraffic)
                }
                if (shouldUpdate && showTraffic) {
                    onTimeout(TimeUnit.SECONDS.toMillis(1)) {
                        update(showTraffic)
                    }
                }
            }
        }
    }
}
