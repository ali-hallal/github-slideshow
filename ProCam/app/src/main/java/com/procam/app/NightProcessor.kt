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

    class Result(val jpeg: ByteArray, val merged: Int, val reference: Int)

    /** Blocking: call off the main thread. [iso] is the per-frame ISO (sets the motion threshold). */
    /**
     * @param silky Silky (long-exposure look): keep motion instead of rejecting it, so moving water,
     *   clouds and lights blur into streaks while the aligned still parts stay sharp; no shadow lift.
     */
    fun process(frames: List<ByteArray>, iso: Int, quality: Int, silky: Boolean = false): Result {
        require(frames.isNotEmpty())
        // Small grey copies for choosing the reference and aligning.
        val smalls = frames.map { bytes ->
            val bmp = PortraitProcessor.decode(bytes)
            val s = Gray.of(bmp, ALIGN_SCALE)
            bmp.recycle()
            s
        }
        val refIndex = smalls.indices.maxByOrNull { smalls[it].sharpness() } ?: 0
        val ref = PortraitProcessor.decode(frames[refIndex])
        val w = ref.width
        val h = ref.height
        val n = w * h
        val refPx = IntArray(n)
        ref.getPixels(refPx, 0, w, 0, 0, w, h)

        // Accumulators: weights are 0..256 per frame, so Int sums can't overflow for <= 16 frames.
        val sumR = IntArray(n)
        val sumG = IntArray(n)
        val sumB = IntArray(n)
        val sumW = IntArray(n)
        // Silky: also track per-pixel brightness variance to find the moving areas.
        val sumL = if (silky) IntArray(n) else null
        val sumSq = if (silky) IntArray(n) else null
        accumulate(refPx, refPx, w, h, 0, 0, 0, sumR, sumG, sumB, sumW, isReference = true, sumL = sumL, sumSq = sumSq)

        var merged = 1
        val framePx = IntArray(n)
        for (i in frames.indices) {
            if (i == refIndex) continue
            try {
                val coarse = align(smalls[refIndex], smalls[i])
                val bmp = PortraitProcessor.decode(frames[i])
                if (bmp.width != w || bmp.height != h) {
                    bmp.recycle()
                    continue
                }
                bmp.getPixels(framePx, 0, w, 0, 0, w, h)
                bmp.recycle()
                val (dx, dy) = refine(refPx, framePx, w, h, coarse.first, coarse.second)
                val t = if (silky) 255 else motionThreshold(refPx, framePx, w, h, dx, dy)
                Log.i(TAG, "frame $i offset ($dx, $dy), motion threshold $t")
                accumulate(refPx, framePx, w, h, dx, dy, t, sumR, sumG, sumB, sumW, isReference = false, sumL = sumL, sumSq = sumSq)
                merged++
            } catch (e: Exception) {
                Log.w(TAG, "frame $i skipped", e)
            }
        }

        // Average (+ shadow lift for Night).
        val curve = if (silky) IDENTITY else LIFT
        for (i in 0 until n) {
            val wt = sumW[i]
            val r = curve[(sumR[i] / wt).coerceIn(0, 255)]
            val g = curve[(sumG[i] / wt).coerceIn(0, 255)]
            val b = curve[(sumB[i] / wt).coerceIn(0, 255)]
            refPx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        ref.setPixels(refPx, 0, w, 0, 0, w, h)
        if (silky && sumL != null && sumSq != null) smoothMotion(ref, sumL, sumSq, sumW)
        val out = ByteArrayOutputStream(frames[refIndex].size)
        ref.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), out)
        ref.recycle()
        Log.i(TAG, "merged $merged of ${frames.size} frames at ISO $iso, reference $refIndex")
        return Result(out.toByteArray(), merged, refIndex)
    }

    /** A small greyscale image used for alignment. */
    class Gray(val w: Int, val h: Int, val v: IntArray) {
        fun sharpness(): Double {
            var sum = 0L
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                sum += abs(4 * v[i] - v[i - 1] - v[i + 1] - v[i - w] - v[i + w])
            }
            return sum.toDouble() / (w * h)
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
            sumR: IntArray, sumG: IntArray, sumB: IntArray, sumW: IntArray, isReference: Boolean,
            sumL: IntArray? = null, sumSq: IntArray? = null,
        ) {
            for (y in 0 until h) {
                val fy = y + dy
                val rowOk = fy in 0 until h
                for (x in 0 until w) {
                    val i = y * w + x
                    if (isReference) {
                        val p = refPx[i]
                        sumR[i] += ((p shr 16) and 0xFF) * 256
                        sumG[i] += ((p shr 8) and 0xFF) * 256
                        sumB[i] += (p and 0xFF) * 256
                        sumW[i] += 256
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
                        d <= t -> 256
                        d >= 3 * t -> 0
                        else -> 256 * (3 * t - d) / (2 * t)
                    }
                    if (wt == 0) continue
                    sumR[i] += ((p shr 16) and 0xFF) * wt
                    sumG[i] += ((p shr 8) and 0xFF) * wt
                    sumB[i] += (p and 0xFF) * wt
                    sumW[i] += wt
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
        fun smoothMotion(img: Bitmap, sumL: IntArray, sumSq: IntArray, sumW: IntArray) {
            val w = img.width
            val h = img.height
            val small = PortraitProcessor.shrink(img, max(1, w / MOTION_BLUR_SCALE), max(1, h / MOTION_BLUR_SCALE))
            val blurred = Bitmap.createScaledBitmap(small, w, h, true)
            small.recycle()
            // Where did things move? Decided per coarse cell (and spread to neighbours), so the
            // still gaps between the "steps" of a moving object get smoothed too.
            val cw = max(1, w / MOTION_BLUR_SCALE)
            val ch = max(1, h / MOTION_BLUR_SCALE)
            val cellVar = FloatArray(cw * ch)
            val cellN = IntArray(cw * ch)
            for (y in 0 until h) {
                val cy = min(ch - 1, y * ch / h)
                for (x in 0 until w) {
                    val i = y * w + x
                    val count = sumW[i] / 256
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
        private const val MOTION_BLUR_SCALE = 12
        /** Mean variance per cell: a cell with a moving object averages a few hundred levels². */
        private const val CELL_VAR_LOW = 40f
        private const val CELL_VAR_HIGH = 250f

        /**
         * Final alignment at full resolution: tries shifts within ±[range] px of the coarse
         * result on a sparse grid of pixels.
         */
        fun refine(refPx: IntArray, framePx: IntArray, w: Int, h: Int, cx: Int, cy: Int, range: Int = 3): Pair<Int, Int> {
            var best = Long.MAX_VALUE
            var bx = cx
            var by = cy
            val x0 = w / 5
            val y0 = h / 5
            for (dy in cy - range..cy + range) for (dx in cx - range..cx + range) {
                var sad = 0L
                var count = 0
                var y = y0
                while (y < h - y0) {
                    val fy = y + dy
                    if (fy in 0 until h) {
                        var x = x0
                        while (x < w - x0) {
                            val fx = x + dx
                            if (fx in 0 until w) {
                                sad += abs(luma(refPx[y * w + x]) - luma(framePx[fy * w + fx]))
                                count++
                            }
                            x += 3
                        }
                    }
                    y += 3
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
