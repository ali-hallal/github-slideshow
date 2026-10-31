package com.procam.app

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Portrait mode: finds the person (ML Kit selfie segmentation, on-device) and blurs the
 * background like a large-aperture lens, keeping the person sharp.
 */
class PortraitProcessor {

    class Result(val jpeg: ByteArray?, val personFound: Boolean)

    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build(),
    )

    /**
     * Turns a captured JPEG into a portrait. Blocking: call off the main thread.
     * @param strength 0 = soft, 1 = medium, 2 = strong.
     */
    fun process(jpeg: ByteArray, strength: Int, quality: Int): Result {
        val full = decode(jpeg)
        try {
            val mask = segment(full) ?: return Result(null, false)
            renderPortrait(full, mask, strength)
            val out = ByteArrayOutputStream(jpeg.size)
            full.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), out)
            return Result(out.toByteArray(), true)
        } finally {
            full.recycle()
        }
    }

    fun close() = segmenter.close()

    /** Person mask (0 = background, 1 = person) or null if nobody is in the photo. */
    private fun segment(full: Bitmap): Mask? {
        val scale = SEGMENT_SIDE.toFloat() / max(full.width, full.height)
        val small = Bitmap.createScaledBitmap(
            full, (full.width * scale).roundToInt().coerceAtLeast(1), (full.height * scale).roundToInt().coerceAtLeast(1), true,
        )
        val m = Tasks.await(segmenter.process(InputImage.fromBitmap(small, 0)), 15, TimeUnit.SECONDS)
        small.recycle()
        val buf = m.buffer
        buf.rewind()
        val values = FloatArray(m.width * m.height) { buf.float }
        // Firm up the soft model output so edges are crisp but not jagged.
        var person = 0
        for (i in values.indices) {
            values[i] = smoothstep(0.3f, 0.7f, values[i])
            if (values[i] > 0.5f) person++
        }
        val fraction = person.toFloat() / values.size
        Log.i(TAG, "person covers ${(fraction * 100).roundToInt()}% of the frame")
        if (fraction < MIN_PERSON_FRACTION) return null
        return Mask(values, m.width, m.height)
    }

    /** A low-resolution mask, sampled with bilinear interpolation. */
    class Mask(val values: FloatArray, val width: Int, val height: Int) {
        fun sample(fx: Float, fy: Float): Float {
            val x = (fx * width - 0.5f).coerceIn(0f, width - 1f)
            val y = (fy * height - 0.5f).coerceIn(0f, height - 1f)
            val x0 = x.toInt()
            val y0 = y.toInt()
            val x1 = min(x0 + 1, width - 1)
            val y1 = min(y0 + 1, height - 1)
            val tx = x - x0
            val ty = y - y0
            val a = values[y0 * width + x0] * (1 - tx) + values[y0 * width + x1] * tx
            val b = values[y1 * width + x0] * (1 - tx) + values[y1 * width + x1] * tx
            return a * (1 - ty) + b * ty
        }
    }

    companion object {
        private const val TAG = "Portrait"
        private const val MAX_SIDE = 3000
        private const val SEGMENT_SIDE = 512
        private const val BLUR_SCALE = 8
        private const val MIN_PERSON_FRACTION = 0.03f
        private val RADIUS = intArrayOf(3, 6, 10) // in 1/8-scale pixels: ~25 / 50 / 80 px at full size

        /**
         * Blurs the background of [full] (mutable) in place, keeping the masked person sharp.
         * The background blur is a "normalised" blur that ignores person pixels, so the person
         * doesn't leave a glowing halo on the background.
         */
        fun renderPortrait(full: Bitmap, mask: Mask, strength: Int) {
            val w = full.width
            val h = full.height
            val sw = max(1, w / BLUR_SCALE)
            val sh = max(1, h / BLUR_SCALE)
            val small = Bitmap.createScaledBitmap(full, sw, sh, true)
            val px = IntArray(sw * sh)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            small.recycle()

            val n = sw * sh
            val r = FloatArray(n)
            val g = FloatArray(n)
            val b = FloatArray(n)
            val wt = FloatArray(n)
            for (y in 0 until sh) {
                for (x in 0 until sw) {
                    val i = y * sw + x
                    val m = mask.sample((x + 0.5f) / sw, (y + 0.5f) / sh)
                    val weight = (1f - m) * (1f - m)
                    val p = px[i]
                    wt[i] = weight
                    r[i] = ((p shr 16) and 0xFF) * weight
                    g[i] = ((p shr 8) and 0xFF) * weight
                    b[i] = (p and 0xFF) * weight
                }
            }
            val radius = RADIUS[strength.coerceIn(0, 2)]
            for (ch in arrayOf(r, g, b, wt)) {
                repeat(3) { boxBlur(ch, sw, sh, radius) } // 3 box passes ≈ Gaussian
            }
            for (i in 0 until n) {
                val weight = wt[i]
                px[i] = if (weight > 1e-3f) {
                    0xFF shl 24 or
                        ((r[i] / weight).toInt().coerceIn(0, 255) shl 16) or
                        ((g[i] / weight).toInt().coerceIn(0, 255) shl 8) or
                        (b[i] / weight).toInt().coerceIn(0, 255)
                } else {
                    px[i]
                }
            }
            val blurredSmall = Bitmap.createBitmap(px, sw, sh, Bitmap.Config.ARGB_8888)
            val blurred = Bitmap.createScaledBitmap(blurredSmall, w, h, true)
            blurredSmall.recycle()

            // Composite row by row to keep memory low.
            val rowO = IntArray(w)
            val rowB = IntArray(w)
            for (y in 0 until h) {
                full.getPixels(rowO, 0, w, 0, y, w, 1)
                blurred.getPixels(rowB, 0, w, 0, y, w, 1)
                val fy = (y + 0.5f) / h
                for (x in 0 until w) {
                    val m = mask.sample((x + 0.5f) / w, fy)
                    if (m >= 0.999f) continue
                    val o = rowO[x]
                    val q = rowB[x]
                    val inv = 1f - m
                    val rr = (((o shr 16) and 0xFF) * m + ((q shr 16) and 0xFF) * inv).toInt()
                    val gg = (((o shr 8) and 0xFF) * m + ((q shr 8) and 0xFF) * inv).toInt()
                    val bb = ((o and 0xFF) * m + (q and 0xFF) * inv).toInt()
                    rowO[x] = 0xFF shl 24 or (rr shl 16) or (gg shl 8) or bb
                }
                full.setPixels(rowO, 0, w, 0, y, w, 1)
            }
            blurred.recycle()
        }

        /** In-place box blur (horizontal then vertical), O(n) per pass. */
        private fun boxBlur(a: FloatArray, w: Int, h: Int, radius: Int) {
            val tmp = FloatArray(max(w, h))
            val norm = 1f / (2 * radius + 1)
            for (y in 0 until h) {
                val o = y * w
                var sum = 0f
                for (k in -radius..radius) sum += a[o + k.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    tmp[x] = sum * norm
                    sum += a[o + min(x + radius + 1, w - 1)] - a[o + max(x - radius, 0)]
                }
                System.arraycopy(tmp, 0, a, o, w)
            }
            for (x in 0 until w) {
                var sum = 0f
                for (k in -radius..radius) sum += a[k.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    tmp[y] = sum * norm
                    sum += a[min(y + radius + 1, h - 1) * w + x] - a[max(y - radius, 0) * w + x]
                }
                for (y in 0 until h) a[y * w + x] = tmp[y]
            }
        }

        private fun smoothstep(e0: Float, e1: Float, v: Float): Float {
            val t = ((v - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }

        /** Decodes with EXIF rotation applied (so the result is upright), capped at ~3000 px. */
        fun decode(jpeg: ByteArray): Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(jpeg))) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                val longest = max(info.size.width, info.size.height)
                if (longest > MAX_SIDE) {
                    val s = MAX_SIDE.toFloat() / longest
                    decoder.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
                }
            }
    }
}
