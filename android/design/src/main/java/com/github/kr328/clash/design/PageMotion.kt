package com.github.kr328.clash.design

import android.animation.ValueAnimator
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils

/** Moves the live page after navigation commits, keeping the header and tabs still. */
internal class PageMotion(private val host: ViewGroup) {
    private val interpolator = AnimationUtils.loadInterpolator(host.context, android.R.interpolator.fast_out_slow_in)
    private var animatedPage: View? = null

    fun start(direction: String) {
        cancel()
        if (!host.isAttachedToWindow || !host.isShown || host.width == 0 ||
            (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())
        ) return
        val page = host.getChildAt(host.childCount - 1) ?: return

        val distance = 24f * page.resources.displayMetrics.density
        val end = if (page.layoutDirection == View.LAYOUT_DIRECTION_RTL) -distance else distance
        when (direction) {
            "push" -> page.translationX = end
            "pop" -> page.translationX = -end
            "present" -> page.translationY = distance
            "dismiss" -> page.translationY = -distance
            else -> return
        }
        animatedPage = page
        page.animate()
            .translationX(0f)
            .translationY(0f)
            .setDuration(200L)
            .setInterpolator(interpolator)
            .start()
    }

    fun cancel() {
        val page = animatedPage ?: return
        animatedPage = null
        page.animate().cancel()
        page.translationX = 0f
        page.translationY = 0f
    }
}
