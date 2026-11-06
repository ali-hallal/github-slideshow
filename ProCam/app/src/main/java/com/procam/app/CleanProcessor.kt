package com.procam.app

import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * "Clean" mode: removes people and cars passing through the picture.
 *
 * A few frames are taken over ~3 seconds. Each is aligned to the middle one (hands drift), then
 * every pixel takes the value most frames agree on (the median brightness): someone walking past
 * covers a spot in only one or two frames, so they're out-voted by the background. Frames close
 * to that median are averaged, which also removes noise.
 */
class CleanProcessor {

    /** [reference]: index of the frame the others were aligned to (the review's "before"). */
    class Result(val jpeg: ByteArray, val used: Int, val reference: Int)

    /** Blocking: call off the main thread. */
    fun process(frames: List<ByteArray>, quality: Int): Result {
        require(frames.isNotEmpty())
        val side = Budget.sideFor(frames[0], frames.size * 4 + 12)
        return try {
            run(frames, quality, side)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "out of memory at $side px, retrying smaller")
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
        val ref = pixels.size / 2 // the middle frame in time
        val offsets = pixels.indices.map { k ->
            if (k == ref) 0 to 0
            else {
                val c = NightProcessor.align(grays[ref], grays[k])
                NightProcessor.refine(pixels[ref], pixels[k], w, h, c.first, c.second)
            }
        }
        Log.i(TAG, "clean: ${pixels.size} frames ${w}x$h, offsets $offsets")
        val out = IntArray(w * h)
        merge(pixels, offsets, w, h, out)
        pixels.clear()
        val bmp = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        val jpeg = ByteArrayOutputStream(frames[0].size).also { bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), it) }.toByteArray()
        bmp.recycle()
        return Result(jpeg, offsets.size, ref)
    }

    companion object {
        private const val TAG = "Clean"
        /** Frames within this many brightness levels of the median are averaged (noise, not motion). */
        const val TOLERANCE = 14

        /**
         * Per pixel: the median-brightness value across the aligned frames, averaged with the
         * frames that agree with it. [offsets] are (dx, dy) such that frame(x + dx, y + dy) ≈ ref(x, y).
         * Runs in parallel bands.
         */
        fun merge(frames: List<IntArray>, offsets: List<Pair<Int, Int>>, w: Int, h: Int, out: IntArray) {
            val bands = max(1, min(Runtime.getRuntime().availableProcessors(), 8))
            val threads = (0 until bands).map { b ->
                Thread {
                    mergeRows(frames, offsets, w, h, out, h * b / bands, h * (b + 1) / bands)
                }.apply { start() }
            }
            threads.forEach { it.join() }
        }

        private fun mergeRows(frames: List<IntArray>, offsets: List<Pair<Int, Int>>, w: Int, h: Int, out: IntArray, y0: Int, y1: Int) {
            val n = frames.size
            val vals = IntArray(n)
            val lum = IntArray(n)
            val order = IntArray(n)
            for (y in y0 until y1) {
                for (x in 0 until w) {
                    var m = 0
                    for (k in 0 until n) {
                        val fx = x + offsets[k].first
                        val fy = y + offsets[k].second
                        if (fx < 0 || fy < 0 || fx >= w || fy >= h) continue
                        val p = frames[k][fy * w + fx]
                        vals[m] = p
                        lum[m] = NightProcessor.luma(p)
                        order[m] = m
                        m++
                    }
                    if (m == 0) {
                        out[y * w + x] = frames[n / 2][y * w + x]
                        continue
                    }
                    // Insertion sort of the few candidates by brightness.
                    for (i in 1 until m) {
                        val o = order[i]
                        var j = i - 1
                        while (j >= 0 && lum[order[j]] > lum[o]) {
                            order[j + 1] = order[j]
                            j--
                        }
                        order[j + 1] = o
                    }
                    val med = lum[order[m / 2]]
                    var r = 0
                    var g = 0
                    var b = 0
                    var c = 0
                    for (i in 0 until m) {
                        if (abs(lum[i] - med) > TOLERANCE) continue
                        val p = vals[i]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        c++
                    }
                    out[y * w + x] = (0xFF shl 24) or ((r / c) shl 16) or ((g / c) shl 8) or (b / c)
                }
            }
        }
    }
}
