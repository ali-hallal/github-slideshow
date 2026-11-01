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
        val crops = frames.mapNotNull { decodeCentre(it, crop) }
        require(crops.isNotEmpty()) { "could not decode any frame" }
        val cw = crops[0].width
        val ch = crops[0].height
        // Enlarge 2x (or less, to keep the result around 3000 px).
        val up = (MAX_SIDE.toFloat() / max(cw, ch)).coerceIn(1f, 2f)
        val w = (cw * up).roundToInt()
        val h = (ch * up).roundToInt()
        val n = w * h

        // Enlarge one crop at a time (keeps memory low): an enlarged frame's pixels, or null.
        fun enlarged(i: Int, into: IntArray): Boolean {
            val c = crops[i]
            if (c.width != cw || c.height != ch) return false
            val big = Bitmap.createScaledBitmap(c, w, h, true)
            big.getPixels(into, 0, w, 0, 0, w, h)
            if (big !== c) big.recycle()
            return true
        }
        val grays = crops.map { NightProcessor.Gray.of(it, max(1, (NightProcessor.ALIGN_SCALE / up).roundToInt())) }
        val refIndex = grays.indices.maxByOrNull { grays[it].sharpness() } ?: 0
        val refPx = IntArray(n)
        enlarged(refIndex, refPx)
        // Gray copies were made from the small crops: scale their shifts to the enlarged size.
        val alignScale = w.toFloat() / grays[refIndex].w

        val sumR = IntArray(n)
        val sumG = IntArray(n)
        val sumB = IntArray(n)
        val sumW = IntArray(n)
        NightProcessor.accumulate(refPx, refPx, w, h, 0, 0, 0, sumR, sumG, sumB, sumW, isReference = true)
        var merged = 1
        val px = IntArray(n)
        for (i in crops.indices) {
            if (i == refIndex) continue
            if (!enlarged(i, px)) continue
            val coarse = NightProcessor.align(grays[refIndex], grays[i])
            val cx = (coarse.first / NightProcessor.ALIGN_SCALE * alignScale).roundToInt()
            val cy = (coarse.second / NightProcessor.ALIGN_SCALE * alignScale).roundToInt()
            // Refined on the enlarged image: 1 px here = half a pixel of the original crop.
            val (dx, dy) = NightProcessor.refine(refPx, px, w, h, cx, cy, range = 4)
            val t = NightProcessor.motionThreshold(refPx, px, w, h, dx, dy)
            NightProcessor.accumulate(refPx, px, w, h, dx, dy, t, sumR, sumG, sumB, sumW, isReference = false)
            merged++
            Log.i(TAG, "frame $i offset ($dx, $dy) threshold $t")
        }
        crops.forEach { it.recycle() }

        for (i in 0 until n) {
            val wt = sumW[i]
            refPx[i] = (0xFF shl 24) or
                ((sumR[i] / wt).coerceIn(0, 255) shl 16) or
                ((sumG[i] / wt).coerceIn(0, 255) shl 8) or
                (sumB[i] / wt).coerceIn(0, 255)
        }
        sharpen(refPx, w, h)

        var out = Bitmap.createBitmap(refPx, w, h, Bitmap.Config.ARGB_8888)
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(out, 0, 0, w, h, m, true)
            out.recycle()
            out = rotated
        }
        val bytes = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), bytes)
        val result = Result(bytes.toByteArray(), merged, out.width, out.height)
        out.recycle()
        Log.i(TAG, "super zoom x$crop: merged $merged frames into ${result.width}x${result.height}")
        return result
    }

    companion object {
        private const val TAG = "SuperZoom"
        private const val MAX_SIDE = 3000
        private const val SHARPEN_AMOUNT = 0.7f
        private const val SHARPEN_RADIUS = 2

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

        /** Unsharp mask: adds back fine detail lost to enlarging and averaging. */
        fun sharpen(px: IntArray, w: Int, h: Int) {
            val chans = Array(3) { c -> FloatArray(w * h) { i -> ((px[i] shr (16 - 8 * c)) and 0xFF).toFloat() } }
            val blurred = Array(3) { c -> chans[c].copyOf() }
            for (b in blurred) repeat(2) { PortraitProcessor.boxBlur(b, w, h, SHARPEN_RADIUS) }
            for (i in px.indices) {
                var p = 0xFF shl 24
                for (c in 0 until 3) {
                    val v = chans[c][i] + SHARPEN_AMOUNT * (chans[c][i] - blurred[c][i])
                    p = p or (min(255, max(0, v.roundToInt())) shl (16 - 8 * c))
                }
                px[i] = p
            }
        }
    }
}
