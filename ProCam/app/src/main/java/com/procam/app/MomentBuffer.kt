package com.procam.app

import android.media.Image
import kotlin.math.abs
import kotlin.math.max

/**
 * "Never miss the moment": keeps the last few frames of the camera's video-size stream so that
 * pressing the shutter a little late still gets the instant you pressed (phones normally take the
 * photo a fraction of a second after the press).
 *
 * Frames are kept as raw YUV plane copies (a fast memory copy per frame, no conversion); only the
 * chosen frame is converted to a picture. Not thread-safe: used from the camera thread.
 */
class MomentBuffer(private val capacity: Int = 10) {

    /** A YUV_420_888 frame copied out of the camera (planes kept with their original strides). */
    class Frame(
        var width: Int = 0,
        var height: Int = 0,
        var y: ByteArray = ByteArray(0),
        var u: ByteArray = ByteArray(0),
        var v: ByteArray = ByteArray(0),
        var yRow: Int = 0,
        var yPix: Int = 1,
        var uvRow: Int = 0,
        var uvPix: Int = 1,
        var sequence: Long = 0,
    ) {
        fun copy() = Frame(width, height, y.copyOf(), u.copyOf(), v.copyOf(), yRow, yPix, uvRow, uvPix, sequence)
    }

    private val slots = Array(capacity) { Frame() }
    private var next = 0
    private var count = 0
    private var sequence = 0L

    val size get() = count

    fun clear() {
        count = 0
        next = 0
    }

    /** Copies [image] into the ring (reusing the slot's arrays when the size matches). */
    fun add(image: Image) {
        val f = slots[next]
        val planes = image.planes
        f.width = image.width
        f.height = image.height
        f.y = copyPlane(planes[0].buffer, f.y)
        f.u = copyPlane(planes[1].buffer, f.u)
        f.v = copyPlane(planes[2].buffer, f.v)
        f.yRow = planes[0].rowStride
        f.yPix = planes[0].pixelStride
        f.uvRow = planes[1].rowStride
        f.uvPix = planes[1].pixelStride
        f.sequence = ++sequence
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    private fun copyPlane(buf: java.nio.ByteBuffer, reuse: ByteArray): ByteArray {
        buf.rewind()
        val n = buf.remaining()
        val out = if (reuse.size == n) reuse else ByteArray(n)
        buf.get(out)
        return out
    }

    /** The newest frame's sequence number (0 if empty). */
    val latestSequence get() = if (count == 0) 0L else slots[(next - 1 + capacity) % capacity].sequence

    /**
     * The sharpest frame whose sequence is within [from]..[to] (inclusive), copied out of the ring
     * so it survives further frames. Null if none.
     */
    fun sharpestBetween(from: Long, to: Long): Frame? {
        var best: Frame? = null
        var bestScore = -1.0
        for (i in 0 until count) {
            val f = slots[(next - 1 - i + capacity * 2) % capacity]
            if (f.sequence < from || f.sequence > to) continue
            val s = sharpness(f)
            if (s > bestScore) {
                bestScore = s
                best = f
            }
        }
        return best?.copy()
    }

    companion object {
        /** Mean absolute Laplacian of the luma in the central area, sampled sparsely. */
        fun sharpness(f: Frame): Double {
            var sum = 0L
            var n = 0
            val x0 = f.width / 5
            val y0 = f.height / 5
            var y = y0
            while (y < f.height - y0) {
                var x = x0
                while (x < f.width - x0) {
                    val c = lum(f, x, y)
                    sum += abs(4 * c - lum(f, x - 1, y) - lum(f, x + 1, y) - lum(f, x, y - 1) - lum(f, x, y + 1))
                    n++
                    x += 4
                }
                y += 4
            }
            return if (n == 0) 0.0 else sum.toDouble() / n
        }

        private fun lum(f: Frame, x: Int, y: Int) = f.y[y * f.yRow + x * f.yPix].toInt() and 0xFF

        /** Whole frame to ARGB pixels (BT.601 full range, as JPEG uses). */
        fun toArgb(f: Frame): SuperRes.Frame {
            val w = f.width and 1.inv()
            val h = f.height and 1.inv()
            val out = IntArray(w * h)
            for (y in 0 until h) {
                val yBase = y * f.yRow
                val uvBase = (y / 2) * f.uvRow
                for (x in 0 until w) {
                    val yy = f.y[yBase + x * f.yPix].toInt() and 0xFF
                    val uvi = uvBase + (x / 2) * f.uvPix
                    val u = (f.u[uvi].toInt() and 0xFF) - 128
                    val v = (f.v[uvi].toInt() and 0xFF) - 128
                    val r = (yy + ((91881 * v) shr 16)).coerceIn(0, 255)
                    val g = (yy - ((22554 * u + 46802 * v) shr 16)).coerceIn(0, 255)
                    val b = (yy + ((116130 * u) shr 16)).coerceIn(0, 255)
                    out[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return SuperRes.Frame(out, w, h)
        }

        /**
         * Freeze exposure: the shutter is at most [maxShutterNs] (fast enough to stop motion);
         * ISO makes up the brightness. If ISO runs out, the shutter may lengthen up to
         * [limitShutterNs] — a slightly darker sharp photo beats a bright blurry one.
         */
        fun freezePlan(
            aeIso: Int, aeExposureNs: Long, maxShutterNs: Long, limitShutterNs: Long,
            isoRange: android.util.Range<Int>, exposureRange: android.util.Range<Long>,
        ): Pair<Int, Long> {
            val product = aeIso.toDouble() * aeExposureNs
            var t = minOf(aeExposureNs, maxShutterNs).coerceIn(exposureRange.lower, exposureRange.upper)
            var iso = (product / t).toInt()
            if (iso > isoRange.upper) {
                iso = isoRange.upper
                t = (product / iso).toLong().coerceAtMost(limitShutterNs).coerceIn(exposureRange.lower, exposureRange.upper)
            } else if (iso < isoRange.lower) {
                iso = isoRange.lower
                t = max(exposureRange.lower, (product / iso).toLong())
            }
            return Pair(iso.coerceIn(isoRange.lower, isoRange.upper), t)
        }
    }
}
