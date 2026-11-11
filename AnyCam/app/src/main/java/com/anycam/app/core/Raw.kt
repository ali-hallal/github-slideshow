package com.anycam.app.core

import kotlin.math.abs
import kotlin.math.sqrt

/** One frame straight off the sensor: one number per pixel, each pixel behind a red, green or blue filter. */
class RawFrame(val w: Int, val h: Int, val px: ShortArray) {
    init {
        require(w % 2 == 0 && h % 2 == 0 && px.size == w * h) { "even size expected: $w x $h" }
    }
}

/**
 * What is needed to turn sensor numbers into a picture, as the camera reports it for the shot.
 *
 * [cfa]: which filter sits where (0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR). [black]: the reading for no
 * light, per position of the 2×2 filter pattern (index = row%2 * 2 + column%2). [white]: the
 * reading for a full pixel. [wb]: white-balance gains (red, green, blue). [ccm]: the 3×3 matrix
 * (row by row) from the sensor's colours to standard ones. [shading]: the lens's fall-off map.
 */
class RawInfo(
    val cfa: Int,
    val black: FloatArray,
    val white: Float,
    val wb: FloatArray,
    val ccm: FloatArray,
    val shading: Shading? = null,
) {
    /** 0 red, 1 green, 2 blue at pattern position [pos] (row%2 * 2 + column%2). */
    fun colour(pos: Int): Int = PATTERNS[cfa.coerceIn(0, 3)][pos]

    companion object {
        private val PATTERNS = arrayOf(intArrayOf(0, 1, 1, 2), intArrayOf(1, 0, 2, 1), intArrayOf(1, 2, 0, 1), intArrayOf(2, 1, 1, 0))
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }
}

/** How much each colour must be brightened across the frame to undo the lens's darker corners: [gains] holds red, green, blue per cell. */
class Shading(val cols: Int, val rows: Int, val gains: FloatArray) {
    /** The gain for [colour] at a point of the frame (0..1 across and down), between the map's cells. */
    fun at(colour: Int, fx: Float, fy: Float): Float {
        val x = (fx * (cols - 1)).coerceIn(0f, cols - 1f)
        val y = (fy * (rows - 1)).coerceIn(0f, rows - 1f)
        val x0 = x.toInt().coerceAtMost(cols - 2).coerceAtLeast(0)
        val y0 = y.toInt().coerceAtMost(rows - 2).coerceAtLeast(0)
        val x1 = minOf(x0 + 1, cols - 1)
        val y1 = minOf(y0 + 1, rows - 1)
        val ax = x - x0
        val ay = y - y0
        fun g(c: Int, r: Int) = gains[(r * cols + c) * 3 + colour]
        return (g(x0, y0) * (1 - ax) + g(x1, y0) * ax) * (1 - ay) + (g(x0, y1) * (1 - ax) + g(x1, y1) * ax) * ay
    }
}

/**
 * Merges a burst of sensor frames into one, before any of the camera's processing.
 *
 * The same idea as [Merge], one step earlier: here the numbers are still what the sensor
 * measured, so averaging them is exact, and the picture that is then developed from the
 * average starts out clean. Frames are aligned on a half-size brightness copy (one value per
 * 2×2 filter group), which keeps every red, green and blue sample over its own kind.
 */
object RawMerge {

    /**
     * The merged burst, ready to develop: light per pixel from 0 (black) to 1 (full), white
     * balanced and with the lens fall-off undone, with a 2-pixel border of repeats around it
     * ([stride] = width + 4) so neighbours can be read without checks.
     */
    class Merged(val w: Int, val h: Int, val light: FloatArray, val reference: Int, val used: Int, val noise: Float) {
        val stride get() = w + 4
    }

