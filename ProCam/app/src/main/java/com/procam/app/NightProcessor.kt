package com.procam.app

import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Night mode: merges a handheld burst into one cleaner photo.
 *
 * 1. Pick the sharpest frame as the reference.
 * 2. Align every other frame to it (global shift, coarse-to-fine search on small grey copies).
 * 3. Average aligned pixels, but ignore pixels that differ a lot from the reference
 *    (something moved there), so moving people/cars don't leave ghosts.
 * 4. Gently lift the shadows: the merged image is clean enough to brighten.
 */
class NightProcessor {

    /** Subject of the current merge (0..1 of the upright photo), or null. */
    private var subject: android.graphics.RectF? = null

    /** [before]: for HDR, what a normal photo would have looked like (for the review screen). */
    class Result(val jpeg: ByteArray, val merged: Int, val reference: Int, val before: ByteArray? = null)

    /**
     * Blocking: call off the main thread. [iso] is the per-frame ISO (sets the motion threshold).
     * @param hdrStops HDR: the frames were shot this many stops darker; brighten the dark areas back.
     * @param silky Silky (long-exposure look): keep motion instead of rejecting it, so moving water,
     *   clouds and lights blur into streaks while the aligned still parts stay sharp; no shadow lift.
     */
    /** [roi]: the subject (0..1 of the upright photo) to align on and judge sharpness by, or null. */
    fun process(frames: List<ByteArray>, iso: Int, quality: Int, silky: Boolean = false, hdrStops: Float = 0f, lift: Boolean = true,
                roi: android.graphics.RectF? = null): Result {
        subject = roi
        require(frames.isNotEmpty())
        val perPixel = if (silky) BYTES_PER_PIXEL_SILKY else BYTES_PER_PIXEL
        val side = Budget.sideFor(frames[0], perPixel)
        return try {
            merge(frames, iso, quality, silky, hdrStops, side, lift)
        } catch (e: OutOfMemoryError) {
            if (side <= Budget.FLOOR) throw e
            // Memory was tighter than it looked: try again at the safe size.
            Log.w(TAG, "out of memory at $side px, retrying at ${Budget.FLOOR}")
            System.gc()
            merge(frames, iso, quality, silky, hdrStops, Budget.FLOOR, lift)
        }
    }

