package com.github.kr328.clash.forward

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

object ForwardRecovery {
    @Volatile var connected=false
    private var requestedAt:Long?=null
    @Synchronized fun ensure(context:Context){
        val p=TelegramForward.prefs(context)
        if(!p.getBoolean("enabled",false)||!NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))return
        ChatReceiver.start(context)
        if(connected)return
        val now=SystemClock.elapsedRealtime()
        if(requestedAt?.let{now-it<30000}==true)return
        requestedAt=now
        runCatching{NotificationListenerService.requestRebind(ComponentName(context,ForwardListener::class.java))}
    }
}
