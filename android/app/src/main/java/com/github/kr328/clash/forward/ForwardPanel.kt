package com.github.kr328.clash.forward

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.widget.*
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import com.github.kr328.clash.R
import com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder
import com.github.kr328.clash.common.ui.TouchFeedback
import kotlinx.coroutines.*
import org.json.JSONObject

class ForwardPanel(private val activity: android.app.Activity) {
    val view = FrameLayout(activity)
    private val home = activity.layoutInflater.inflate(R.layout.roytools_forward_home, view, false)
    private val configuration = activity.layoutInflater.inflate(R.layout.roytools_forward, view, false)
    var isSecondary = false; private set
    var onNavigationChanged: ((Boolean) -> Unit)? = null
    fun goHome() { if (isSecondary) show(false) else (home as? ScrollView)?.scrollTo(0, 0) }
    private fun show(settings: Boolean) {
        isSecondary = settings
        view.removeAllViews()
        val page = if(settings) configuration else home
        view.addView(page)
        page.startAnimation(android.view.animation.AnimationUtils.loadAnimation(activity,
            if(settings) com.github.kr328.clash.design.R.anim.roytools_push_in else com.github.kr328.clash.design.R.anim.roytools_pop_in))
        onNavigationChanged?.invoke(settings)
        refresh()
    }
    private val prefs = TelegramForward.prefs(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val token = configuration.findViewById<EditText>(R.id.forward_token)
    private val chat = configuration.findViewById<EditText>(R.id.forward_chat)
    private val enabled = home.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.forward_enabled)
    private val status = home.findViewById<TextView>(R.id.forward_status)
    private var binding = false
    private var busy = false
    private var statsJob:Job?=null
    private var statsEpoch=0L
    init {
        configuration.addOnAttachStateChangeListener(object:android.view.View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(v:android.view.View){
                statsJob?.cancel()
                statsJob=scope.launch{while(isActive){refreshCounts();delay(1000)}}
            }
            override fun onViewDetachedFromWindow(v:android.view.View){statsJob?.cancel();statsJob=null}
        })
        view.addView(home)
        home.findViewById<android.view.View>(R.id.forward_chat_open).setOnClickListener { activity.startActivity(Intent(activity, ForwardChatActivity::class.java)) }
        home.findViewById<android.view.View>(R.id.forward_settings).setOnClickListener { show(true) }
        token.setText(prefs.getString("token", ""))
        chat.setText(prefs.getString("chat", ""))
        enabled.setOnCheckedChangeListener { _, value ->
            if (!binding) {
                if (value && (!authorized() || !save())) {
                    binding = true; enabled.isChecked = false; binding = false
                    toast("请先保存机器人配置并授予通知使用权")
                } else {
                    prefs.edit().putBoolean("enabled", value).apply()
                    if (!value) WorkManager.getInstance(activity).cancelAllWorkByTag("notification-forward")
                    refresh()
                }
            }
        }
        button(R.id.forward_clear_media) { confirmClear(true) }
        button(R.id.forward_clear_records) { confirmClear(false) }
        button(R.id.forward_permission) { activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        button(R.id.forward_save) { if(save()) toast("配置已保存") }
        button(R.id.forward_discover) { discover() }
        button(R.id.forward_test) {
            if (save()) request {
                TelegramForward.call(token.text.toString().trim(), "sendMessage", JSONObject().put("chat_id", chat.text.toString().trim()).put("text", "RoyTools通知转发测试成功。此后的Android通知将在这里接收。"))
                val done: () -> Unit = { toast("测试消息已发送，请在iPhone查看") }; done
            }
        }
        refresh()
    }
    private suspend fun refreshCounts(force:Boolean=false){
        if(busy&&!force)return
        val epoch=statsEpoch
        try{
            val stats=withContext(Dispatchers.IO){ForwardStorageStats.read(activity.applicationContext)}
            if(epoch!=statsEpoch)return
            configuration.findViewById<TextView>(R.id.forward_media_size).text=ForwardStorageStats.formatBytes(stats.first)
            configuration.findViewById<TextView>(R.id.forward_record_count).text=stats.second.toString()+"条"
        }catch(e:CancellationException){throw e}
        catch(_:Exception){
            if(epoch==statsEpoch){configuration.findViewById<TextView>(R.id.forward_media_size).text="—";configuration.findViewById<TextView>(R.id.forward_record_count).text="—"}
        }
    }
    private fun confirmClear(media:Boolean) {
        val title=if(media)"清除所有转发媒体" else "清除所有转发记录"
        val description=if(media)"删除本机所有Chat图片、视频缓存及已下载文件，并取消正在进行的文件下载。聊天记录和机器人配置保留。Telegram中的文件不受影响。"
            else "删除本机所有Chat会话的聊天及转发记录，清除对应消息通知。机器人配置、媒体文件和Telegram中的消息保留。此操作无法撤销。"
        RoyToolsDialogBuilder(activity).setTitle(title).setMessage(description).setNegativeButton("取消",null)
            .setPositiveButton("确认清除"){_,_->
                if(busy)return@setPositiveButton
                busy=true
                statsEpoch++
                val buttons=listOf(R.id.forward_clear_media,R.id.forward_clear_records).map{configuration.findViewById<android.view.View>(it)}
                buttons.forEach{it.isEnabled=false}
                scope.launch {
                    try {
                        if(media)ChatFileDownloadService.cancelAndAwait()
                        withContext(Dispatchers.IO){
                            if(media)ChatMediaCache.clearAll(activity.applicationContext) else ChatHistory.clearAll(activity.applicationContext)
                        }
                        val manager=activity.getSystemService(android.app.NotificationManager::class.java)
                        if(media){
                            ChatFileDownloads.state.value=ChatFileDownloads.State()
                            manager.cancel(8130)
                            com.github.kr328.clash.common.util.NotificationGroups.refresh(activity,com.github.kr328.clash.common.util.NotificationGroups.RUNTIME)
                        }else{
                            manager.activeNotifications.filter{it.tag?.startsWith("telegram-chat-")==true||it.id==8101}.forEach{manager.cancel(it.tag,it.id)}
                        }
                        refreshCounts(force=true)
                        toast(if(media)"所有转发媒体已清除" else "所有转发记录已清除")
                    }catch(e:CancellationException){throw e}
                    catch(_:Exception){toast("未能全部清除，请重试")}
                    finally{busy=false;buttons.forEach{it.isEnabled=true}}
                }
            }.show()
    }
    private fun button(id: Int, action: () -> Unit) { configuration.findViewById<android.view.View>(id).setOnClickListener { TouchFeedback.step(it); action() } }
    private fun toast(message: String) { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show() }
    private fun authorized() = NotificationManagerCompat.getEnabledListenerPackages(activity).contains(activity.packageName)
    fun refresh() {
        binding = true; enabled.isChecked = prefs.getBoolean("enabled", false); binding = false
        status.text = when {
            !authorized() -> "待授权 · 请允许通知使用权"
            !prefs.getBoolean("enabled", false) -> "已关闭 · 开启后转发新通知"
            else -> "已开启 · " + prefs.getString("status", "等待新通知")
        }
        ForwardRecovery.ensure(activity)
    }
    private fun save(requireChat: Boolean = true): Boolean {
        val t = token.text.toString().trim(); val c = chat.text.toString().trim()
        if (!t.matches(Regex("[0-9]+:[A-Za-z0-9_-]+"))) { token.error = "请输入有效Bot Token"; return false }
        if (requireChat && !c.matches(Regex("-?[0-9]+"))) { chat.error = "请先获取或填写Chat ID"; return false }
        if (t != prefs.getString("token", "") || c != prefs.getString("chat", "")) {
            prefs.edit().remove("chat_offset").putString("token", t).putString("chat", c).putLong("generation", System.currentTimeMillis()).apply()
            WorkManager.getInstance(activity).cancelAllWorkByTag("notification-forward")
        }
        return true
    }
    private fun request(action: suspend () -> (() -> Unit)) {
        if(busy) return
        busy = true
        scope.launch {
            try { val done = withContext(Dispatchers.IO) { action() }; if (!activity.isFinishing) done() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(if(e is TelegramForward.ApiError) e.message.orEmpty() else "连接Telegram失败，请检查网络后重试") }
            finally { busy = false }
        }
    }
    private fun discover() {
        if(!save(false)) return
        val t = token.text.toString().trim()
        request {
            val updates = TelegramForward.call(t, "getUpdates", JSONObject().put("timeout", 0).put("allowed_updates", org.json.JSONArray().put("message"))).getJSONArray("result")
            val chats = linkedMapOf<String, String>()
            for(i in 0 until updates.length()) {
                val c = updates.getJSONObject(i).optJSONObject("message")?.optJSONObject("chat") ?: continue
                if(c.optString("type") != "private") continue
                chats[c.getString("id")] = c.optString("first_name", "接收会话") + " · " + c.getString("id")
            }
            val done: () -> Unit = {
                if(chats.isEmpty()) toast("请先向机器人发送一条新消息，再获取Chat ID")
                else RoyToolsDialogBuilder(activity).setTitle("选择接收会话")
                    .setItems(chats.values.toTypedArray()) { _, which ->
                        chat.setText(chats.keys.elementAt(which)); save(); toast("接收会话已保存")
                    }.setNegativeButton("取消", null).show()
            }
            done
        }
    }
    fun destroy() { scope.cancel() }
}
