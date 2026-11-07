package com.procam.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CaptureResult
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Zoom from a RAW burst: aligns the frames on their green samples, merges the raw samples
 * ([RawMerge]), develops them (lens shading, white balance, colour matrix) and then matches the
 * phone's own JPEG for brightness, contrast and colour — so the photo looks like the phone's,
 * with the detail and low noise of many raw frames. Tiles are merged in parallel.
 */
class RawZoomProcessor {

    /** [roi]: the subject (0..1 of the zoomed view, sensor orientation) to align on, or null. */
    fun process(capture: RawBurstCapture, jpegs: List<ByteArray>, rotation: Int, quality: Int, roi: android.graphics.RectF? = null): SuperZoomProcessor.Result {
        val t0 = android.os.SystemClock.uptimeMillis()
        val result = capture.result
        val wbVec = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val wb = if (wbVec != null) floatArrayOf(wbVec.red, (wbVec.greenEven + wbVec.greenOdd) / 2, wbVec.blue) else floatArrayOf(2f, 1f, 1.6f)
        val cst = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        val ccm = FloatArray(9) { i -> cst?.getElement(i % 3, i / 3)?.toFloat() ?: if (i % 4 == 0) 1f else 0f }
        val noise = result.get(CaptureResult.SENSOR_NOISE_PROFILE)?.let { pairs ->
            DoubleArray(pairs.size * 2) { i -> if (i % 2 == 0) pairs[i / 2].first else pairs[i / 2].second }
        }
        val shading = shadingOf(capture)
        val prelim = RawMerge.Burst(capture.frames, capture.w, capture.h, capture.cfa, List(capture.frames.size) { 0f to 0f },
            capture.black, capture.white, wb, ccm, shading, noise)

        // 1. Sharpest frames and their sub-pixel offsets, from green-only previews.
        val previews = capture.frames.map { RawMerge.greenPreview(prelim, it) }
        val window = roi?.let { r ->
            val pw = previews[0].w
            val ph = previews[0].h
            SuperRes.clampWindow(SuperRes.Window((r.left * pw).roundToInt(), (r.top * ph).roundToInt(),
                (r.width() * pw).roundToInt(), (r.height() * ph).roundToInt()), pw, ph)
        }
        val order = SuperRes.sharpest(previews, KEEP, window)
        val found = SuperRes.offsets(order.map { previews[it] }, 1, window)
        val typical = found.drop(1).map { it.cost }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        val good = found.indices.filter { k -> k == 0 || found[k].cost <= max(typical * 3, 4.0) }
        val frames = good.map { capture.frames[order[it]] }
        val offsets = good.map { (found[it].dx * 2) to (found[it].dy * 2) } // half-size previews → raw pixels
        val burst = RawMerge.Burst(frames, capture.w, capture.h, capture.cfa, offsets, capture.black, capture.white, wb, ccm, shading, noise)
        val refIndex = order[good[0]]

        // 2. Output size: up to 2× the raw crop where the zoom crops a lot, never above the full photo.
        val crop = capture.fullW.toFloat() / capture.w
        var scale = if (crop >= 1.3f) min(2f, crop) else 1f
        scale = min(scale, min(capture.fullW.toFloat() / capture.w, capture.fullH.toFloat() / capture.h).coerceAtLeast(1f))
        val ow = (capture.w * scale).roundToInt()
        val oh = (capture.h * scale).roundToInt()

        // 3. Look of the phone's JPEG (tone and colour), from the reference shot.
        val jpeg = jpegs.getOrNull(refIndex) ?: jpegs.first()
        val (tone, colour) = fitLook(burst, jpeg, rotation)

        // 4. Tiles in parallel.
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val tiles = ArrayList<IntArray>()
        for (y in 0 until oh step TILE) for (x in 0 until ow step TILE) tiles += intArrayOf(x, y, min(ow, x + TILE), min(oh, y + TILE))
        val pool = Executors.newFixedThreadPool(SuperRes.threads())
        try {
            tiles.map { t ->
                pool.submit {
                    val m = MARGIN
                    val x0 = max(0, t[0] - m)
                    val y0 = max(0, t[1] - m)
                    val x1 = min(ow, t[2] + m)
                    val y1 = min(oh, t[3] + m)
                    val rgb = RawMerge.mergeRegion(burst, scale, x0, y0, x1, y1)
                    val px = RawMerge.toPixels(rgb, tone, colour)
                    val f = SuperRes.Frame(px, x1 - x0, y1 - y0)
                    Deconvolve.sharpen(f, sigma = 0.8f, iterations = SHARPEN_ROUNDS)
                    val tw = t[2] - t[0]
                    val th = t[3] - t[1]
                    val inner = IntArray(tw * th)
                    for (yy in 0 until th) System.arraycopy(f.px, (yy + t[1] - y0) * f.w + (t[0] - x0), inner, yy * tw, tw)
                    synchronized(out) { out.setPixels(inner, 0, tw, t[0], t[1], tw, th) }
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        var upright = out
        if (rotation != 0) {
            upright = Bitmap.createBitmap(out, 0, 0, ow, oh, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            out.recycle()
        }
        val bytes = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), bytes)
        val res = SuperZoomProcessor.Result(bytes.toByteArray(), frames.size, upright.width, upright.height, before = jpeg)
        upright.recycle()
        lastSucceededAt = System.currentTimeMillis()
        Log.i(TAG, "raw zoom: ${res.width}x${res.height} (x$scale) from ${frames.size} of ${capture.frames.size} raw frames " +
            "${capture.w}x${capture.h}, offsets ${offsets.map { "%.2f,%.2f".format(it.first, it.second) }}, " +
            "shading ${shading != null}, ${android.os.SystemClock.uptimeMillis() - t0} ms")
        return res
    }

    /** Lens shading gains (corner darkening of RAW) as a function of (channel, crop x, crop y), or null. */
    private fun shadingOf(c: RawBurstCapture): ((Int, Float, Float) -> Float)? {
        val map = c.result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) ?: return null
        val cols = map.columnCount
        val rows = map.rowCount
        if (cols < 2 || rows < 2) return null
        // channel 0 R, 1 G (average of both greens), 2 B → map channels R, Geven, Godd, B
        val grid = Array(3) { ch ->
            FloatArray(cols * rows) { i ->
                val col = i % cols
                val row = i / cols
                when (ch) {
                    0 -> map.getGainFactor(android.hardware.camera2.params.RggbChannelVector.RED, col, row)
                    1 -> (map.getGainFactor(android.hardware.camera2.params.RggbChannelVector.GREEN_EVEN, col, row) +
                        map.getGainFactor(android.hardware.camera2.params.RggbChannelVector.GREEN_ODD, col, row)) / 2
                    else -> map.getGainFactor(android.hardware.camera2.params.RggbChannelVector.BLUE, col, row)
                }
            }
        }
        val sx = (cols - 1f) / max(1, c.fullW - 1)
        val sy = (rows - 1f) / max(1, c.fullH - 1)
        return { ch, x, y ->
            val u = ((c.cropLeft + x) * sx).coerceIn(0f, cols - 1f)
            val v = ((c.cropTop + y) * sy).coerceIn(0f, rows - 1f)
            val u0 = u.toInt().coerceAtMost(cols - 2)
            val v0 = v.toInt().coerceAtMost(rows - 2)
            val fu = u - u0
            val fv = v - v0
            val g = grid[ch]
            (g[v0 * cols + u0] * (1 - fu) + g[v0 * cols + u0 + 1] * fu) * (1 - fv) +
                (g[(v0 + 1) * cols + u0] * (1 - fu) + g[(v0 + 1) * cols + u0 + 1] * fu) * fv
        }
    }

    /**
     * Brightness curve and colour matrix that make our developed reference look like the
     * camera's JPEG of the same shot, from block averages over the picture.
     */
    private fun fitLook(b: RawMerge.Burst, jpeg: ByteArray, rotation: Int): Pair<RawMerge.ToneFit, FloatArray> {
        // Our side: block averages of the developed reference at 1/16 size.
        val bs = 16
        val bw = b.w / bs
        val bh = b.h / bs
        val rgb = RawMerge.developBlocks(b, bs)
        // Theirs: the JPEG at the same size, in sensor orientation.
        var bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = 8 })
        val landscapeRaw = b.w >= b.h
        if ((bmp.width >= bmp.height) != landscapeRaw && rotation != 0) {
            // The camera turned the JPEG's pixels upright: turn them back.
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(-rotation.toFloat()) }, true)
            bmp.recycle()
            bmp = r
        }
        val small = Bitmap.createScaledBitmap(bmp, bw, bh, true)
        bmp.recycle()
        val jp = IntArray(bw * bh)
        small.getPixels(jp, 0, bw, 0, 0, bw, bh)
        small.recycle()
        val lin = ArrayList<Float>()
        val bright = ArrayList<Float>()
        for (i in 0 until bw * bh) {
            val p = jp[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val bl = p and 0xFF
            if (max(r, max(g, bl)) >= 250) continue // clipped in the JPEG: no information
            lin += 0.2126f * rgb[i * 3] + 0.7152f * rgb[i * 3 + 1] + 0.0722f * rgb[i * 3 + 2]
            bright += 0.2126f * r + 0.7152f * g + 0.0722f * bl
        }
        if (lin.size < 16) return RawMerge.ToneFit(floatArrayOf(0.001f, 0.18f, 1f), floatArrayOf(10f, 118f, 255f)) to
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val tone = RawMerge.ToneFit(lin.toFloatArray(), bright.toFloatArray())
        // Colour: our toned linear RGB vs the JPEG's linear RGB, block by block.
        val ours = ArrayList<Float>()
        val theirs = ArrayList<Float>()
        for (i in 0 until bw * bh) {
            val p = jp[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val bl = p and 0xFF
            if (max(r, max(g, bl)) >= 250 || max(r, max(g, bl)) < 8) continue
            val y = max(1e-5f, 0.2126f * rgb[i * 3] + 0.7152f * rgb[i * 3 + 1] + 0.0722f * rgb[i * 3 + 2])
            val gain = RawMerge.srgbToLinear(tone.apply(y)) / y
            ours += rgb[i * 3] * gain
            ours += rgb[i * 3 + 1] * gain
            ours += rgb[i * 3 + 2] * gain
            theirs += RawMerge.srgbToLinear(r.toFloat())
            theirs += RawMerge.srgbToLinear(g.toFloat())
            theirs += RawMerge.srgbToLinear(bl.toFloat())
        }
        val colour = RawMerge.fitColour(ours.toFloatArray(), theirs.toFloatArray())
        Log.i(TAG, "raw zoom look: ${lin.size} blocks, colour ${colour.joinToString { "%.2f".format(it) }}")
        return tone to colour
    }

    companion object {
        /** When a RAW zoom photo was last made (tests check the RAW path really ran). */
        @Volatile var lastSucceededAt = 0L
        private const val TAG = "RawZoom"
        private const val KEEP = 6
        private const val TILE = 512
        private const val MARGIN = 12
        private const val SHARPEN_ROUNDS = 6
    }
}
