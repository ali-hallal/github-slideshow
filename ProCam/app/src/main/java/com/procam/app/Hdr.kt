package com.procam.app

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * HDR for backlit scenes (bright sky or window, dark subject).
 *
 * Like the HDR modes on phones: the burst is taken slightly darker so bright areas aren't blown
 * out, merged to remove noise, then [toneMap] brightens the dark areas back up — area by area,
 * guided by an edge-preserving smooth of the brightness so edges don't get halos — while bright
 * areas stay as they were.
 *
 * Pure functions on ARGB pixel arrays, testable anywhere.
 */
object Hdr {

    /** When to use HDR: from the preview's brightness histogram (and a face's brightness, if any). */
    data class Scene(val clipped: Float, val dark: Float, val faceVsFrame: Float? = null)

    /**
     * How many stops darker to shoot (0 = no HDR needed). Bright areas clipping while a good
     * part of the picture is dark, or a face clearly darker than the rest, calls for HDR.
     */
    fun stopsDarker(s: Scene): Float {
        val backlitFace = s.faceVsFrame != null && s.faceVsFrame < 0.6f && s.clipped > 0.01f
        val highContrast = s.clipped > 0.03f && s.dark > 0.15f
        if (!backlitFace && !highContrast) return 0f
        return when {
            s.clipped > 0.12f -> 2f
            s.clipped > 0.05f -> 1.5f
            else -> 1f
        }
    }

    /**
     * Exposure for the HDR burst: [stops] darker than auto. Keeps the shutter within what the
     * hands can hold; if ISO would go below its minimum, the shutter gets shorter instead.
     */
    fun plan(
        aeIso: Int, aeExposureNs: Long, safeShutterNs: Long, stops: Float,
        isoRange: android.util.Range<Int>, exposureRange: android.util.Range<Long>,
    ): Pair<Int, Long> {
        val product = aeIso.toDouble() * aeExposureNs / 2.0.pow(stops.toDouble())
        var t = min(aeExposureNs, safeShutterNs).coerceIn(exposureRange.lower, exposureRange.upper)
        var iso = (product / t).roundToInt()
        if (iso < isoRange.lower) {
            iso = isoRange.lower
            t = (product / iso).toLong().coerceIn(exposureRange.lower, exposureRange.upper)
        } else if (iso > isoRange.upper) {
            iso = isoRange.upper
            t = (product / iso).toLong().coerceIn(exposureRange.lower, exposureRange.upper)
        }
        return Pair(iso, t)
    }

    // ------------------------------------------------------------------ tone mapping

