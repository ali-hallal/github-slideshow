package com.procam.app

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Undoing hand-shake blur with the gyroscope: the gyroscope says exactly how the phone turned
 * while the shutter was open, which is the path every point of the picture was smeared along
 * (the blur "kernel"). Knowing it, Richardson–Lucy deconvolution recovers the sharp picture —
 * far better than general sharpening, which can't know which way things were smeared.
 *
 * Because phones differ in how the gyroscope's axes map onto the picture (and optical
 * stabilisation already removed an unknown part of the shake), the few possible kernels are each
 * tried on a detailed patch and the one giving the crispest result wins; if none is clearly
 * better than the photo as it is, the photo is left alone.
 */
object MotionDeblur {

    /** A blur kernel: taps at (dx, dy) with weights summing to 1. */
    class Kernel(val dx: IntArray, val dy: IntArray, val w: FloatArray) {
        val radius = max(dx.maxOf { abs(it) }, dy.maxOf { abs(it) })
    }

    /**
     * The gyroscope path (turn about the device's x and y axes, radians, interleaved) as a path
     * of picture displacement in pixels of the upright photo. [pxPerRad]: pixels per radian at the
     * photo's size; [deviceRotation]: how far the phone was turned from its natural orientation
     * (degrees clockwise) when the photo was taken.
     */
    fun pathPx(gyro: FloatArray, pxPerRad: Float, deviceRotation: Int): FloatArray {
        val out = FloatArray(gyro.size)
        for (i in 0 until gyro.size / 2) {
            // Back camera, natural orientation: turning about y slides the picture sideways,
            // about x up and down.
            val nx = gyro[2 * i + 1] * pxPerRad
            val ny = gyro[2 * i] * pxPerRad
            val (ux, uy) = when (((deviceRotation % 360) + 360) % 360) {
                90 -> ny to -nx
                180 -> -nx to -ny
                270 -> -ny to nx
                else -> nx to ny
            }
            out[2 * i] = ux
            out[2 * i + 1] = uy
        }
        return out
    }

    /** Length of the path (pixels). */
    fun length(path: FloatArray): Float {
        var l = 0f
        for (i in 1 until path.size / 2) {
            val dx = path[2 * i] - path[2 * i - 2]
            val dy = path[2 * i + 1] - path[2 * i - 1]
            l += sqrt(dx * dx + dy * dy)
        }
        return l
    }

    /**
     * The ways the path could map onto the picture that the geometry can't rule out: each axis
     * mirrored or not ([path] first). (Swapped axes are ruled out by the phone's orientation; the
     * size comes from the gyroscope — sharpness can judge a mirror but not a size, since a too
     * big kernel's ringing looks "crisp" too.)
     */
    fun variants(path: FloatArray): List<FloatArray> = listOf(1f to 1f, -1f to -1f, -1f to 1f, 1f to -1f).map { (sx, sy) ->
        FloatArray(path.size) { i -> path[i] * if (i % 2 == 0) sx else sy }
    }

