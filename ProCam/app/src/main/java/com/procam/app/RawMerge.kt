package com.procam.app

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * RAW burst merge (the idea behind HDR+ / Super Res Zoom): instead of merging finished JPEGs,
 * merge the sensor's raw samples. Each frame is a Bayer mosaic (one colour per pixel); hand
 * shake puts every frame's red, green and blue samples at slightly different places, so together
 * they fill in each colour at full resolution — no demosaicing guesswork, no JPEG smoothing — and
 * the noise of many frames averages out.
 *
 * Pure maths on arrays (testable anywhere). The camera side collects [Burst]; this turns it into
 * linear sRGB, and [ToneFit] gives it the phone's own brightness and contrast.
 */
object RawMerge {

    /** Colour of each Bayer position: 0 red, 1 green, 2 blue. [cfa] = SENSOR_INFO_COLOR_FILTER_ARRANGEMENT. */
    fun channel(cfa: Int, x: Int, y: Int): Int {
        val i = (y and 1) * 2 + (x and 1)
        return when (cfa) {
            0 -> intArrayOf(0, 1, 1, 2)[i] // RGGB
            1 -> intArrayOf(1, 0, 2, 1)[i] // GRBG
            2 -> intArrayOf(1, 2, 0, 1)[i] // GBRG
            else -> intArrayOf(2, 1, 1, 0)[i] // BGGR
        }
    }

    /**
     * What's needed to develop the frames.
     * @param frames raw values, w × h each (an even-aligned crop of the sensor), frame 0 = reference.
     * @param offsets per frame (dx, dy) in raw pixels: frame(x + dx, y + dy) ≈ reference(x, y).
     * @param black per 2×2 position (row-major), [white] = saturation level.
     * @param wb white-balance gains R, G, B; [ccm] 3×3 row-major camera RGB → linear sRGB.
     * @param shading gain for (channel, x, y) in crop coordinates (lens corner darkening), or null.
     * @param noise variance model per channel: σ² = noise[2c]·v + noise[2c+1] (v normalised 0..1).
     */
    class Burst(
        val frames: List<ShortArray>, val w: Int, val h: Int, val cfa: Int,
        val offsets: List<Pair<Float, Float>>,
        val black: FloatArray, val white: Float,
        val wb: FloatArray, val ccm: FloatArray,
        val shading: ((Int, Float, Float) -> Float)?,
        val noise: DoubleArray?,
    )

    /** Normalised (0..1, black removed) value at raw (x, y) of frame [f]. */
    private fun value(b: Burst, f: ShortArray, x: Int, y: Int): Float {
        val black = b.black[(y and 1) * 2 + (x and 1)]
        return ((f[y * b.w + x].toInt() and 0xFFFF) - black) / (b.white - black)
    }