    private val TO_LINEAR = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }
    private const val ENC = 4096
    private val TO_SRGB = IntArray(ENC + 1) { i ->
        val v = i.toFloat() / ENC
        val s = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1 / 2.4f) - 0.055f
        (s * 255f).roundToInt().coerceIn(0, 255)
    }

    private fun encode(v: Float): Int = TO_SRGB[(v.coerceIn(0f, 1f) * ENC).toInt()]

    /** Gentle shoulder: values approaching white roll off instead of clipping hard. */
    private fun shoulder(v: Float): Float = if (v <= KNEE) v else KNEE + (1 - KNEE) * (1 - exp(-(v - KNEE) / (1 - KNEE)))

    private const val KNEE = 0.8f
    private const val SCALE = 8 // the brightness "map" is computed at 1/8 size
    private const val LOG_LO = -7f // log2 of the darkest level we care about (~0.008)
    /** Areas brighter than this (0..1 of the log-brightness range) get progressively less lift. */
    private const val LIFT_FROM = 0.65f
    /** Edge-preserving smoothing: smaller eps keeps edges (sky / building) sharper = fewer halos. */
    private const val GUIDE_RADIUS = 3
    private const val GUIDE_EPS = 0.05f

    /**
     * Brightens a picture shot [stops] darker: dark areas get up to [stops] of brightening, bright
     * areas almost none. In place.
     */
    fun toneMap(px: IntArray, w: Int, h: Int, stops: Float) {
        // 1. Small map of log brightness (box-averaged linear luminance).
        val sw = max(1, w / SCALE)
        val sh = max(1, h / SCALE)
        val logL = FloatArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            var sum = 0f
            var n = 0
            for (yy in y * SCALE until min(h, (y + 1) * SCALE)) for (xx in x * SCALE until min(w, (x + 1) * SCALE)) {
                sum += lum(px[yy * w + xx])
                n++
            }
            logL[y * sw + x] = ln((sum / max(1, n)) + 1e-4f) / LN2
        }
        // 2. Edge-preserving smooth (guided filter): areas, not pixels, decide the brightening,
        //    and strong edges (building vs sky) stay sharp in the map, so no halos.
        val base = guided(logL, sw, sh, radius = GUIDE_RADIUS, eps = GUIDE_EPS)
        // 3. Per pixel: brightening from the map, in linear light, with a soft shoulder.
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / SCALE - 0.5f).coerceIn(0f, sh - 1f)
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, sh - 1)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / SCALE - 0.5f).coerceIn(0f, sw - 1f)
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, sw - 1)
                val tx = fx - x0
                val b = (base[y0 * sw + x0] * (1 - tx) + base[y0 * sw + x1] * tx) * (1 - ty) +
                    (base[y1 * sw + x0] * (1 - tx) + base[y1 * sw + x1] * tx) * ty
                // b is log2 brightness of the (underexposed) area: map dark → full lift, bright → none.
                val t = ((b - LOG_LO) / (0f - LOG_LO)).coerceIn(0f, 1f) // 0 = darkest, 1 = white
                val lift = stops * (1f - smooth(LIFT_FROM, 1f, t))
                val gain = 2f.pow(lift)
                val p = px[y * w + x]
                val r = encode(shoulder(TO_LINEAR[(p shr 16) and 0xFF] * gain))
                val g = encode(shoulder(TO_LINEAR[(p shr 8) and 0xFF] * gain))
                val bb = encode(shoulder(TO_LINEAR[p and 0xFF] * gain))
                px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bb
            }
        }
    }

    /** What a normal (non-HDR) photo would have looked like: the dark frame simply brightened. */
    fun naiveBrighten(px: IntArray, stops: Float): IntArray {
        val gain = 2f.pow(stops)
        return IntArray(px.size) { i ->
            val p = px[i]
            val r = encode(TO_LINEAR[(p shr 16) and 0xFF] * gain)
            val g = encode(TO_LINEAR[(p shr 8) and 0xFF] * gain)
            val b = encode(TO_LINEAR[p and 0xFF] * gain)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private const val LN2 = 0.6931472f

    private fun lum(p: Int) =
        0.2126f * TO_LINEAR[(p shr 16) and 0xFF] + 0.7152f * TO_LINEAR[(p shr 8) and 0xFF] + 0.0722f * TO_LINEAR[p and 0xFF]

    private fun smooth(e0: Float, e1: Float, v: Float): Float {
        val t = ((v - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /** Self-guided filter (He et al.): an edge-preserving smooth built from box filters. */
    fun guided(p: FloatArray, w: Int, h: Int, radius: Int, eps: Float): FloatArray {
        val n = p.size
        val meanP = p.copyOf().also { PortraitProcessor.boxBlur(it, w, h, radius) }
        val sq = FloatArray(n) { p[it] * p[it] }.also { PortraitProcessor.boxBlur(it, w, h, radius) }
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val variance = sq[i] - meanP[i] * meanP[i]
            a[i] = variance / (variance + eps)
            b[i] = meanP[i] - a[i] * meanP[i]
        }
        PortraitProcessor.boxBlur(a, w, h, radius)
        PortraitProcessor.boxBlur(b, w, h, radius)
        return FloatArray(n) { a[it] * p[it] + b[it] }
    }
}
