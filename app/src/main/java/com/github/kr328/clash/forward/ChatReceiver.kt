package com.github.kr328.clash.forward

import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject

object ChatReceiver {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var job: Job?=null
    @Volatile var connection="连接中…";private set
    @Volatile var failed=false;private set
    @Volatile var visible=false
    @Synchronized fun start(context: Context) {
        if(job?.isActive==true)return
        val app=context.applicationContext
        job=scope.launch {
            var connectedToken=""
            while(isActive) {
                val p=TelegramForward.prefs(app)
                val token=p.getString("token", "").orEmpty();val chat=p.getString("chat", "").orEmpty()
                if(token.isBlank()||chat.isBlank()){failed=false;connection="请先配置机器人和Chat ID";delay(2000);continue}
                try {
                    if(connectedToken!=token) {
                        connection="连接中…"
                        TelegramForward.call(token,"getMe",JSONObject())
                        connectedToken=token;failed=false;connection="已连接"
                    }
                    val updates=TelegramForward.call(token,"getUpdates",JSONObject().put("offset",p.getLong("chat_offset",0)).put("timeout",10).put("allowed_updates",org.json.JSONArray().put("message"))).getJSONArray("result")
                    for(i in 0 until updates.length()) {
                        val update=updates.getJSONObject(i);val message=update.optJSONObject("message")
                        if(p.getString("token", "")!=token||p.getString("chat", "")!=chat)break
                        if(message?.optJSONObject("chat")?.optString("id")==chat) {
                            val fresh=ChatHistory.append(app,message,true)
                            if(fresh && message.optLong("date")*1000>System.currentTimeMillis()-60000) notify(app,message)
                        }
                        p.edit().putLong("chat_offset",update.getLong("update_id")+1).apply()
                    }
                    failed=false;connection="已连接"
                }catch(e:CancellationException){throw e}
                catch(e:Exception){failed=true;connectedToken="";connection=if(e is TelegramForward.ApiError)e.message.orEmpty() else "连接失败，正在重试";delay(5000)}
                delay(300)
            }
        }
    }
    private fun notify(c:Context,m:JSONObject) {
        val nm=c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("telegram_chat_messages","Chat消息",NotificationManager.IMPORTANCE_HIGH))
        val text=m.optString("text").ifBlank{m.optString("caption").ifBlank{if(m.has("photo"))"[图片]" else "[文件]"}}
        val intent=PendingIntent.getActivity(c,8101,Intent(c,ForwardChatActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(c,"telegram_chat_messages")
            .setSmallIcon(com.github.kr328.clash.design.R.drawable.roytools_nav_forward)
            .setContentTitle("Chat · iPhone新消息").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(visible).build()
        val chat = m.optJSONObject("chat")?.optString("id").orEmpty()
        val messageId = m.optLong("message_id").toInt()
        try{nm.notify("telegram-chat-$chat",messageId,notification)}catch(_:SecurityException){}
    }
}
