package com.github.kr328.clash.forward

import android.os.Bundle
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RectF
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*

class ChatMediaActivity:AppCompatActivity(),CoroutineScope by MainScope(){
    private var videoPlayer:VideoView?=null
    private var videoPrepared = false
    private var inForeground = false
    private var resumePlayback = true
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val density=resources.displayMetrics.density
        val progress = ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
            contentDescription = "媒体加载中"
        }
        val progressSize = (36 * density).toInt()
        root.addView(progress, FrameLayout.LayoutParams(progressSize, progressSize, Gravity.CENTER))
        setContentView(root)
        val back=ImageButton(this).apply{
            setImageResource(com.github.kr328.clash.design.R.drawable.roytools_back)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription="返回"
            setOnClickListener{finish()}
        }
        root.addView(back,FrameLayout.LayoutParams((48*density).toInt(),(48*density).toInt(),Gravity.TOP or Gravity.START).apply{
            setMargins((16*density).toInt(),(8*density).toInt(),0,0)
        })
        if (state == null) overridePendingTransition(com.github.kr328.clash.design.R.anim.roytools_push_in,com.github.kr328.clash.design.R.anim.roytools_push_out)
        launch {try{
            val file=ChatMediaCache.get(this@ChatMediaActivity,intent.getStringExtra("file_id").orEmpty())
            if(intent.getStringExtra("kind")=="video"){
                val video = VideoView(this@ChatMediaActivity)
                videoPlayer = video
                root.addView(video, 0, FrameLayout.LayoutParams(-1, -1))
                video.setMediaController(MediaController(this@ChatMediaActivity).apply { setAnchorView(video) })
                video.setOnPreparedListener {
                    videoPrepared = true
                    progress.visibility = View.GONE
                    if (inForeground && resumePlayback) video.start()
                }
                video.setOnErrorListener { _, _, _ ->
                    progress.visibility = View.GONE
                    Toast.makeText(this@ChatMediaActivity, "视频播放失败，请返回重试", Toast.LENGTH_LONG).show()
                    true
                }
                video.setVideoPath(file.path)
            }else{
                val bitmap=withContext(Dispatchers.IO){ChatMediaCache.bitmap(file)} ?: error("无法读取图片")
                val image=ZoomImage(this@ChatMediaActivity);image.setImageBitmap(bitmap);root.addView(image,0,FrameLayout.LayoutParams(-1,-1))
                progress.visibility = View.GONE
            }
        }catch(e:CancellationException){throw e}catch(_:Exception){progress.visibility=View.GONE;Toast.makeText(this@ChatMediaActivity,"媒体加载失败，请返回重试",Toast.LENGTH_LONG).show()}}
    }
    override fun onResume() {
        super.onResume()
        inForeground = true
        if (videoPrepared && resumePlayback) videoPlayer?.start()
    }

    override fun onPause() {
        inForeground = false
        if (videoPrepared) {
            resumePlayback = videoPlayer?.isPlaying == true
            videoPlayer?.pause()
        }
        super.onPause()
    }
    override fun onDestroy(){videoPlayer?.stopPlayback();cancel();super.onDestroy()}
    override fun finish(){super.finish();overridePendingTransition(com.github.kr328.clash.design.R.anim.roytools_pop_in,com.github.kr328.clash.design.R.anim.roytools_pop_out)}
}
private class ZoomImage(context: android.content.Context) : androidx.appcompat.widget.AppCompatImageView(context) {
    private var zoom = 1f
    private var previousRawX = Float.NaN
    private var previousRawY = Float.NaN
    private val scaling = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, 5f)
            scaleX = zoom
            scaleY = zoom
            constrainTranslation()
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true

        override fun onDoubleTap(event: MotionEvent): Boolean {
            zoom = if (zoom > 1f) 1f else 2.5f
            animate().scaleX(zoom).scaleY(zoom).translationX(0f).translationY(0f).setDuration(180).start()
            return true
        }
    })

    init {
        scaleType = ScaleType.FIT_CENTER
        contentDescription = "图片预览，支持双击和双指缩放"
    }

    private fun constrainTranslation() {
        val image = drawable ?: return
        val fitted = RectF(image.bounds)
        imageMatrix.mapRect(fitted)
        val horizontalLimit = ((fitted.width() * zoom - width) / 2).coerceAtLeast(0f)
        val verticalLimit = ((fitted.height() * zoom - height) / 2).coerceAtLeast(0f)
        translationX = translationX.coerceIn(-horizontalLimit, horizontalLimit)
        translationY = translationY.coerceIn(-verticalLimit, verticalLimit)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            animate().cancel()
            zoom = scaleX
        }
        scaling.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.pointerCount != 1) {
            previousRawX = Float.NaN
            previousRawY = Float.NaN
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && zoom > 1f && previousRawX.isFinite()) {
            translationX += event.rawX - previousRawX
            translationY += event.rawY - previousRawY
            constrainTranslation()
        }
        previousRawX = event.rawX
        previousRawY = event.rawY
        return true
    }
}
