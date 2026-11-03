package com.github.kr328.clash.common.ui

import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.SeekBar
import android.widget.AbsListView
import android.widget.AdapterView

/** One feedback path for activity buttons, dialog buttons and discrete slider steps. */
object TouchFeedback {
    fun click(view: View) = vibrate(view, VibrationEffect.EFFECT_CLICK, 12L)
    fun step(view: View) = vibrate(view, VibrationEffect.EFFECT_TICK, 8L)

    private fun vibrate(view: View, effect: Int, duration: Long) {
        val vibrator = view.context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            ?: view.context.getSystemService(Vibrator::class.java)
            ?: return
        if (!vibrator.hasVibrator()) return
        val vibration = if (vibrator.areEffectsSupported(effect).firstOrNull() == Vibrator.VIBRATION_EFFECT_SUPPORT_YES)
            VibrationEffect.createPredefined(effect)
        else VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator.vibrate(vibration, AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
    }

    fun install(window: Window) {
        val original = window.callback ?: return
        if (original is FeedbackCallback) return
        window.callback = FeedbackCallback(window, original)
    }

    private class FeedbackCallback(
        private val window: Window,
        private val original: Window.Callback,
    ) : Window.Callback by original {
        private var target: View? = null
        private var downX = 0f
        private var downY = 0f
        private val slop = ViewConfiguration.get(window.context).scaledTouchSlop

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    target = findButton(window.decorView, downX.toInt(), downY.toInt())
                }
                MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(event.rawX - downX) > slop ||
                    kotlin.math.abs(event.rawY - downY) > slop) target = null
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> target = null
                MotionEvent.ACTION_UP -> {
                    target?.takeIf { it.isEnabled && it.isShown && contains(it, event.rawX.toInt(), event.rawY.toInt()) }
                        ?.let(::click)
                    target = null
                }
            }
            return original.dispatchTouchEvent(event)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_UP && event.keyCode in
                intArrayOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE)) {
                window.currentFocus?.takeIf { it.isEnabled && it.isClickable && it !is SeekBar && it !is EditText }
                    ?.let(::click)
            }
            return original.dispatchKeyEvent(event)
        }
    }

    private fun contains(view: View, x: Int, y: Int): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return x >= location[0] && x < location[0] + view.width &&
            y >= location[1] && y < location[1] + view.height
    }

    private fun findButton(view: View, x: Int, y: Int): View? {
        if (!view.isShown || !view.isEnabled || !contains(view, x, y)) return null
        if (view is SeekBar || view is EditText || view is android.webkit.WebView) return null
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                findButton(view.getChildAt(index), x, y)?.let { return it }
            }
        }
        if (view is AbsListView) {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            if (view.pointToPosition(x - location[0], y - location[1]) != AdapterView.INVALID_POSITION) return view
        }
        return view.takeIf { it.isClickable }
    }
}