    /**
     * What optical stabilisation left of the shake. The lens shift is learnt against the
     * gyroscope over the moments before the photo (shift ≈ C · turn, a 2×2 fit, so no sign or
     * axis conventions are assumed), and the residual turn during the exposure is
     * turn − C⁻¹ · shift. All paths are cumulative (x, y interleaved) at the same times;
     * [calGyro] / [calOis] for calibration, [gyro] / [ois] during the exposure. Null if the lens
     * doesn't follow the gyroscope well enough to tell.
     */
    fun oisResidual(calGyro: FloatArray, calOis: FloatArray, gyro: FloatArray, ois: FloatArray): FloatArray? {
        var sgg00 = 0.0; var sgg01 = 0.0; var sgg11 = 0.0
        var sog00 = 0.0; var sog01 = 0.0; var sog10 = 0.0; var sog11 = 0.0
        val n = calGyro.size / 2
        if (n < 8 || calOis.size != calGyro.size) return null
        val dox = DoubleArray(n - 1)
        val doy = DoubleArray(n - 1)
        val dgx = DoubleArray(n - 1)
        val dgy = DoubleArray(n - 1)
        for (i in 1 until n) {
            dgx[i - 1] = (calGyro[2 * i] - calGyro[2 * i - 2]).toDouble()
            dgy[i - 1] = (calGyro[2 * i + 1] - calGyro[2 * i - 1]).toDouble()
            dox[i - 1] = (calOis[2 * i] - calOis[2 * i - 2]).toDouble()
            doy[i - 1] = (calOis[2 * i + 1] - calOis[2 * i - 1]).toDouble()
        }
        for (i in dgx.indices) {
            sgg00 += dgx[i] * dgx[i]; sgg01 += dgx[i] * dgy[i]; sgg11 += dgy[i] * dgy[i]
            sog00 += dox[i] * dgx[i]; sog01 += dox[i] * dgy[i]; sog10 += doy[i] * dgx[i]; sog11 += doy[i] * dgy[i]
        }
        val det = sgg00 * sgg11 - sgg01 * sgg01
        if (det <= 1e-18 * max(1.0, sgg00 * sgg11)) return null
        // C = Sog · Sgg⁻¹
        val i00 = sgg11 / det; val i01 = -sgg01 / det; val i11 = sgg00 / det
        val c00 = sog00 * i00 + sog01 * i01
        val c01 = sog00 * i01 + sog01 * i11
        val c10 = sog10 * i00 + sog11 * i01
        val c11 = sog10 * i01 + sog11 * i11
        var res = 0.0
        var tot = 0.0
        for (i in dgx.indices) {
            val ex = dox[i] - (c00 * dgx[i] + c01 * dgy[i])
            val ey = doy[i] - (c10 * dgx[i] + c11 * dgy[i])
            res += ex * ex + ey * ey
            tot += dox[i] * dox[i] + doy[i] * doy[i]
        }
        val fit = if (tot > 0) 1 - res / tot else 0.0
        val cdet = c00 * c11 - c01 * c10
        log("ois fit: C = [%.1f %.1f; %.1f %.1f], R² %.2f".format(c00, c01, c10, c11, fit))
        if (fit < MIN_OIS_FIT || abs(cdet) < 1e-9) return null
        val out = FloatArray(gyro.size)
        for (i in 0 until gyro.size / 2) {
            val ox = (ois[2 * i] - ois[0]).toDouble()
            val oy = (ois[2 * i + 1] - ois[1]).toDouble()
            // C⁻¹ · shift
            val tx = (c11 * ox - c01 * oy) / cdet
            val ty = (-c10 * ox + c00 * oy) / cdet
            out[2 * i] = (gyro[2 * i] - gyro[0] - tx).toFloat()
            out[2 * i + 1] = (gyro[2 * i + 1] - gyro[1] - ty).toFloat()
        }
        return out
    }