    fun merge(frames: List<RawFrame>, info: RawInfo): Merged {
        require(frames.isNotEmpty())
        val w = frames[0].w
        val h = frames[0].h
        val same = frames.filter { it.w == w && it.h == h }
        val proxies = same.map { proxy(it, info) }
        val smalls = proxies.map { Align.small(it) }
        val refIndex = smalls.indices.maxByOrNull { Align.sharpness(smalls[it]) } ?: 0
        val ref = same[refIndex]
        val refProxy = proxies[refIndex]
        val others = same.indices.filter { it != refIndex }
        val fields = others.map { k -> Align.tiles(refProxy, proxies[k], Align.global(smalls[refIndex], smalls[k])) }

        val costs = fields.flatMap { f -> f.cost.filter { it < Float.MAX_VALUE }.toList() }.sorted()
        val noise = if (costs.isEmpty()) 0f else costs[costs.size / 2]
        val keep = (noise * 2.5f).coerceAtLeast(3f)
        val drop = keep * 2.5f
        val tileLimit = noise * 2.5f + 2f
        val slope = 1f / (drop - keep)

        val stride = w + 4
        val light = FloatArray(stride * (h + 4))
        val pw = refProxy.w
        val ph = refProxy.h
        val cols = fields.firstOrNull()?.cols ?: 1
        val scale = FloatArray(4) { 1f / (info.white - info.black[it]).coerceAtLeast(1f) }
        val gain = FloatArray(4) { info.wb[info.colour(it)] }
        var contributed = 0.0
        val lock = Any()

        Parallel.rows(ph) { qy ->
            var rowWeight = 0.0
            val sum = FloatArray(4)
            for (qx in 0 until pw) {
                // One 2×2 filter group at a time: its four samples move together.
                val x = qx * 2
                val y = qy * 2
                val tile = (qy / Align.TILE) * cols + qx / Align.TILE
                val r = refProxy.px[qy * pw + qx].toInt() and 0xFF
                for (p in 0..3) sum[p] = (ref.px[(y + p / 2) * w + x + p % 2].toInt() and 0xFFFF).toFloat()
                var wsum = 1f
                for (k in fields.indices) {
                    val f = fields[k]
                    if (f.cost[tile] > tileLimit) continue
                    val fx = qx + f.dx[tile]
                    val fy = qy + f.dy[tile]
                    if (fx < 0 || fy < 0 || fx >= pw || fy >= ph) continue
                    val d = abs((proxies[others[k]].px[fy * pw + fx].toInt() and 0xFF) - r).toFloat()
                    val wt = if (d <= keep) 1f else if (d >= drop) 0f else (drop - d) * slope
                    if (wt == 0f) continue
                    val fr = same[others[k]]
                    val o = fy * 2 * w + fx * 2
                    sum[0] += (fr.px[o].toInt() and 0xFFFF) * wt
                    sum[1] += (fr.px[o + 1].toInt() and 0xFFFF) * wt
                    sum[2] += (fr.px[o + w].toInt() and 0xFFFF) * wt
                    sum[3] += (fr.px[o + w + 1].toInt() and 0xFFFF) * wt
                    wsum += wt
                }
                rowWeight += wsum
                for (p in 0..3) {
                    val px = x + p % 2
                    val py = y + p / 2
                    var v = (sum[p] / wsum - info.black[p]) * scale[p]
                    if (v < 0f) v = 0f
                    val sh = info.shading
                    if (sh != null) v *= sh.at(info.colour(p), px / (w - 1f), py / (h - 1f))
                    // A full pixel is white whatever its colour (no tinted highlights).
                    v *= gain[p]
                    light[(py + 2) * stride + px + 2] = if (v > 1f) 1f else v
                }
            }
            synchronized(lock) { contributed += rowWeight }
        }
        pad(light, w, h)
        val used = Math.round(contributed / (pw.toLong() * ph)).toInt().coerceIn(1, same.size)
        return Merged(w, h, light, refIndex, used, noise)
    }

    /**
     * A half-size brightness copy for alignment: one byte per 2×2 filter group, on a curve that
     * spreads the shadows (so dim scenes still have something to align on).
     */
    fun proxy(f: RawFrame, info: RawInfo): Align.Plane {
        val pw = f.w / 2
        val ph = f.h / 2
        val out = ByteArray(pw * ph)
        val black = info.black.sum()
        val range = (info.white * 4 - black).coerceAtLeast(1f)
        Parallel.rows(ph) { qy ->
            for (qx in 0 until pw) {
                val o = qy * 2 * f.w + qx * 2
                val v = (f.px[o].toInt() and 0xFFFF) + (f.px[o + 1].toInt() and 0xFFFF) +
                    (f.px[o + f.w].toInt() and 0xFFFF) + (f.px[o + f.w + 1].toInt() and 0xFFFF) - black
                out[qy * pw + qx] = (sqrt((v / range).coerceIn(0f, 1f)) * 255f + 0.5f).toInt().toByte()
            }
        }
        return Align.Plane(pw, ph, out)
    }

    /** Fills the 2-pixel border by repeating the nearest pixels of the same filter colour. */
    private fun pad(light: FloatArray, w: Int, h: Int) {
        val stride = w + 4
        for (y in 2 until h + 2) {
            val o = y * stride
            light[o] = light[o + 2]
            light[o + 1] = light[o + 3]
            light[o + w + 2] = light[o + w]
            light[o + w + 3] = light[o + w + 1]
        }
        for (x in 0 until stride) {
            light[x] = light[2 * stride + x]
            light[stride + x] = light[3 * stride + x]
            light[(h + 2) * stride + x] = light[h * stride + x]
            light[(h + 3) * stride + x] = light[(h + 1) * stride + x]
        }
    }
}
