package com.procam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Zoom ruler: drag sideways to zoom smoothly (left = closer), like turning a lens barrel. Ticks
 * are on a log scale (each doubling is the same distance), lens stops are labelled and the
 * super zoom range is drawn in the accent colour.
 *
 * Values are the open camera's zoom; labels show [base] × that (zoom relative to the main lens).
 */
class ZoomDial @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Camera zoom range: [lo]..[hi]; above [hardwareMax] is super zoom. */
    var lo = 1f
    var hi = 10f
    var hardwareMax = 10f
    var base = 1f
    /** Display zooms to label (lens buttons). */
    var labels: List<Float> = emptyList()
    var value = 1f
        private set

    /** Called with the new camera zoom while dragging. */
    var onChange: ((Float) -> Unit)? = null

    private val d = resources.displayMetrics.density
    /** Pixels per doubling of zoom. */
    private val perDoubling = 72 * d
    private val pxPerLn = perDoubling / ln(2f)
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 1.5f * d }
    private val accent = context.getColor(R.color.accent)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11 * d
        textAlign = Paint.Align.CENTER
    }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        textSize = 16 * d
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val path = Path()

    private var downX = 0f
    private var downValue = 1f
    private var lastTickStep = 0

    fun setValue(v: Float) {
        value = v.coerceIn(lo, hi)
        invalidate()
    }

    /** Starts a drag that began elsewhere (e.g. on a lens button). */
    fun beginDrag() {
        downValue = value
        lastTickStep = step(value)
    }

    /** Moves the zoom for a finger that has moved [dx] px since [beginDrag]. */
    fun dragBy(dx: Float) {
        val v = (downValue * exp(-dx / pxPerLn)).coerceIn(lo, hi)
        if (v == value) return
        value = v
        val s = step(v)
        if (s != lastTickStep) {
            lastTickStep = s
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        invalidate()
        onChange?.invoke(v)
    }

    /** Tenths of a doubling: one haptic tick each. */
    private fun step(v: Float) = (ln(v * base) / ln(2f) * 4).toInt()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                beginDrag()
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> dragBy(e.x - downX)
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val baseY = height - 10 * d
        val lnV = ln(value)
        // Minor ticks every quarter doubling, from lo to hi.
        val first = (ln(lo) / ln(2f) * 4).toInt() - 1
        val last = (ln(hi) / ln(2f) * 4).toInt() + 1
        for (k in first..last) {
            val z = 2f.pow(k / 4f)
            if (z < lo * 0.999f || z > hi * 1.001f) continue
            val x = cx + (ln(z) - lnV) * pxPerLn
            if (x < 0 || x > width) continue
            tick.color = if (z > hardwareMax * 1.001f) accent else Color.WHITE
            tick.alpha = fade(x)
            val len = if (k % 4 == 0) 12 * d else 7 * d
            canvas.drawLine(x, baseY, x, baseY - len, tick)
        }
        // Labels at lens stops and at the ends.
        val marks = (labels.map { it / base } + listOf(lo, hi)).filter { it in lo * 0.999f..hi * 1.001f }.distinct()
        for (z in marks) {
            val x = cx + (ln(z) - lnV) * pxPerLn
            if (x < 0 || x > width) continue
            label.color = if (z > hardwareMax * 1.001f) accent else Color.WHITE
            label.alpha = fade(x)
            val text = (if (z > hardwareMax * 1.001f) "✦" else "") + Stops.zoomLabel(z * base).removeSuffix("×")
            canvas.drawText(text, x, baseY - 16 * d, label)
        }
        // Centre marker and the current value.
        path.reset()
        path.moveTo(cx - 6 * d, baseY + 8 * d)
        path.lineTo(cx + 6 * d, baseY + 8 * d)
        path.lineTo(cx, baseY + 1 * d)
        path.close()
        canvas.drawPath(path, marker)
        canvas.drawLine(cx, baseY, cx, baseY - 18 * d, marker.apply { strokeWidth = 2 * d })
        val superZoom = value > hardwareMax * 1.001f
        canvas.drawText((if (superZoom) "✦ " else "") + Stops.zoomLabel(value * base), cx, 18 * d, big)
    }

    /** Ticks fade out towards the edges. */
    private fun fade(x: Float): Int {
        val edge = abs(x - width / 2f) / (width / 2f)
        return (255 * (1f - edge * edge)).toInt().coerceIn(0, 255)
    }
}
