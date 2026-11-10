package com.procam.app

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * AI detail for super zoom: a small on-device super-resolution network enlarges a picture 4×,
 * restoring edges and texture that plain enlargement can't.
 *
 * The model is the compact Real-ESRGAN (BSD-3, assets/LICENSE-Real-ESRGAN.txt) edited for phones:
 * its "general" and "weak denoise" weights blended 50/50 (natural, not painted), 25 % of the
 * channels pruned with least-squares re-fitting (1.75× less work), and 8-bit weights
 * (0.76 MB). It works on 64×64 pieces; overlapping pieces are stitched without seams, and each is
 * scaled straight to the final size so a big enlargement never needs a huge picture in memory.
 */
class AiUpscaler(context: Context) : Closeable {

    private val interpreter: Interpreter

    init {
        val model = loadModel(context, MODEL)
        val options = Interpreter.Options().setNumThreads(min(4, Runtime.getRuntime().availableProcessors()))
        interpreter = Interpreter(model, options)
    }

    private val input = ByteBuffer.allocateDirect(TILE * TILE * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(TILE * 4 * TILE * 4 * 3 * 4).order(ByteOrder.nativeOrder())

    /**
     * Enlarges [src] to [outW] × [outH] (up to 4× its size): the network makes a 4× version of
     * each piece, which is then scaled to the requested size.
     */
    fun upscale(src: SuperRes.Frame, outW: Int, outH: Int): SuperRes.Frame =
        upscaleWithin(src, outW, outH, Long.MAX_VALUE)!!

    /**
     * The same, unless this phone is too slow for it: after the first pieces the whole job is
     * projected, and if it would take longer than [budgetMs] it is abandoned (null) and
     * [tooSlow] is set, so the caller falls back to the ordinary enlargement from then on.
     */
    fun upscaleWithin(src: SuperRes.Frame, outW: Int, outH: Int, budgetMs: Long): SuperRes.Frame? {
        val t0 = android.os.SystemClock.uptimeMillis()
        val perSide = { n: Int -> (n + TILE - 2 * OVERLAP - 1) / (TILE - 2 * OVERLAP) }
        val total = perSide(src.w) * perSide(src.h)
        val out = IntArray(outW * outH)
        val fx = outW.toFloat() / src.w
        val fy = outH.toFloat() / src.h
        val step = TILE - 2 * OVERLAP
        var y = 0
        var tiles = 0
        while (y < src.h) {
            var x = 0
            while (x < src.w) {
                // The piece fed to the network: this step's area plus OVERLAP around it.
                val x0 = (x - OVERLAP).coerceIn(0, max(0, src.w - TILE))
                val y0 = (y - OVERLAP).coerceIn(0, max(0, src.h - TILE))
                val big = run4x(src, x0, y0)
                tiles++
                if (tiles == PROBE_TILES && total > PROBE_TILES) {
                    val projected = (android.os.SystemClock.uptimeMillis() - t0) * total / tiles
                    if (projected > budgetMs) {
                        tooSlow = true
                        Log.i(TAG, "ai upscale abandoned: $total pieces would take $projected ms (limit $budgetMs)")
                        return null
                    }
                }
                // Keep only this step's area (no seams), scaled to the final size.
                val ax0 = x
                val ay0 = y
                val ax1 = min(src.w, x + step)
                val ay1 = min(src.h, y + step)
                val ox0 = (ax0 * fx).roundToInt()
                val oy0 = (ay0 * fy).roundToInt()
                val ox1 = (ax1 * fx).roundToInt().coerceAtMost(outW)
                val oy1 = (ay1 * fy).roundToInt().coerceAtMost(outH)
                for (oy in oy0 until oy1) {
                    // Position in the 4× piece (pixel centres).
                    val py = ((oy + 0.5f) / fy - y0) * 4f - 0.5f
                    for (ox in ox0 until ox1) {
                        val px = ((ox + 0.5f) / fx - x0) * 4f - 0.5f
                        out[oy * outW + ox] = sample(big, px, py, 4f / fx)
                    }
                }
                x += step
            }
            y += step
        }
        lastRunMs = android.os.SystemClock.uptimeMillis() - t0
        Log.i(TAG, "ai upscale ${src.w}x${src.h} -> ${outW}x$outH: $tiles pieces in $lastRunMs ms")
        return SuperRes.Frame(out, outW, outH)
    }

    /** The network's 4× version of the 64×64 piece at ([x0], [y0]) (edges repeated if the picture is smaller). */
    private fun run4x(src: SuperRes.Frame, x0: Int, y0: Int): FloatArray {
        input.rewind()
        for (yy in 0 until TILE) {
            val sy = (y0 + yy).coerceIn(0, src.h - 1)
            for (xx in 0 until TILE) {
                val p = src.px[sy * src.w + (x0 + xx).coerceIn(0, src.w - 1)]
                input.putFloat(((p shr 16) and 0xFF) / 255f)
                input.putFloat(((p shr 8) and 0xFF) / 255f)
                input.putFloat((p and 0xFF) / 255f)
            }
        }
        input.rewind()
        output.rewind()
        interpreter.run(input, output)
        output.rewind()
        val f = FloatArray(TILE * 4 * TILE * 4 * 3)
        output.asFloatBuffer().get(f)
        return f
    }

    /**
     * Value of the 4× piece at ([px], [py]): bilinear when enlarging; when the final size is
     * smaller than 4× ([footprint] > 1) an average over the area each output pixel covers.
     */
    private fun sample(big: FloatArray, px: Float, py: Float, footprint: Float): Int {
        val n = TILE * 4
        val r = ((footprint - 1f) / 2f).coerceAtLeast(0f)
        var sr = 0f
        var sg = 0f
        var sb = 0f
        var c = 0f
        val ya = (py - r).toInt().coerceIn(0, n - 1)
        val yb = (py + r + 1f).toInt().coerceIn(0, n - 1)
        val xa = (px - r).toInt().coerceIn(0, n - 1)
        val xb = (px + r + 1f).toInt().coerceIn(0, n - 1)
        for (yy in ya..yb) for (xx in xa..xb) {
            val wy = (1f - kotlin.math.abs(yy - py) / (r + 1f)).coerceAtLeast(0f)
            val wx = (1f - kotlin.math.abs(xx - px) / (r + 1f)).coerceAtLeast(0f)
            val w = wx * wy
            if (w <= 0f) continue
            val i = (yy * n + xx) * 3
            sr += big[i] * w
            sg += big[i + 1] * w
            sb += big[i + 2] * w
            c += w
        }
        if (c <= 0f) return 0xFF000000.toInt()
        val r8 = (sr / c * 255f).roundToInt().coerceIn(0, 255)
        val g8 = (sg / c * 255f).roundToInt().coerceIn(0, 255)
        val b8 = (sb / c * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
    }

    override fun close() = interpreter.close()

    companion object {
        private const val TAG = "ProCam"
        const val MODEL = "procam_sr_x4.tflite"
        const val TILE = 64
        /** Pieces overlap by this much on each side; only their centres are kept. */
        private const val OVERLAP = 6

        /** How long the last enlargement took (diagnostics, speed test). */
        @Volatile var lastRunMs = 0L
        /** This phone can't run the network in reasonable time (found out on the first try). */
        @Volatile var tooSlow = false
        /** The longest a photo may wait for the network (tests lift it). */
        @Volatile var budgetMs = 15_000L
        private const val PROBE_TILES = 12

        private fun loadModel(context: Context, name: String): MappedByteBuffer {
            context.assets.openFd(name).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    return stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        }

        /**
         * Quick speed test: enlarges a 256×192 test picture and returns
         * (milliseconds, estimated seconds for a typical super-zoom photo of [photoPixels] input pixels).
         */
        fun speedTest(context: Context, photoPixels: Int = 1_400_000): Pair<Long, Float> {
            val w = 256
            val h = 192
            val rnd = java.util.Random(1)
            val px = IntArray(w * h) { val v = 80 + rnd.nextInt(96); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
            AiUpscaler(context).use { ai ->
                ai.upscale(SuperRes.Frame(px, 64, 64), 256, 256) // warm-up
                val t0 = android.os.SystemClock.uptimeMillis()
                ai.upscale(SuperRes.Frame(px, w, h), w * 2, h * 2)
                val ms = android.os.SystemClock.uptimeMillis() - t0
                return ms to ms / 1000f * photoPixels / (w * h)
            }
        }
    }
}
