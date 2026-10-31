package com.procam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.camera2.CaptureResult
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Draws the rule-of-thirds grid, the electronic level and the tap-to-focus ring over the preview. */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var gridEnabled = true
        set(value) { field = value; invalidate() }
    var levelEnabled = true
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val green = Color.rgb(0x4C, 0xD9, 0x64)
    private val red = Color.rgb(0xFF, 0x45, 0x3A)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0x70, 0xFF, 0xFF, 0xFF)
        strokeWidth = density
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2 * density
        strokeCap = Paint.Cap.ROUND
    }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }

    private var roll = 0f
    private var flat = true
    private var focusX = -1f
    private var focusY = -1f
    private var focusStart = 0L
    private var focusColor = Color.WHITE

    /** [degrees] is how far the device is rotated clockwise from upright. */
    fun setRoll(degrees: Float, isFlat: Boolean) {
        roll = degrees
        flat = isFlat
        if (levelEnabled) invalidate()
    }

    fun showFocus(x: Float, y: Float) {
        focusX = x
        focusY = y
        focusStart = SystemClock.uptimeMillis()
        focusColor = Color.WHITE
        invalidate()
    }

    fun setAfState(state: Int?) {
        val color = when (state) {
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> green
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> red
            else -> Color.WHITE
        }
        if (color != focusColor && focusX >= 0) {
            focusColor = color
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        if (gridEnabled) {
            for (i in 1..2) {
                canvas.drawLine(w * i / 3, 0f, w * i / 3, h, gridPaint)
                canvas.drawLine(0f, h * i / 3, w, h * i / 3, gridPaint)
            }
        }

        if (levelEnabled && !flat) drawLevel(canvas, w / 2, h / 2, min(w, h))

        if (focusX >= 0) {
            val elapsed = SystemClock.uptimeMillis() - focusStart
            if (elapsed > 2500) {
                focusX = -1f
            } else {
                val grow = if (elapsed < 200) 1.3f - 0.3f * elapsed / 200f else 1f
                focusPaint.color = focusColor
                focusPaint.alpha = if (elapsed > 2000) (255 * (2500 - elapsed) / 500).toInt() else 255
                canvas.drawCircle(focusX, focusY, 36 * density * grow, focusPaint)
                canvas.drawCircle(focusX, focusY, 3 * density, focusPaint)
                postInvalidateOnAnimation()
            }
        }
    }

    private fun drawLevel(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val snapped = (roll / 90f).roundToInt() * 90f
        val deviation = roll - snapped
        val level = abs(deviation) < 1f
        val half = size * 0.3f
        val gap = 14 * density

        // Fixed reference ticks (where "level" is).
        canvas.save()
        canvas.rotate(-snapped, cx, cy)
        levelPaint.color = if (level) green else Color.argb(0xB0, 0xFF, 0xFF, 0xFF)
        canvas.drawLine(cx - half - 18 * density, cy, cx - half - 4 * density, cy, levelPaint)
        canvas.drawLine(cx + half + 4 * density, cy, cx + half + 18 * density, cy, levelPaint)
        canvas.restore()

        // The real horizon.
        canvas.save()
        canvas.rotate(-roll, cx, cy)
        levelPaint.color = if (level) green else Color.WHITE
        canvas.drawLine(cx - half, cy, cx - gap, cy, levelPaint)
        canvas.drawLine(cx + gap, cy, cx + half, cy, levelPaint)
        canvas.restore()
    }
}
