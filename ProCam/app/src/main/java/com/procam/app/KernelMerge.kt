package com.procam.app

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Multi-frame merge onto a finer grid, after Wronski et al., "Handheld Multi-Frame
 * Super-Resolution" (SIGGRAPH 2019, the method behind Google's Super Res Zoom):
 *
 * 1. **Local alignment.** Every 16×16 patch of every frame gets its own sub-pixel offset (around
 *    the frame's overall offset), so a slight tilt, rolling shutter or lens distortion doesn't
 *    leave parts of the picture doubled or soft.
 * 2. **Edge-shaped kernels.** Each output pixel gathers the nearby samples of all frames with a
 *    kernel shaped by the reference's local structure: long along an edge and thin across it
 *    (crisp, unjagged edges), wide and round in flat areas (strong denoising).
 * 3. **Robustness.** A frame's samples count only where that frame agrees with the reference
 *    within what the local texture and the noise explain — moving things don't ghost.
 */
object KernelMerge {

    /**
     * Merges [frames] (the first is the reference; [offsets]: frame(x + dx) ≈ ref(x)) onto a
     * grid [scale] times finer than the reference.
     */
    fun merge(frames: List<SuperRes.Frame>, offsets: List<Pair<Float, Float>>, scale: Float, params: Params = Params()): SuperRes.Frame {
        val ref = frames[0]
        val w = ref.w
        val h = ref.h
        val ow = (w * scale).roundToInt()
        val oh = (h * scale).roundToInt()
        val n = frames.size
        val t0 = System.nanoTime()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(SuperRes.threads())
        try {
            val lum = pool.invokeAll(frames.map { f -> java.util.concurrent.Callable { lumaOf(f) } }).map { it.get() }
            val l0 = lum[0]
            // ---- reference structure (kernel shape per reference pixel) and, per frame, local
            // alignment and robustness: all independent, so in parallel.
            val kernelJob = pool.submit<FloatArray> { kernelShapes(l0, w, h, params) }
            val noise = noiseLevel(l0, w, h)
            val perFrame = pool.invokeAll((1 until n).map { k ->
                java.util.concurrent.Callable {
                    val field = localOffsets(l0, lum[k], w, h, frames[k].w, frames[k].h, offsets[k], params)
                    field to robustness(l0, lum[k], w, h, frames[k].w, frames[k].h, field, noise, params)
                }
            }).map { it.get() }
            val kernels = kernelJob.get()
            val fields = listOf<Field?>(null) + perFrame.map { it.first }
            val robust = listOf<FloatArray?>(null) + perFrame.map { it.second }
            val t1 = System.nanoTime()
            // ---- gather, in bands of output rows
            val out = IntArray(ow * oh)
            val bands = SuperRes.threads() * 2
            pool.invokeAll((0 until bands).map { band ->
                java.util.concurrent.Callable {
                    // Each frame's offset at every reference column of the current reference row
                    // (the field changes slowly: per reference pixel is plenty).
                    val rowDx = Array(n) { FloatArray(w) }
                    val rowDy = Array(n) { FloatArray(w) }
                    var rowFor = -1
                    for (oy in oh * band / bands until oh * (band + 1) / bands) {
                        val v = (oy + 0.5f) / scale - 0.5f
                        val iv = v.roundToInt().coerceIn(0, h - 1)
                        if (iv != rowFor) {
                            rowFor = iv
                            for (k in 1 until n) {
                                val fl = fields[k]!!
                                for (x in 0 until w) {
                                    rowDx[k][x] = fl.dxAt(x.toFloat(), iv.toFloat())
                                    rowDy[k][x] = fl.dyAt(x.toFloat(), iv.toFloat())
                                }
                            }
                        }
                        val rowBase = iv * w
                        for (ox in 0 until ow) {
                            val u = (ox + 0.5f) / scale - 0.5f
                            val iu = u.roundToInt().coerceIn(0, w - 1)
                            val ki = rowBase + iu
                            val a = kernels[3 * ki]
                            val b2 = 2 * kernels[3 * ki + 1]
                            val c = kernels[3 * ki + 2]
                            var sr = 0f
                            var sg = 0f
                            var sb = 0f
                            var sw = 0f
                            for (k in 0 until n) {
                                val rk = if (k == 0) 1f else robust[k]!![ki]
                                if (rk <= 0.01f) continue
                                val f = frames[k]
                                val fpx = f.px
                                val fw = f.w
                                val px = if (k == 0) u else u + rowDx[k][iu]
                                val py = if (k == 0) v else v + rowDy[k][iu]
                                // The 2×2 nearest samples (farther ones weigh next to nothing).
                                val cx = floor(px).toInt()
                                val cy = floor(py).toInt()
                                for (yy in max(0, cy)..min(f.h - 1, cy + 1)) {
                                    val dy = yy - py
                                    val row = yy * fw
                                    for (xx in max(0, cx)..min(fw - 1, cx + 1)) {
                                        val dx = xx - px
                                        val q = a * dx * dx + b2 * dx * dy + c * dy * dy
                                        if (q >= EXP_MAX) continue
                                        val wt = EXP[(q * EXP_SCALE).toInt()] * rk
                                        val p = fpx[row + xx]
                                        sr += wt * ((p shr 16) and 0xFF)
                                        sg += wt * ((p shr 8) and 0xFF)
                                        sb += wt * (p and 0xFF)
                                        sw += wt
                                    }
                                }
                            }
                            out[oy * ow + ox] = if (sw > 1e-4f) {
                                val inv = 1f / sw
                                (0xFF shl 24) or
                                    ((sr * inv + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                                    ((sg * inv + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                                    (sb * inv + 0.5f).toInt().coerceIn(0, 255)
                            } else ref.px[ki]
                        }
                    }
                }
            }).forEach { it.get() }
            lastTimings = "prepare ${(t1 - t0) / 1_000_000} ms, gather ${(System.nanoTime() - t1) / 1_000_000} ms"
            return SuperRes.Frame(out, ow, oh)
        } finally {
            pool.shutdown()
        }
    }

    /** How long the last merge's stages took (diagnostics). */
    /**
     * For merging at the frames' own size (zoom enhance), which is sharpened by deconvolution
     * afterwards: wider kernels, so every pixel averages more of the burst. Narrow ones leave
     * more noise, and the deconvolution then amplifies it (measured: error 5.7 vs 4.8 on a noisy
     * burst, against 6.4 for one shot).
     */
    val SAME_SIZE = Params(acrossEdge = 0.4f, alongEdge = 0.8f, flat = 0.8f)

    @Volatile var lastTimings = ""

    /** Mean local offset of [f] against [ref] (checks). */
    fun debugField(ref: SuperRes.Frame, f: SuperRes.Frame, global: Pair<Float, Float>, p: Params = Params()): Pair<Float, Float> {
        val fl = localOffsets(lumaOf(ref), lumaOf(f), ref.w, ref.h, f.w, f.h, global, p)
        val n = fl.dx.size
        return fl.dx.sortedArray()[n / 2] to fl.dy.sortedArray()[n / 2]
    }

    /** Tuning (input-pixel units; luma 0..255). */
    data class Params(
        /** Kernel width across an edge (px). */
        val acrossEdge: Float = 0.2f,
        /** Kernel length along an edge (px). */
        val alongEdge: Float = 0.6f,
        /** Kernel width in flat areas (px): wide = more denoising. */
        val flat: Float = 0.5f,
        /** Edge strength (gradient, levels/px) above which a place counts as an edge. */
        val edgeAt: Float = 6f,
        /** Robustness: a frame may differ from the reference by this many (texture + noise) units. */
        val tolerance: Float = 2.5f,
        /** Patch size for local alignment (reference pixels). */
        val tile: Int = 16,
        /** Local alignment may move this far (px) from the frame's overall offset. */
        val reach: Int = 2,
    )

    // ------------------------------------------------------------------ kernels

    /**
     * Per reference pixel, the kernel's inverse covariance (a, b, c): weight = exp(-½·(a dx² +
     * 2b dx dy + c dy²)). From the structure tensor of the (lightly smoothed) luma.
     */
    private fun kernelShapes(l0: FloatArray, w: Int, h: Int, p: Params): FloatArray {
        val s = l0.copyOf().also { PortraitProcessor.boxBlur(it, w, h, 1) }
        val ixx = FloatArray(w * h)
        val ixy = FloatArray(w * h)
        val iyy = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val gx = (s[y * w + min(w - 1, x + 1)] - s[y * w + max(0, x - 1)]) / 2f
            val gy = (s[min(h - 1, y + 1) * w + x] - s[max(0, y - 1) * w + x]) / 2f
            ixx[i] = gx * gx
            ixy[i] = gx * gy
            iyy[i] = gy * gy
        }
        PortraitProcessor.boxBlur(ixx, w, h, 2)
        PortraitProcessor.boxBlur(ixy, w, h, 2)
        PortraitProcessor.boxBlur(iyy, w, h, 2)
        val out = FloatArray(3 * w * h)
        for (i in 0 until w * h) {
            val t = (ixx[i] + iyy[i]) / 2
            val d = sqrt(max(0f, (ixx[i] - iyy[i]) * (ixx[i] - iyy[i]) / 4 + ixy[i] * ixy[i]))
            val l1 = t + d
            val l2 = max(0f, t - d)
            // Dominant direction (the gradient): eigenvector of l1.
            var ex = ixy[i]
            var ey = l1 - ixx[i]
            var n = sqrt(ex * ex + ey * ey)
            if (n < 1e-6f) {
                ex = if (ixx[i] >= iyy[i]) 1f else 0f
                ey = 1f - ex
                n = 1f
            }
            ex /= n
            ey /= n
            // How edge-like (0 = flat or isotropic texture, 1 = clean edge), and how strong.
            val aniso = if (l1 + l2 > 1e-6f) sqrt((l1 - l2) / (l1 + l2)) else 0f
            val strength = (sqrt(l1) / p.edgeAt).coerceIn(0f, 1f)
            val e = aniso * strength
            // Across the edge (along the gradient) thin, along the edge long; flat: round and wide.
            val across = p.flat + (p.acrossEdge - p.flat) * strength
            val along = p.flat + (p.alongEdge - p.flat) * strength
            val sAcross = across
            val sAlong = along + (across - along) * (1 - e)
            // Inverse covariance: R diag(1/σa², 1/σb²) Rᵀ with axis 1 = gradient direction.
            val ia = 1f / (sAcross * sAcross)
            val ib = 1f / (sAlong * sAlong)
            out[3 * i] = ia * ex * ex + ib * ey * ey
            out[3 * i + 1] = (ia - ib) * ex * ey
            out[3 * i + 2] = ia * ey * ey + ib * ex * ex
        }
        return out
    }

    // ------------------------------------------------------------------ local alignment

    /** A frame's offset field: one sub-pixel offset per tile, interpolated in between. */
    class Field(val tile: Int, val tw: Int, val th: Int, val dx: FloatArray, val dy: FloatArray) {
        private fun at(arr: FloatArray, u: Float, v: Float): Float {
            val gx = (u / tile - 0.5f).coerceIn(0f, (tw - 1).toFloat())
            val gy = (v / tile - 0.5f).coerceIn(0f, (th - 1).toFloat())
            val x0 = gx.toInt().coerceAtMost(tw - 1)
            val y0 = gy.toInt().coerceAtMost(th - 1)
            val x1 = min(tw - 1, x0 + 1)
            val y1 = min(th - 1, y0 + 1)
            val fx = gx - x0
            val fy = gy - y0
            return (arr[y0 * tw + x0] * (1 - fx) + arr[y0 * tw + x1] * fx) * (1 - fy) +
                (arr[y1 * tw + x0] * (1 - fx) + arr[y1 * tw + x1] * fx) * fy
        }
        fun dxAt(u: Float, v: Float) = at(dx, u, v)
        fun dyAt(u: Float, v: Float) = at(dy, u, v)
    }

    private fun localOffsets(l0: FloatArray, lk: FloatArray, w: Int, h: Int, fw: Int, fh: Int, global: Pair<Float, Float>, p: Params): Field {
        val t = p.tile
        val tw = max(1, w / t)
        val th = max(1, h / t)
        val dx = FloatArray(tw * th) { global.first }
        val dy = FloatArray(tw * th) { global.second }
        val gx0 = global.first.roundToInt()
        val gy0 = global.second.roundToInt()
        val r = p.reach + 1
        val costs = FloatArray((2 * r + 1) * (2 * r + 1))
        for (ty in 0 until th) for (tx in 0 until tw) {
            val x0 = tx * t
            val y0 = ty * t
            // Featureless patches keep the overall offset (every shift would match).
            var mean = 0f
            for (y in y0 until y0 + t) for (x in x0 until x0 + t) mean += l0[min(h - 1, y) * w + min(w - 1, x)]
            mean /= t * t
            var varr = 0f
            for (y in y0 until y0 + t) for (x in x0 until x0 + t) { val d = l0[min(h - 1, y) * w + min(w - 1, x)] - mean; varr += d * d }
            if (varr / (t * t) < MIN_PATCH_VARIANCE) continue
            var best = Float.MAX_VALUE
            var bi = 0
            var bj = 0
            for (j in -r..r) for (i in -r..r) {
                var s = 0f
                val sx = gx0 + i
                val sy = gy0 + j
                for (y in y0 until y0 + t) {
                    val ry = min(h - 1, y)
                    val fy = (ry + sy).coerceIn(0, fh - 1)
                    for (x in x0 until x0 + t) {
                        val rx = min(w - 1, x)
                        val d = l0[ry * w + rx] - lk[fy * fw + (rx + sx).coerceIn(0, fw - 1)]
                        s += d * d
                    }
                }
                costs[(j + r) * (2 * r + 1) + (i + r)] = s
                if (s < best) {
                    best = s
                    bi = i
                    bj = j
                }
            }
            // Sub-pixel: parabola through the best and its neighbours (each axis).
            fun c(i: Int, j: Int) = costs[(j + r) * (2 * r + 1) + (i + r)]
            var sx = bi.toFloat()
            var sy = bj.toFloat()
            if (bi > -r && bi < r) {
                val den = c(bi - 1, bj) - 2 * c(bi, bj) + c(bi + 1, bj)
                if (den > 1e-3f) sx += ((c(bi - 1, bj) - c(bi + 1, bj)) / (2 * den)).coerceIn(-0.5f, 0.5f)
            }
            if (bj > -r && bj < r) {
                val den = c(bi, bj - 1) - 2 * c(bi, bj) + c(bi, bj + 1)
                if (den > 1e-3f) sy += ((c(bi, bj - 1) - c(bi, bj + 1)) / (2 * den)).coerceIn(-0.5f, 0.5f)
            }
            val ndx = gx0 + sx
            val ndy = gy0 + sy
            if (abs(ndx - global.first) <= p.reach && abs(ndy - global.second) <= p.reach) {
                dx[ty * tw + tx] = ndx
                dy[ty * tw + tx] = ndy
            }
        }
        // Outliers (a patch matched something else) give way to their neighbours: 3×3 median.
        return Field(t, tw, th, median3(dx, tw, th), median3(dy, tw, th))
    }

    private fun median3(a: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(a.size)
        val buf = FloatArray(9)
        for (y in 0 until h) for (x in 0 until w) {
            var n = 0
            for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) buf[n++] = a[yy * w + xx]
            java.util.Arrays.sort(buf, 0, n)
            out[y * w + x] = buf[n / 2]
        }
        return out
    }

    // ------------------------------------------------------------------ robustness

    /**
     * Per reference pixel, how much frame k's samples may count (0..1): its local mean must match
     * the reference's within what the local texture and noise explain. Shrunk by a 3×3 minimum so
     * the edges of moving things are rejected too.
     */
    private fun robustness(l0: FloatArray, lk: FloatArray, w: Int, h: Int, fw: Int, fh: Int, field: Field, noise: Float, p: Params): FloatArray {
        val m0 = l0.copyOf().also { PortraitProcessor.boxBlur(it, w, h, 1) }
        val sq = FloatArray(w * h) { l0[it] * l0[it] }.also { PortraitProcessor.boxBlur(it, w, h, 1) }
        val aligned = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            aligned[y * w + x] = bilinear(lk, fw, fh, x + field.dxAt(x.toFloat(), y.toFloat()), y + field.dyAt(x.toFloat(), y.toFloat()))
        }
        PortraitProcessor.boxBlur(aligned, w, h, 1)
        val r = FloatArray(w * h)
        for (i in 0 until w * h) {
            val sd = sqrt(max(0f, sq[i] - m0[i] * m0[i]))
            val tol = p.tolerance * (0.5f * sd + noise + 1f)
            val d = abs(aligned[i] - m0[i]) / tol
            r[i] = exp(-d * d)
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 1f
            for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) m = min(m, r[yy * w + xx])
            out[y * w + x] = m
        }
        return out
    }

    /** Noise of the reference (luma levels): robust spread of its fine detail. */
    private fun noiseLevel(l0: FloatArray, w: Int, h: Int): Float {
        val vals = ArrayList<Float>()
        var y = 1
        while (y < h - 1) {
            var x = 1
            while (x < w - 1) {
                val i = y * w + x
                vals += abs(4 * l0[i] - l0[i - 1] - l0[i + 1] - l0[i - w] - l0[i + w]) / 4.5f
                x += 3
            }
            y += 3
        }
        if (vals.isEmpty()) return 2f
        vals.sort()
        return vals[vals.size / 2] * 1.48f
    }

    private fun bilinear(a: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val xc = x.coerceIn(0f, (w - 1).toFloat())
        val yc = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = floor(xc).toInt().coerceAtMost(w - 1)
        val y0 = floor(yc).toInt().coerceAtMost(h - 1)
        val x1 = min(w - 1, x0 + 1)
        val y1 = min(h - 1, y0 + 1)
        val fx = xc - x0
        val fy = yc - y0
        return (a[y0 * w + x0] * (1 - fx) + a[y0 * w + x1] * fx) * (1 - fy) + (a[y1 * w + x0] * (1 - fx) + a[y1 * w + x1] * fx) * fy
    }

    private fun lumaOf(f: SuperRes.Frame) = FloatArray(f.px.size) { i ->
        val p = f.px[i]
        0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
    }

    private const val EXP_SIZE = 2048
    private const val EXP_MAX = 18f
    private const val EXP_SCALE = EXP_SIZE / EXP_MAX
    private val EXP = FloatArray(EXP_SIZE + 1) { exp(-0.5f * it * EXP_MAX / EXP_SIZE) }

    private const val MIN_PATCH_VARIANCE = 4f
}
