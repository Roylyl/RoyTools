package com.github.kr328.clash.forward

import android.os.Bundle
import androidx.core.widget.doAfterTextChanged
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.github.kr328.clash.R
import com.github.kr328.clash.design.util.resolveThemedColor
import kotlinx.coroutines.*
import org.json.JSONObject

class ForwardChatActivity: AppCompatActivity(), CoroutineScope by MainScope() {
    private var poll: Job? = null
    private lateinit var status: TextView
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private var displayed = -1
    private var historyRevision=ChatHistory.revision
    private var dark = false
    private var sending = false
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val filePicker=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()){uri->if(uri!=null)upload(listOf(uri),false)}
    private val gallery=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(10)){uris->if(uris.isNotEmpty())upload(uris,true)}
    private fun upload(uris:List<android.net.Uri>,media:Boolean) {
        if(sending)return
        val selected=uris.filter{it.scheme=="content"}.take(10)
        if(selected.isEmpty())return
        com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder(this).setTitle("发送附件")
            .setMessage("将所选的"+selected.size+"个附件发送到当前Telegram会话？")
            .setNegativeButton("取消",null).setPositiveButton("发送"){_,_->
                val p=TelegramForward.prefs(this)
                val token=p.getString("token", "").orEmpty();val chat=p.getString("chat", "").orEmpty()
                if(token.isBlank()||chat.isBlank()){toast("请先完成机器人配置");return@setPositiveButton}
                setSending(true)
                var remaining=selected
                launch {try{
                    for(uri in selected){
                        withContext(Dispatchers.IO){
                            val mime=contentResolver.getType(uri).orEmpty()
                            val kind=if(!media)"document" else if(mime.startsWith("video/"))"video" else "photo"
                            val message=ChatUpload.send(this@ForwardChatActivity,token,chat,uri,kind)
                            ChatHistory.append(this@ForwardChatActivity,message,false)
                        }
                        remaining=remaining.drop(1)
                        render(forceBottom=true)
                    }
                }catch(e:CancellationException){throw e}
                catch(e:Exception){
                    val reason=if(e is TelegramForward.ApiError)e.message.orEmpty() else if(e is IllegalArgumentException)"图片最大10MB，视频和文件最大50MB" else "请检查网络或文件，已发送的附件会保留"
                    com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder(this@ForwardChatActivity).setTitle("附件发送失败").setMessage(reason)
                        .setNegativeButton("取消",null).setPositiveButton("重试"){_,_->upload(remaining,media)}.show()
                }
                finally{setSending(false)}}
            }.show()
    }
    private fun attachmentMenu(anchor:View) {
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(6),dp(6),dp(6),dp(6));background=android.graphics.drawable.GradientDrawable().apply{setColor(if(dark)0xff2c2c2e.toInt() else -1);cornerRadius=dp(20).toFloat()}}
        val popup=PopupWindow(panel,dp(200),dp(116),true).apply{setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));elevation=dp(12).toFloat();isOutsideTouchable=true}
        fun item(text:String,action:()->Unit){panel.addView(TextView(this).apply{this.text=text;textSize=17f;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(16),0,dp(16),0);setOnClickListener{popup.dismiss();action()}},LinearLayout.LayoutParams(-1,dp(52)))}
        item("图片与视频"){gallery.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo))}
        item("文件"){filePicker.launch("*/*")}
        val position=IntArray(2);anchor.getLocationOnScreen(position)
        popup.showAtLocation(viewRoot(),android.view.Gravity.TOP or android.view.Gravity.LEFT,position[0],position[1]-dp(124))
    }
    private fun viewRoot():View=window.decorView
    @Suppress("DEPRECATION") private fun handleShare(source:android.content.Intent) {
        if(source.getBooleanExtra("show_chat_download",false)){source.removeExtra("show_chat_download");ChatFileDownloads.show(this)}
        if(source.action !in listOf(android.content.Intent.ACTION_SEND,android.content.Intent.ACTION_SEND_MULTIPLE))return
        val text=source.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT)?.toString().orEmpty().take(3900)
        if(text.isNotBlank())findViewById<EditText>(R.id.chat_input).setText(text)
        val uris=if(source.action==android.content.Intent.ACTION_SEND_MULTIPLE)source.getParcelableArrayListExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM).orEmpty()
            else listOfNotNull(source.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM))
        if(uris.isNotEmpty())upload(uris,source.type?.let{it.startsWith("image/")||it.startsWith("video/")}==true)
        source.action=null
    }
    override fun onNewIntent(source:android.content.Intent){super.onNewIntent(source);setIntent(source);handleShare(source)}
    private fun setSending(value: Boolean) {
        sending=value
        findViewById<View>(R.id.chat_send).apply {val ready=!value && !this@ForwardChatActivity.findViewById<EditText>(R.id.chat_input).text.isNullOrBlank();isEnabled=ready;alpha=if(ready)1f else 0.35f;contentDescription=if(value)"发送中" else "发送"}
        findViewById<View>(R.id.chat_add).isEnabled=!value
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val mode = com.github.kr328.clash.design.store.UiStore(this).darkMode
        dark = mode == com.github.kr328.clash.design.model.DarkMode.ForceDark ||
            mode == com.github.kr328.clash.design.model.DarkMode.Auto && resources.configuration.uiMode and 48 == 32
        theme.applyStyle(if(dark) com.github.kr328.clash.design.R.style.AppThemeDark else com.github.kr328.clash.design.R.style.AppThemeLight, true)
        setContentView(R.layout.roytools_forward_chat)
        com.github.kr328.clash.common.ui.TouchFeedback.install(window)
        overridePendingTransition(com.github.kr328.clash.design.R.anim.roytools_push_in, com.github.kr328.clash.design.R.anim.roytools_push_out)
        status = findViewById(R.id.chat_status); messages = findViewById(R.id.chat_messages); scroll = findViewById(R.id.chat_scroll)
        findViewById<View>(R.id.chat_back).setOnClickListener { finish() }
        val input = findViewById<EditText>(R.id.chat_input)
        val send = findViewById<ImageButton>(R.id.chat_send)
        fun shape(color:Int, stroke:Int?=null) = android.graphics.drawable.GradientDrawable().apply { setColor(color);cornerRadius=dp(24).toFloat();if(stroke!=null)setStroke(dp(1),stroke) }
        findViewById<View>(R.id.chat_composer).background=shape(if(dark)0xff1c1c1e.toInt() else 0xffffffff.toInt(), if(dark)0xff38383a.toInt() else 0xffd1d1d6.toInt())
        send.background=shape(0xff007aff.toInt())
        findViewById<View>(R.id.chat_add).apply {
            background=shape(if(dark)0xff2c2c2e.toInt() else 0xffe5e5ea.toInt())
            setOnClickListener {
                attachmentMenu(this)
            }
        }
        input.doAfterTextChanged { input.error=null; setSending(sending) }
        setSending(false)
        send.setOnClickListener {
            val text = input.text.toString()
            if(text.isBlank()) return@setOnClickListener
            val p = TelegramForward.prefs(this)
            val token = p.getString("token", "").orEmpty(); val chat = p.getString("chat", "").orEmpty()
            if(token.isBlank() || chat.isBlank()) { toast("请先在转发设置中配置机器人和Chat ID"); return@setOnClickListener }
            setSending(true)
            launch {
                try {
                    withContext(Dispatchers.IO) {
                        val result = TelegramForward.call(token, "sendMessage", JSONObject().put("chat_id", chat).put("text", text))
                        ChatHistory.append(this@ForwardChatActivity, result.getJSONObject("result"), false, text)
                    }
                    if(input.text.toString() == text) input.text.clear()
                    render(forceBottom=true)
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) { toast(if(e is TelegramForward.ApiError) e.message.orEmpty() else "发送失败，请检查网络") }
                finally { setSending(false) }
            }
        }
        if(state == null)handleShare(intent)
    }
    override fun onStart() {
        super.onStart()
        ChatReceiver.visible=true
        ChatReceiver.start(this)
        poll=launch {while(isActive){status.text="连接失败";status.setTextColor(0xffff3b30.toInt());status.visibility=if(ChatReceiver.failed)View.VISIBLE else View.GONE;render();delay(500)}}
    }
    private suspend fun render(forceBottom: Boolean = false) {
        val records = withContext(Dispatchers.IO) { ChatHistory.read(this@ForwardChatActivity) }
        if(historyRevision!=ChatHistory.revision || records.size<displayed){displayed=-1;messages.removeAllViews();historyRevision=ChatHistory.revision}
        if(records.size == displayed) {if(forceBottom)scroll.post {scroll.fullScroll(View.FOCUS_DOWN)};return}
        val nearBottom = scroll.getChildAt(0).height - scroll.scrollY - scroll.height < 100
        val first = displayed < 0
        val previous=displayed.coerceAtLeast(0)
        if(previous==0)messages.removeAllViews()
        if(records.isEmpty()) messages.addView(TextView(this).apply {text="发送一条消息开始聊天";gravity=android.view.Gravity.CENTER;setTextColor(0xff8e8e93.toInt());setPadding(0,dp(24),0,0)})
        records.drop(previous).forEachIndexed { offset, record ->
            val index=previous+offset
            if(index==0 || record.optLong("time")-records[index-1].optLong("time")>300000) {
                messages.addView(TextView(this).apply {text=android.text.format.DateFormat.format("MM-dd HH:mm",record.optLong("time"));textSize=11f;gravity=android.view.Gravity.CENTER;setTextColor(0xff8e8e93.toInt());setPadding(0,dp(14),0,dp(8))})
            }
            val incoming=record.optBoolean("incoming")
            val row=LinearLayout(this).apply {gravity=if(incoming)android.view.Gravity.START else android.view.Gravity.END}
            val bubble=TextView(this).apply {
                text=record.optString("text");textSize=17f;setTextIsSelectable(true)
                maxWidth=(resources.displayMetrics.widthPixels*0.76).toInt()
                setPadding(dp(if(incoming)20 else 14),dp(10),dp(if(incoming)14 else 20),dp(10))
                setTextColor(if(!incoming||dark)android.graphics.Color.WHITE else android.graphics.Color.BLACK)
                background=ChatBubbleDrawable(if(!incoming)0xff007aff.toInt() else if(dark)0xff2c2c2e.toInt() else 0xffe9e9eb.toInt(),!incoming,resources.displayMetrics.density)
            }
            if(record.optString("media_type").isNotBlank())row.addView(mediaBubble(record,incoming)) else row.addView(bubble)
            messages.addView(row,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(3),0,dp(3))})
            if(!first) {row.alpha=0f;row.translationY=dp(16).toFloat();row.scaleX=0.94f;row.scaleY=0.94f;row.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(220).start()}
        }
        displayed=records.size
        if(forceBottom || first || nearBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
    private fun documentBubble(record:JSONObject,incoming:Boolean):View {
        val frame=FrameLayout(this).apply{layoutParams=LinearLayout.LayoutParams(minOf((resources.displayMetrics.widthPixels*0.76).toInt(),dp(300)),dp(84));background=ChatBubbleDrawable(if(incoming)if(dark)0xff2c2c2e.toInt() else 0xffe9e9eb.toInt() else 0xff007aff.toInt(),!incoming,resources.displayMetrics.density)}
        val label=TextView(this).apply {
            val size=record.optLong("file_size")
            text=record.optString("file_name","文件")+if(size>0)"\n"+String.format(java.util.Locale.ROOT,"%.2fMB",size/1048576.0) else ""
            maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END;textSize=15f;gravity=android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16),dp(8),dp(60),dp(8));setTextColor(if(incoming&&!dark)android.graphics.Color.BLACK else -1)
        }
        frame.addView(label,FrameLayout.LayoutParams(-1,-1))
        val action=ImageButton(this).apply{setBackgroundColor(android.graphics.Color.TRANSPARENT);imageTintList=android.content.res.ColorStateList.valueOf(if(incoming)0xff007aff.toInt() else -1);setPadding(dp(12),dp(12),dp(12),dp(12))}
        frame.addView(action,FrameLayout.LayoutParams(dp(48),dp(48),android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL).apply{marginEnd=dp(10)})
        fun downloaded()=ChatFileDownloads.file(this,record).isFile
        fun open(){
            if(!downloaded())return
            try{
                val uri=androidx.core.content.FileProvider.getUriForFile(this,packageName+".update-files",ChatFileDownloads.file(this,record))
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri,record.optString("mime","application/octet-stream")).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }catch(_:Exception){toast("未找到可打开此文件的应用")}
        }
        action.setOnClickListener{if(downloaded())open() else ChatFileDownloads.begin(this,record)}
        frame.setOnClickListener{if(downloaded())open() else toast("点击右侧下载按钮保存文件")}
        launch{ChatFileDownloads.state.collect{
            action.setImageResource(if(downloaded())com.github.kr328.clash.design.R.drawable.roytools_back else R.drawable.chat_download)
            action.rotation=if(downloaded())180f else 0f
            action.contentDescription=if(downloaded())"打开文件" else if(it.active&&it.id==record.optString("file_id"))"查看下载进度" else "下载文件"
        }}
        return frame
    }
    private fun mediaBubble(record:JSONObject,incoming:Boolean):View {
        val type=record.optString("media_type")
        if(type=="document")return documentBubble(record,incoming)
        val frame=FrameLayout(this).apply{layoutParams=LinearLayout.LayoutParams(dp(232),dp(if(type=="document")80 else 220));background=android.graphics.drawable.GradientDrawable().apply{setColor(if(incoming)if(dark)0xff2c2c2e.toInt() else 0xffe9e9eb.toInt() else 0xff007aff.toInt());cornerRadius=dp(18).toFloat()};clipToOutline=true;outlineProvider=android.view.ViewOutlineProvider.BACKGROUND}
        fun fitMedia(width:Int,height:Int) {
            if(type=="document")return
            val ratio=if(width>0&&height>0)(width.toFloat()/height).coerceIn(0.55f,2.8f) else 4f/3f
            val maxWidth=minOf((resources.displayMetrics.widthPixels*0.72f).toInt(),dp(300))
            val maxHeight=minOf((resources.displayMetrics.heightPixels*0.42f).toInt(),dp(360))
            val w:Int;val h:Int
            if(ratio>=1f){w=maxWidth;h=(w/ratio).toInt()}else{h=minOf(maxHeight,(maxWidth/ratio).toInt());w=(h*ratio).toInt()}
            frame.layoutParams=LinearLayout.LayoutParams(w,h)
        }
        fitMedia(record.optInt("width"),record.optInt("height"))
        val image=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP;contentDescription=if(type=="video")"视频预览" else "图片预览"}
        val loading=ProgressBar(this).apply{contentDescription="媒体加载中"}
        val retry=TextView(this).apply{text="加载失败\n点击重试";gravity=android.view.Gravity.CENTER;setTextColor(0xff8e8e93.toInt());visibility=View.GONE}
        if(type!="document"){frame.addView(image,FrameLayout.LayoutParams(-1,-1));frame.addView(loading,FrameLayout.LayoutParams(dp(32),dp(32),android.view.Gravity.CENTER))}
        else frame.addView(TextView(this).apply{text="文件 · "+record.optString("file_name","附件");setTextColor(if(incoming&&!dark)android.graphics.Color.BLACK else -1);textSize=16f;gravity=android.view.Gravity.CENTER;setPadding(dp(12),0,dp(12),0)},FrameLayout.LayoutParams(-1,-1))
        frame.addView(retry,FrameLayout.LayoutParams(-1,-1))
        val previewId=if(type=="video")record.optString("thumb_id") else record.optString("file_id")
        if(type=="video")frame.addView(TextView(this).apply{text="▶";textSize=32f;setTextColor(-1);gravity=android.view.Gravity.CENTER},FrameLayout.LayoutParams(-1,-1))
        fun load(){
            loading.visibility=View.VISIBLE;retry.visibility=View.GONE
            launch{try{
                if(previewId.isBlank()){loading.visibility=View.GONE;return@launch}
                val file=ChatMediaCache.get(this@ForwardChatActivity,previewId)
                val bitmap=withContext(Dispatchers.IO){ChatMediaCache.bitmap(file)}?:error("decode")
                if(record.optInt("width")<=0)fitMedia(bitmap.width,bitmap.height)
                image.setImageBitmap(bitmap);loading.visibility=View.GONE;retry.visibility=View.GONE
            }catch(e:CancellationException){throw e}catch(_:Exception){loading.visibility=View.GONE;retry.visibility=View.VISIBLE}}
        }
        retry.setOnClickListener{load()}
        if(type!="document")load()
        frame.setOnClickListener {
            if(type!="document")startActivity(android.content.Intent(this,ChatMediaActivity::class.java).putExtra("file_id",record.optString("file_id")).putExtra("kind",type))
            else launch{try{
                val file=ChatMediaCache.get(this@ForwardChatActivity,record.optString("file_id"))
                val uri=androidx.core.content.FileProvider.getUriForFile(this@ForwardChatActivity,packageName+".update-files",file)
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri,record.optString("mime","application/octet-stream")).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }catch(e:CancellationException){throw e}catch(_:Exception){toast("无法打开文件，请确认已安装相应查看器")}}
        }
        return frame
    }
    private fun toast(text: String) { Toast.makeText(this,text,Toast.LENGTH_SHORT).show() }
    override fun onStop() { ChatReceiver.visible=false; poll?.cancel(); poll = null; super.onStop() }
    override fun onDestroy() { cancel(); super.onDestroy() }
    override fun finish() { super.finish(); overridePendingTransition(com.github.kr328.clash.design.R.anim.roytools_pop_in, com.github.kr328.clash.design.R.anim.roytools_pop_out) }
}