    private fun merge(frames: List<ByteArray>, iso: Int, quality: Int, silky: Boolean, hdrStops: Float, side: Int, lift: Boolean): Result {
        val hdr = hdrStops > 0f
        // Small grey copies for choosing the reference and aligning.
        val smalls = frames.map { bytes ->
            val bmp = PortraitProcessor.decode(bytes, side)
            val s = Gray.of(bmp, ALIGN_SCALE)
            bmp.recycle()
            s
        }
        // Reference: the frame where the subject (or the whole picture) is sharpest.
        val refIndex = smalls.indices.maxByOrNull { smalls[it].sharpness(subject) } ?: 0
        val refBmp = PortraitProcessor.decode(frames[refIndex], side)
        val w = refBmp.width
        val h = refBmp.height
        val n = w * h
        val refPx = IntArray(n)
        refBmp.getPixels(refPx, 0, w, 0, 0, w, h)
        refBmp.recycle() // memory: only the pixel array is kept while merging
        // HDR "before" (what a normal photo would have looked like), encoded now so it isn't held.
        val before = if (hdr) encode(Hdr.naiveBrighten(refPx, hdrStops), w, h, 90) else null

        // Accumulators, 16 bits each to halve memory: weights are 0..16 per frame, so sums stay
        // below 255 × 16 × 16 = 65280 for up to 16 frames (read back as unsigned).
        var sumR: ShortArray? = ShortArray(n)
        var sumG: ShortArray? = ShortArray(n)
        var sumB: ShortArray? = ShortArray(n)
        val sumW = ShortArray(n)
        // Silky: also track per-pixel brightness variance to find the moving areas.
        val sumL = if (silky) IntArray(n) else null
        val sumSq = if (silky) IntArray(n) else null
        accumulate(refPx, refPx, w, h, 0, 0, 0, sumR!!, sumG!!, sumB!!, sumW, isReference = true, sumL = sumL, sumSq = sumSq)

        var merged = 1
        var framePx: IntArray? = IntArray(n)
        for (i in frames.indices) {
            if (i == refIndex) continue
            try {
                val coarse = align(smalls[refIndex], smalls[i])
                val bmp = PortraitProcessor.decode(frames[i], side)
                if (bmp.width != w || bmp.height != h) {
                    bmp.recycle()
                    continue
                }
                val fp = framePx!!
                bmp.getPixels(fp, 0, w, 0, 0, w, h)
                bmp.recycle()
                val (dx, dy) = refine(refPx, fp, w, h, coarse.first, coarse.second, roi = subject)
                val t = if (silky) 255 else motionThreshold(refPx, fp, w, h, dx, dy)
                Log.i(TAG, "frame $i offset ($dx, $dy), motion threshold $t")
                accumulate(refPx, fp, w, h, dx, dy, t, sumR!!, sumG!!, sumB!!, sumW, isReference = false, sumL = sumL, sumSq = sumSq)
                merged++
            } catch (e: Exception) {
                Log.w(TAG, "frame $i skipped", e)
            }
        }
        framePx = null

        // Average (+ shadow lift for Night).
        val curve = if (silky || hdr || !lift) IDENTITY else LIFT
        val sr = sumR!!
        val sg = sumG!!
        val sb = sumB!!
        for (i in 0 until n) {
            val wt = sumW[i].toInt() and 0xFFFF
            val r = curve[((sr[i].toInt() and 0xFFFF) / wt).coerceIn(0, 255)]
            val g = curve[((sg[i].toInt() and 0xFFFF) / wt).coerceIn(0, 255)]
            val b = curve[((sb[i].toInt() and 0xFFFF) / wt).coerceIn(0, 255)]
            refPx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        sumR = null
        sumG = null
        sumB = null
        if (hdr) {
            // Brighten the dark areas back up; keep the protected highlights.
            Hdr.toneMap(refPx, w, h, hdrStops)
        }
        val ref = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        ref.setPixels(refPx, 0, w, 0, 0, w, h)
        if (silky && sumL != null && sumSq != null) smoothMotion(ref, sumL, sumSq, sumW)
        val out = ByteArrayOutputStream(frames[refIndex].size)
        ref.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), out)
        ref.recycle()
        Log.i(TAG, "merged $merged of ${frames.size} frames at ISO $iso, reference $refIndex, ${w}x$h")
        return Result(out.toByteArray(), merged, refIndex, before)
    }

    private fun encode(px: IntArray, w: Int, h: Int, quality: Int): ByteArray {
        val b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { o -> b.compress(Bitmap.CompressFormat.JPEG, quality, o); b.recycle() }.toByteArray()
    }

    /** A small greyscale image used for alignment. */
    class Gray(val w: Int, val h: Int, val v: IntArray) {
        /** Mean edge strength, within [roi] (0..1) if given. */
        fun sharpness(roi: android.graphics.RectF? = null): Double {
            var sum = 0L
            val x0 = max(1, ((roi?.left ?: 0f) * w).toInt())
            val x1 = min(w - 1, ((roi?.right ?: 1f) * w).toInt())
            val y0 = max(1, ((roi?.top ?: 0f) * h).toInt())
            val y1 = min(h - 1, ((roi?.bottom ?: 1f) * h).toInt())
            var n = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                val i = y * w + x
                sum += abs(4 * v[i] - v[i - 1] - v[i + 1] - v[i - w] - v[i + w])
                n++
            }
            return if (n == 0) 0.0 else sum.toDouble() / n
        }

        fun half(): Gray {
            val hw = max(1, w / 2)
            val hh = max(1, h / 2)
            val out = IntArray(hw * hh)
            for (y in 0 until hh) for (x in 0 until hw) {
                val i = (2 * y) * w + 2 * x
                val r = if (2 * x + 1 < w) 1 else 0
                val d = if (2 * y + 1 < h) w else 0
                out[y * hw + x] = (v[i] + v[i + r] + v[i + d] + v[i + d + r]) / 4
            }
            return Gray(hw, hh, out)
        }

        companion object {
            fun of(bmp: Bitmap, scale: Int): Gray {
                val sw = max(1, bmp.width / scale)
                val sh = max(1, bmp.height / scale)
                val small = PortraitProcessor.shrink(bmp, sw, sh)
                val px = IntArray(sw * sh)
                small.getPixels(px, 0, sw, 0, 0, sw, sh)
                small.recycle()
                return Gray(sw, sh, IntArray(px.size) { luma(px[it]) })
            }
        }
    }

    companion object {
        private const val TAG = "Night"
        /** Alignment works on 1/4-size grey copies, then a 1/16 pyramid level for the coarse search. */
        const val ALIGN_SCALE = 4

        private val IDENTITY = IntArray(256) { it }

        /**
         * Exposure plan for Silky: normal brightness per frame (lowest ISO, so the shutter is as
         * long as the light allows, up to 1/8 s), [frames] frames.
         */
        fun silkyPlan(
            aeIso: Int, aeExposureNs: Long, isoRange: android.util.Range<Int>, exposureRange: android.util.Range<Long>,
        ): Pair<Int, Long> {
            val product = aeIso.toDouble() * aeExposureNs
            var iso = isoRange.lower
            var t = (product / iso).toLong()
            if (t > SILKY_MAX_FRAME_NS) {
                t = SILKY_MAX_FRAME_NS
                iso = (product / t).roundToInt().coerceIn(isoRange.lower, isoRange.upper)
            }
            return Pair(iso, t.coerceIn(exposureRange.lower, exposureRange.upper))
        }

        private const val SILKY_MAX_FRAME_NS = 125_000_000L

        /** Shadow lift: brightens dark tones, leaves highlights alone. */
        private val LIFT = IntArray(256) { i ->
            val x = i / 255f
            val lifted = x + 0.18f * x * (1 - x).pow(2) * 4f
            (lifted.coerceIn(0f, 1f) * 255f).roundToInt()
        }

        fun luma(p: Int) = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8

        /**
         * Shift (in full-resolution pixels) such that frame(x + dx, y + dy) ≈ ref(x, y).
         * Coarse search on a 1/16 copy, refined on the 1/4 copy.
         */
        fun align(ref: Gray, frame: Gray): Pair<Int, Int> {
            val refC = ref.half().half()
            val frmC = frame.half().half()
            var (bx, by) = search(refC, frmC, 0, 0, COARSE_RANGE, penalize = true)
            // 1/16 → 1/4 is 4x.
            val fine = search(ref, frame, bx * 4, by * 4, 4)
            bx = fine.first
            by = fine.second
            return Pair(bx * ALIGN_SCALE, by * ALIGN_SCALE)
        }

        private const val COARSE_RANGE = 8
        /** Cost per coarse-pixel of shift, in SAD units (×1024 per pixel). */
        private const val SHIFT_PENALTY = 600L

        /**
     * Best shift by sum of absolute differences. With [penalize], larger shifts must be clearly
     * better to win: on repeating textures (tiles, fences) several shifts match almost equally
     * and hands only drift a little, so the smaller one is the right one.
     */
    private fun search(ref: Gray, frame: Gray, cx: Int, cy: Int, range: Int, penalize: Boolean = false): Pair<Int, Int> {
            var best = Long.MAX_VALUE
            var bx = cx
            var by = cy
            val mx = ref.w / 8 + abs(cx) + range
            val my = ref.h / 8 + abs(cy) + range
            for (dy in cy - range..cy + range) for (dx in cx - range..cx + range) {
                var sad = 0L
                var count = 0
                var y = my
                while (y < ref.h - my) {
                    val fy = y + dy
                    if (fy in 0 until frame.h) {
                        var x = mx
                        while (x < ref.w - mx) {
                            val fx = x + dx
                            if (fx in 0 until frame.w) {
                                sad += abs(ref.v[y * ref.w + x] - frame.v[fy * frame.w + fx])
                                count++
                            }
                            x += 2
                        }
                    }
                    y += 2
                }
                if (count == 0) continue
                var score = sad * 1024 / count
                if (penalize) score += (abs(dx) + abs(dy)) * SHIFT_PENALTY
                if (score < best) {
                    best = score
                    bx = dx
                    by = dy
                }
            }
            return Pair(bx, by)
        }

        /**
         * Adds one frame (shifted by dx, dy) into the sums, weighting each pixel by how well it
         * matches the reference: noise is averaged away, motion is rejected.
         */
        fun accumulate(
            refPx: IntArray, framePx: IntArray, w: Int, h: Int, dx: Int, dy: Int, t: Int,
            sumR: ShortArray, sumG: ShortArray, sumB: ShortArray, sumW: ShortArray, isReference: Boolean,
            sumL: IntArray? = null, sumSq: IntArray? = null,
        ) {
            for (y in 0 until h) {
                val fy = y + dy
                val rowOk = fy in 0 until h
                for (x in 0 until w) {
                    val i = y * w + x
                    if (isReference) {
                        val p = refPx[i]
                        sumR[i] = (sumR[i] + ((p shr 16) and 0xFF) * FULL).toShort()
                        sumG[i] = (sumG[i] + ((p shr 8) and 0xFF) * FULL).toShort()
                        sumB[i] = (sumB[i] + (p and 0xFF) * FULL).toShort()
                        sumW[i] = (sumW[i] + FULL).toShort()
                        if (sumL != null && sumSq != null) {
                            val l = luma(p)
                            sumL[i] += l
                            sumSq[i] += l * l
                        }
                        continue
                    }
                    if (!rowOk) continue
                    val fx = x + dx
                    if (fx < 0 || fx >= w) continue
                    val p = framePx[fy * w + fx]
                    val d = abs(luma(p) - luma(refPx[i]))
                    val wt = when {
                        d <= t -> FULL
                        d >= 3 * t -> 0
                        else -> FULL * (3 * t - d) / (2 * t)
                    }
                    if (wt == 0) continue
                    // Unsigned 16-bit sums: add to the unsigned value, store the low 16 bits.
                    sumR[i] = ((sumR[i].toInt() and 0xFFFF) + ((p shr 16) and 0xFF) * wt).toShort()
                    sumG[i] = ((sumG[i].toInt() and 0xFFFF) + ((p shr 8) and 0xFF) * wt).toShort()
                    sumB[i] = ((sumB[i].toInt() and 0xFFFF) + (p and 0xFF) * wt).toShort()
                    sumW[i] = (sumW[i] + wt).toShort()
                    if (sumL != null && sumSq != null) {
                        val l = luma(p)
                        sumL[i] += l
                        sumSq[i] += l * l
                    }
                }
            }
        }

        /**
         * Silky: a burst of short exposures leaves moving things as separate "steps". Where the
         * frames disagreed (high variance = motion), blend in a softly blurred copy so the streaks
         * become smooth like a real long exposure; still areas stay untouched and sharp.
         */
        fun smoothMotion(img: Bitmap, sumL: IntArray, sumSq: IntArray, sumW: ShortArray) {
            val w = img.width
            val h = img.height
            // Same look at any resolution: the soft copy and motion cells are ~190 px across.
            val scale = max(MOTION_BLUR_SCALE, (max(w, h) + 95) / 190)
            val small = PortraitProcessor.shrink(img, max(1, w / scale), max(1, h / scale))
            // Soften the small copy further (≈3 small pixels): enough to merge the steps of fast
            // motion into one even streak; only used where things moved.
            val sw = small.width
            val sh = small.height
            val spx = IntArray(sw * sh)
            small.getPixels(spx, 0, sw, 0, 0, sw, sh)
            small.recycle()
            val chans = Array(3) { c -> FloatArray(sw * sh) { i -> ((spx[i] shr (16 - 8 * c)) and 0xFF).toFloat() } }
            for (ch in chans) repeat(3) { PortraitProcessor.boxBlur(ch, sw, sh, 1) }
            for (i in spx.indices) {
                spx[i] = (0xFF shl 24) or
                    (chans[0][i].roundToInt().coerceIn(0, 255) shl 16) or
                    (chans[1][i].roundToInt().coerceIn(0, 255) shl 8) or
                    chans[2][i].roundToInt().coerceIn(0, 255)
            }
            val soft = Bitmap.createBitmap(spx, sw, sh, Bitmap.Config.ARGB_8888)
            val blurred = Bitmap.createScaledBitmap(soft, w, h, true)
            soft.recycle()
            // Where did things move? Decided per coarse cell (and spread to neighbours), so the
            // still gaps between the "steps" of a moving object get smoothed too.
            val cw = max(1, w / scale)
            val ch = max(1, h / scale)
            val cellVar = FloatArray(cw * ch)
            val cellN = IntArray(cw * ch)
            for (y in 0 until h) {
                val cy = min(ch - 1, y * ch / h)
                for (x in 0 until w) {
                    val i = y * w + x
                    val count = sumW[i] / FULL
                    if (count < 2) continue
                    val mean = sumL[i].toFloat() / count
                    val c = cy * cw + min(cw - 1, x * cw / w)
                    cellVar[c] += sumSq[i].toFloat() / count - mean * mean
                    cellN[c]++
                }
            }
            val cellMask = FloatArray(cw * ch) { c ->
                val v = if (cellN[c] > 0) cellVar[c] / cellN[c] else 0f
                ((v - CELL_VAR_LOW) / (CELL_VAR_HIGH - CELL_VAR_LOW)).coerceIn(0f, 1f)
            }
            val spread = FloatArray(cw * ch) { c ->
                val cx = c % cw
                val cy = c / cw
                var m = 0f
                for (yy in max(0, cy - 1)..min(ch - 1, cy + 1)) for (xx in max(0, cx - 1)..min(cw - 1, cx + 1)) {
                    m = max(m, cellMask[yy * cw + xx])
                }
                m
            }
            val mask = PortraitProcessor.Mask(spread, cw, ch)

            val rowO = IntArray(w)
            val rowB = IntArray(w)
            for (y in 0 until h) {
                img.getPixels(rowO, 0, w, 0, y, w, 1)
                blurred.getPixels(rowB, 0, w, 0, y, w, 1)
                var changed = false
                val fy = (y + 0.5f) / h
                for (x in 0 until w) {
                    val m = mask.sample((x + 0.5f) / w, fy)
                    if (m <= 0.01f) continue
                    val o = rowO[x]
                    val b = rowB[x]
                    val r = (((o shr 16) and 0xFF) * (1 - m) + ((b shr 16) and 0xFF) * m).toInt()
                    val g = (((o shr 8) and 0xFF) * (1 - m) + ((b shr 8) and 0xFF) * m).toInt()
                    val bb = ((o and 0xFF) * (1 - m) + (b and 0xFF) * m).toInt()
                    rowO[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bb
                    changed = true
                }
                if (changed) img.setPixels(rowO, 0, w, 0, y, w, 1)
            }
            blurred.recycle()
        }

        /** Motion smoothing: blur scale, and the variance range (levels²) that fades it in. */
        private const val MOTION_BLUR_SCALE = 16
        /** A frame's full weight in the sums (so 16 frames still fit in 16 bits). */
        private const val FULL = 16
        /** Working memory per pixel: reference + frame + decoded frame (4 B each) + 16-bit sums (8 B). */
        private const val BYTES_PER_PIXEL = 22
        /** Silky: + brightness and variance sums (8 B). */
        private const val BYTES_PER_PIXEL_SILKY = 30
        /** Mean variance per cell: a cell with a moving object averages a few hundred levels². */
        private const val CELL_VAR_LOW = 40f
        private const val CELL_VAR_HIGH = 250f

        /**
         * Final alignment at full resolution: tries shifts within ±[range] px of the coarse
         * result on a sparse grid of pixels.
         */
        fun refine(
            refPx: IntArray, framePx: IntArray, w: Int, h: Int, cx: Int, cy: Int, range: Int = 3,
            /** Subject (0..1) to line up exactly; else the middle 60 %. */
            roi: android.graphics.RectF? = null,
        ): Pair<Int, Int> {
            var best = Long.MAX_VALUE
            var bx = cx
            var by = cy
            // At least ~1/8 of the picture, so a small subject still has enough to match.
            val r = roi?.let {
                val cw = max(it.width(), 0.125f)
                val ch = max(it.height(), 0.125f)
                android.graphics.RectF(it.centerX() - cw / 2, it.centerY() - ch / 2, it.centerX() + cw / 2, it.centerY() + ch / 2)
            }
            val x0 = r?.let { (it.left * w).toInt().coerceIn(0, w - 2) } ?: (w / 5)
            val y0 = r?.let { (it.top * h).toInt().coerceIn(0, h - 2) } ?: (h / 5)
            val x1 = r?.let { (it.right * w).toInt().coerceIn(x0 + 1, w) } ?: (w - w / 5)
            val y1 = r?.let { (it.bottom * h).toInt().coerceIn(y0 + 1, h) } ?: (h - h / 5)
            val stepPx = if (r != null) 2 else 3
            for (dy in cy - range..cy + range) for (dx in cx - range..cx + range) {
                var sad = 0L
                var count = 0
                var y = y0
                while (y < y1) {
                    val fy = y + dy
                    if (fy in 0 until h) {
                        var x = x0
                        while (x < x1) {
                            val fx = x + dx
                            if (fx in 0 until w) {
                                sad += abs(luma(refPx[y * w + x]) - luma(framePx[fy * w + fx]))
                                count++
                            }
                            x += stepPx
                        }
                    }
                    y += stepPx
                }
                if (count == 0) continue
                val score = sad * 1024 / count
                if (score < best) {
                    best = score
                    bx = dx
                    by = dy
                }
            }
            return Pair(bx, by)
        }

        /**
         * How different two aligned pixels can be before we call it motion: a few times the
         * measured noise (median absolute difference → standard deviation), so pure noise is
         * averaged away and only real changes are rejected.
         */
        fun motionThreshold(refPx: IntArray, framePx: IntArray, w: Int, h: Int, dx: Int, dy: Int): Int {
            val hist = IntArray(256)
            var count = 0
            var y = h / 8
            while (y < h - h / 8) {
                val fy = y + dy
                if (fy in 0 until h) {
                    var x = w / 8
                    while (x < w - w / 8) {
                        val fx = x + dx
                        if (fx in 0 until w) {
                            hist[abs(luma(refPx[y * w + x]) - luma(framePx[fy * w + fx]))]++
                            count++
                        }
                        x += 7
                    }
                }
                y += 7
            }
            var acc = 0
            var median = 0
            for (v in 0..255) {
                acc += hist[v]
                if (acc * 2 >= count) {
                    median = v
                    break
                }
            }
            val sigma = 1.4826f * median
            return (2.5f * sigma).roundToInt().coerceIn(6, 60)
        }

        /**
         * Exposure plan for a night burst: per-frame shutter limited to what the hands can hold
         * (and 1/4 s), ISO raised so the merge comes out about [brighten]x brighter than auto.
         */
        fun plan(
            aeIso: Int, aeExposureNs: Long, safeShutterNs: Long,
            isoRange: android.util.Range<Int>, exposureRange: android.util.Range<Long>, brighten: Double = 1.6,
        ): Triple<Int, Long, Int> {
            // Only brighten scenes that are actually dim; a bright scene keeps auto's exposure.
            val dim = aeIso >= 400 || aeExposureNs >= 30_000_000L
            val product = aeIso.toDouble() * aeExposureNs * (if (dim) brighten else 1.0)
            var t = min(safeShutterNs, MAX_FRAME_NS).coerceIn(exposureRange.lower, exposureRange.upper)
            var iso = (product / t).roundToInt()
            if (iso > isoRange.upper) {
                // Not enough ISO: allow a bit longer than "safe" (merge forgives some blur).
                iso = isoRange.upper
                t = (product / iso).toLong().coerceAtMost(min(MAX_FRAME_NS, safeShutterNs * 2)).coerceIn(exposureRange.lower, exposureRange.upper)
            } else if (iso < isoRange.lower) {
                // Bright scene: ISO can't go lower, so shorten the shutter instead (else it overexposes).
                iso = isoRange.lower
                t = (product / iso).toLong().coerceIn(exposureRange.lower, exposureRange.upper)
            }
            iso = iso.coerceIn(isoRange.lower, isoRange.upper)
            val frames = if (t > 100_000_000L) 6 else 8
            return Triple(iso, t, frames)
        }

        private const val MAX_FRAME_NS = 250_000_000L
    }
}
