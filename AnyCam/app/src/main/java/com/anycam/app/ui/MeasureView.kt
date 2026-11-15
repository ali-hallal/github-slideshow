package com.anycam.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.anycam.app.core.Measure
import kotlin.math.hypot

/**
 * Measuring, drawn over the viewfinder.
 *
 * Live: a cross in the middle to aim with. On a frozen photo: four corner handles to put on the
 * corners of the known object, and lines whose ends can be dragged anywhere on the same surface,
 * each labelled with its length. A magnifier shows what is under the finger while dragging.
 */
class MeasureView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val accent = Color.rgb(0x4F, 0xD1, 0xC5)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = Color.WHITE }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4.5f * density; color = Color.argb(140, 0, 0, 0) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15 * density; typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0) }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (!measureLikeViewfinder(widthSpec, heightSpec) { w, h -> setMeasuredDimension(w, h) }) super.onMeasure(widthSpec, heightSpec)
    }

    /** Live aiming: the cross is shown. */
    var aiming = false
        set(value) { field = value; invalidate() }

    /** The frozen photo being measured, or null. */
    var photo: Bitmap? = null
        private set

    var reference = Measure.Reference.CARD
        set(value) { field = value; invalidate() }

    /** The known object's corners (x, y pairs, view pixels). */
    private val corners = FloatArray(8)
    /** Measuring lines: x1, y1, x2, y2 each. */
    private val lines = ArrayList<FloatArray>()

    /** Called when what is measured changes. */
    var onChange: (() -> Unit)? = null
    /** A tap on the photo away from any handle (to find the card there). */
    var onTap: ((Float, Float) -> Unit)? = null
    /** The known object has been found (or placed): its outline, handles and the lines are shown. */
    var found = false
        private set

    /** The photo to measure on; nothing is marked until the card is tapped. */
    fun freeze(bmp: Bitmap) {
        photo = bmp
        aiming = false
        found = false
        lines.clear()
        invalidate()
    }

    /** The card's corners as found; a first line to measure with, under it. */
    fun setCorners(c: FloatArray) {
        c.copyInto(corners)
        found = true
        if (lines.isEmpty()) {
            val bottom = maxOf(c[1], c[3], c[5], c[7])
            val y = (bottom + height * 0.12f).coerceAtMost(height * 0.92f)
            lines += floatArrayOf(width * 0.25f, y, width * 0.75f, y)
        }
        invalidate()
        onChange?.invoke()
    }

    /** Not found: a card-shaped box where the tap was, to drag onto the corners by hand. */
    fun placeCardAt(x: Float, y: Float) {
        val cw = width * 0.36f
        val ch = cw * (reference.hMm / reference.wMm).toFloat()
        setCorners(floatArrayOf(x - cw / 2, y - ch / 2, x + cw / 2, y - ch / 2, x + cw / 2, y + ch / 2, x - cw / 2, y + ch / 2))
    }

    fun unfreeze() {
        photo = null
        found = false
        lines.clear()
        invalidate()
    }

    fun addLine() {
        if (!found) return
        val w = width.toFloat()
        val h = height.toFloat()
        val y = h * (0.68f + 0.06f * (lines.size % 4))
        lines += floatArrayOf(w * 0.2f, y, w * 0.8f, y)
        invalidate()
        onChange?.invoke()
    }

    fun plane(): Measure.Plane? = if (!found) null else try {
        Measure.Plane(DoubleArray(8) { corners[it].toDouble() }, reference)
    } catch (e: Exception) {
        null
    }

    /** Each line's length on the surface, in millimetres (null where the corners make no shape). */
    fun lengths(): List<Double?> {
        val p = plane()
        return lines.map { l -> p?.distance(l[0].toDouble(), l[1].toDouble(), l[2].toDouble(), l[3].toDouble()) }
    }

    /** The photo with the measurements drawn on it, at the photo's own size. */
    fun render(): Bitmap? {
        val bmp = photo ?: return null
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.scale(bmp.width / width.toFloat(), bmp.height / height.toFloat())
        drawPhoto(c, handles = false)
        return out
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        if (photo != null) {
            drawPhoto(canvas, handles = true)
            drawLoupe(canvas)
        } else if (aiming) drawCross(canvas)
    }

    private fun drawCross(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = 14 * density
        for (p in listOf(shadow, line)) {
            c.drawLine(cx - r * 2, cy, cx - r * 0.5f, cy, p)
            c.drawLine(cx + r * 0.5f, cy, cx + r * 2, cy, p)
            c.drawLine(cx, cy - r * 2, cx, cy - r * 0.5f, p)
            c.drawLine(cx, cy + r * 0.5f, cx, cy + r * 2, p)
        }
        fill.color = accent
        c.drawCircle(cx, cy, 2.5f * density, fill)
    }

    private fun drawPhoto(c: Canvas, handles: Boolean) {
        val bmp = photo ?: return
        c.drawBitmap(bmp, null, Rect(0, 0, width, height), null)
        if (!found) return
        // The known object.
        val path = Path().apply {
            moveTo(corners[0], corners[1]); lineTo(corners[2], corners[3]); lineTo(corners[4], corners[5]); lineTo(corners[6], corners[7]); close()
        }
        val ref = Paint(line).apply { color = accent }
        c.drawPath(path, shadow)
        c.drawPath(path, ref)
        if (handles) for (i in 0 until 4) handle(c, corners[2 * i], corners[2 * i + 1], accent)
        label(c, reference.label, (corners[0] + corners[2] + corners[4] + corners[6]) / 4, (corners[1] + corners[3] + corners[5] + corners[7]) / 4)
        // The lines and their lengths.
        val lengths = lengths()
        for ((i, l) in lines.withIndex()) {
            c.drawLine(l[0], l[1], l[2], l[3], shadow)
            c.drawLine(l[0], l[1], l[2], l[3], line)
            if (handles) {
                handle(c, l[0], l[1], Color.WHITE)
                handle(c, l[2], l[3], Color.WHITE)
            } else {
                // Ends marked in the saved photo.
                fill.color = Color.WHITE
                c.drawCircle(l[0], l[1], 3 * density, fill)
                c.drawCircle(l[2], l[3], 3 * density, fill)
            }
            label(c, lengths[i]?.let { Measure.label(it) } ?: "?", (l[0] + l[2]) / 2, (l[1] + l[3]) / 2 - 16 * density)
        }
    }

    private fun handle(c: Canvas, x: Float, y: Float, color: Int) {
        fill.color = Color.argb(90, Color.red(color), Color.green(color), Color.blue(color))
        c.drawCircle(x, y, 16 * density, fill)
        fill.color = color
        c.drawCircle(x, y, 3 * density, fill)
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float) {
        val w = text.measureText(s) / 2 + 8 * density
        val h = text.textSize
        c.drawRoundRect(RectF(x - w, y - h, x + w, y + h * 0.45f), 6 * density, 6 * density, tag)
        c.drawText(s, x, y, text)
    }

    /** What is under the finger, three times larger, in a circle above it (in the far corner if the finger is high up). */
    private fun drawLoupe(c: Canvas) {
        val bmp = photo ?: return
        val i = dragging
        if (i < 0) return
        val (x, y) = point(i)
        val r = 56 * density
        val cx = if (x < width / 2f) width - r - 12 * density else r + 12 * density
        val cy = r + 12 * density
        val sx = bmp.width / width.toFloat()
        val sy = bmp.height / height.toFloat()
        val half = r / 3
        val src = Rect(((x - half) * sx).toInt(), ((y - half) * sy).toInt(), ((x + half) * sx).toInt(), ((y + half) * sy).toInt())
        c.save()
        val clip = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        c.clipPath(clip)
        fill.color = Color.BLACK
        c.drawCircle(cx, cy, r, fill)
        c.drawBitmap(bmp, src, RectF(cx - r, cy - r, cx + r, cy + r), null)
        c.restore()
        c.drawCircle(cx, cy, r, line)
        fill.color = accent
        c.drawCircle(cx, cy, 2 * density, fill)
    }

    // ------------------------------------------------------------------ touch

    /** The handle being dragged: 0..3 the corners, 4 + 2k / 5 + 2k the ends of line k; -1 none. */
    private var dragging = -1

    private fun point(i: Int): Pair<Float, Float> =
        if (i < 4) corners[2 * i] to corners[2 * i + 1] else lines[(i - 4) / 2].let { l -> if ((i - 4) % 2 == 0) l[0] to l[1] else l[2] to l[3] }

    private fun move(i: Int, x: Float, y: Float) {
        val cx = x.coerceIn(0f, width.toFloat())
        val cy = y.coerceIn(0f, height.toFloat())
        if (i < 4) { corners[2 * i] = cx; corners[2 * i + 1] = cy }
        else lines[(i - 4) / 2].let { l -> if ((i - 4) % 2 == 0) { l[0] = cx; l[1] = cy } else { l[2] = cx; l[3] = cy } }
    }

    private var tapX = 0f
    private var tapY = 0f
    private var grabDx = 0f
    private var grabDy = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (photo == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapX = e.x
                tapY = e.y
                val count = if (found) 4 + 2 * lines.size else 0
                var best = -1
                var bestD = 36 * density
                for (i in 0 until count) {
                    val (px, py) = point(i)
                    val d = hypot(px - e.x, py - e.y)
                    if (d < bestD) { bestD = d; best = i }
                }
                dragging = best
                if (best >= 0) {
                    val (px, py) = point(best)
                    grabDx = px - e.x
                    grabDy = py - e.y
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging >= 0) {
                move(dragging, e.x + grabDx, e.y + grabDy)
                invalidate()
                onChange?.invoke()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // A tap away from every handle: look for the card there.
                if (dragging < 0 && e.actionMasked == MotionEvent.ACTION_UP && hypot(e.x - tapX, e.y - tapY) < 12 * density) onTap?.invoke(e.x, e.y)
                dragging = -1
                invalidate()
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
