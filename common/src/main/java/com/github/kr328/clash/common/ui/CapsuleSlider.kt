package com.github.kr328.clash.common.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView

/** One geometry and palette for the mode controls and idle-delay control. */
open class CapsuleSlider @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    SeekBar(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorAccent)).let {
        try { it.getColor(0, Color.rgb(0, 122, 255)) } finally { it.recycle() }
    }
    private val dark = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).let {
        try { Color.luminance(it.getColorStateList(0)?.defaultColor ?: Color.BLACK) > 0.5f }
        finally { it.recycle() }
    }
    private val trackColor = if (dark) Color.rgb(44, 44, 46) else Color.rgb(242, 242, 247)
    private fun dp(value: Int) = value * resources.displayMetrics.density
    var tickCount: Int = 4
        set(value) { field = value.coerceAtLeast(2); invalidate() }

    init {
        max = 3
        keyProgressIncrement = 1
        thumbOffset = 0
        minimumHeight = dp(48).toInt()
        background = null
        elevation = 0f
        stateListAnimator = null
        setPadding(dp(20).toInt(), 0, dp(20).toInt(), 0)
    }

    fun alignLabels(labels: ViewGroup) {
        fun position() {
            if (width <= 0 || labels.width <= 0 || labels.childCount < 2) return
            for (index in 0 until labels.childCount) {
                val label = labels.getChildAt(index)
                val halfText = if (label is TextView) label.paint.measureText(label.text.toString()) / 2f else label.width / 2f
                val target = left - labels.left + paddingLeft +
                    (width - paddingLeft - paddingRight) * index / (labels.childCount - 1f)
                val minimum = halfText.coerceAtMost(labels.width / 2f)
                val center = target.coerceIn(minimum, labels.width - minimum)
                label.translationX = center - label.left - label.width / 2f
            }
        }
        labels.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> position() }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> position() }
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val center = height / 2f
        val radius = dp(15)
        val start = left - radius
        val end = right + radius
        paint.color = trackColor
        canvas.drawRoundRect(start, center - radius, end, center + radius, radius, radius, paint)
        val fraction = if (max > 0) progress.toFloat() / max else 0f
        val position = left + (right - left) * fraction
        paint.color = accent
        val clip = Path().apply {
            addRoundRect(RectF(start, center - radius, end, center + radius), radius, radius, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(start, center - radius, position, center + radius, paint)
        canvas.restore()
        for (index in 0 until tickCount) {
            val tickFraction = index / (tickCount - 1f)
            paint.color = if (tickFraction <= fraction) {
                if (dark) Color.rgb(69, 69, 74) else Color.rgb(199, 199, 204)
            } else accent
            canvas.drawCircle(left + (right - left) * tickFraction, center, dp(2), paint)
        }
        paint.color = Color.WHITE
        canvas.drawCircle(position, center, radius, paint)
    }
}

open class FourStepSlider @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    CapsuleSlider(context, attrs)
