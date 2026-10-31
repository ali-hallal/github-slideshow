package com.procam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** Live RGB + luminance histogram of the viewfinder, fed by [FrameAnalyzer]. */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var data: FrameAnalyzer? = null

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

    fun setData(analyzer: FrameAnalyzer) {
        data = analyzer
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 6 * density
        canvas.drawRoundRect(0f, 0f, w, h, r, r, bgPaint)
        val d = data ?: return
        var peak = 1
        for (i in 0 until d.bins) peak = max(peak, max(d.luma[i], max(d.red[i], max(d.green[i], d.blue[i]))))
        drawChannel(canvas, d.red, d.bins, peak, w, h, redPaint, closed = true)
        drawChannel(canvas, d.green, d.bins, peak, w, h, greenPaint, closed = true)
        drawChannel(canvas, d.blue, d.bins, peak, w, h, bluePaint, closed = true)
        drawChannel(canvas, d.luma, d.bins, peak, w, h, lumaPaint, closed = false)
    }

    private fun drawChannel(
        canvas: Canvas, values: IntArray, bins: Int, peak: Int, w: Float, h: Float, paint: Paint, closed: Boolean,
    ) {
        val pad = 3 * density
        val usableH = h - 2 * pad
        path.reset()
        for (i in 0 until bins) {
            val x = pad + (w - 2 * pad) * i / (bins - 1)
            val y = h - pad - usableH * values[i] / peak
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
