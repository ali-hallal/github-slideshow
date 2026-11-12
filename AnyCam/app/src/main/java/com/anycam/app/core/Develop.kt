package com.anycam.app.core

import kotlin.math.pow

/**
 * Develops merged sensor data into a picture.
 *
 * Each pixel measured only one colour; the other two are worked out from its neighbours
 * (the Malvar–He–Cutler method: the missing colour follows the neighbours of that colour,
 * corrected by how the pixel's own colour changes there, which keeps edges crisp). The
 * sensor's colours are then turned into standard ones with the matrix the camera reports,
 * given the standard brightness curve, and finally the [Look].
 */
object Develop {

    fun develop(m: RawMerge.Merged, info: RawInfo, look: Look = Look.PLAIN): Yuv {
        val w = m.w
        val h = m.h
        val outY = ByteArray(w * h)
        val outU = ByteArray(w * h / 4)
        val outV = ByteArray(w * h / 4)
        val ccm = info.ccm
        val a = look.colour
        val own = IntArray(4) { info.colour(it) }
        val besideIsRed = BooleanArray(4) { info.colour(it xor 1) == 0 }
        Parallel.rows(h / 2) { cy ->
            val rgb = FloatArray(3)
            for (cx in 0 until w / 2) {
                var su = 0f
                var sv = 0f
                for (p in 0..3) {
                    val x = cx * 2 + p % 2
                    val y = cy * 2 + p / 2
                    val pos = (y and 1) * 2 + (x and 1)
                    pixel(m, x, y, own[pos], besideIsRed[pos], rgb)
                    // Sensor colours → standard colours (linear light), then the display curve.
                    val lr = (ccm[0] * rgb[0] + ccm[1] * rgb[1] + ccm[2] * rgb[2]).coerceIn(0f, 1f)
                    val lg = (ccm[3] * rgb[0] + ccm[4] * rgb[1] + ccm[5] * rgb[2]).coerceIn(0f, 1f)
                    val lb = (ccm[6] * rgb[0] + ccm[7] * rgb[1] + ccm[8] * rgb[2]).coerceIn(0f, 1f)
                    var r = GAMMA[(lr * GAMMA_STEPS).toInt()]
                    var g = GAMMA[(lg * GAMMA_STEPS).toInt()]
                    var b = GAMMA[(lb * GAMMA_STEPS).toInt()]
                    // The look: brightness curve (as a gain, so colours keep their hue), then colour.
                    val gain = look.gain[(0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)]
                    r *= gain
                    g *= gain
                    b *= gain
                    val fr = (a[0] * r + a[1] * g + a[2] * b + a[3]).coerceIn(0f, 255f)
                    val fg = (a[4] * r + a[5] * g + a[6] * b + a[7]).coerceIn(0f, 255f)
                    val fb = (a[8] * r + a[9] * g + a[10] * b + a[11]).coerceIn(0f, 255f)
                    outY[y * w + x] = (0.299f * fr + 0.587f * fg + 0.114f * fb + 0.5f).toInt().coerceIn(0, 255).toByte()
                    su += -0.168736f * fr - 0.331264f * fg + 0.5f * fb
                    sv += 0.5f * fr - 0.418688f * fg - 0.081312f * fb
                }
                outU[cy * (w / 2) + cx] = (su / 4 + 128.5f).toInt().coerceIn(0, 255).toByte()
                outV[cy * (w / 2) + cx] = (sv / 4 + 128.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return Yuv(w, h, outY, outU, outV)
    }

    /** Red, green and blue at one pixel, from its own sample and its neighbours'. */
    internal fun pixel(m: RawMerge.Merged, x: Int, y: Int, own: Int, besideIsRed: Boolean, out: FloatArray) {
        val s = m.stride
        val l = m.light
        val i = (y + 2) * s + x + 2
        val c = l[i]
        val cross = l[i - 1] + l[i + 1] + l[i - s] + l[i + s]
        val diagonal = l[i - s - 1] + l[i - s + 1] + l[i + s - 1] + l[i + s + 1]
        val farH = l[i - 2] + l[i + 2]
        val farV = l[i - 2 * s] + l[i + 2 * s]
        if (own == 1) {
            // A green pixel: red and blue lie beside it, one pair left-right, the other up-down.
            val beside = ((5 * c + 4 * (l[i - 1] + l[i + 1]) - diagonal - farH + 0.5f * farV) / 8f).coerceAtLeast(0f)
            val above = ((5 * c + 4 * (l[i - s] + l[i + s]) - diagonal - farV + 0.5f * farH) / 8f).coerceAtLeast(0f)
            out[0] = if (besideIsRed) beside else above
            out[1] = c
            out[2] = if (besideIsRed) above else beside
        } else {
            val green = ((4 * c + 2 * cross - farH - farV) / 8f).coerceAtLeast(0f)
            val other = ((6 * c + 2 * diagonal - 1.5f * (farH + farV)) / 8f).coerceAtLeast(0f)
            out[0] = if (own == 0) c else other
            out[1] = green
            out[2] = if (own == 0) other else c
        }
    }

    /**
     * The colours of the merged data on a coarse grid ([gw]×[gh] cells; red, green, blue per
     * cell, 0..255 after the display curve and before any look): what [Look.fit] compares with
     * the phone's own picture.
     */
    fun coarse(m: RawMerge.Merged, info: RawInfo, gw: Int, gh: Int): FloatArray {
        val out = FloatArray(gw * gh * 3)
        val ccm = info.ccm
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val x0 = gx * m.w / gw
            val x1 = ((gx + 1) * m.w / gw).coerceAtLeast(x0 + 2)
            val y0 = gy * m.h / gh
            val y1 = ((gy + 1) * m.h / gh).coerceAtLeast(y0 + 2)
            val sum = FloatArray(3)
            val n = IntArray(3)
            var y = y0
            while (y < y1 && y < m.h) {
                var x = x0
                while (x < x1 && x < m.w) {
                    val c = info.colour((y and 1) * 2 + (x and 1))
                    sum[c] += m.light[(y + 2) * m.stride + x + 2]
                    n[c]++
                    x++
                }
                y++
            }
            val r = sum[0] / n[0].coerceAtLeast(1)
            val g = sum[1] / n[1].coerceAtLeast(1)
            val b = sum[2] / n[2].coerceAtLeast(1)
            val o = (gy * gw + gx) * 3
            out[o] = GAMMA[((ccm[0] * r + ccm[1] * g + ccm[2] * b).coerceIn(0f, 1f) * GAMMA_STEPS).toInt()]
            out[o + 1] = GAMMA[((ccm[3] * r + ccm[4] * g + ccm[5] * b).coerceIn(0f, 1f) * GAMMA_STEPS).toInt()]
            out[o + 2] = GAMMA[((ccm[6] * r + ccm[7] * g + ccm[8] * b).coerceIn(0f, 1f) * GAMMA_STEPS).toInt()]
        }
        return out
    }

    /** Linear light 0..1 → 0..255 on the standard display curve. */
    internal fun display(v: Float): Float = GAMMA[(v * GAMMA_STEPS).toInt()]

    private const val GAMMA_STEPS = 4095
    /** The standard display curve (sRGB): linear light 0..1 → 0..255. */
    private val GAMMA = FloatArray(GAMMA_STEPS + 1) { i ->
        val v = i / GAMMA_STEPS.toFloat()
        255f * if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - 0.055f
    }
}
