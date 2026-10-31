package com.procam.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** Live RGB + luminance histogram, fed from small snapshots of the preview. */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val bins = 64
    private val red = IntArray(bins)
    private val green = IntArray(bins)
    private val blue = IntArray(bins)
    private val luma = IntArray(bins)
    private var pixels = IntArray(0)
    private var hasData = false

    private val density = resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x80, 0, 0, 0) }
    private val redPaint = channelPaint(Color.argb(0x70, 0xFF, 0x40, 0x40))
    private val greenPaint = channelPaint(Color.argb(0x70, 0x40, 0xFF, 0x40))
    private val bluePaint = channelPaint(Color.argb(0x70, 0x40, 0x80, 0xFF))
    private val lumaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.argb(0xE0, 0xFF, 0xFF, 0xFF)
    }
    private val path = Path()

    private fun channelPaint(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = c
    }

    fun update(bitmap: Bitmap) {
        val n = bitmap.width * bitmap.height
        if (pixels.size != n) pixels = IntArray(n)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        red.fill(0); green.fill(0); blue.fill(0); luma.fill(0)
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            red[r shr 2]++
            green[g shr 2]++
            blue[b shr 2]++
            luma[((r * 77 + g * 150 + b * 29) shr 8) shr 2]++
        }
        hasData = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 6 * density
        canvas.drawRoundRect(0f, 0f, w, h, r, r, bgPaint)
        if (!hasData) return
        var peak = 1
        for (i in 0 until bins) peak = max(peak, max(luma[i], max(red[i], max(green[i], blue[i]))))
        drawChannel(canvas, red, peak, w, h, redPaint, closed = true)
        drawChannel(canvas, green, peak, w, h, greenPaint, closed = true)
        drawChannel(canvas, blue, peak, w, h, bluePaint, closed = true)
        drawChannel(canvas, luma, peak, w, h, lumaPaint, closed = false)
    }

    private fun drawChannel(canvas: Canvas, data: IntArray, peak: Int, w: Float, h: Float, paint: Paint, closed: Boolean) {
        val pad = 3 * density
        val usableH = h - 2 * pad
        path.reset()
        for (i in 0 until bins) {
            val x = pad + (w - 2 * pad) * i / (bins - 1)
            val y = h - pad - usableH * data[i] / peak
            if (i == 0) {
                if (closed) { path.moveTo(x, h - pad); path.lineTo(x, y) } else path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        if (closed) {
            path.lineTo(w - pad, h - pad)
            path.close()
        }
        canvas.drawPath(path, paint)
    }
}