    /** Linear interpolation of samples ([ts], [xs], [ys]) at [t]; null outside them. */
    fun sampleAt(ts: LongArray, xs: FloatArray, ys: FloatArray, t: Long): Pair<Float, Float>? {
        if (ts.isEmpty() || t < ts.first() || t > ts.last()) return null
        var lo = 0
        var hi = ts.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (ts[mid] <= t) lo = mid else hi = mid
        }
        if (ts[hi] == ts[lo]) return xs[lo] to ys[lo]
        val f = (t - ts[lo]).toFloat() / (ts[hi] - ts[lo])
        return (xs[lo] + (xs[hi] - xs[lo]) * f) to (ys[lo] + (ys[hi] - ys[lo]) * f)
    }

    /** [path] scaled by [s] (optical stabilisation removed part of the movement). */
    fun scaled(path: FloatArray, s: Float) = FloatArray(path.size) { path[it] * s }

    /** The kernel of [path]: equal time at each point (bilinear), centred on its middle. */
    fun kernel(path: FloatArray): Kernel {
        val n = path.size / 2
        // Densify so consecutive points are at most 0.5 px apart.
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (i in 0 until n) {
            if (i == 0) {
                xs += path[0]; ys += path[1]
                continue
            }
            val x0 = path[2 * i - 2]
            val y0 = path[2 * i - 1]
            val x1 = path[2 * i]
            val y1 = path[2 * i + 1]
            val steps = max(1, ceil(max(abs(x1 - x0), abs(y1 - y0)) / 0.5f).toInt())
            for (k in 1..steps) {
                xs += x0 + (x1 - x0) * k / steps
                ys += y0 + (y1 - y0) * k / steps
            }
        }
        val cx = (xs.min() + xs.max()) / 2
        val cy = (ys.min() + ys.max()) / 2
        val map = HashMap<Long, Float>()
        for (i in xs.indices) {
            val x = xs[i] - cx
            val y = ys[i] - cy
            val ix = floor(x).toInt()
            val iy = floor(y).toInt()
            val fx = x - ix
            val fy = y - iy
            fun add(a: Int, b: Int, v: Float) {
                if (v <= 0f) return
                val key = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)
                map[key] = (map[key] ?: 0f) + v
            }
            add(ix, iy, (1 - fx) * (1 - fy))
            add(ix + 1, iy, fx * (1 - fy))
            add(ix, iy + 1, (1 - fx) * fy)
            add(ix + 1, iy + 1, fx * fy)
        }
        val total = map.values.sum()
        val entries = map.entries.filter { it.value > total * 1e-3f }
        val sum = entries.sumOf { it.value.toDouble() }.toFloat()
        return Kernel(
            IntArray(entries.size) { (entries[it].key shr 32).toInt() },
            IntArray(entries.size) { entries[it].key.toInt() },
            FloatArray(entries.size) { entries[it].value / sum },
        )
    }

    /** [f] smeared along [path] (tests: a shaken photo with a known shake). */
    fun blur(f: SuperRes.Frame, path: FloatArray): SuperRes.Frame {
        val k = kernel(path)
        val out = IntArray(f.px.size)
        for (y in 0 until f.h) for (x in 0 until f.w) {
            var r = 0f
            var g = 0f
            var b = 0f
            for (t in k.w.indices) {
                val p = f.px[(y + k.dy[t]).coerceIn(0, f.h - 1) * f.w + (x + k.dx[t]).coerceIn(0, f.w - 1)]
                r += k.w[t] * ((p shr 16) and 0xFF)
                g += k.w[t] * ((p shr 8) and 0xFF)
                b += k.w[t] * (p and 0xFF)
            }
            out[y * f.w + x] = (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        }
        return SuperRes.Frame(out, f.w, f.h)
    }

    /** A straight blur of [length] px at [angle] radians (tests, checks). */
    fun line(length: Float, angle: Float, points: Int = 16): FloatArray =
        FloatArray(2 * (points + 1)) { i -> val t = (i / 2).toFloat() / points * length; if (i % 2 == 0) t * cos(angle) else t * sin(angle) }

    // ------------------------------------------------------------------ deconvolution

    /**
     * Richardson–Lucy on [lum] ([w] × [h], values > 0) for [k]; returns the estimate.
     * Model: blurred(p) = Σ w·sharp(p + d).
     */
    fun richardsonLucy(lum: FloatArray, w: Int, h: Int, k: Kernel, iterations: Int): FloatArray {
        val n = w * h
        val est = lum.copyOf()
        val conv = FloatArray(n)
        val ratio = FloatArray(n)
        repeat(iterations) {
            apply(est, conv, w, h, k, +1)
            for (i in 0 until n) ratio[i] = lum[i] / max(conv[i], 1e-3f)
            apply(ratio, conv, w, h, k, -1)
            for (i in 0 until n) est[i] *= conv[i]
        }
        return est
    }

    private fun apply(src: FloatArray, dst: FloatArray, w: Int, h: Int, k: Kernel, sign: Int) {
        val taps = k.w.size
        val r = k.radius
        for (y in 0 until h) {
            val inner = y >= r && y < h - r
            for (x in 0 until w) {
                var s = 0f
                if (inner && x >= r && x < w - r) {
                    val base = y * w + x
                    for (t in 0 until taps) s += k.w[t] * src[base + sign * (k.dy[t] * w + k.dx[t])]
                } else {
                    for (t in 0 until taps) {
                        val xx = (x + sign * k.dx[t]).coerceIn(0, w - 1)
                        val yy = (y + sign * k.dy[t]).coerceIn(0, h - 1)
                        s += k.w[t] * src[yy * w + xx]
                    }
                }
                dst[y * w + x] = s
            }
        }
    }

    /**
     * How crisp a picture is: gradients' L1/L2 ratio (lower = fewer, stronger edges = sharper;
     * blur spreads edges, noise and ringing add many small gradients — both raise it).
     */
    fun crispness(v: FloatArray, w: Int, h: Int, border: Int): Double {
        var l1 = 0.0
        var l2 = 0.0
        var n = 0
        for (y in border until h - border - 1) for (x in border until w - border - 1) {
            val i = y * w + x
            val gx = (v[i + 1] - v[i]).toDouble()
            val gy = (v[i + w] - v[i]).toDouble()
            l1 += abs(gx) + abs(gy)
            l2 += gx * gx + gy * gy
            n++
        }
        if (n == 0 || l2 <= 0) return Double.MAX_VALUE
        return l1 / sqrt(l2 * n)
    }

    class Choice(val path: FloatArray, val variant: Int, val gain: Double)

    /**
     * Which of [candidates] (paths) really is the blur, judged on the [patch]-sized area around
     * ([cx], [cy]) of [f]; null if none clearly beats leaving the photo alone.
     */
    fun choose(f: SuperRes.Frame, candidates: List<FloatArray>, cx: Int, cy: Int, patch: Int = PATCH, iterations: Int = ITERATIONS): Choice? {
        val pw = min(patch, f.w)
        val ph = min(patch, f.h)
        val x0 = (cx - pw / 2).coerceIn(0, f.w - pw)
        val y0 = (cy - ph / 2).coerceIn(0, f.h - ph)
        val lum = FloatArray(pw * ph) { i -> lumaOf(f.px[(y0 + i / pw) * f.w + x0 + i % pw]) + 1f }
        val kernels = candidates.map { kernel(it) }
        val border = kernels.maxOf { it.radius } + 2
        if (2 * border + 16 > min(pw, ph)) return null
        val before = crispness(lum, pw, ph, border)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(SuperRes.threads())
        val scores = try {
            kernels.map { k -> pool.submit<Double> { crispness(clamp(lum, richardsonLucy(lum, pw, ph, k, iterations), pw, ph, k.radius), pw, ph, border) } }
                .map { it.get() }
        } finally {
            pool.shutdown()
        }
        var best = 0
        for (i in scores.indices) if (scores[i] < scores[best] * (1 - PRIOR_MARGIN) || (i == 0 && scores[0] <= scores[best])) best = i
        // The first candidate (the geometry's best guess) keeps the win unless another is clearly crisper.
        if (best != 0 && scores[0] <= scores[best] * (1 + PRIOR_MARGIN)) best = 0
        val gain = before / scores[best]
        log("deblur: before %.4f, candidates %s -> %d (gain %.3f)".format(
            before, scores.joinToString { "%.4f".format(it) }, best, gain))
        return if (gain >= MIN_GAIN) Choice(candidates[best], best, gain) else null
    }

    /**
     * Deblurs [f] for [path], tile by tile (any size, in parallel); each finished tile is handed
     * to [sink] as (x, y, w, h, pixels).
     */
    fun deblur(f: SuperRes.Frame, path: FloatArray, iterations: Int = ITERATIONS, sink: (Int, Int, Int, Int, IntArray) -> Unit) {
        val k = kernel(path)
        val margin = 3 * k.radius + 8
        val tiles = ArrayList<IntArray>()
        var ty = 0
        while (ty < f.h) {
            var tx = 0
            while (tx < f.w) {
                tiles += intArrayOf(tx, ty, min(TILE, f.w - tx), min(TILE, f.h - ty))
                tx += TILE
            }
            ty += TILE
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(SuperRes.threads())
        try {
            tiles.map { t ->
                pool.submit {
                    val (tx, tyy, tw, th) = t.toList()
                    val rx0 = max(0, tx - margin)
                    val ry0 = max(0, tyy - margin)
                    val rx1 = min(f.w, tx + tw + margin)
                    val ry1 = min(f.h, tyy + th + margin)
                    val rw = rx1 - rx0
                    val rh = ry1 - ry0
                    val lum = FloatArray(rw * rh) { i -> lumaOf(f.px[(ry0 + i / rw) * f.w + rx0 + i % rw]) + 1f }
                    val est = clamp(lum, richardsonLucy(lum, rw, rh, k, iterations), rw, rh, k.radius)
                    val out = IntArray(tw * th)
                    for (y in 0 until th) for (x in 0 until tw) {
                        val i = (y + tyy - ry0) * rw + (x + tx - rx0)
                        val p = f.px[(tyy + y) * f.w + tx + x]
                        val gain = (est[i] / lum[i]).coerceIn(0.4f, 2.5f)
                        val r = (((p shr 16) and 0xFF) * gain).toInt().coerceIn(0, 255)
                        val g = (((p shr 8) and 0xFF) * gain).toInt().coerceIn(0, 255)
                        val b = ((p and 0xFF) * gain).toInt().coerceIn(0, 255)
                        out[y * tw + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    sink(tx, tyy, tw, th, out)
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    /**
     * Keeps [est] within the range the blurred photo shows around each point (a sharp value is
     * smeared over the kernel's reach, so it must appear there), plus a little: no halos.
     */
    private fun clamp(lum: FloatArray, est: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val r = radius + 1
        val lo = FloatArray(w * h)
        val hi = FloatArray(w * h)
        val tlo = FloatArray(w * h)
        val thi = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var a = Float.MAX_VALUE
            var b = -Float.MAX_VALUE
            for (xx in max(0, x - r)..min(w - 1, x + r)) {
                val v = lum[y * w + xx]
                if (v < a) a = v
                if (v > b) b = v
            }
            tlo[y * w + x] = a
            thi[y * w + x] = b
        }
        for (y in 0 until h) for (x in 0 until w) {
            var a = Float.MAX_VALUE
            var b = -Float.MAX_VALUE
            for (yy in max(0, y - r)..min(h - 1, y + r)) {
                val i = yy * w + x
                if (tlo[i] < a) a = tlo[i]
                if (thi[i] > b) b = thi[i]
            }
            lo[y * w + x] = a
            hi[y * w + x] = b
        }
        for (i in est.indices) {
            var e = est[i].coerceIn(lo[i] - OVERSHOOT, hi[i] + OVERSHOOT)
            val d = e - lum[i]
            if (abs(d) < NOISE_FLOOR) e = lum[i] + d * (abs(d) / NOISE_FLOOR)
            est[i] = e
        }
        return est
    }

    private fun lumaOf(p: Int) = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)

    private const val TAG = "ProCam"
    /** Where diagnostics go (replaceable off the phone). */
    var log: (String) -> Unit = { android.util.Log.i(TAG, it) }
    const val ITERATIONS = 10
    private const val TILE = 512
    private const val PATCH = 384
    private const val OVERSHOOT = 12f
    private const val NOISE_FLOOR = 3f
    /** Another kernel must be this much crisper to beat the geometry's guess. */
    private const val PRIOR_MARGIN = 0.005
    /**
     * Deblurring a photo that wasn't blurred that way makes it far less crisp (ratio ~0.7); a
     * right kernel keeps it about as crisp while sharpening (~1.0). Below this, leave it alone.
     */
    const val MIN_GAIN = 0.95
    /** Longer blur than this (px) can't be undone well; such photos are left alone. */
    const val MAX_BLUR_PX = 40f
    /** The lens shift must follow the gyroscope this well (R²) to be trusted. */
    private const val MIN_OIS_FIT = 0.6
}
