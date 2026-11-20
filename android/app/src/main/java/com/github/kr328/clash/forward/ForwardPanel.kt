package com.github.kr328.clash.forward

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.*
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.work.WorkManager
import com.github.kr328.clash.R
import com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder
import com.github.kr328.clash.common.ui.TouchFeedback
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.*
import org.json.JSONObject

class ForwardPanel(private val activity: android.app.Activity) {
    val view = FrameLayout(activity)
    private val home = activity.layoutInflater.inflate(R.layout.roytools_forward_home, view, false)
    private val configuration = activity.layoutInflater.inflate(R.layout.roytools_forward, view, false)
    private var transition: AnimatorSet? = null
    private var navigationGeneration = 0L
    var isSecondary = false; private set
    var onNavigationChanged: ((Boolean) -> Unit)? = null
    fun goHome() { if (isSecondary) show(false) else (home as? ScrollView)?.scrollTo(0, 0) }
    private fun show(settings: Boolean) {
        if (isSecondary == settings) return
        isSecondary = settings
        navigationGeneration++
        val outgoing = if (settings) home else configuration
        val incoming = if (settings) configuration else home
        if (!settings) {
            outgoing.findFocus()?.clearFocus()
            WindowCompat.getInsetsController(activity.window, view).hide(WindowInsetsCompat.Type.ime())
        }
        transition?.removeAllListeners()
        transition?.cancel()
        transition = null
        outgoing.translationX = 0f
        incoming.translationX = 0f
        outgoing.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        incoming.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        view.removeAllViews()
        val width = view.width.toFloat()
        if (width > 0 && view.isShown && ValueAnimator.areAnimatorsEnabled()) {
            view.addView(outgoing)
            view.addView(incoming, if (settings) 1 else 0)
            incoming.translationX = if (settings) width else -width * 0.18f
            transition = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(incoming, View.TRANSLATION_X, 0f),
                    ObjectAnimator.ofFloat(outgoing, View.TRANSLATION_X, if (settings) -width * 0.18f else width),
                )
                duration = 220L
                interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.removeView(outgoing)
                        outgoing.translationX = 0f
                        transition = null
                    }
                })
                start()
            }
        } else view.addView(incoming)
        onNavigationChanged?.invoke(settings)
        refresh()
    }
    private val prefs = TelegramForward.prefs(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val token = configuration.findViewById<EditText>(R.id.forward_token)
    private val chat = configuration.findViewById<EditText>(R.id.forward_chat)
    private val tokenField = configuration.findViewById<TextInputLayout>(R.id.forward_token_field)
    private val chatField = configuration.findViewById<TextInputLayout>(R.id.forward_chat_field)
    private val enabled = home.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.forward_enabled)
    private val status = home.findViewById<TextView>(R.id.forward_status)
    private val tokenPattern = Regex("[0-9]+:[A-Za-z0-9_-]+")
    private val chatPattern = Regex("-?[0-9]+")
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
        token.doAfterTextChanged { tokenField.error = null }
        chat.doAfterTextChanged { chatField.error = null }
        enabled.setOnCheckedChangeListener { _, value ->
            if (!binding) {
                if (value && (!authorized() || !hasSavedConfiguration())) {
                    binding = true; enabled.isChecked = false; binding = false
                    toast("请先保存机器人配置并授予通知使用权")
                } else {
                    prefs.edit().putBoolean("enabled", value).apply()
                    if (!value) WorkManager.getInstance(activity).cancelAllWorkByTag("notification-forward")
                    refresh()
                }
            }
        }
        home.findViewById<View>(R.id.forward_toggle_row).setOnClickListener { enabled.toggle() }
        button(R.id.forward_clear_media) { confirmClear(true) }
        button(R.id.forward_clear_records) { confirmClear(false) }
        button(R.id.forward_permission) { activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        button(R.id.forward_save) { if(save()) toast("配置已保存") }
        button(R.id.forward_discover) { discover() }
        button(R.id.forward_test) {
            if (save()) {
                val savedToken = token.text.toString().trim()
                val savedChat = chat.text.toString().trim()
                request {
                    TelegramForward.call(savedToken, "sendMessage", JSONObject().put("chat_id", savedChat).put("text", "RoyTools通知转发测试成功。此后的Android通知将在这里接收。"))
                    val done: () -> Unit = { toast("测试消息已发送，请在iPhone查看") }; done
                }
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
    private fun hasSavedConfiguration(): Boolean =
        tokenPattern.matches(prefs.getString("token", "").orEmpty()) &&
            chatPattern.matches(prefs.getString("chat", "").orEmpty())

    private fun validatedToken(): String? {
        val value = token.text.toString().trim()
        if (!tokenPattern.matches(value)) {
            tokenField.error = "请输入有效Bot Token"
            if (isSecondary) token.requestFocus()
            return null
        }
        return value
    }

    private fun save(): Boolean {
        val t = validatedToken() ?: return false
        val c = chat.text.toString().trim()
        if (!chatPattern.matches(c)) {
            chatField.error = "请先获取或填写Chat ID"
            if (isSecondary) chat.requestFocus()
            return false
        }
        if (t != prefs.getString("token", "") || c != prefs.getString("chat", "")) {
            prefs.edit().remove("chat_offset").putString("token", t).putString("chat", c).putLong("generation", System.currentTimeMillis()).apply()
            WorkManager.getInstance(activity).cancelAllWorkByTag("notification-forward")
        }
        return true
    }
    private fun request(action: suspend () -> (() -> Unit)) {
        if(busy) return
        busy = true
        val generation = navigationGeneration
        scope.launch {
            try {
                val done = withContext(Dispatchers.IO) { action() }
                if (navigationGeneration == generation && configuration.isShown && !activity.isFinishing) done()
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (navigationGeneration == generation && configuration.isShown && !activity.isFinishing) {
                    toast(if(e is TelegramForward.ApiError) e.message.orEmpty() else "连接Telegram失败，请检查网络后重试")
                }
            }
            finally { busy = false }
        }
    }
    private fun discover() {
        val t = validatedToken() ?: return
        request {
            val updates = TelegramForward.call(t, "getUpdates", JSONObject().put("timeout", 0).put("allowed_updates", org.json.JSONArray().put("message"))).getJSONArray("result")
            val chats = linkedMapOf<String, String>()
            for(i in 0 until updates.length()) {
                val c = updates.getJSONObject(i).optJSONObject("message")?.optJSONObject("chat") ?: continue
                if(c.optString("type") != "private") continue
                chats[c.getString("id")] = c.optString("first_name", "接收会话") + " · " + c.getString("id")
            }
            val done: () -> Unit = showResult@{
                if (token.text.toString().trim() != t) return@showResult
                if(chats.isEmpty()) toast("请先向机器人发送一条新消息，再获取Chat ID")
                else RoyToolsDialogBuilder(activity).setTitle("选择接收会话")
                    .setItems(chats.values.toTypedArray()) { _, which ->
                        if (token.text.toString().trim() == t) {
                            chat.setText(chats.keys.elementAt(which))
                            if (save()) toast("接收会话已保存")
                        }
                    }.setNegativeButton("取消", null).show()
            }
            done
        }
    }
    fun destroy() {
        transition?.removeAllListeners()
        transition?.cancel()
        transition = null
        scope.cancel()
    }
}
