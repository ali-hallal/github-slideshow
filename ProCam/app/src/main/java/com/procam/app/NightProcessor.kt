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
    fun process(frames: List<ByteArray>, iso: Int, quality: Int): Result {
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
        accumulate(refPx, refPx, w, h, 0, 0, 0, sumR, sumG, sumB, sumW, isReference = true)

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
                val t = motionThreshold(refPx, framePx, w, h, dx, dy)
                Log.i(TAG, "frame $i offset ($dx, $dy), motion threshold $t")
                accumulate(refPx, framePx, w, h, dx, dy, t, sumR, sumG, sumB, sumW, isReference = false)
                merged++
            } catch (e: Exception) {
                Log.w(TAG, "frame $i skipped", e)
            }
        }

        // Average + shadow lift.
        for (i in 0 until n) {
            val wt = sumW[i]
            val r = LIFT[(sumR[i] / wt).coerceIn(0, 255)]
            val g = LIFT[(sumG[i] / wt).coerceIn(0, 255)]
            val b = LIFT[(sumB[i] / wt).coerceIn(0, 255)]
            refPx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        ref.setPixels(refPx, 0, w, 0, 0, w, h)
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
                val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
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
                }
            }
        }

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
