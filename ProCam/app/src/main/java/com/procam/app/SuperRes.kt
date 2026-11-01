package com.procam.app

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Multi-frame super-resolution ("shift-and-add"), the idea behind handheld super-res zoom.
 *
 * Natural hand tremor means each frame of a burst samples the scene at slightly different
 * sub-pixel positions. After measuring each frame's offset to sub-pixel precision, every pixel
 * of every frame is placed ("splatted") onto a finer output grid at its true position. Together
 * the frames fill that grid with real samples, so the result holds more genuine detail than any
 * single frame enlarged, and averaging removes noise at the same time.
 *
 * Pure functions on ARGB pixel arrays (no Android types), so they can be tested anywhere.
 */
object SuperRes {

    class Frame(val px: IntArray, val w: Int, val h: Int)

    /** Lucky imaging: indices of the [keep] sharpest frames, sharpest first. */
    fun sharpest(frames: List<Frame>, keep: Int): List<Int> =
        frames.indices.sortedByDescending { sharpness(frames[it]) }.take(keep)

    /** Mean absolute Laplacian of luma on a sparse grid: higher = sharper. */
    fun sharpness(f: Frame): Double {
        var sum = 0L
        var n = 0
        var y = 1
        while (y < f.h - 1) {
            var x = 1
            while (x < f.w - 1) {
                val i = y * f.w + x
                sum += abs(4 * l(f.px[i]) - l(f.px[i - 1]) - l(f.px[i + 1]) - l(f.px[i - f.w]) - l(f.px[i + f.w]))
                n++
                x += 2
            }
            y += 2
        }
        return if (n == 0) 0.0 else sum.toDouble() / n
    }

