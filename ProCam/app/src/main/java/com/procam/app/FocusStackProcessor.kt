package com.procam.app

import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Close-up focus stacking: close up, only a thin slice is in focus. A few shots focused from
 * near to far are aligned, and each pixel takes mostly from the shot where that spot is sharpest
 * (local contrast), so the whole subject comes out sharp.
 */
class FocusStackProcessor {

    class Result(val jpeg: ByteArray, val used: Int, val reference: Int)

    fun process(frames: List<ByteArray>, quality: Int): Result {
        require(frames.isNotEmpty())
        val side = Budget.sideFor(frames[0], frames.size * 8 + 8)
        return try {
            run(frames, quality, side)
        } catch (e: OutOfMemoryError) {
            System.gc()
            run(frames, quality, min(side, 2000))
        }
    }

    private fun run(frames: List<ByteArray>, quality: Int, side: Int): Result {
        val pixels = ArrayList<IntArray>()
        val grays = ArrayList<NightProcessor.Gray>()
        var w = 0
        var h = 0
        for (bytes in frames) {
            val bmp = PortraitProcessor.decode(bytes, side)
            if (w == 0) {
                w = bmp.width
                h = bmp.height
            }
            if (bmp.width == w && bmp.height == h) {
                grays += NightProcessor.Gray.of(bmp, NightProcessor.ALIGN_SCALE)
                pixels += IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
            }
            bmp.recycle()
        }
        val ref = pixels.size / 2
        val offsets = pixels.indices.map { k ->
            if (k == ref) 0 to 0
            else {
                val c = NightProcessor.align(grays[ref], grays[k])
                NightProcessor.refine(pixels[ref], pixels[k], w, h, c.first, c.second)
            }
        }
        val radius = max(2, max(w, h) / 500)
        val sharp = pixels.map { sharpness(it, w, h, radius) }
        val out = IntArray(w * h)
        stack(pixels, sharp, offsets, w, h, out)
        Log.i(TAG, "close-up: stacked ${pixels.size} frames ${w}x$h, offsets $offsets")
        pixels.clear()
        val bmp = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        val jpeg = ByteArrayOutputStream(frames[0].size).also { bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), it) }.toByteArray()
        bmp.recycle()
        return Result(jpeg, offsets.size, ref)
    }

    companion object {
        private const val TAG = "CloseUp"

        /** Local sharpness: |Laplacian| of brightness, smoothed over a small area. */
        fun sharpness(px: IntArray, w: Int, h: Int, radius: Int): FloatArray {
            val s = FloatArray(w * h)
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                val c = NightProcessor.luma(px[i])
                s[i] = abs(4 * c - NightProcessor.luma(px[i - 1]) - NightProcessor.luma(px[i + 1]) -
                    NightProcessor.luma(px[i - w]) - NightProcessor.luma(px[i + w])).toFloat()
            }
            PortraitProcessor.boxBlur(s, w, h, radius)
            PortraitProcessor.boxBlur(s, w, h, radius)
            return s
        }

        /**
         * Each pixel: weighted mix of the frames, weight = sharpness⁴ (the sharpest frame
         * dominates, with soft transitions). [offsets]: frame(x + dx, y + dy) ≈ ref(x, y).
         */
        fun stack(frames: List<IntArray>, sharp: List<FloatArray>, offsets: List<Pair<Int, Int>>, w: Int, h: Int, out: IntArray) {
            val n = frames.size
            val bands = max(1, min(Runtime.getRuntime().availableProcessors(), 8))
            (0 until bands).map { b ->
                Thread {
                    for (y in h * b / bands until h * (b + 1) / bands) for (x in 0 until w) {
                        var r = 0f
                        var g = 0f
                        var bl = 0f
                        var wt = 0f
                        for (k in 0 until n) {
                            val fx = x + offsets[k].first
                            val fy = y + offsets[k].second
                            if (fx < 0 || fy < 0 || fx >= w || fy >= h) continue
                            val j = fy * w + fx
                            val s = sharp[k][j] + 1f
                            val s2 = s * s
                            val weight = s2 * s2
                            val p = frames[k][j]
                            r += ((p shr 16) and 0xFF) * weight
                            g += ((p shr 8) and 0xFF) * weight
                            bl += (p and 0xFF) * weight
                            wt += weight
                        }
                        out[y * w + x] = if (wt <= 0f) frames[n / 2][y * w + x]
                        else (0xFF shl 24) or ((r / wt).toInt().coerceIn(0, 255) shl 16) or
                            ((g / wt).toInt().coerceIn(0, 255) shl 8) or (bl / wt).toInt().coerceIn(0, 255)
                    }
                }.apply { start() }
            }.forEach { it.join() }
        }

        /** Focus distances (diopters) bracketing the current focus [d], within 0..[minFocus]. */
        fun plan(d: Float, minFocus: Float, count: Int): FloatArray {
            val near = min(minFocus, d * 1.6f + 0.5f)
            val far = max(0f, d * 0.6f - 0.5f)
            return FloatArray(count) { k -> near + (far - near) * k / (count - 1).coerceAtLeast(1) }
        }
    }
}
