package com.mahmutalperenunal.adaptivehz.core.service
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.mahmutalperenunal.adaptivehz.core.engine.model.AdaptiveHzMode
object RefreshModeNotice {
    fun show(c:Context,mode:AdaptiveHzMode) {
        val nm=c.getSystemService(NotificationManager::class.java)
        if (!com.mahmutalperenunal.adaptivehz.core.prefs.AdaptiveHzPrefs.isControlsEnabled(c)) {
            nm.cancel(1012)
            return
        }
        nm.createNotificationChannel(NotificationChannel("refresh_mode_changes","刷新率切换",NotificationManager.IMPORTANCE_DEFAULT))
        val text=when(mode){AdaptiveHzMode.OFF->"关闭";AdaptiveHzMode.FORCE_MIN->"60Hz";AdaptiveHzMode.FORCE_MAX->"120Hz";else->"自适应刷新率"}
        try{nm.notify(1012,NotificationCompat.Builder(c,"refresh_mode_changes").setSmallIcon(com.mahmutalperenunal.adaptivehz.R.drawable.ic_qs_refresh_phone)
            .setContentTitle("刷新率控制").setContentText(text).setTimeoutAfter(3000).setAutoCancel(true).build())}catch(_:SecurityException){}
    }
}
