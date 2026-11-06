package com.procam.app

import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Light trails: a few seconds of back-to-back frames stacked with "lighten" (each pixel keeps
 * its brightest value), so moving lights — cars, sparklers, a torch — draw continuous lines while
 * the still scene stays as it is. Frames are aligned to the first (hand drift).
 *
 * Streams: only the running result, the first frame and the current frame are in memory.
 */
class TrailsProcessor {

    class Result(val jpeg: ByteArray, val used: Int)

    fun process(frames: List<ByteArray>, quality: Int): Result {
        require(frames.isNotEmpty())
        val side = Budget.sideFor(frames[0], BYTES_PER_PIXEL)
        return try {
            run(frames, quality, side)
        } catch (e: OutOfMemoryError) {
            System.gc()
            run(frames, quality, minOf(side, 2000))
        }
    }

    private fun run(frames: List<ByteArray>, quality: Int, side: Int): Result {
        val first = PortraitProcessor.decode(frames[0], side)
        val w = first.width
        val h = first.height
        val refGray = NightProcessor.Gray.of(first, NightProcessor.ALIGN_SCALE)
        val refPx = IntArray(w * h).also { first.getPixels(it, 0, w, 0, 0, w, h) }
        first.recycle()
        val acc = refPx.copyOf()
        val px = IntArray(w * h)
        var used = 1
        for (k in 1 until frames.size) {
            val bmp = PortraitProcessor.decode(frames[k], side)
            if (bmp.width != w || bmp.height != h) {
                bmp.recycle()
                continue
            }
            val gray = NightProcessor.Gray.of(bmp, NightProcessor.ALIGN_SCALE)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            val c = NightProcessor.align(refGray, gray)
            val (dx, dy) = NightProcessor.refine(refPx, px, w, h, c.first, c.second)
            lighten(acc, px, w, h, dx, dy)
            used++
        }
        Log.i(TAG, "trails: stacked $used of ${frames.size} frames ${w}x$h")
        val bmp = Bitmap.createBitmap(acc, w, h, Bitmap.Config.ARGB_8888)
        val jpeg = ByteArrayOutputStream(frames[0].size).also { bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), it) }.toByteArray()
        bmp.recycle()
        return Result(jpeg, used)
    }

    companion object {
        private const val TAG = "Trails"
        /** Running result + first frame + current frame + decode (4 B each). */
        private const val BYTES_PER_PIXEL = 16

        /** Lighten blend: acc = max(acc, frame shifted by dx, dy), per channel. */
        fun lighten(acc: IntArray, frame: IntArray, w: Int, h: Int, dx: Int, dy: Int) {
            for (y in 0 until h) {
                val fy = y + dy
                if (fy < 0 || fy >= h) continue
                for (x in 0 until w) {
                    val fx = x + dx
                    if (fx < 0 || fx >= w) continue
                    val a = acc[y * w + x]
                    val p = frame[fy * w + fx]
                    acc[y * w + x] = (0xFF shl 24) or
                        (max((a shr 16) and 0xFF, (p shr 16) and 0xFF) shl 16) or
                        (max((a shr 8) and 0xFF, (p shr 8) and 0xFF) shl 8) or
                        max(a and 0xFF, p and 0xFF)
                }
            }
        }

        /**
         * Per-frame exposure: as bright as auto exposure, each frame up to [maxFrameNs] long so the
         * frames join up into continuous lines (shorter in bright light, where ISO is at minimum).
         */
        fun plan(aeIso: Int, aeExposureNs: Long, isoRange: android.util.Range<Int>, exposureRange: android.util.Range<Long>, maxFrameNs: Long): Pair<Int, Long> {
            val product = aeIso.toDouble() * aeExposureNs
            var t = maxFrameNs.coerceIn(exposureRange.lower, exposureRange.upper)
            var iso = (product / t).toInt()
            if (iso < isoRange.lower) {
                iso = isoRange.lower
                t = (product / iso).toLong().coerceIn(exposureRange.lower, exposureRange.upper)
            }
            return Pair(iso.coerceAtMost(isoRange.upper), t)
        }
    }
}
