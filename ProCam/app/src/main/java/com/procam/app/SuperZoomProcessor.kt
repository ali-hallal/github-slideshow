package com.procam.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Super zoom: zoom past the camera's own maximum.
 *
 * From a short handheld burst, the zoomed-in centre of each photo is cut out at full resolution
 * and enlarged 2x. Hand shake means every frame samples the scene at slightly different
 * positions, so aligning the enlarged crops to half a pixel and averaging them recovers more real
 * detail (and far less noise) than enlarging one crop. A final sharpening pass restores crispness.
 */
class SuperZoomProcessor {

    class Result(val jpeg: ByteArray, val merged: Int, val width: Int, val height: Int)

    /**
     * @param crop how much further than the camera's own zoom (e.g. 3 = the centre third).
     * Blocking: call off the main thread.
     */
    fun process(frames: List<ByteArray>, crop: Float, quality: Int): Result {
        require(frames.isNotEmpty())
        val rotation = exifRotation(frames.first())
        // 1. Full-resolution centre crops.
        val crops = frames.mapNotNull { bytes ->
            decodeCentre(bytes, crop)?.let { bmp ->
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                val f = SuperRes.Frame(px, bmp.width, bmp.height)
                bmp.recycle()
                f
            }
        }
        require(crops.isNotEmpty()) { "could not decode any frame" }
        return processFrames(crops, rotation, quality, frames.size)
    }

    /**
     * The merge itself, from frames already cropped and converted to pixels (fast bursts arrive
     * like this). [rotation] = clockwise degrees to make the result upright.
     */
    fun processFrames(crops: List<SuperRes.Frame>, rotation: Int, quality: Int, taken: Int = crops.size): Result {
        require(crops.isNotEmpty())
        val t0 = android.os.SystemClock.uptimeMillis()
        val w0 = crops[0].w
        val h0 = crops[0].h
        val same = crops.filter { it.w == w0 && it.h == h0 }
        // Lucky frames: keep the sharpest (frames taken mid-tremor are dropped). Sharpest first = reference.
        val chosen = SuperRes.sharpest(same, KEEP_FRAMES).map { same[it] }
        val t1 = android.os.SystemClock.uptimeMillis()
        // Sub-pixel offsets of every frame relative to the reference (in parallel).
        val all = SuperRes.offsets(chosen, GRAY_SCALE)
        // Drop frames that don't really match the reference (moved subject, stale frame, failed
        // alignment) instead of trusting them: cost well above the typical frame's.
        val typical = all.drop(1).map { it.cost }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        val good = all.indices.filter { k -> k == 0 || all[k].cost <= max(typical * 3, 4.0) }
        val frames = good.map { chosen[it] }
        val offsets = good.map { all[it].dx to all[it].dy }
        val t2 = android.os.SystemClock.uptimeMillis()
        val motion = IntArray(frames.size) { k ->
            val (dx, dy) = offsets[k]
            if (k == 0) 255 else NightProcessor.motionThreshold(frames[0].px, frames[k].px, w0, h0, dx.roundToInt(), dy.roundToInt())
        }
        Log.i(TAG, "kept ${frames.size} of $taken frames; offsets ${all.map { "%.2f,%.2f (%.0f)".format(it.dx, it.dy, it.cost) }}")
        // Shift-and-add onto a finer grid (2-3x, aiming for ~3000 px), then sharpen.
        val scale = (MAX_SIDE.toFloat() / max(w0, h0)).coerceIn(1f, MAX_SCALE)
        val merged = SuperRes.merge(frames, offsets, motion, scale)
        val t3 = android.os.SystemClock.uptimeMillis()
        SuperRes.sharpen(merged)
        val t4 = android.os.SystemClock.uptimeMillis()

        var out = Bitmap.createBitmap(merged.px, merged.w, merged.h, Bitmap.Config.ARGB_8888)
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(out, 0, 0, out.width, out.height, m, true)
            out.recycle()
            out = rotated
        }
        val bytes = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), bytes)
        val result = Result(bytes.toByteArray(), frames.size, out.width, out.height)
        out.recycle()
        val t5 = android.os.SystemClock.uptimeMillis()
        Log.i(TAG, "super zoom: ${result.width}x${result.height} from ${frames.size} of $taken frames ${w0}x$h0 (x$scale); " +
            "timing ms: lucky ${t1 - t0}, align ${t2 - t1}, merge ${t3 - t2}, sharpen ${t4 - t3}, encode ${t5 - t4}, " +
            "cores ${Runtime.getRuntime().availableProcessors()}")
        return result
    }

    companion object {
        private const val TAG = "SuperZoom"
        private const val MAX_SIDE = 3000
        /** Enlargement measured to recover real detail from ~6 frames; more would only add pixels. */
        private const val MAX_SCALE = 2f
        /** Frames merged out of the burst (the sharpest ones). */
        const val KEEP_FRAMES = 6
        private const val GRAY_SCALE = 2

        /**
         * Exposure for a super-zoom burst: a shutter fast enough to freeze hand shake at this
         * zoom (with margin), ISO raised to keep the brightness; the merge removes the extra noise.
         */
        fun plan(
            aeIso: Int, aeExposureNs: Long, safeShutterNs: Long,
            isoRange: android.util.Range<Int>, exposureRange: android.util.Range<Long>,
        ): Pair<Int, Long> {
            val product = aeIso.toDouble() * aeExposureNs
            var t = min(aeExposureNs, (safeShutterNs * 0.7).toLong()).coerceIn(exposureRange.lower, exposureRange.upper)
            var iso = (product / t).roundToInt()
            if (iso > isoRange.upper) {
                iso = isoRange.upper
                t = (product / iso).toLong().coerceIn(exposureRange.lower, exposureRange.upper)
            }
            return Pair(iso.coerceIn(isoRange.lower, isoRange.upper), t)
        }

        /** Full-resolution centre 1/[crop] of a JPEG, decoded without decoding the rest. */
        @Suppress("DEPRECATION")
        fun decodeCentre(jpeg: ByteArray, crop: Float): Bitmap? = try {
            val decoder = BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false)
            val fw = decoder!!.width
            val fh = decoder.height
            val rw = max(1, (fw / crop).roundToInt())
            val rh = max(1, (fh / crop).roundToInt())
            val left = (fw - rw) / 2
            val top = (fh - rh) / 2
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bmp = decoder.decodeRegion(Rect(left, top, left + rw, top + rh), opts)
            decoder.recycle()
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "decodeCentre failed", e)
            null
        }

        /** Clockwise degrees the JPEG must be turned to be upright. */
        fun exifRotation(jpeg: ByteArray): Int = try {
            when (ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }
}
