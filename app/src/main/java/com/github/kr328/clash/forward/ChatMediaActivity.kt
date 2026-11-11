package com.github.kr328.clash.forward

import android.os.Bundle
import android.graphics.Matrix
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

class ChatMediaActivity:AppCompatActivity(),CoroutineScope by MainScope(){
    private var videoPlayer:VideoView?=null
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val root=FrameLayout(this).apply{setBackgroundColor(android.graphics.Color.BLACK)}
        val progress=ProgressBar(this)
        root.addView(progress,FrameLayout.LayoutParams(90,90,Gravity.CENTER));setContentView(root)
        val density=resources.displayMetrics.density
        val back=ImageButton(this).apply{
            setImageResource(com.github.kr328.clash.design.R.drawable.roytools_back)
            imageTintList=android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            contentDescription="返回"
            setOnClickListener{finish()}
        }
        root.addView(back,FrameLayout.LayoutParams((48*density).toInt(),(48*density).toInt(),Gravity.TOP or Gravity.START).apply{
            setMargins((16*density).toInt(),(8*density).toInt(),0,0)
        })
        if (state == null) overridePendingTransition(com.github.kr328.clash.design.R.anim.roytools_push_in,com.github.kr328.clash.design.R.anim.roytools_push_out)
        launch {try{
            val file=ChatMediaCache.get(this@ChatMediaActivity,intent.getStringExtra("file_id").orEmpty())
            progress.visibility=View.GONE
            if(intent.getStringExtra("kind")=="video"){
                val video=VideoView(this@ChatMediaActivity);videoPlayer=video;root.addView(video,0,FrameLayout.LayoutParams(-1,-1));video.setVideoPath(file.path)
                video.setMediaController(MediaController(this@ChatMediaActivity).apply{setAnchorView(video)});video.setOnPreparedListener{video.start()}
            }else{
                val bitmap=withContext(Dispatchers.IO){ChatMediaCache.bitmap(file)} ?: error("无法读取图片")
                val image=ZoomImage(this@ChatMediaActivity);image.setImageBitmap(bitmap);root.addView(image,0,FrameLayout.LayoutParams(-1,-1))
            }
        }catch(e:CancellationException){throw e}catch(_:Exception){progress.visibility=View.GONE;Toast.makeText(this@ChatMediaActivity,"媒体加载失败，请返回重试",Toast.LENGTH_LONG).show()}}
    }
    override fun onPause(){videoPlayer?.pause();super.onPause()}
    override fun onDestroy(){videoPlayer?.stopPlayback();cancel();super.onDestroy()}
    override fun finish(){super.finish();overridePendingTransition(com.github.kr328.clash.design.R.anim.roytools_pop_in,com.github.kr328.clash.design.R.anim.roytools_pop_out)}
}
private class ZoomImage(c:android.content.Context):androidx.appcompat.widget.AppCompatImageView(c){
    private var zoom=1f;private var x=0f;private var y=0f
    private val scaling=ScaleGestureDetector(c,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
        override fun onScale(d:ScaleGestureDetector):Boolean{zoom=(zoom*d.scaleFactor).coerceIn(1f,5f);scaleX=zoom;scaleY=zoom;return true}
    })
    private val gestures=GestureDetector(c,object:GestureDetector.SimpleOnGestureListener(){override fun onDoubleTap(e:MotionEvent):Boolean{zoom=if(zoom>1f)1f else 2.5f;animate().scaleX(zoom).scaleY(zoom).translationX(0f).translationY(0f).setDuration(180).start();return true}})
    init{scaleType=ScaleType.FIT_CENTER;contentDescription="图片预览，支持双击和双指缩放"}
    override fun onTouchEvent(e:MotionEvent):Boolean{
        scaling.onTouchEvent(e);gestures.onTouchEvent(e)
        if(e.actionMasked==MotionEvent.ACTION_MOVE && e.pointerCount==1 && zoom>1f){translationX+=(e.x-x);translationY+=(e.y-y)}
        x=e.x;y=e.y;return true
    }
}
