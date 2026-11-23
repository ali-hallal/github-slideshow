package com.starsteps.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Region
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.starsteps.app.core.Rocket
import kotlin.math.min
import kotlin.math.sin

/**
 * The rocket the child colours in: eight parts, each tapped to colour it in today's colour
 * (green, orange or red). Parts that can be coloured shimmer; when all are done, it blasts off.
 */
class RocketView(context: Context) : View(context) {
    private var mood = Rocket.Mood.NONE
    private var coloured = 0
    private var allowed = 0
    /** Asks to colour a part; returns whether it was. */
    var onPaint: ((Int) -> Boolean)? = null

    private val density = resources.displayMetrics.density
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; color = 0xFF263238.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapes = Array(Rocket.PARTS) { Path() }
    private val drawn = Array(Rocket.PARTS) { Path() }
    private val regions = Array(Rocket.PARTS) { Region() }
    private val glass = Path()
    private val flame = Path()
    private val matrix = Matrix()
    private var lift = 0f
    private var flicker = 0f
    private var shimmer = 0f
    private val shimmerAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { shimmer = it.animatedValue as Float; flicker = shimmer; invalidate() }
    }

    init {
        // Drawn in a 200 × 320 box.
        shapes[0].apply { moveTo(100f, 8f); cubicTo(130f, 30f, 142f, 62f, 140f, 92f); lineTo(60f, 92f); cubicTo(58f, 62f, 70f, 30f, 100f, 8f); close() }
        shapes[1].apply { addCircle(100f, 132f, 26f, Path.Direction.CW) }
        shapes[2].apply { addRect(60f, 92f, 140f, 172f, Path.Direction.CW); op(Path().apply { addCircle(100f, 132f, 26f, Path.Direction.CW) }, Path.Op.DIFFERENCE) }
        shapes[3].apply { addRect(60f, 172f, 140f, 190f, Path.Direction.CW) }
        shapes[4].apply { addRect(60f, 190f, 140f, 252f, Path.Direction.CW) }
        shapes[5].apply { moveTo(60f, 186f); lineTo(18f, 252f); lineTo(22f, 270f); lineTo(60f, 248f); close() }
        shapes[6].apply { moveTo(140f, 186f); lineTo(182f, 252f); lineTo(178f, 270f); lineTo(140f, 248f); close() }
        shapes[7].apply { moveTo(76f, 252f); lineTo(124f, 252f); lineTo(134f, 276f); lineTo(66f, 276f); close() }
        glass.addCircle(100f, 132f, 15f, Path.Direction.CW)
        contentDescription = "Rocket"
    }

    fun show(mood: Rocket.Mood, coloured: Int, allowed: Int) {
        this.mood = mood
        this.coloured = coloured
        this.allowed = allowed
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); shimmerAnim.start() }
    override fun onDetachedFromWindow() { shimmerAnim.cancel(); super.onDetachedFromWindow() }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, (min(w.toFloat(), 220 * density) * 1.6f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val s = min(w / 200f, h / 330f)
        matrix.setScale(s, s)
        matrix.postTranslate((w - 200 * s) / 2, (h - 330 * s) / 2)
        val clip = Region(0, 0, w, h)
        for (i in 0 until Rocket.PARTS) {
            drawn[i].set(shapes[i]); drawn[i].transform(matrix)
            regions[i].setPath(drawn[i], clip)
        }
        outline.strokeWidth = 4f * s
    }

    private fun colour(): Int = when (mood) {
        Rocket.Mood.GREEN -> 0xFF43A047.toInt()
        Rocket.Mood.ORANGE -> 0xFFFB8C00.toInt()
        Rocket.Mood.RED -> 0xFFE53935.toInt()
        Rocket.Mood.NONE -> 0xFF90A4AE.toInt()
    }

    override fun onDraw(c: Canvas) {
        c.save()
        c.translate(0f, -lift)
        val done = Integer.bitCount(coloured) == Rocket.PARTS
        // Flames under a finished rocket.
        if (done) {
            flame.reset()
            val f = 1f + 0.15f * sin(flicker * 6.283f * 3)
            flame.moveTo(70f, 278f); flame.quadTo(100f, 278f + 60f * f, 130f, 278f); flame.close()
            flame.transform(matrix)
            fill.color = 0xFFFFB300.toInt(); c.drawPath(flame, fill)
            flame.reset(); flame.moveTo(84f, 278f); flame.quadTo(100f, 278f + 34f * f, 116f, 278f); flame.close(); flame.transform(matrix)
            fill.color = 0xFFFFF176.toInt(); c.drawPath(flame, fill)
        }
        val canPaint = Integer.bitCount(coloured) < allowed
        for (i in 0 until Rocket.PARTS) {
            val on = coloured and (1 shl i) != 0
            fill.color = when {
                on -> colour()
                canPaint -> blend(Color.WHITE, colour(), 0.12f + 0.18f * sin(shimmer * 3.1416f))
                else -> Color.WHITE
            }
            c.drawPath(drawn[i], fill)
            c.drawPath(drawn[i], outline)
        }
        val g = Path(glass).apply { transform(matrix) }
        fill.color = 0xFFB3E5FC.toInt(); c.drawPath(g, fill); c.drawPath(g, outline)
        c.restore()
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt()
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) return true
        if (e.actionMasked != MotionEvent.ACTION_UP) return true
        val x = e.x.toInt()
        val y = (e.y + lift).toInt()
        // The window sits inside the body: it is tested first.
        val order = intArrayOf(1, 0, 2, 3, 4, 5, 6, 7)
        for (i in order) if (regions[i].contains(x, y)) {
            if (onPaint?.invoke(i) == true) {
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                if (Integer.bitCount(coloured) == Rocket.PARTS) blastOff()
            }
            break
        }
        performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** Colours the next free part (for tests and accessibility). */
    fun paintNext(): Boolean {
        for (i in 0 until Rocket.PARTS) if (coloured and (1 shl i) == 0) {
            val ok = onPaint?.invoke(i) == true
            if (ok && Integer.bitCount(coloured) == Rocket.PARTS) blastOff()
            return ok
        }
        return false
    }

    /** Up and away, and back again. */
    fun blastOff() {
        ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 2600
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { lift = (it.animatedValue as Float) * height * 0.8f; invalidate() }
            start()
        }
    }
}
