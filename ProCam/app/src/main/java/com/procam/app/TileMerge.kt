package com.procam.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-size multi-frame merge, tile by tile (frames too big to hold at once), locked onto the
 * subject:
 *
 * 1. The subject region ([roi]: a face or the tapped point; else the centre) is decoded from every
 *    frame; the frames sharpest THERE are kept, and aligned to sub-pixel precision ON THE SUBJECT.
 * 2. Each tile is re-aligned locally around that (dense alignment: the background, a swaying
 *    person, a slight tilt each line up on their own), merged (noise and motion handled per
 *    sample), deconvolution-sharpened, and cross-faded into its neighbours.
 *
 * Works with any [Source] of frames: JPEGs ([JpegSource]) or raw YUV frames from the camera.
 */
object TileMerge {

    /** A frame that can hand out regions of itself (sensor orientation). */
    interface Source {
        val width: Int
        val height: Int
        /** Pixels of [r] (full-size coordinates), shrunk by [sample]. */
        fun region(r: Rect, sample: Int): SuperRes.Frame
        fun close() {}
    }

    class JpegSource(bytes: ByteArray) : Source {
        private val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)!!
        override val width get() = decoder.width
        override val height get() = decoder.height
        override fun region(r: Rect, sample: Int): SuperRes.Frame {
            val bmp = decoder.decodeRegion(r, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sample
            })
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            return SuperRes.Frame(px, bmp.width, bmp.height).also { bmp.recycle() }
        }
        override fun close() = decoder.recycle()
    }

    /** A raw YUV frame kept by the camera (steadiest moment), converted region by region. */
    /**
     * A raw YUV frame kept by the camera (steadiest moment), converted region by region.
     * [rowShift] (dx, dy per sensor row, interleaved): rolling-shutter correction — where each
     * row's content really is, read from there instead.
     */
    class YuvSource(private val f: MomentBuffer.Frame, private val rowShift: FloatArray? = null) : Source {
        override val width get() = f.width and 1.inv()
        override val height get() = f.height and 1.inv()
        override fun region(r: Rect, sample: Int): SuperRes.Frame {
            val w = r.width() / sample
            val h = r.height() / sample
            val out = IntArray(w * h)
            for (yy in 0 until h) {
                val row = (r.top + yy * sample).coerceIn(0, height - 1)
                val sx = rowShift?.let { Math.round(it[2 * row]) } ?: 0
                val y = rowShift?.let { (row + Math.round(it[2 * row + 1])).coerceIn(0, height - 1) } ?: row
                val yBase = y * f.yRow
                val uvBase = (y / 2) * f.uvRow
                for (xx in 0 until w) {
                    val x = (r.left + xx * sample + sx).coerceIn(0, width - 1)
                    val l = f.y[yBase + x * f.yPix].toInt() and 0xFF
                    val uvi = uvBase + (x / 2) * f.uvPix
                    val u = (f.u[uvi].toInt() and 0xFF) - 128
                    val v = (f.v[uvi].toInt() and 0xFF) - 128
                    val rr = (l + ((91881 * v) shr 16)).coerceIn(0, 255)
                    val gg = (l - ((22554 * u + 46802 * v) shr 16)).coerceIn(0, 255)
                    val bb = (l + ((116130 * u) shr 16)).coerceIn(0, 255)
                    out[yy * w + xx] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
            return SuperRes.Frame(out, w, h)
        }
    }

    class Result(val bitmap: Bitmap, val used: Int, val reference: Int)

    /**
     * How well [sources] line up on the subject (mean alignment cost against the first; lower
     * is better) — for choosing between corrections of the same frames.
     */
    fun alignmentCost(sources: List<Source>, roi: RectF?, sample: Int = 2): Double {
        val fullW = sources[0].width
        val fullH = sources[0].height
        val rw = max(64, fullW / 3)
        val rh = max(64, fullH / 3)
        val cx = ((roi?.centerX() ?: 0.5f) * fullW).roundToInt().coerceIn(rw / 2, fullW - rw / 2)
        val cy = ((roi?.centerY() ?: 0.5f) * fullH).roundToInt().coerceIn(rh / 2, fullH - rh / 2)
        val area = Rect(cx - rw / 2, cy - rh / 2, cx + rw / 2, cy + rh / 2)
        val parts = sources.map { it.region(area, sample) }
        val offsets = SuperRes.offsets(parts, GRAY_SCALE, null)
        return offsets.drop(1).map { it.cost }.average()
    }

    /**
     * Merges [sources] (same size). [roi] = subject in 0..1 coordinates of the frames, or null.
     * [keep] = at most this many frames merged. Returns the merged picture (sensor orientation)
     * and which source was the reference.
     */
    fun merge(sources: List<Source>, roi: RectF?, keep: Int, iterations: Int, sample: Int = 1): Result {
        require(sources.isNotEmpty())
        val t0 = android.os.SystemClock.uptimeMillis()
        val fullW = sources[0].width
        val fullH = sources[0].height
        // 1. The subject region (or the centre third) of every frame.
        val rw = max(64, fullW / 3)
        val rh = max(64, fullH / 3)
        val cx = ((roi?.centerX() ?: 0.5f) * fullW).roundToInt().coerceIn(rw / 2, fullW - rw / 2)
        val cy = ((roi?.centerY() ?: 0.5f) * fullH).roundToInt().coerceIn(rh / 2, fullH - rh / 2)
        val area = Rect(cx - rw / 2, cy - rh / 2, cx + rw / 2, cy + rh / 2)
        val centres = sources.map { s -> try { s.region(area, sample) } catch (e: Exception) { null } }
        val firstOk = centres.firstNotNullOfOrNull { it } ?: throw IllegalStateException("could not decode any frame")
        val usable = centres.indices.filter { centres[it]?.let { c -> c.w == firstOk.w && c.h == firstOk.h } == true }
        // The subject inside that region (in the region's sampled pixels).
        val window = roi?.let {
            SuperRes.Window(
                ((it.left * fullW - area.left) / sample).roundToInt(), ((it.top * fullH - area.top) / sample).roundToInt(),
                (it.width() * fullW / sample).roundToInt(), (it.height() * fullH / sample).roundToInt(),
            )
        }?.let { SuperRes.clampWindow(it, firstOk.w, firstOk.h) }
        val order = SuperRes.sharpest(usable.map { centres[it]!! }, keep, window).map { usable[it] }
        val all = SuperRes.offsets(order.map { centres[it]!! }, GRAY_SCALE, window)
        val typical = all.drop(1).map { it.cost }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        val good = all.indices.filter { k -> k == 0 || all[k].cost <= max(typical * 3, 4.0) }
        val chosen = good.map { order[it] }
        val global = good.map { all[it].dx to all[it].dy }
        Log.i(TAG, "tile merge: kept ${chosen.size} of ${sources.size}, sample $sample, subject ${roi ?: "centre"}, " +
            "offsets ${global.map { "%.2f,%.2f".format(it.first, it.second) }}")

        // 2. Tile by tile, each re-aligned locally, feathered into its neighbours.
        val fw = fullW / sample
        val fh = fullH / sample
        val out = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
        val reach = global.maxOf { max(abs(it.first), abs(it.second)) }.roundToInt() + LOCAL_REACH
        val margin = 16 + reach
        var localTiles = 0
        var tiles = 0
        var ty = 0
        while (ty < fh) {
            val th = min(TILE, fh - ty)
            var tx = 0
            while (tx < fw) {
                val tw = min(TILE, fw - tx)
                val ow = min(tw + OVERLAP, fw - tx)
                val oh = min(th + OVERLAP, fh - ty)
                val region = Rect(max(0, tx - margin), max(0, ty - margin), min(fw, tx + ow + margin), min(fh, ty + oh + margin))
                val full = Rect(region.left * sample, region.top * sample, region.right * sample, region.bottom * sample)
                val parts = chosen.map { sources[it].region(full, sample) }
                val prepared = SuperRes.prepare(parts[0], GRAY_SCALE)
                val offsets = global.mapIndexed { k, g ->
                    if (k == 0) g
                    else {
                        val local = SuperRes.refineNear(prepared, parts[k], g.first, g.second)
                        if (local.cost >= 0 && abs(local.dx - g.first) <= LOCAL_REACH && abs(local.dy - g.second) <= LOCAL_REACH) {
                            localTiles++
                            local.dx to local.dy
                        } else g
                    }
                }
                tiles++
                // Patch-by-patch alignment within the tile, edge-shaped kernels, ghost rejection.
                val merged = KernelMerge.merge(parts, offsets, 1f, KernelMerge.SAME_SIZE)
                Deconvolve.sharpen(merged, iterations = iterations)
                blendInto(out, merged, region, tx, ty, ow, oh)
                tx += TILE
            }
            ty += TILE
        }
        Log.i(TAG, "tile merge: ${fw}x$fh from ${chosen.size} frames, locally aligned $localTiles of " +
            "${tiles * (chosen.size - 1)} tile offsets, in ${android.os.SystemClock.uptimeMillis() - t0} ms")
        return Result(out, chosen.size, chosen[0])
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
                val a = wx * wy
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

    private const val TAG = "TileMerge"
    private const val GRAY_SCALE = 2
    private const val TILE = 1024
    private const val OVERLAP = 48
    private const val LOCAL_REACH = 2
}
