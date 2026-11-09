package com.github.kr328.clash.forward

import android.graphics.*
import android.graphics.drawable.Drawable

class ChatBubbleDrawable(private val color: Int, private val outgoing: Boolean, private val density: Float): Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@ChatBubbleDrawable.color }
    override fun draw(canvas: Canvas) {
        val r = 19*density; val tail=6*density
        val l=bounds.left.toFloat(); val t=bounds.top.toFloat(); val right=bounds.right.toFloat(); val b=bounds.bottom.toFloat()
        canvas.drawRoundRect(if(outgoing) l else l+tail,t,if(outgoing) right-tail else right,b,r,r,paint)
        val path=Path()
        if(outgoing) {
            path.moveTo(right-19*density,b-19*density)
            path.cubicTo(right-8*density,b-17*density,right-10*density,b-4*density,right,b)
            path.cubicTo(right-10*density,b+1*density,right-18*density,b-4*density,right-22*density,b-9*density)
        } else {
            path.moveTo(l+19*density,b-19*density)
            path.cubicTo(l+8*density,b-17*density,l+10*density,b-4*density,l,b)
            path.cubicTo(l+10*density,b+1*density,l+18*density,b-4*density,l+22*density,b-9*density)
        }
        path.close();canvas.drawPath(path,paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha=alpha;invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter=filter;invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
