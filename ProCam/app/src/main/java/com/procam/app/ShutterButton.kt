package com.procam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Classic ring-and-disc shutter button. Yellow core in PRO mode, grey while a capture is in flight. */
class ShutterButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var busy = false
        set(value) { field = value; invalidate() }
    var pro = false
        set(value) { field = value; invalidate() }
    /** Video mode: red record button; while [recording] a red square (stop). */
    var video = false
        set(value) { field = value; invalidate() }
    var recording = false
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
        color = Color.WHITE
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isFocusable = true
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f - ring.strokeWidth / 2
        canvas.drawCircle(cx, cy, outer, ring)
        if (video) {
            core.color = if (busy) Color.GRAY else RED
            if (recording) {
                val half = outer * 0.42f
                canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half, 6 * density, 6 * density, core)
            } else {
                canvas.drawCircle(cx, cy, (outer - 7 * density) * if (isPressed) 0.85f else 1f, core)
            }
            return
        }
        core.color = when {
            busy -> Color.GRAY
            pro -> Color.rgb(0xFF, 0xC1, 0x07)
            else -> Color.WHITE
        }
        val inner = (outer - 7 * density) * if (isPressed) 0.85f else 1f
        canvas.drawCircle(cx, cy, inner, core)
    }

    private companion object {
        val RED = Color.rgb(0xE5, 0x39, 0x35)
    }
}
