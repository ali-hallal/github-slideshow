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
import kotlin.math.ln
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

    /** [before] = the reference frame's plain crop (what ordinary digital zoom gives), for review. */
    class Result(val jpeg: ByteArray, val merged: Int, val width: Int, val height: Int, val before: ByteArray? = null)

    /**
     * @param crop how much further than the camera's own zoom (e.g. 3 = the centre third).
     * Blocking: call off the main thread.
     */
    fun process(frames: List<ByteArray>, crop: Float, quality: Int, ai: AiUpscaler? = null, maxScale: Float = MAX_SCALE, iso: Int? = null): Result {
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
        return processFrames(crops, rotation, quality, frames.size, ai, maxScale, iso)
    }

    /**
     * The merge itself, from frames already cropped and converted to pixels (fast bursts arrive
     * like this). [rotation] = clockwise degrees to make the result upright. [iso] = the
     * sensitivity the burst was shot at: the grainier the shots, the more colour speckle is
     * smoothed away and the less the grain is sharpened.
     */
    fun processFrames(crops: List<SuperRes.Frame>, rotation: Int, quality: Int, taken: Int = crops.size, ai: AiUpscaler? = null, maxScale: Float = MAX_SCALE, iso: Int? = null): Result {
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
        Log.i(TAG, "kept ${frames.size} of $taken frames; offsets ${all.map { "%.2f,%.2f (%.0f)".format(it.dx, it.dy, it.cost) }}")
        val merged: SuperRes.Frame
        val scale: Float
        // AI detail: merge at the frames' own size (clean, no noise), then the network enlarges
        // it — unless this phone turns out too slow for that (a photo mustn't take a minute).
        val aiScale = (AI_MAX_SIDE.toFloat() / max(w0, h0)).coerceIn(1f, 4f)
        val byAi = if (ai == null || AiUpscaler.tooSlow) null else
            ai.upscaleWithin(KernelMerge.merge(frames, offsets, 1f), (w0 * aiScale).roundToInt(), (h0 * aiScale).roundToInt(), AiUpscaler.budgetMs)
        if (byAi != null) {
            scale = aiScale
            merged = byAi
        } else {
            // Onto a finer grid (2-3x, aiming for ~3000 px): patch-by-patch alignment and
            // edge-shaped kernels (KernelMerge), then sharpen.
            scale = (MAX_SIDE.toFloat() / max(w0, h0)).coerceIn(1f, maxScale)
            merged = KernelMerge.merge(frames, offsets, scale)
        }
        val t3 = android.os.SystemClock.uptimeMillis()
        // Grain: 0 at ISO 400 and below, +1 for every doubling.
        val grain = if (iso == null) 0f else (ln(iso / 400f) / ln(2f)).coerceIn(0f, 5f)
        if (grain > 0f) SuperRes.smoothChroma(merged, (1 + grain).roundToInt())
        if (byAi == null) SuperRes.sharpen(merged, amount = (1.2f - 0.15f * grain).coerceAtLeast(0.5f), noiseFloor = 2f + 2f * grain)
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
        // "Before": the reference crop on its own, upright, for the review screen.
        val before = try {
            var b = Bitmap.createBitmap(frames[0].px, w0, h0, Bitmap.Config.ARGB_8888)
            if (rotation != 0) {
                val r = Bitmap.createBitmap(b, 0, 0, w0, h0, Matrix().apply { postRotate(rotation.toFloat()) }, true)
                b.recycle()
                b = r
            }
            ByteArrayOutputStream().also { o -> b.compress(Bitmap.CompressFormat.JPEG, 90, o); b.recycle() }.toByteArray()
        } catch (e: Throwable) {
            null
        }
        val result = Result(bytes.toByteArray(), frames.size, out.width, out.height, before)
        out.recycle()
        val t5 = android.os.SystemClock.uptimeMillis()
        Log.i(TAG, "super zoom: ${result.width}x${result.height} from ${frames.size} of $taken frames ${w0}x$h0 (x$scale); " +
            "timing ms: lucky ${t1 - t0}, align ${t2 - t1}, merge ${t3 - t2}, sharpen ${t4 - t3}, encode ${t5 - t4}, " +
            "cores ${Runtime.getRuntime().availableProcessors()}; merge stages: ${KernelMerge.lastTimings}")
        return result
    }

    /**
     * Zoom enhance (like the Pixel's Super Res Zoom) for zoomed photos within the camera's range:
     * the burst is already zoomed by the camera, so the frames are merged at their full size. Hand
     * shake offsets every frame by a fraction of a pixel, so merging them recovers detail that one
     * enlarged frame doesn't have, and removes noise.
     *
     * Full 12 MP frames x 6 don't fit in an app's memory at once, so the shots are aligned once
     * (on the centre), then merged tile by tile straight from the JPEGs and stitched together.
     */
    fun enhance(frames: List<ByteArray>, quality: Int, roi: android.graphics.RectF? = null): Result {
        require(frames.isNotEmpty())
        val rotation = exifRotation(frames.first())
        val decoded = frames.indices.mapNotNull { i -> try { i to TileMerge.JpegSource(frames[i]) } catch (e: Exception) { null } }
        require(decoded.isNotEmpty()) { "could not decode any frame" }
        val sources = decoded.map { it.second }
        try {
            val fullW = sources[0].width
            val fullH = sources[0].height
            // 50 MP photos (or a phone short of memory): work at half size, never fail.
            val sample = if (fullW.toLong() * fullH > MAX_ENHANCE_PIXELS ||
                fullW.toLong() * fullH * 12 > Budget.available() * 0.7) 2 else 1
            val iterations = if (Runtime.getRuntime().availableProcessors() >= 8) Deconvolve.ITERATIONS else Deconvolve.ITERATIONS / 2
            val merged = TileMerge.merge(sources, roi, KEEP_FRAMES, iterations, sample)
            var upright = merged.bitmap
            if (rotation != 0) {
                upright = Bitmap.createBitmap(merged.bitmap, 0, 0, merged.bitmap.width, merged.bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
                merged.bitmap.recycle()
            }
            val reference = frames[decoded[merged.reference].first]
            val bytes = ByteArrayOutputStream(reference.size)
            upright.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), bytes)
            val result = Result(bytes.toByteArray(), merged.used, upright.width, upright.height, before = reference)
            upright.recycle()
            return result
        } finally {
            sources.forEach { it.close() }
        }
    }

    companion object {
        private const val TAG = "SuperZoom"
        /** Above this, zoom enhance works at half size (50 MP photos). */
        private const val MAX_ENHANCE_PIXELS = 16_000_000L
        private const val MAX_SIDE = 3000
        /** With AI detail: up to 4× the crop, about 12 MP. */
        private const val AI_MAX_SIDE = 4000
        /** Enlargement measured to recover real detail from ~6 frames; more would only add pixels. */
        const val MAX_SCALE = 3f
        /** Frames merged out of the burst (the sharpest ones). */
        const val KEEP_FRAMES = 6
        private const val GRAY_SCALE = 2
        /** A super-zoom burst raises the sensitivity at most this many times over what auto exposure chose ... */
        private const val MAX_ISO_BOOST = 4
        /** ... and never past this (unless auto exposure itself is already there). */
        const val MAX_PLAN_ISO = 3200

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
            // Sensitivity can't be raised without limit: past a couple of stops the grain costs
            // more detail than the faster shutter saves (and the merge only removes so much).
            val most = max(aeIso, minOf(isoRange.upper, aeIso * MAX_ISO_BOOST, MAX_PLAN_ISO))
            if (iso > most) {
                iso = most
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
