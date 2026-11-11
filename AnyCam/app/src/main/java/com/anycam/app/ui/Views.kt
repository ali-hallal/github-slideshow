package com.anycam.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.TextureView
import android.view.View
import kotlin.math.min

/** The viewfinder: as wide as the screen, as tall as the camera's picture shape makes it. */
class ViewfinderView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : TextureView(context, attrs) {
    private var across = 3
    private var down = 4

    /** The picture's shape as shown upright (e.g. 3:4). */
    fun setShape(across: Int, down: Int) {
        if (across <= 0 || down <= 0 || (across == this.across && down == this.down)) return
        this.across = across
        this.down = down
        requestLayout()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, w * down / across)
    }
}

/** Drawn over the viewfinder: the focus ring and the brightness mark. */
class MarksView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * density }
    private var fx = -1f
    private var fy = 0f
    private var since = 0L
    private var color = Color.WHITE

    fun focusAt(x: Float, y: Float) {
        fx = x
        fy = y
        since = SystemClock.uptimeMillis()
        color = Color.WHITE
        invalidate()
    }

    fun focused(sharp: Boolean) {
        if (fx < 0) return
        color = if (sharp) SHARP else Color.WHITE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (fx < 0) return
        val age = SystemClock.uptimeMillis() - since
        if (age > SHOWN_MS) {
            fx = -1f
            return
        }
        val settle = if (age < 180) 1.35f - 0.35f * age / 180f else 1f
        paint.color = color
        paint.alpha = if (age > SHOWN_MS - 400) (255 * (SHOWN_MS - age) / 400).toInt() else 255
        canvas.drawCircle(fx, fy, 34 * density * settle, paint)
        canvas.drawCircle(fx, fy, 2.5f * density, paint)
        postInvalidateOnAnimation()
    }

    private companion object {
        const val SHOWN_MS = 2600L
        val SHARP = Color.rgb(0x6F, 0xE3, 0x8D)
    }
}

/** The shutter: a ring and a disc that gives under the finger; grey while a photo is being taken. */
class ShutterView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var busy = false
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * density; color = Color.WHITE }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)

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
        disc.color = if (busy) Color.GRAY else Color.WHITE
        canvas.drawCircle(cx, cy, (outer - 7 * density) * if (isPressed) 0.86f else 1f, disc)
    }
}
