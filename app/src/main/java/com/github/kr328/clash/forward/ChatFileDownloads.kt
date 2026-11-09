package com.github.kr328.clash.forward

import android.app.*
import android.content.*
import android.os.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.github.kr328.clash.common.util.NotificationGroups
import com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object ChatFileDownloads {
    data class State(val id:String="",val name:String="",val active:Boolean=false,val bytes:Long=0,val total:Long=0,val speed:Double=0.0,val message:String=""){
        val percent:Int get()=if(!active&&message=="下载完成")100 else if(total>0)((bytes.toDouble()/total)*100).toInt().coerceIn(0,100) else 0
        fun detail()=String.format(Locale.ROOT,"%.2fMB/s · %.2fMB / %s",speed/1048576.0,bytes/1048576.0,if(total>0)String.format(Locale.ROOT,"%.2fMB · %d%%",total/1048576.0,percent) else "未知大小")
    }
    val state=MutableStateFlow(State())
    private var dialog:androidx.appcompat.app.AlertDialog?=null
    fun file(c:Context,record:JSONObject):File {
        val id=record.optString("file_id").filter{it.isLetterOrDigit()||it=='_'||it=='-'}
        require(id.isNotBlank())
        val name=record.optString("file_name","文件").replace(Regex("[\\\\/\\r\\n]"),"_").take(120).takeUnless{it=="."||it==".."||it.isBlank()} ?: "文件"
        return File(c.filesDir,"chat-downloads/$id/$name")
    }
    fun begin(a:Activity,record:JSONObject){
        if(!state.value.active){
            state.value=State(record.getString("file_id"),record.optString("file_name","文件"),true,message="正在连接…")
            try{ContextCompat.startForegroundService(a,Intent(a,ChatFileDownloadService::class.java).putExtra("record",record.toString()))}
            catch(_:RuntimeException){state.value=state.value.copy(active=false,message="无法启动下载，请重试")}
        }
        show(a)
    }
    fun show(a:Activity){
        if(a.isFinishing||a.isDestroyed||dialog?.isShowing==true)return
        val spacing=(20*a.resources.displayMetrics.density).toInt()
        val group=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;setPadding(spacing,spacing,spacing,spacing)}
        val name=TextView(a).apply{textSize=16f};val progress=ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal)
        val details=TextView(a).apply{textSize=14f;setPadding(0,spacing/2,0,0)}
        group.addView(name);group.addView(progress,LinearLayout.LayoutParams(-1,-2));group.addView(details)
        val window=RoyToolsDialogBuilder(a).setTitle("文件下载").setView(group).setCancelable(false)
            .setNegativeButton("取消下载"){_,_->a.startService(Intent(a,ChatFileDownloadService::class.java).setAction("cancel"))}
            .setNeutralButton("后台下载"){_,_->}
            .setPositiveButton("完成",null).create()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        window.setOnDismissListener{scope.cancel();if(dialog===window)dialog=null}
        window.setOwnerActivity(a)
        dialog=window;window.show()
        scope.launch{state.collect{s->
            name.text=s.name
            progress.isIndeterminate=s.active&&s.total<=0;progress.progress=s.percent
            details.text=s.message+"\n"+s.detail()
            window.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).isEnabled=s.active
            window.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).visibility=if(s.active)android.view.View.VISIBLE else android.view.View.GONE
            window.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).visibility=if(s.active)android.view.View.GONE else android.view.View.VISIBLE
        }}
    }
    fun dismiss(a:Activity){if(dialog?.ownerActivity===a)dialog?.dismiss()}
}

