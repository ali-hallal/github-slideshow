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

    /**
     * Offset of [frame] relative to [ref] in pixels, with sub-pixel precision:
     * frame(x + dx, y + dy) ≈ ref(x, y).
     */
    fun offset(ref: Frame, frame: Frame, refGray: NightProcessor.Gray, frameGray: NightProcessor.Gray, grayScale: Int): Pair<Float, Float> {
        val coarse = NightProcessor.align(refGray, frameGray)
        // align() reports shifts in units of ALIGN_SCALE pixels of the grey copy's source.
        val cx = (coarse.first.toFloat() / NightProcessor.ALIGN_SCALE * grayScale).roundToInt()
        val cy = (coarse.second.toFloat() / NightProcessor.ALIGN_SCALE * grayScale).roundToInt()
        val (ix, iy) = NightProcessor.refine(ref.px, frame.px, ref.w, ref.h, cx, cy, range = 3)
        // Sub-pixel: try shifts in 1/8-pixel steps around the best whole-pixel match, resampling
        // the frame (bilinear) each time, and keep the closest match.
        // Lightly smoothed luma: matching on raw noisy pixels is biased toward half-pixel shifts.
        val rl = lumaOf(ref).also { PortraitProcessor.boxBlur(it, ref.w, ref.h, 1) }
        val fl = lumaOf(frame).also { PortraitProcessor.boxBlur(it, frame.w, frame.h, 1) }
        var best = Double.MAX_VALUE
        var bx = ix.toFloat()
        var by = iy.toFloat()
        for (sy in -SUB_STEPS..SUB_STEPS) for (sx in -SUB_STEPS..SUB_STEPS) {
            val dx = ix + sx / 8f
            val dy = iy + sy / 8f
            val c = subCost(rl, fl, ref.w, ref.h, frame.w, frame.h, dx, dy)
            if (c < best) {
                best = c
                bx = dx
                by = dy
            }
        }
        return Pair(bx, by)
    }

    private const val SUB_STEPS = 6 // ±0.75 px in 1/8 steps

    private fun lumaOf(f: Frame) = FloatArray(f.px.size) { l(f.px[it]).toFloat() }

    /**
     * Mean squared difference between ref(x - d/2) and frame(x + d/2): both images are resampled
     * by half the shift, so interpolation smooths them equally and doesn't bias the match.
     */
    private fun subCost(rl: FloatArray, fl: FloatArray, w: Int, h: Int, fw: Int, fh: Int, dx: Float, dy: Float): Double {
        var sum = 0.0
        var n = 0
        val x0 = w / 6
        val y0 = h / 6
        var y = y0
        while (y < h - y0) {
            var x = x0
            while (x < w - x0) {
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
        return if (n == 0) Double.MAX_VALUE else sum / n
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

        for ((k, f) in frames.withIndex()) {
            val (ox, oy) = offsets[k]
            val t = motion[k].coerceAtLeast(1)
            for (y in 0 until f.h) {
                // Where this frame row lands on the reference, then on the fine grid.
                val gy = (y - oy + 0.5f) * scale - 0.5f
                val y0 = floor(gy).toInt()
                val ty = gy - y0
                if (y0 < -1 || y0 >= h) continue
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
                    // Bilinear "splat" onto the 4 nearest fine-grid pixels.
                    splat(sr, sg, sb, sw, w, h, x0, y0, (1 - tx) * (1 - ty) * mw, r, g, b)
                    splat(sr, sg, sb, sw, w, h, x0 + 1, y0, tx * (1 - ty) * mw, r, g, b)
                    splat(sr, sg, sb, sw, w, h, x0, y0 + 1, (1 - tx) * ty * mw, r, g, b)
                    splat(sr, sg, sb, sw, w, h, x0 + 1, y0 + 1, tx * ty * mw, r, g, b)
                }
            }
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

    private fun splat(
        sr: FloatArray, sg: FloatArray, sb: FloatArray, sw: FloatArray,
        w: Int, h: Int, x: Int, y: Int, wt: Float, r: Int, g: Int, b: Int,
    ) {
        if (wt <= 0f || x < 0 || y < 0 || x >= w || y >= h) return
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
        val out = IntArray(w * h) { 0xFF shl 24 }
        val chan = FloatArray(w * h)
        val blur = FloatArray(w * h)
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            for (i in px.indices) chan[i] = ((px[i] shr shift) and 0xFF).toFloat()
            System.arraycopy(chan, 0, blur, 0, chan.size)
            repeat(2) { PortraitProcessor.boxBlur(blur, w, h, radius) }
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                val v = chan[i]
                val detail = v - blur[i]
                val d = if (abs(detail) <= noiseFloor) 0f else detail - noiseFloor * kotlin.math.sign(detail)
                var lo = v
                var hi = v
                for (yy in max(0, y - 1)..min(h - 1, y + 1)) {
                    val row = yy * w
                    for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                        val q = chan[row + xx]
                        if (q < lo) lo = q
                        if (q > hi) hi = q
                    }
                }
                val s = (v + amount * d).coerceIn(lo, hi)
                out[i] = out[i] or (s.roundToInt().coerceIn(0, 255) shl shift)
            }
        }
        System.arraycopy(out, 0, px, 0, px.size)
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