    /** Small grey copy (box-averaged) for alignment. */
    fun gray(f: Frame, scale: Int): NightProcessor.Gray {
        val sw = max(1, f.w / scale)
        val sh = max(1, f.h / scale)
        val v = IntArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            var s = 0
            var c = 0
            for (yy in y * scale until min(f.h, (y + 1) * scale)) for (xx in x * scale until min(f.w, (x + 1) * scale)) {
                s += l(f.px[yy * f.w + xx])
                c++
            }
            v[y * sw + x] = if (c == 0) 0 else s / c
        }
        return NightProcessor.Gray(sw, sh, v)
    }

    /** Result of aligning one frame: offset (frame(x+dx, y+dy) ≈ ref(x, y)) and match cost. */
    data class Offset(val dx: Float, val dy: Float, val cost: Double)

    /** A rectangle of the reference used for alignment. */
    class Window(val x: Int, val y: Int, val w: Int, val h: Int)

    /**
     * Reference frame prepared once for aligning many frames to it: grey copy, lightly smoothed
     * luma, and the most detailed patch (aligning on a ~256 px patch gives the same answer as the
     * whole frame at a fraction of the work, whatever the zoom).
     */
    class Prepared(
        val frame: Frame, val gray: NightProcessor.Gray, val luma: FloatArray, val window: Window,
        /** Mean edge strength in the window (luma levels). */
        val texture: Double,
    )

    fun prepare(f: Frame, grayScale: Int): Prepared {
        val luma = lumaOf(f).also { PortraitProcessor.boxBlur(it, f.w, f.h, 1) }
        val win = detailedWindow(luma, f.w, f.h)
        return Prepared(f, gray(f, grayScale), luma, win, textureIn(luma, f.w, win))
    }

    private fun textureIn(luma: FloatArray, w: Int, win: Window): Double {
        var e = 0.0
        var n = 0
        for (y in win.y + 1 until win.y + win.h - 1 step 2) for (x in win.x + 1 until win.x + win.w - 1 step 2) {
            val i = y * w + x
            e += abs(luma[i + 1] - luma[i - 1]) + abs(luma[i + w] - luma[i - w])
            n++
        }
        return if (n == 0) 0.0 else e / n
    }

    /** The patch (about 256x256) with the most edges, away from the borders. */
    private fun detailedWindow(luma: FloatArray, w: Int, h: Int): Window {
        val size = min(WINDOW, min(w, h) / 2).coerceAtLeast(16)
        val margin = max(8, min(w, h) / 10)
        var best = Window((w - size) / 2, (h - size) / 2, size, size)
        var bestScore = -1.0
        val step = max(8, size / 2)
        var y = margin
        while (y + size <= h - margin) {
            var x = margin
            while (x + size <= w - margin) {
                var e = 0.0
                var yy = y + 1
                while (yy < y + size - 1) {
                    var xx = x + 1
                    while (xx < x + size - 1) {
                        val i = yy * w + xx
                        e += abs(luma[i + 1] - luma[i - 1]) + abs(luma[i + w] - luma[i - w])
                        xx += 4
                    }
                    yy += 4
                }
                if (e > bestScore) {
                    bestScore = e
                    best = Window(x, y, size, size)
                }
                x += step
            }
            y += step
        }
        return best
    }

    fun offset(ref: Prepared, frame: Frame, grayScale: Int): Offset {
        // A featureless patch (sky, wall) can't be aligned: every shift matches. Don't wander.
        if (ref.texture < MIN_TEXTURE) return Offset(0f, 0f, 0.0)
        val coarse = NightProcessor.align(ref.gray, gray(frame, grayScale))
        // align() reports shifts in units of ALIGN_SCALE pixels of the grey copy's source.
        var bx = (coarse.first.toFloat() / NightProcessor.ALIGN_SCALE * grayScale).roundToInt().toFloat()
        var by = (coarse.second.toFloat() / NightProcessor.ALIGN_SCALE * grayScale).roundToInt().toFloat()
        // Refine in the detailed patch: whole pixels (±3), then 1/2 px (±1), then 1/8 px (±3/8).
        // Lightly smoothed luma: raw noisy pixels bias sub-pixel matching toward half pixels.
        val fl = lumaOf(frame).also { PortraitProcessor.boxBlur(it, frame.w, frame.h, 1) }
        var best = Double.MAX_VALUE
        for ((step, reach) in arrayOf(1f to 3, 0.5f to 2, 0.125f to 3)) {
            val cx0 = bx
            val cy0 = by
            for (sy in -reach..reach) for (sx in -reach..reach) {
                val dx = cx0 + sx * step
                val dy = cy0 + sy * step
                // Tiny bias toward small shifts: breaks ties on flat areas, negligible on detail.
                val c = subCost(ref.luma, fl, ref.frame.w, ref.frame.h, frame.w, frame.h, dx, dy, ref.window) +
                    TIE_BREAK * (abs(dx) + abs(dy))
                if (c < best) {
                    best = c
                    bx = dx
                    by = dy
                }
            }
        }
        return Offset(bx, by, best)
    }

    /** Offsets of all [frames] relative to frames[0], computed in parallel on all CPU cores. */
    fun offsets(frames: List<Frame>, grayScale: Int): List<Offset> {
        val ref = prepare(frames[0], grayScale)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads())
        try {
            val jobs = frames.indices.map { k ->
                pool.submit<Offset> { if (k == 0) Offset(0f, 0f, 0.0) else offset(ref, frames[k], grayScale) }
            }
            return jobs.map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private const val WINDOW = 256
    /** Below this mean edge strength the patch is featureless (alignment would be guesswork). */
    private const val MIN_TEXTURE = 1.5
    private const val TIE_BREAK = 0.02

    fun threads() = Runtime.getRuntime().availableProcessors().coerceIn(1, 6)

    private fun lumaOf(f: Frame) = FloatArray(f.px.size) { l(f.px[it]).toFloat() }

    /**
     * Mean squared difference between ref(x - d/2) and frame(x + d/2): both images are resampled
     * by half the shift, so interpolation smooths them equally and doesn't bias the match.
     */
    private fun subCost(
        rl: FloatArray, fl: FloatArray, w: Int, h: Int, fw: Int, fh: Int, dx: Float, dy: Float, win: Window,
    ): Double {
        var sum = 0.0
        var n = 0
        var y = win.y
        while (y < win.y + win.h) {
            var x = win.x
            while (x < win.x + win.w) {
                val a = sample(fl, fw, fh, x + dx / 2, y + dy / 2)
                val b = sample(rl, w, h, x - dx / 2, y - dy / 2)
                if (!a.isNaN() && !b.isNaN()) {
                    val d = a - b
                    sum += d * d
                    n++
                }
                x += 2
            }
            y += 2
        }
        // Too little overlap to judge: treat as a bad match.
        return if (n < (win.w * win.h) / 16) Double.MAX_VALUE else sum / n
    }

    private fun sample(a: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        if (x0 < 0 || y0 < 0 || x0 + 1 >= w || y0 + 1 >= h) return Float.NaN
        val tx = x - x0
        val ty = y - y0
        val top = a[y0 * w + x0] * (1 - tx) + a[y0 * w + x0 + 1] * tx
        val bot = a[(y0 + 1) * w + x0] * (1 - tx) + a[(y0 + 1) * w + x0 + 1] * tx
        return top * (1 - ty) + bot * ty
    }

    /**
     * Shift-and-add onto a grid [scale]x finer than the frames.
     * @param offsets per frame (dx, dy) relative to frame 0, which is the reference.
     * @param motion per frame, the luma difference above which a sample is treated as motion.
     * @return ARGB pixels of size (w*scale) x (h*scale).
     */
    fun merge(frames: List<Frame>, offsets: List<Pair<Float, Float>>, motion: IntArray, scale: Float): Frame {
        val ref = frames[0]
        val w = (ref.w * scale).roundToInt()
        val h = (ref.h * scale).roundToInt()
        val n = w * h
        val sr = FloatArray(n)
        val sg = FloatArray(n)
        val sb = FloatArray(n)
        val sw = FloatArray(n)

        // Base layer: the reference, smoothly enlarged, with a small weight. It fills any spot
        // no sample landed on and steadies the result; real samples dominate wherever they exist.
        val base = enlarge(ref, w, h)
        for (i in 0 until n) {
            val p = base[i]
            sr[i] = ((p shr 16) and 0xFF) * BASE_WEIGHT
            sg[i] = ((p shr 8) and 0xFF) * BASE_WEIGHT
            sb[i] = (p and 0xFF) * BASE_WEIGHT
            sw[i] = BASE_WEIGHT
        }

        // Splat in parallel: each core owns a horizontal band of the output, so no two threads
        // ever write the same pixel.
        val bands = threads()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(bands)
        try {
            (0 until bands).map { band ->
                pool.submit {
                    val bandTop = h * band / bands
                    val bandBottom = h * (band + 1) / bands
                    for ((k, f) in frames.withIndex()) {
                        val (ox, oy) = offsets[k]
                        val t = motion[k].coerceAtLeast(1)
                        for (y in 0 until f.h) {
                            // Where this frame row lands on the reference, then on the fine grid.
                            val gy = (y - oy + 0.5f) * scale - 0.5f
                            val y0 = floor(gy).toInt()
                            if (y0 + 1 < bandTop || y0 >= bandBottom) continue
                            val ty = gy - y0
                            for (x in 0 until f.w) {
                                val gx = (x - ox + 0.5f) * scale - 0.5f
                                val x0 = floor(gx).toInt()
                                val tx = gx - x0
                                if (x0 < -1 || x0 >= w) continue
                                val p = f.px[y * f.w + x]
                                val r = (p shr 16) and 0xFF
                                val g = (p shr 8) and 0xFF
                                val b = p and 0xFF
                                // Motion rejection against the reference at the same place.
                                val ci = min(h - 1, max(0, y0)) * w + min(w - 1, max(0, x0))
                                val d = abs(l(p) - l(base[ci]))
                                val mw = when {
                                    d <= t -> 1f
                                    d >= 3 * t -> 0f
                                    else -> (3f * t - d) / (2f * t)
                                }
                                if (mw == 0f) continue
                                // Bilinear "splat" onto the 4 nearest fine-grid pixels (only this band's rows).
                                splatBand(sr, sg, sb, sw, w, bandTop, bandBottom, x0, y0, (1 - tx) * (1 - ty) * mw, r, g, b)
                                splatBand(sr, sg, sb, sw, w, bandTop, bandBottom, x0 + 1, y0, tx * (1 - ty) * mw, r, g, b)
                                splatBand(sr, sg, sb, sw, w, bandTop, bandBottom, x0, y0 + 1, (1 - tx) * ty * mw, r, g, b)
                                splatBand(sr, sg, sb, sw, w, bandTop, bandBottom, x0 + 1, y0 + 1, tx * ty * mw, r, g, b)
                            }
                        }
                    }
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        val out = IntArray(n)
        for (i in 0 until n) {
            val wt = sw[i]
            out[i] = (0xFF shl 24) or
                ((sr[i] / wt).roundToInt().coerceIn(0, 255) shl 16) or
                ((sg[i] / wt).roundToInt().coerceIn(0, 255) shl 8) or
                (sb[i] / wt).roundToInt().coerceIn(0, 255)
        }
        return Frame(out, w, h)
    }

    private fun splatBand(
        sr: FloatArray, sg: FloatArray, sb: FloatArray, sw: FloatArray,
        w: Int, top: Int, bottom: Int, x: Int, y: Int, wt: Float, r: Int, g: Int, b: Int,
    ) {
        if (wt <= 0f || x < 0 || x >= w || y < top || y >= bottom) return
        val i = y * w + x
        sr[i] += r * wt
        sg[i] += g * wt
        sb[i] += b * wt
        sw[i] += wt
    }

    /** Bilinear enlargement (pixel-centre aligned). */
    fun enlarge(f: Frame, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        val sx = f.w.toFloat() / w
        val sy = f.h.toFloat() / h
        for (y in 0 until h) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, f.h - 1f)
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, f.h - 1)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, f.w - 1f)
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, f.w - 1)
                val tx = fx - x0
                var p = 0xFF shl 24
                for (c in 0 until 3) {
                    val shift = 16 - 8 * c
                    val a = ((f.px[y0 * f.w + x0] shr shift) and 0xFF) * (1 - tx) + ((f.px[y0 * f.w + x1] shr shift) and 0xFF) * tx
                    val bb = ((f.px[y1 * f.w + x0] shr shift) and 0xFF) * (1 - tx) + ((f.px[y1 * f.w + x1] shr shift) and 0xFF) * tx
                    p = p or ((a * (1 - ty) + bb * ty).roundToInt().coerceIn(0, 255) shl shift)
                }
                out[y * w + x] = p
            }
        }
        return out
    }

    /**
     * Edge-aware sharpening: an unsharp mask that ignores tiny differences (noise), and is
     * clamped to the local min/max so edges get crisper without bright/dark halos.
     * Processes one colour channel at a time to keep memory low.
     */
    fun sharpen(f: Frame, amount: Float = SHARPEN_AMOUNT, radius: Int = 2, noiseFloor: Float = SHARPEN_FLOOR) {
        val w = f.w
        val h = f.h
        val px = f.px
        val result = Array(3) { IntArray(0) }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            (0 until 3).map { c ->
                pool.submit {
                    val shift = 16 - 8 * c
                    val chan = FloatArray(w * h) { ((px[it] shr shift) and 0xFF).toFloat() }
                    val blur = chan.copyOf()
                    repeat(2) { PortraitProcessor.boxBlur(blur, w, h, radius) }
                    // 3x3 min and max of each pixel's neighbourhood: row pass, then column pass.
                    val rowLo = FloatArray(w * h)
                    val rowHi = FloatArray(w * h)
                    for (y in 0 until h) {
                        val o = y * w
                        for (x in 0 until w) {
                            val v = chan[o + x]
                            val l = if (x > 0) chan[o + x - 1] else v
                            val r = if (x < w - 1) chan[o + x + 1] else v
                            rowLo[o + x] = kotlin.math.min(v, kotlin.math.min(l, r))
                            rowHi[o + x] = kotlin.math.max(v, kotlin.math.max(l, r))
                        }
                    }
                    val out = IntArray(w * h)
                    for (y in 0 until h) for (x in 0 until w) {
                        val i = y * w + x
                        var lo = rowLo[i]
                        var hi = rowHi[i]
                        if (y > 0) { lo = kotlin.math.min(lo, rowLo[i - w]); hi = kotlin.math.max(hi, rowHi[i - w]) }
                        if (y < h - 1) { lo = kotlin.math.min(lo, rowLo[i + w]); hi = kotlin.math.max(hi, rowHi[i + w]) }
                        val v = chan[i]
                        val detail = v - blur[i]
                        val d = if (detail > noiseFloor) detail - noiseFloor else if (detail < -noiseFloor) detail + noiseFloor else 0f
                        var sv = v + amount * d
                        if (sv < lo) sv = lo else if (sv > hi) sv = hi
                        out[i] = ((sv + 0.5f).toInt().coerceIn(0, 255)) shl shift
                    }
                    result[c] = out
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        for (i in px.indices) px[i] = (0xFF shl 24) or result[0][i] or result[1][i] or result[2][i]
    }

    private const val SHARPEN_AMOUNT = 1.2f
    private const val SHARPEN_FLOOR = 2f

    /** Root-mean-square luma error against [truth] (same size), for measuring quality. */
    fun rmse(a: Frame, truth: Frame, margin: Int): Double {
        var s = 0.0
        var n = 0
        for (y in margin until a.h - margin) for (x in margin until a.w - margin) {
            val d = l(a.px[y * a.w + x]) - l(truth.px[y * truth.w + x])
            s += d * d
            n++
        }
        return sqrt(s / n)
    }

    private const val BASE_WEIGHT = 0.15f

    private fun l(p: Int) = NightProcessor.luma(p)
}