    /**
     * Green-only half-resolution picture of a frame (average of the two greens in each 2×2), as
     * grey pixels for the aligner. Coordinates: half-size, so offsets found on it ×2 = raw pixels.
     */
    fun greenPreview(b: Burst, frame: ShortArray): SuperRes.Frame {
        val hw = b.w / 2
        val hh = b.h / 2
        var maxV = 1e-6f
        val g = FloatArray(hw * hh)
        val chan = IntArray(4) { channel(b.cfa, it and 1, it shr 1) }
        for (y in 0 until hh) for (x in 0 until hw) {
            var s = 0f
            var n = 0
            for (dy in 0..1) for (dx in 0..1) {
                if (chan[dy * 2 + dx] == 1) {
                    s += value(b, frame, 2 * x + dx, 2 * y + dy)
                    n++
                }
            }
            val v = s / max(1, n)
            g[y * hw + x] = v
            if (v > maxV) maxV = v
        }
        // Gamma for alignment (dark detail counts too), 0..255 grey.
        val px = IntArray(hw * hh) { i ->
            val v = (255f * sqrt((g[i] / maxV).coerceIn(0f, 1f))).toInt()
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return SuperRes.Frame(px, hw, hh)
    }

    private const val EXP_STEPS = 64
    /** exp(-t) for t = 0..4 in EXP_STEPS steps per unit. */
    private val EXP = FloatArray(4 * EXP_STEPS + 2) { exp(-it.toFloat() / EXP_STEPS) }

    /**
     * Merges the region [x0, x1) × [y0, y1) of the output (output = crop × [scale]) into linear
     * sRGB (3 floats per pixel, row-major over the region).
     */
    fun mergeRegion(b: Burst, scale: Float, x0: Int, y0: Int, x1: Int, y1: Int): FloatArray {
        val rw = x1 - x0
        val rh = y1 - y0
        val n = rw * rh
        val acc = Array(3) { FloatArray(n) }
        val wt = Array(3) { FloatArray(n) }
        val chan = IntArray(4) { channel(b.cfa, it and 1, it shr 1) }
        val blackAt = FloatArray(4) { b.black[it] }
        val inv = FloatArray(4) { 1f / (b.white - b.black[it]) }
        // Kernel widths (output pixels): red and blue samples are sparser than green.
        val sigma = floatArrayOf(0.85f * scale, 0.6f * scale, 0.85f * scale)
        val radius = IntArray(3) { ceil(2 * sigma[it]).toInt() }
        val invS2 = FloatArray(3) { 1f / (2 * sigma[it] * sigma[it]) }
        // Raw area of the reference this region covers (plus kernel reach).
        val maxR = radius.max() + 1
        val rx0 = max(0, floor((x0 - maxR + 0.5f) / scale - 0.5f).toInt() - 3)
        val rx1 = min(b.w - 1, ceil((x1 + maxR + 0.5f) / scale - 0.5f).toInt() + 3)
        val ry0 = max(0, floor((y0 - maxR + 0.5f) / scale - 0.5f).toInt() - 3)
        val ry1 = min(b.h - 1, ceil((y1 + maxR + 0.5f) / scale - 0.5f).toInt() + 3)
        val aw = rx1 - rx0 + 1
        val ah = ry1 - ry0 + 1
        // The reference's range of each colour within ±2 px of every position (separable min/max),
        // for motion rejection: a sample inside that range fits; far outside is motion. (Comparing
        // with an average would reject genuine fine detail as "motion".)
        val lo = Array(3) { FloatArray(aw * ah) }
        val hi = Array(3) { FloatArray(aw * ah) }
        val ref = b.frames[0]
        run {
            val tmpLo = FloatArray(aw * ah)
            val tmpHi = FloatArray(aw * ah)
            for (c in 0..2) {
                for (y in 0 until ah) for (x in 0 until aw) {
                    var l = Float.MAX_VALUE
                    var h = -Float.MAX_VALUE
                    val ry = y + ry0
                    for (xx in max(0, x - 2)..min(aw - 1, x + 2)) {
                        val rx = xx + rx0
                        val pos = (ry and 1) * 2 + (rx and 1)
                        if (chan[pos] != c) continue
                        val v = ((ref[ry * b.w + rx].toInt() and 0xFFFF) - blackAt[pos]) * inv[pos]
                        if (v < l) l = v
                        if (v > h) h = v
                    }
                    tmpLo[y * aw + x] = l
                    tmpHi[y * aw + x] = h
                }
                for (y in 0 until ah) for (x in 0 until aw) {
                    var l = Float.MAX_VALUE
                    var h = -Float.MAX_VALUE
                    for (yy in max(0, y - 2)..min(ah - 1, y + 2)) {
                        val a = tmpLo[yy * aw + x]
                        val z = tmpHi[yy * aw + x]
                        if (a < l) l = a
                        if (z > h) h = z
                    }
                    lo[c][y * aw + x] = l
                    hi[c][y * aw + x] = h
                }
            }
        }
        for ((k, frame) in b.frames.withIndex()) {
            val (dx, dy) = b.offsets[k]
            val reference = k == 0
            val fy0 = max(0, floor(ry0 + dy).toInt())
            val fy1 = min(b.h - 1, ceil(ry1 + dy).toInt())
            val fx0 = max(0, floor(rx0 + dx).toInt())
            val fx1 = min(b.w - 1, ceil(rx1 + dx).toInt())
            for (fy in fy0..fy1) {
                val py = fy - dy
                val oy = (py + 0.5f) * scale - 0.5f - y0
                val ay = py.roundToInt() - ry0
                for (fx in fx0..fx1) {
                    val pos = (fy and 1) * 2 + (fx and 1)
                    val c = chan[pos]
                    val v = ((frame[fy * b.w + fx].toInt() and 0xFFFF) - blackAt[pos]) * inv[pos]
                    val px = fx - dx
                    var weight = 1f
                    if (!reference) {
                        val ax = px.roundToInt() - rx0
                        if (ax in 0 until aw && ay in 0 until ah) {
                            val l = lo[c][ay * aw + ax]
                            val h = hi[c][ay * aw + ax]
                            if (l <= h) {
                                val sd = noiseSd(b, c, v) * 3f + 0.01f
                                val d = max(l - v, v - h) - sd
                                if (d > 0) {
                                    weight = 1f - d / sd
                                    if (weight <= 0f) continue
                                }
                            }
                        }
                    }
                    val ox = (px + 0.5f) * scale - 0.5f - x0
                    val rad = radius[c]
                    val is2 = invS2[c]
                    val accC = acc[c]
                    val wtC = wt[c]
                    val yyA = max(0, floor(oy - rad).toInt())
                    val yyB = min(rh - 1, ceil(oy + rad).toInt())
                    val xxA = max(0, floor(ox - rad).toInt())
                    val xxB = min(rw - 1, ceil(ox + rad).toInt())
                    for (yy in yyA..yyB) {
                        val ey = yy - oy
                        val ey2 = ey * ey
                        val row = yy * rw
                        for (xx in xxA..xxB) {
                            val ex = xx - ox
                            val t = (ex * ex + ey2) * is2
                            if (t >= 4f) continue
                            val g = weight * EXP[(t * EXP_STEPS).toInt()]
                            accC[row + xx] += g * v
                            wtC[row + xx] += g
                        }
                    }
                }
            }
        }
        // Develop: average, lens shading, white balance, colour matrix → linear sRGB.
        val out = FloatArray(n * 3)
        val m = b.ccm
        val cam = FloatArray(3)
        for (yy in 0 until rh) for (xx in 0 until rw) {
            val i = yy * rw + xx
            // Sensor position of this output pixel (for the shading map).
            val sx = (xx + x0 + 0.5f) / scale - 0.5f
            val sy = (yy + y0 + 0.5f) / scale - 0.5f
            for (c in 0..2) {
                val v = if (wt[c][i] > 1e-4f) acc[c][i] / wt[c][i] else 0f
                val sh = b.shading?.invoke(c, sx, sy) ?: 1f
                cam[c] = (v * sh * b.wb[c]).coerceAtLeast(0f)
            }
            out[i * 3] = m[0] * cam[0] + m[1] * cam[1] + m[2] * cam[2]
            out[i * 3 + 1] = m[3] * cam[0] + m[4] * cam[1] + m[5] * cam[2]
            out[i * 3 + 2] = m[6] * cam[0] + m[7] * cam[1] + m[8] * cam[2]
        }
        return out
    }

    /**
     * The reference frame developed at 1/[bs] size: each block's average of each colour, with
     * shading, white balance and colour matrix → linear sRGB (3 floats per block).
     */
    fun developBlocks(b: Burst, bs: Int): FloatArray {
        val bw = b.w / bs
        val bh = b.h / bs
        val out = FloatArray(bw * bh * 3)
        val chan = IntArray(4) { channel(b.cfa, it and 1, it shr 1) }
        val f = b.frames[0]
        val m = b.ccm
        val sum = FloatArray(3)
        val cnt = IntArray(3)
        for (by in 0 until bh) for (bx in 0 until bw) {
            sum.fill(0f)
            cnt.fill(0)
            for (y in by * bs until (by + 1) * bs) for (x in bx * bs until (bx + 1) * bs) {
                val c = chan[(y and 1) * 2 + (x and 1)]
                sum[c] += value(b, f, x, y)
                cnt[c]++
            }
            val cx = (bx + 0.5f) * bs
            val cy = (by + 0.5f) * bs
            val cam = FloatArray(3) { c -> (sum[c] / max(1, cnt[c])) * (b.shading?.invoke(c, cx, cy) ?: 1f) * b.wb[c] }
            val i = (by * bw + bx) * 3
            out[i] = m[0] * cam[0] + m[1] * cam[1] + m[2] * cam[2]
            out[i + 1] = m[3] * cam[0] + m[4] * cam[1] + m[5] * cam[2]
            out[i + 2] = m[6] * cam[0] + m[7] * cam[1] + m[8] * cam[2]
        }
        return out
    }

    private fun noiseSd(b: Burst, c: Int, v: Float): Float {
        val p = b.noise ?: return 0.01f + 0.02f * sqrt(max(v, 0f))
        // Noise profile is per CFA channel (R, Ge, Go, B); use R, G(e), B.
        val idx = when (c) { 0 -> 0; 1 -> 1; else -> 3 }
        val s = p.getOrElse(idx * 2) { 0.0 }
        val o = p.getOrElse(idx * 2 + 1) { 0.0 }
        return sqrt(max(1e-8, s * v + o)).toFloat()
    }

    /**
     * Tone curve matching the camera's own JPEG: from pairs of (our linear luminance, the JPEG's
     * 0..255 brightness) over the picture, a monotonic lookup in log-luminance bins.
     */
    class ToneFit(lin: FloatArray, jpeg: FloatArray) {
        private val lo: Float
        private val hi: Float
        private val table: FloatArray

        init {
            require(lin.size == jpeg.size && lin.isNotEmpty())
            val logs = lin.map { ln(max(it, 1e-5f)) }
            lo = logs.min()
            hi = max(logs.max(), lo + 1e-3f)
            val bins = Array(BINS) { mutableListOf<Float>() }
            for (i in lin.indices) bins[binOf(logs[i])].add(jpeg[i])
            val med = FloatArray(BINS) { k -> bins[k].sorted().let { if (it.isEmpty()) Float.NaN else it[it.size / 2] } }
            // Fill empty bins by interpolation, then make it rise monotonically.
            val filled = med.copyOf()
            for (k in 0 until BINS) if (filled[k].isNaN()) {
                val prev = (k - 1 downTo 0).firstOrNull { !med[it].isNaN() }
                val next = (k + 1 until BINS).firstOrNull { !med[it].isNaN() }
                filled[k] = when {
                    prev != null && next != null -> med[prev] + (med[next] - med[prev]) * (k - prev) / (next - prev)
                    prev != null -> med[prev]
                    next != null -> med[next]
                    else -> 128f
                }
            }
            for (k in 1 until BINS) filled[k] = max(filled[k], filled[k - 1])
            table = filled
        }

        private fun binOf(l: Float) = (((l - lo) / (hi - lo)) * (BINS - 1)).roundToInt().coerceIn(0, BINS - 1)

        /** Target 0..255 brightness for linear luminance [y]. */
        fun apply(y: Float): Float {
            val t = ((ln(max(y, 1e-5f)) - lo) / (hi - lo) * (BINS - 1)).coerceIn(0f, BINS - 1f)
            val k = t.toInt().coerceAtMost(BINS - 2)
            val f = t - k
            return table[k] * (1 - f) + table[k + 1] * f
        }

        companion object {
            private const val BINS = 32
        }
    }

    /**
     * 3×3 colour matrix taking [ours] (linear RGB triples, already toned) to [theirs] (the camera
     * JPEG's linear RGB at the same places), least squares; identity if it can't be solved.
     */
    fun fitColour(ours: FloatArray, theirs: FloatArray): FloatArray {
        val a = DoubleArray(9) // ΣoᵀO (3×3)
        val bm = Array(3) { DoubleArray(3) } // Σ oᵢ tⱼ
        val n = ours.size / 3
        for (k in 0 until n) {
            for (i in 0..2) {
                val oi = ours[k * 3 + i].toDouble()
                for (j in 0..2) {
                    a[i * 3 + j] += oi * ours[k * 3 + j]
                    bm[i][j] += oi * theirs[k * 3 + j]
                }
            }
        }
        val inv = invert3(a) ?: return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        // M (rows = output channel) = (AᵀA)⁻¹ AᵀB, transposed into row-major output × input.
        val m = FloatArray(9)
        for (out in 0..2) for (inp in 0..2) {
            var v = 0.0
            for (k in 0..2) v += inv[inp * 3 + k] * bm[k][out]
            m[out * 3 + inp] = v.toFloat()
        }
        // Sanity: a wild fit (too few colours in the scene) is worse than none.
        val sane = (0..2).all { m[it * 4] in 0.3f..2.5f } && m.indices.filter { it % 4 != 0 }.all { m[it] in -1.2f..1.2f }
        return if (sane) m else floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }

    private fun invert3(m: DoubleArray): DoubleArray? {
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        if (abs(det) < 1e-12) return null
        val d = 1.0 / det
        return doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) * d, (m[2] * m[7] - m[1] * m[8]) * d, (m[1] * m[5] - m[2] * m[4]) * d,
            (m[5] * m[6] - m[3] * m[8]) * d, (m[0] * m[8] - m[2] * m[6]) * d, (m[2] * m[3] - m[0] * m[5]) * d,
            (m[3] * m[7] - m[4] * m[6]) * d, (m[1] * m[6] - m[0] * m[7]) * d, (m[0] * m[4] - m[1] * m[3]) * d,
        )
    }

    fun srgbToLinear(v: Float): Float {
        val c = v / 255f
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    fun linearToSrgb(v: Float): Int {
        val c = v.coerceIn(0f, 1f)
        val s = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1 / 2.4f) - 0.055f
        return (s * 255f).roundToInt().coerceIn(0, 255)
    }

    /**
     * Linear sRGB → display pixels with the fitted tone: brightness from [tone], colour ratios
     * kept (slightly richer, as phone JPEGs are), highlights rolled off instead of clipped.
     */
    fun toPixels(rgb: FloatArray, tone: ToneFit, colour: FloatArray? = null, saturation: Float = 1f): IntArray = IntArray(rgb.size / 3) { i ->
        val r = rgb[i * 3]
        val g = rgb[i * 3 + 1]
        val b = rgb[i * 3 + 2]
        val y = max(1e-5f, 0.2126f * r + 0.7152f * g + 0.0722f * b)
        val target = srgbToLinear(tone.apply(y))
        val gain = target / y
        var rr = r * gain
        var gg = g * gain
        var bb = b * gain
        // Colour matched to the camera's own JPEG (white balance, saturation), then any extra.
        if (colour != null) {
            val nr = colour[0] * rr + colour[1] * gg + colour[2] * bb
            val ng = colour[3] * rr + colour[4] * gg + colour[5] * bb
            val nb = colour[6] * rr + colour[7] * gg + colour[8] * bb
            rr = nr
            gg = ng
            bb = nb
        }
        rr = target + (rr - target) * saturation
        gg = target + (gg - target) * saturation
        bb = target + (bb - target) * saturation
        // Soft highlight roll-off on the brightest channel.
        val peak = max(rr, max(gg, bb))
        if (peak > 1f) {
            val k = 1f / peak
            rr = target + (rr * k - target * k)
            gg = target + (gg * k - target * k)
            bb = target + (bb * k - target * k)
        }
        (0xFF shl 24) or (linearToSrgb(rr) shl 16) or (linearToSrgb(gg) shl 8) or linearToSrgb(bb)
    }
}
