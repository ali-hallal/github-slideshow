package com.procam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View


/** The shake meter pill: 5 bars + "Steady · sharp to 1/30s". */
class ShakeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private var bars = 0
    private var color = GREEN
    private var title = ""
    private var detail = ""

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0xA0, 0, 0, 0) }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13 * d
        isFakeBoldText = true
    }
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xC8, 0xC8, 0xC8)
        textSize = 12 * d
    }
    private val rect = RectF()

    fun show(bars: Int, color: Int, title: String, detail: String) {
        if (bars == this.bars && color == this.color && title == this.title && detail == this.detail) return
        val relayout = title != this.title || detail != this.detail
        this.bars = bars
        this.color = color
        this.title = title
        this.detail = detail
        contentDescription = "$title, $detail"
        if (relayout) requestLayout()
        invalidate()
    }

    private val barsWidth get() = 5 * 4 * d + 4 * 3 * d

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = 12 * d + barsWidth + 8 * d + titlePaint.measureText(title) +
            (if (detail.isEmpty()) 0f else 6 * d + detailPaint.measureText(detail)) + 12 * d
        setMeasuredDimension(w.toInt(), (30 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, h / 2, h / 2, bg)
        var x = 12 * d
        val bottom = h / 2 + 7 * d
        for (i in 0 until 5) {
            barPaint.color = if (i < bars) color else Color.argb(0x50, 0xFF, 0xFF, 0xFF)
            val bh = (6 + 2 * i) * d
            rect.set(x, bottom - bh, x + 4 * d, bottom)
            canvas.drawRoundRect(rect, 2 * d, 2 * d, barPaint)
            x += 7 * d
        }
        x += 5 * d
        val baseline = h / 2 - (titlePaint.ascent() + titlePaint.descent()) / 2
        canvas.drawText(title, x, baseline, titlePaint)
        x += titlePaint.measureText(title) + 6 * d
        if (detail.isNotEmpty()) canvas.drawText(detail, x, baseline, detailPaint)
    }

    companion object {
        val GREEN = Color.rgb(0x4C, 0xD9, 0x64)
        val YELLOW = Color.rgb(0xFF, 0xC1, 0x07)
        val ORANGE = Color.rgb(0xFF, 0x8A, 0x3D)
        val RED = Color.rgb(0xFF, 0x45, 0x3A)

    }
}
