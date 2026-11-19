package com.github.kr328.clash

import android.content.*
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.constants.Permissions
import com.mahmutalperenunal.adaptivehz.OneSetup
import com.mahmutalperenunal.adaptivehz.core.service.StabilityForegroundService

object KeepAliveCoordinator {
    private val main=Handler(Looper.getMainLooper())
    private var started=false
    fun start(context:Context) {
        if(started)return
        started=true
        val app=context.applicationContext
        fun reconcile() {
            if(StabilityForegroundService.proxyForeground(app)) StabilityForegroundService.stop(app)
            else if(OneSetup.isKeepAliveEnabled(app)) runCatching{StabilityForegroundService.start(app)}
        }
        ContextCompat.registerReceiver(app,object:BroadcastReceiver(){
            override fun onReceive(c:Context,i:Intent){reconcile()}
        },IntentFilter().apply{addAction(Intents.ACTION_CLASH_STARTED);addAction(Intents.ACTION_CLASH_STOPPED)},
            Permissions.RECEIVE_SELF_BROADCASTS,null,ContextCompat.RECEIVER_NOT_EXPORTED)
        main.postDelayed({reconcile()},1000)
        main.postDelayed(object:Runnable{
            override fun run(){
                if(StabilityForegroundService.proxyForeground(app)&&OneSetup.isKeepAliveEnabled(app)){
                    StabilityForegroundService.stop(app)
                    OneSetup.recoverAccessibility(app)
                }
                com.github.kr328.clash.forward.ForwardRecovery.ensure(app)
                main.postDelayed(this,5000)
            }
        },5000)
    }
}
