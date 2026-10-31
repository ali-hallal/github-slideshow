package com.procam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.abs

/** Classic -3…+3 EV exposure meter: shows how far the current exposure is from "correct". */
class MeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** EV offset from a correct exposure, or null when there's nothing to show. */
    var ev: Float? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val accent = Color.rgb(0xFF, 0xC1, 0x07)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 9 * density
        textAlign = Paint.Align.CENTER
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val pad = 8 * density
        val w = width - 2 * pad
        val base = height * 0.55f
        fun xFor(v: Float) = pad + w * (v + RANGE) / (2 * RANGE)

        for (i in -9..9) {
            val v = i / 3f
            val x = xFor(v)
            val whole = i % 3 == 0
            val len = if (whole) 6 * density else 3 * density
            canvas.drawLine(x, base - len, x, base, tickPaint)
            if (whole) {
                val label = if (i == 0) "0" else String.format(Locale.US, "%+d", i / 3)
                canvas.drawText(label, x, height - 1 * density, textPaint)
            }
        }

        val value = ev ?: return
        val clamped = value.coerceIn(-RANGE, RANGE)
        val x = xFor(clamped)
        markerPaint.color = if (abs(value) <= 0.34f) accent else Color.WHITE
        val s = 5 * density
        path.reset()
        path.moveTo(x, base - 7 * density)
        path.lineTo(x - s, base - 7 * density - s * 1.4f)
        path.lineTo(x + s, base - 7 * density - s * 1.4f)
        path.close()
        canvas.drawPath(path, markerPaint)
        if (abs(value) > RANGE) {
            // Off the scale: draw an arrow at the end.
            val dir = if (value > 0) 1 else -1
            canvas.drawLine(x, base - 2 * density, x + dir * 6 * density, base - 2 * density, markerPaint.apply { strokeWidth = 2 * density })
        }
    }

    companion object {
        private const val RANGE = 3f
    }
}
