package com.procam.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Looks (filters). Each is a colour matrix plus optional vignette, film grain and a retro date
 * stamp. The viewfinder shows the same colour matrix live (as a layer colour filter) and the
 * same vignette / date stamp overlay, so the saved photo matches what was on screen.
 */
object Looks {

    class Look(
        val id: String,
        val name: String,
        /** 4×5 colour matrix on 0..255 RGB (android.graphics.ColorMatrix layout). */
        val matrix: FloatArray,
        /** Darkening at the corners, 0..1. */
        val vignette: Float = 0f,
        /** Film grain strength (standard deviation in levels). */
        val grain: Float = 0f,
        /** Orange date stamp in the corner, like a 90s compact camera. */
        val dateStamp: Boolean = false,
    ) {
        val isNatural get() = id == NATURAL
        fun colorFilter() = ColorMatrixColorFilter(matrix)
    }

    const val NATURAL = "natural"

    val all: List<Look> by lazy {
        listOf(
            Look(NATURAL, "Natural", ColorMatrix().array),
            Look("vivid", "Vivid", m { saturation(1.35f); contrast(1.08f) }),
            Look("warm", "Golden", m { scaleRgb(1.07f, 1.0f, 0.86f); saturation(1.08f) }),
            Look("cool", "Cool", m { scaleRgb(0.92f, 1.0f, 1.08f); saturation(0.95f) }),
            Look("mono", "Mono", m { saturation(0f); contrast(1.05f) }),
            Look("noir", "Noir", m { saturation(0f); contrast(1.35f) }, vignette = 0.45f),
            Look("film", "Film", m { saturation(0.88f); scaleRgb(1.04f, 1.0f, 0.94f); fade(0.88f, 16f) }, vignette = 0.2f, grain = 5f),
            Look("retro", "Retro 98", m { saturation(1.1f); scaleRgb(1.06f, 1.03f, 0.84f); fade(0.84f, 24f) },
                vignette = 0.32f, grain = 7f, dateStamp = true),
        )
    }

    fun byId(id: String?): Look = all.firstOrNull { it.id == id } ?: all.first()

    // ------------------------------------------------------------------ matrix building

    private class Builder {
        val cm = ColorMatrix()
        fun saturation(s: Float) = cm.postConcat(ColorMatrix().apply { setSaturation(s) })
        fun scaleRgb(r: Float, g: Float, b: Float) = cm.postConcat(ColorMatrix().apply { setScale(r, g, b, 1f) })
        /** Contrast around mid-grey. */
        fun contrast(c: Float) {
            val t = 128f * (1 - c)
            cm.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        }
        /** Faded film: lower contrast, blacks lifted to [lift]. */
        fun fade(range: Float, lift: Float) =
            cm.postConcat(ColorMatrix(floatArrayOf(range, 0f, 0f, 0f, lift, 0f, range, 0f, 0f, lift, 0f, 0f, range, 0f, lift, 0f, 0f, 0f, 1f, 0f)))
    }

    private fun m(block: Builder.() -> Unit): FloatArray = Builder().apply(block).cm.array.copyOf()

    // ------------------------------------------------------------------ applying to photos

    /** Vignette darkening factor at normalised distance [r] (0 = centre, 1 = corner). */
    fun vignetteFactor(look: Look, r: Float): Float {
        if (look.vignette <= 0f) return 1f
        val t = ((r - 0.45f) / 0.55f).coerceIn(0f, 1f)
        return 1f - look.vignette * t * t * (3 - 2 * t)
    }

    /** Applies [look] to [bmp] in place (must be mutable ARGB_8888). Row by row: little extra memory. */
    fun apply(bmp: Bitmap, look: Look, seed: Long = 1L) {
        if (look.isNatural) return
        val w = bmp.width
        val h = bmp.height
        val a = look.matrix
        val row = IntArray(w)
        val cx = w / 2f
        val cy = h / 2f
        val half = sqrt(cx * cx + cy * cy)
        val rnd = java.util.Random(seed)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            val dy = (y - cy) / half
            for (x in 0 until w) {
                val p = row[x]
                val r = ((p shr 16) and 0xFF).toFloat()
                val g = ((p shr 8) and 0xFF).toFloat()
                val b = (p and 0xFF).toFloat()
                var nr = a[0] * r + a[1] * g + a[2] * b + a[4]
                var ng = a[5] * r + a[6] * g + a[7] * b + a[9]
                var nb = a[10] * r + a[11] * g + a[12] * b + a[14]
                if (look.vignette > 0f) {
                    val dx = (x - cx) / half
                    val f = vignetteFactor(look, sqrt(dx * dx + dy * dy))
                    nr *= f; ng *= f; nb *= f
                }
                if (look.grain > 0f) {
                    // Monochrome grain, like film (not coloured digital noise).
                    val n = (rnd.nextGaussian() * look.grain).toFloat()
                    nr += n; ng += n; nb += n
                }
                row[x] = (0xFF shl 24) or (clamp(nr) shl 16) or (clamp(ng) shl 8) or clamp(nb)
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
        if (look.dateStamp) drawDateStamp(Canvas(bmp), w, h)
    }

    private fun clamp(v: Float) = when {
        v <= 0f -> 0
        v >= 255f -> 255
        else -> v.toInt()
    }

    private val stampFormat get() = SimpleDateFormat("''yy MM dd", Locale.US)

    /** Orange date in the bottom-right corner, sized relative to the picture. */
    fun drawDateStamp(canvas: Canvas, w: Int, h: Int, date: Date = Date()) {
        val size = min(w, h) * 0.045f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0xFF, 0x8A, 0x1E)
            textSize = size
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
            setShadowLayer(size * 0.25f, 0f, 0f, Color.argb(0xC0, 0xFF, 0x50, 0x00))
        }
        canvas.drawText(stampFormat.format(date), w - size * 1.4f, h - size * 1.3f, paint)
    }

    /** Vignette as a drawable overlay (viewfinder): same curve as [apply], drawn as black alpha. */
    fun vignetteShader(look: Look, w: Float, h: Float): Shader? {
        if (look.vignette <= 0f) return null
        val half = sqrt(w * w + h * h) / 2
        val stops = FloatArray(12) { it / 11f }
        val colors = IntArray(stops.size) { i ->
            val alpha = ((1f - vignetteFactor(look, stops[i])) * 255).toInt().coerceIn(0, 255)
            Color.argb(alpha, 0, 0, 0)
        }
        return RadialGradient(w / 2, h / 2, max(1f, half), colors, stops, Shader.TileMode.CLAMP)
    }

    /** A small preview of [look] from a viewfinder frame (for the looks strip). */
    fun preview(src: Bitmap, look: Look): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        apply(out, look.let { Look(it.id, it.name, it.matrix, it.vignette, it.grain * 0.5f, dateStamp = false) })
        return out
    }
}
