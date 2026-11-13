package com.anycam.app.core

import kotlin.math.abs

/**
 * Brings out the fine detail of a merged photo.
 *
 * Merging removes noise, which leaves room to sharpen without sharpening grain: differences
 * smaller than what noise is left are ignored, real edges are strengthened, and no pixel is
 * pushed past its neighbours' range (so edges get crisper without bright or dark fringes).
 * Brightness only; colour is left alone.
 */
object Detail {

    /**
     * [noiseLeft]: the noise still in the photo (same units as [Merge.Result.noise], after
     * merging); [amount]: how strongly edges are strengthened.
     */
    fun sharpen(photo: Yuv, noiseLeft: Float, amount: Float = AMOUNT): Yuv {
        val w = photo.w
        val h = photo.h
        val src = photo.y
        if (w < 5 || h < 5 || amount <= 0f) return photo
        val out = ByteArray(w * h)
        val floor = (noiseLeft * 1.5f).coerceIn(1f, 12f)
        // A soft copy (1-2-1 blur, across then down): what's left when it is taken away is the detail.
        val across = ShortArray(w * h)
        Parallel.rows(h) { y ->
            val o = y * w
            for (x in 0 until w) {
                val l = src[o + if (x > 0) x - 1 else x].toInt() and 0xFF
                val c = src[o + x].toInt() and 0xFF
                val r = src[o + if (x < w - 1) x + 1 else x].toInt() and 0xFF
                across[o + x] = (l + 2 * c + r).toShort()
            }
        }
        Parallel.rows(h) { y ->
            val o = y * w
            val up = if (y > 0) o - w else o
            val down = if (y < h - 1) o + w else o
            for (x in 0 until w) {
                val soft = (across[up + x] + 2 * across[o + x] + across[down + x]) / 16f
                val v = src[o + x].toInt() and 0xFF
                var d = v - soft
                // Below the noise that's left: not detail.
                d = if (d > floor) d - floor else if (d < -floor) d + floor else 0f
                if (d == 0f) {
                    out[o + x] = src[o + x]
                    continue
                }
                // Never past the brightest or darkest neighbour.
                var lo = v
                var hi = v
                val x0 = if (x > 0) x - 1 else x
                val x1 = if (x < w - 1) x + 1 else x
                for (row in intArrayOf(up, o, down)) {
                    for (xx in x0..x1) {
                        val n = src[row + xx].toInt() and 0xFF
                        if (n < lo) lo = n
                        if (n > hi) hi = n
                    }
                }
                // Up to the neighbours' range and a little beyond it (held strictly inside, strong
                // sharpening flattens edges into steps).
                val room = (hi - lo) / 6
                val sharp = (v + amount * d + 0.5f).toInt()
                out[o + x] = sharp.coerceIn(maxOf(0, lo - room), minOf(255, hi + room)).toByte()
            }
        }
        return Yuv(w, h, out, photo.u, photo.v)
    }

    const val AMOUNT = 1.5f
    /**
     * Local contrast: each area of the picture is set a little apart from its surroundings (a
     * wide, soft difference, about 1 % of the picture across), as phone cameras do to make a
     * photo look crisp and three-dimensional rather than flat. Mid-tones take most of it;
     * near-black and near-white take little, so nothing is pushed into clipping. Brightness only.
     */
    fun clarity(photo: Yuv, amount: Float = CLARITY): Yuv {
        val w = photo.w
        val h = photo.h
        val radius = (maxOf(w, h) / 100).coerceAtLeast(2)
        if (w <= 2 * radius || h <= 2 * radius || amount <= 0f) return photo
        val src = photo.y
        // A wide soft copy: three box passes each way (close to a Gaussian).
        var a = IntArray(w * h) { src[it].toInt() and 0xFF }
        var b = IntArray(w * h)
        repeat(3) {
            boxRows(a, b, w, h, radius)
            boxCols(b, a, w, h, radius)
        }
        val out = ByteArray(w * h)
        Parallel.rows(h) { y ->
            val o = y * w
            for (x in 0 until w) {
                val v = src[o + x].toInt() and 0xFF
                val mid = 4f * v * (255 - v) / (255f * 255f)
                out[o + x] = (v + amount * mid * (v - a[o + x]) + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return Yuv(w, h, out, photo.u, photo.v)
    }

    private fun boxRows(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        Parallel.rows(h) { y ->
            val o = y * w
            var sum = 0
            for (i in -r..r) sum += src[o + i.coerceIn(0, w - 1)]
            val n = 2 * r + 1
            for (x in 0 until w) {
                dst[o + x] = sum / n
                sum += src[o + (x + r + 1).coerceAtMost(w - 1)] - src[o + (x - r).coerceAtLeast(0)]
            }
        }
    }

    private fun boxCols(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val n = 2 * r + 1
        Parallel.rows((w + 63) / 64) { band ->
            val x0 = band * 64
            val x1 = minOf(w, x0 + 64)
            val sum = IntArray(x1 - x0)
            for (x in x0 until x1) for (i in -r..r) sum[x - x0] += src[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                val add = (y + r + 1).coerceAtMost(h - 1) * w
                val sub = (y - r).coerceAtLeast(0) * w
                for (x in x0 until x1) {
                    dst[y * w + x] = sum[x - x0] / n
                    sum[x - x0] += src[add + x] - src[sub + x]
                }
            }
        }
    }

    const val CLARITY = 0.45f

    /** A sensor photo's finishing: calmer colour, local contrast, then sharpening (compiled where possible). */
    fun finishSensor(photo: Yuv, noiseLeft: Float): Yuv {
        if (Native.use && photo.w % 2 == 0 && photo.h % 2 == 0) {
            val y = ByteArray(photo.w * photo.h)
            val u = ByteArray(photo.cw * photo.ch)
            val v = ByteArray(photo.cw * photo.ch)
            Native.finish(photo.w, photo.h, photo.y, photo.u, photo.v, CLARITY, noiseLeft, SENSOR_AMOUNT, y, u, v)
            return Yuv(photo.w, photo.h, y, u, v)
        }
        return sharpen(clarity(calmColour(photo)), noiseLeft, SENSOR_AMOUNT)
    }

    /** For photos developed from the sensor: nothing has sharpened them yet, as a phone's own processing would have. */
    const val SENSOR_AMOUNT = 2.4f

    /**
     * Smooths colour slightly (1-2-1 across and down on the colour planes), leaving brightness
     * alone: takes out the false colour that fine patterns leave when a picture is developed
     * from the sensor, which the eye reads as tint, never as detail.
     */
    fun calmColour(photo: Yuv): Yuv = Yuv(photo.w, photo.h, photo.y, soften(photo.u, photo.cw, photo.ch), soften(photo.v, photo.cw, photo.ch))

    private fun soften(src: ByteArray, w: Int, h: Int): ByteArray {
        if (w < 3 || h < 3) return src
        val across = ShortArray(w * h)
        Parallel.rows(h) { y ->
            val o = y * w
            for (x in 0 until w) {
                val l = src[o + if (x > 0) x - 1 else x].toInt() and 0xFF
                val r = src[o + if (x < w - 1) x + 1 else x].toInt() and 0xFF
                across[o + x] = (l + 2 * (src[o + x].toInt() and 0xFF) + r).toShort()
            }
        }
        val out = ByteArray(w * h)
        Parallel.rows(h) { y ->
            val o = y * w
            val up = if (y > 0) o - w else o
            val down = if (y < h - 1) o + w else o
            for (x in 0 until w) out[o + x] = ((across[up + x] + 2 * across[o + x] + across[down + x] + 8) / 16).toByte()
        }
        return out
    }
}