class ChatFileDownloadService:Service(){
    companion object {
        private var instance:ChatFileDownloadService?=null
        suspend fun cancelAndAwait(){
            val service=instance ?: return
            ChatFileDownloads.state.value=ChatFileDownloads.state.value.copy(active=false,message="已取消")
            service.connection?.disconnect()
            service.job?.cancelAndJoin()
            service.stopForeground(STOP_FOREGROUND_REMOVE);service.stopSelf()
            com.github.kr328.clash.common.util.NotificationGroups.refresh(service,com.github.kr328.clash.common.util.NotificationGroups.RUNTIME)
        }
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var job:Job?=null
    @Volatile private var connection:HttpURLConnection?=null
    override fun onBind(i:Intent?)=null
    private fun notification():android.app.Notification {
        val s=ChatFileDownloads.state.value
        val open=PendingIntent.getActivity(this,8130,Intent(this,ForwardChatActivity::class.java).putExtra("show_chat_download",true),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel=PendingIntent.getService(this,8131,Intent(this,ChatFileDownloadService::class.java).setAction("cancel"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder=NotificationCompat.Builder(this,"chat_file_downloads").setSmallIcon(android.R.drawable.stat_sys_download)
            .setGroup(NotificationGroups.RUNTIME).setContentTitle(s.name.ifBlank{"Chat文件下载"}).setContentText(if(s.active)s.detail() else s.message)
            .setContentIntent(open).setOngoing(s.active).setOnlyAlertOnce(true).setAutoCancel(!s.active).setSilent(true)
        if(s.active)builder.setProgress(100,s.percent,s.total<=0).addAction(0,"取消下载",cancel)
        return builder.build()
    }
    override fun onCreate(){super.onCreate();instance=this;getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("chat_file_downloads","Chat文件下载",NotificationManager.IMPORTANCE_LOW));startForeground(8130,notification())}
    override fun onStartCommand(i:Intent?,flags:Int,startId:Int):Int{
        if(i?.action=="cancel"){
            ChatFileDownloads.state.value=ChatFileDownloads.state.value.copy(active=false,message="已取消")
            connection?.disconnect();job?.cancel();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();NotificationGroups.refresh(this,NotificationGroups.RUNTIME)
            return START_NOT_STICKY
        }
        if(job?.isActive==true)return START_NOT_STICKY
        val record=runCatching{JSONObject(i?.getStringExtra("record").orEmpty())}.getOrNull()
        if(record==null){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY}
        val generation=ChatMediaCache.generation()
        val destination=ChatFileDownloads.file(this,record)
        job=scope.launch {
            var temporary:File?=null
            try{
                withContext(Dispatchers.IO){
                    val token=TelegramForward.prefs(this@ChatFileDownloadService).getString("token", "").orEmpty()
                    val meta=TelegramForward.call(token,"getFile",JSONObject().put("file_id",record.getString("file_id"))).getJSONObject("result")
                    currentCoroutineContext().ensureActive()
                    val path=meta.getString("file_path");require(path.matches(Regex("[A-Za-z0-9_./-]+"))&&!path.contains(".."))
                    val conn=URL("https://api.telegram.org/file/bot$token/$path").openConnection() as HttpURLConnection
                    connection=conn;conn.connectTimeout=15000;conn.readTimeout=30000;check(conn.responseCode in 200..299)
                    destination.parentFile?.mkdirs();temporary=File.createTempFile("download", ".part",destination.parentFile)
                    val total=conn.contentLengthLong.takeIf{it>0} ?: meta.optLong("file_size")
                    var bytes=0L;var previousBytes=0L;var previousAt=SystemClock.elapsedRealtime()
                    conn.inputStream.use{input->temporary!!.outputStream().use{out->val buffer=ByteArray(16384)
                        while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n);bytes+=n
                            val now=SystemClock.elapsedRealtime()
                            if(now-previousAt>=300){val speed=(bytes-previousBytes)*1000.0/(now-previousAt);previousAt=now;previousBytes=bytes
                                withContext(Dispatchers.Main){ChatFileDownloads.state.value=ChatFileDownloads.state.value.copy(bytes=bytes,total=total,speed=speed,message="正在下载");publish()}
                            }
                        }
                    }}
                    currentCoroutineContext().ensureActive()
                    check(total<=0||bytes==total){"下载未完成"};ChatMediaCache.commit(generation){check(temporary!!.renameTo(destination))}
                    withContext(Dispatchers.Main){ChatFileDownloads.state.value=ChatFileDownloads.state.value.copy(bytes=bytes,total=bytes,active=false,speed=0.0,message="下载完成")}
                }
            }catch(e:CancellationException){throw e}
            catch(e:Exception){ChatFileDownloads.state.value=ChatFileDownloads.state.value.copy(active=false,speed=0.0,message=if(e is TelegramForward.ApiError)e.message.orEmpty() else "下载失败，请重试")}
            finally{
                connection?.disconnect();connection=null;temporary?.delete()
                if(ChatFileDownloads.state.value.message!="已取消"){stopForeground(STOP_FOREGROUND_DETACH);publish()}
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun publish(){getSystemService(NotificationManager::class.java).notify(8130,notification());NotificationGroups.refresh(this,NotificationGroups.RUNTIME)}
    override fun onDestroy(){if(instance===this)instance=null;connection?.disconnect();scope.cancel();super.onDestroy()}
}
