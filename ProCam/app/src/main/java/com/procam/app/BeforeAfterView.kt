package com.procam.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * Shows a photo; when a [before] image is set, a draggable divider compares the two
 * (left of the divider = before, right = after).
 */
class BeforeAfterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var after: Bitmap? = null
        set(value) { field = value; invalidate() }
    var before: Bitmap? = null
        set(value) {
            field = value
            split = 0.5f
            contentDescription = if (value != null) "Before and after comparison" else "Photo"
            invalidate()
        }
    /** Divider position, 0..1 across the photo. */
    var split = 0.5f
        private set

    private val d = resources.displayMetrics.density
    private val dst = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2 * d }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 2 * d
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0xA0, 0, 0, 0) }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12 * d
        isFakeBoldText = true
    }

    private val srcRect = android.graphics.Rect()

    /** The centre region of [b] with the given width/height ratio. */
    private fun cropToAspect(b: Bitmap, aspect: Float): android.graphics.Rect {
        val ba = b.width.toFloat() / b.height
        if (kotlin.math.abs(ba - aspect) < 0.01f) {
            srcRect.set(0, 0, b.width, b.height)
        } else if (ba > aspect) {
            val w = (b.height * aspect).toInt()
            srcRect.set((b.width - w) / 2, 0, (b.width + w) / 2, b.height)
        } else {
            val h = (b.width / aspect).toInt()
            srcRect.set(0, (b.height - h) / 2, b.width, (b.height + h) / 2)
        }
        return srcRect
    }

    override fun onDraw(canvas: Canvas) {
        val a = after ?: return
        val scale = min(width.toFloat() / a.width, height.toFloat() / a.height)
        val w = a.width * scale
        val h = a.height * scale
        dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        canvas.drawBitmap(a, null, dst, bitmapPaint)
        val b = before ?: return

        val x = dst.left + split * dst.width()
        canvas.save()
        canvas.clipRect(dst.left, dst.top, x, dst.bottom)
        // Never stretch "before": if its shape differs from "after", show the matching centre part.
        canvas.drawBitmap(b, cropToAspect(b, dst.width() / dst.height()), dst, bitmapPaint)
        canvas.restore()

        canvas.drawLine(x, dst.top, x, dst.bottom, linePaint)
        val cy = dst.centerY()
        canvas.drawCircle(x, cy, 16 * d, knobPaint)
        val s = 5 * d
        canvas.drawLine(x - 3 * d, cy - s, x - 3 * d - s, cy, arrowPaint)
        canvas.drawLine(x - 3 * d - s, cy, x - 3 * d, cy + s, arrowPaint)
        canvas.drawLine(x + 3 * d, cy - s, x + 3 * d + s, cy, arrowPaint)
        canvas.drawLine(x + 3 * d + s, cy, x + 3 * d, cy + s, arrowPaint)

        label(canvas, "BEFORE", dst.left + 10 * d, dst.top + 10 * d, x)
        label(canvas, "AFTER", dst.right - 10 * d - labelText.measureText("AFTER") - 16 * d, dst.top + 10 * d, x)
    }

    private fun label(canvas: Canvas, text: String, left: Float, top: Float, divider: Float) {
        val w = labelText.measureText(text) + 16 * d
        val r = RectF(left, top, left + w, top + 24 * d)
        // Hide a label once the divider covers it.
        if (text == "BEFORE" && divider < r.right) return
        if (text == "AFTER" && divider > r.left) return
        canvas.drawRoundRect(r, 12 * d, 12 * d, labelBg)
        canvas.drawText(text, left + 8 * d, top + 16.5f * d, labelText)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (before == null || dst.width() <= 0f) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                split = ((event.x - dst.left) / dst.width()).coerceIn(0f, 1f)
                invalidate()
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
