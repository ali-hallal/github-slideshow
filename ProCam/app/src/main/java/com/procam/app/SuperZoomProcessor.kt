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

    /** [before] = the reference frame's plain crop (what ordinary digital zoom gives), for review. */
    class Result(val jpeg: ByteArray, val merged: Int, val width: Int, val height: Int, val before: ByteArray? = null)

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
            "cores ${Runtime.getRuntime().availableProcessors()}")
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
    fun enhance(frames: List<ByteArray>, quality: Int): Result {
        require(frames.isNotEmpty())
        val t0 = android.os.SystemClock.uptimeMillis()
        val rotation = exifRotation(frames.first())
        val first = BitmapRegionDecoder.newInstance(frames[0], 0, frames[0].size, false)!!
        val fullW = first.width
        val fullH = first.height
        first.recycle()
        // 50 MP photos (or a phone short of memory): work at half size, never fail.
        val sample = if (fullW.toLong() * fullH > MAX_ENHANCE_PIXELS ||
            fullW.toLong() * fullH * 12 > Budget.available() * 0.7) 2 else 1
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample
        }
        // 1. Align on the centre third.
        val centres = frames.map { bytes ->
            try {
                val d = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)!!
                val rw = d.width / 3
                val rh = d.height / 3
                val bmp = d.decodeRegion(Rect((d.width - rw) / 2, (d.height - rh) / 2, (d.width + rw) / 2, (d.height + rh) / 2), opts)
                d.recycle()
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                SuperRes.Frame(px, bmp.width, bmp.height).also { bmp.recycle() }
            } catch (e: Exception) {
                null
            }
        }
        val firstOk = centres.firstNotNullOfOrNull { it } ?: throw IllegalStateException("could not decode any frame")
        val usable = centres.indices.filter { centres[it]?.let { c -> c.w == firstOk.w && c.h == firstOk.h } == true }
        val order = SuperRes.sharpest(usable.map { centres[it]!! }, KEEP_FRAMES).map { usable[it] }
        val all = SuperRes.offsets(order.map { centres[it]!! }, GRAY_SCALE)
        val typical = all.drop(1).map { it.cost }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        val good = all.indices.filter { k -> k == 0 || all[k].cost <= max(typical * 3, 4.0) }
        val chosen = good.map { order[it] }
        val global = good.map { all[it].dx to all[it].dy }
        val iterations = if (Runtime.getRuntime().availableProcessors() >= 8) Deconvolve.ITERATIONS else Deconvolve.ITERATIONS / 2
        Log.i(TAG, "enhance: kept ${chosen.size} of ${frames.size}, sample $sample, offsets ${global.map { "%.2f,%.2f".format(it.first, it.second) }}")

        // 2. Merge tile by tile (each tile re-aligned locally), feathering the overlaps.
        val decoders = chosen.map { BitmapRegionDecoder.newInstance(frames[it], 0, frames[it].size, false)!! }
        try {
            val fw = fullW / sample
            val fh = fullH / sample
            val out = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
            val reach = global.maxOf { max(kotlin.math.abs(it.first), kotlin.math.abs(it.second)) }.roundToInt() + LOCAL_REACH
            val margin = 16 + reach
            var localTiles = 0
            var tiles = 0
            var ty = 0
            while (ty < fh) {
                val th = min(TILE, fh - ty)
                var tx = 0
                while (tx < fw) {
                    val tw = min(TILE, fw - tx)
                    // This tile's pixels plus an overlap to the right and below (blended by the next tiles).
                    val ow = min(tw + OVERLAP, fw - tx)
                    val oh = min(th + OVERLAP, fh - ty)
                    val region = Rect(max(0, tx - margin), max(0, ty - margin), min(fw, tx + ow + margin), min(fh, ty + oh + margin))
                    val full = Rect(region.left * sample, region.top * sample, region.right * sample, region.bottom * sample)
                    val parts = decoders.map { d ->
                        val bmp = d.decodeRegion(full, opts)
                        val px = IntArray(bmp.width * bmp.height)
                        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                        SuperRes.Frame(px, bmp.width, bmp.height).also { bmp.recycle() }
                    }
                    val rw = parts[0].w
                    val rh = parts[0].h
                    // Local alignment: a slight tilt shifts each region a little differently.
                    val prepared = SuperRes.prepare(parts[0], GRAY_SCALE)
                    val offsets = global.mapIndexed { k, g ->
                        if (k == 0) g
                        else {
                            val local = SuperRes.refineNear(prepared, parts[k], g.first, g.second)
                            if (local.cost >= 0 && kotlin.math.abs(local.dx - g.first) <= LOCAL_REACH &&
                                kotlin.math.abs(local.dy - g.second) <= LOCAL_REACH
                            ) {
                                localTiles++
                                local.dx to local.dy
                            } else g
                        }
                    }
                    tiles++
                    val motion = IntArray(parts.size) { k ->
                        val (dx, dy) = offsets[k]
                        if (k == 0) 255 else NightProcessor.motionThreshold(parts[0].px, parts[k].px, rw, rh, dx.roundToInt(), dy.roundToInt())
                    }
                    val merged = SuperRes.merge(parts, offsets, motion, 1f)
                    Deconvolve.sharpen(merged, iterations = iterations)
                    blendInto(out, merged, region, tx, ty, ow, oh)
                    tx += TILE
                }
                ty += TILE
            }
            var upright = out
            if (rotation != 0) {
                upright = Bitmap.createBitmap(out, 0, 0, fw, fh, Matrix().apply { postRotate(rotation.toFloat()) }, true)
                out.recycle()
            }
            val bytes = ByteArrayOutputStream(frames[chosen[0]].size)
            upright.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), bytes)
            val result = Result(bytes.toByteArray(), chosen.size, upright.width, upright.height, before = frames[chosen[0]])
            upright.recycle()
            Log.i(TAG, "enhance: ${result.width}x${result.height} from ${chosen.size} frames, locally aligned " +
                "$localTiles of ${tiles * (chosen.size - 1)} tile offsets, in ${android.os.SystemClock.uptimeMillis() - t0} ms")
            return result
        } finally {
            decoders.forEach { it.recycle() }
        }
    }

    /**
     * Writes the tile's part ([tx], [ty], [w] × [h], in output coordinates) of [merged] (whose top
     * left is [region]'s) into [out]. Where an earlier tile already wrote (the overlap to the left
     * or above), the two are cross-faded, so tiles aligned slightly differently join invisibly.
     */
    private fun blendInto(out: Bitmap, merged: SuperRes.Frame, region: Rect, tx: Int, ty: Int, w: Int, h: Int) {
        val mine = IntArray(w * h)
        // (At half size a decoded region can be a pixel short: repeat the last row/column.)
        val x0 = tx - region.left
        for (y in 0 until h) {
            val sy = (ty + y - region.top).coerceIn(0, merged.h - 1)
            for (x in 0 until w) mine[y * w + x] = merged.px[sy * merged.w + (x0 + x).coerceIn(0, merged.w - 1)]
        }
        if (tx > 0 || ty > 0) {
            val old = IntArray(w * h)
            out.getPixels(old, 0, w, tx, ty, w, h)
            for (y in 0 until h) for (x in 0 until w) {
                val wx = if (tx > 0) ((x + 0.5f) / OVERLAP).coerceIn(0f, 1f) else 1f
                val wy = if (ty > 0) ((y + 0.5f) / OVERLAP).coerceIn(0f, 1f) else 1f
                val a = wx * wy // weight of this tile
                if (a >= 1f) continue
                val i = y * w + x
                val p = mine[i]
                val q = old[i]
                val r = (((p shr 16) and 0xFF) * a + ((q shr 16) and 0xFF) * (1 - a)).roundToInt()
                val g = (((p shr 8) and 0xFF) * a + ((q shr 8) and 0xFF) * (1 - a)).roundToInt()
                val b = ((p and 0xFF) * a + (q and 0xFF) * (1 - a)).roundToInt()
                mine[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        out.setPixels(mine, 0, w, tx, ty, w, h)
    }

    companion object {
        private const val TAG = "SuperZoom"
        /** Zoom enhance works on tiles this size (plus margins), to fit in memory. */
        private const val TILE = 1024
        /** Neighbouring tiles overlap by this much and are cross-faded there. */
        private const val OVERLAP = 48
        /** How far (pixels) a tile's local alignment may differ from the whole picture's. */
        private const val LOCAL_REACH = 2
        /** Above this, zoom enhance works at half size (50 MP photos). */
        private const val MAX_ENHANCE_PIXELS = 16_000_000L
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
