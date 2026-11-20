package com.github.kr328.clash.design.component

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.github.kr328.clash.common.compat.getDrawableCompat
import kotlin.math.ceil

class ProxyView(context: Context, private val config: ProxyViewConfig) : View(context) {
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardPath = Path()
    private var spokenTitle = ""
    private var spokenSubtitle = ""
    private var spokenDelay = ""

    var state: ProxyViewState? = null
        set(value) {
            field = value
            value?.update(true)
            updateAccessibility()
            requestLayout()
            invalidate()
        }

    constructor(context: Context) : this(context, ProxyViewConfig(context, 2))

    init {
        background = context.getDrawableCompat(config.clickableBackground)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        textPaint.textSize = config.textSize
        val edge = edgePadding
        val lineHeight = textPaint.fontSpacing
        val desiredHeight = ceil((edge + config.contentPadding) * 2 + lineHeight * 2).toInt()
        setMeasuredDimension(
            resolveSize(resources.displayMetrics.widthPixels, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun draw(canvas: Canvas) {
        val node = state ?: return super.draw(canvas)
        if (node.update(false)) postInvalidateOnAnimation()
        updateAccessibility()
        backgroundPaint.color = node.background
        val checkpoint = canvas.save()
        val edge = edgePadding
        if (edge == 0f) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        } else {
            cardPath.reset()
            cardPath.addRoundRect(edge, edge, width - edge, height - edge, config.cardRadius, config.cardRadius, Path.Direction.CW)
            canvas.drawPath(cardPath, backgroundPaint)
            canvas.clipPath(cardPath)
        }
        super.draw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val node = state ?: return
        textPaint.textSize = config.textSize
        textPaint.color = node.controls
        val padding = edgePadding + config.contentPadding.coerceAtMost(width / 6f)
        val available = (width - padding * 2).coerceAtLeast(0f)
        val offset = height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        val halfLine = textPaint.fontSpacing / 2f
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val start = if (rtl) width - padding else padding
        val end = if (rtl) padding else width - padding

        val delay = node.delayText
        val delayWidth = textPaint.measureText(delay)
        val showDelay = delay.isNotEmpty() && delayWidth <= available
        val subtitleWidth = if (showDelay) (available - delayWidth - config.textMargin).coerceAtLeast(0f) else available
        textPaint.textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
        canvas.drawText(ellipsize(node.title, available), start, offset - halfLine, textPaint)
        canvas.drawText(ellipsize(node.subtitle, subtitleWidth), start, offset + halfLine, textPaint)
        if (showDelay) {
            textPaint.textAlign = if (rtl) Paint.Align.LEFT else Paint.Align.RIGHT
            canvas.drawText(delay, end, offset + halfLine, textPaint)
        }
    }

    private val edgePadding get() = if (config.proxyLine == 1) 0f else config.layoutPadding

    private fun ellipsize(value: String, width: Float): String =
        TextUtils.ellipsize(value, textPaint, width, TextUtils.TruncateAt.END).toString()

    private fun updateAccessibility() {
        val node = state ?: return
        if (isSelected != node.selected) isSelected = node.selected
        if (spokenTitle == node.title && spokenSubtitle == node.subtitle && spokenDelay == node.delayText) return
        spokenTitle = node.title
        spokenSubtitle = node.subtitle
        spokenDelay = node.delayText
        contentDescription = listOf(spokenTitle, spokenSubtitle, spokenDelay).filter(String::isNotBlank).joinToString("，")
    }
}
