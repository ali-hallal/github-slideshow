package com.anycam.app.core

import kotlin.math.abs

/**
 * Makes one photo out of a burst.
 *
 * A single frame from a small phone sensor is noisy. Several frames of the same moment, laid
 * exactly on top of each other and averaged, are not: the scene adds up, the noise cancels.
 * The care is in "exactly" and in "the same": every tile is aligned on its own ([Align]), and
 * wherever a frame still disagrees with the reference (something moved, or the alignment
 * failed) that frame is left out there, so nothing ghosts.
 *
 * Works on what every camera gives ([Yuv]); nothing here depends on the phone.
 */
object Merge {

    class Result(val photo: Yuv, val reference: Int, val used: Int, val noise: Float)

    /**
     * Merges [frames] (all the same size). The sharpest becomes the reference: the photo shows
     * its moment, the others only clean it.
     */
    fun merge(frames: List<Yuv>): Result {
        require(frames.isNotEmpty())
        val w = frames[0].w
        val h = frames[0].h
        val same = frames.filter { it.w == w && it.h == h }
        if (same.size == 1) return Result(same[0], 0, 1, 0f)

        val smalls = same.map { Align.small(it) }
        val refIndex = smalls.indices.maxByOrNull { Align.sharpness(smalls[it]) } ?: 0
        val ref = same[refIndex]

        val others = same.indices.filter { it != refIndex }
        val fields = others.map { k -> Align.tiles(ref, same[k], Align.global(smalls[refIndex], smalls[k])) }

        // How noisy this burst is: the typical difference between two aligned frames. (Half the
        // tiles differ less than this; tiles with movement or failed alignment sit far above.)
        val costs = fields.flatMap { f -> f.cost.filter { it < Float.MAX_VALUE }.toList() }.sorted()
        val noise = if (costs.isEmpty()) 2f else costs[costs.size / 2]
        // A pixel that differs by up to [keep] is taken in full; by [drop] or more, not at all.
        val keep = (noise * 2.5f).coerceAtLeast(3f)
        val drop = keep * 2.5f
        val tileLimit = noise * 2.5f + 2f

        val outY = ByteArray(w * h)
        val outU = ByteArray(ref.cw * ref.ch)
        val outV = ByteArray(ref.cw * ref.ch)
        val cols = fields[0].cols
        // Weights are 0..256.
        val slope = 256f / (drop - keep)
        var contributed = 0L
        val lock = Any()

        Parallel.rows(ref.ch) { cy ->
            var rowContribution = 0L
            for (cx in 0 until ref.cw) {
                // The four brightness pixels that share this colour sample.
                val x = cx * 2
                val y = cy * 2
                val tile = (y / Align.TILE) * cols + x / Align.TILE
                val r00 = ref.y[y * w + x].toInt() and 0xFF
                val r01 = ref.y[y * w + x + 1].toInt() and 0xFF
                val r10 = ref.y[(y + 1) * w + x].toInt() and 0xFF
                val r11 = ref.y[(y + 1) * w + x + 1].toInt() and 0xFF
                var s00 = r00 * 256; var s01 = r01 * 256; var s10 = r10 * 256; var s11 = r11 * 256
                var su = (ref.u[cy * ref.cw + cx].toInt() and 0xFF) * 256
                var sv = (ref.v[cy * ref.cw + cx].toInt() and 0xFF) * 256
                var wsum = 256
                for (k in fields.indices) {
                    val f = fields[k]
                    if (f.cost[tile] > tileLimit) continue
                    val fr = same[others[k]]
                    val fx = x + f.dx[tile]
                    val fy = y + f.dy[tile]
                    if (fx < 0 || fy < 0 || fx + 1 >= w || fy + 1 >= h) continue
                    val a00 = fr.y[fy * w + fx].toInt() and 0xFF
                    val a01 = fr.y[fy * w + fx + 1].toInt() and 0xFF
                    val a10 = fr.y[(fy + 1) * w + fx].toInt() and 0xFF
                    val a11 = fr.y[(fy + 1) * w + fx + 1].toInt() and 0xFF
                    // One weight for the 2×2 block, from how far it is from the reference there.
                    val d = (abs(a00 - r00) + abs(a01 - r01) + abs(a10 - r10) + abs(a11 - r11)) * 0.25f
                    val wt = if (d <= keep) 256 else if (d >= drop) 0 else ((drop - d) * slope).toInt()
                    if (wt == 0) continue
                    s00 += a00 * wt; s01 += a01 * wt; s10 += a10 * wt; s11 += a11 * wt
                    val ci = ((fy + 1) / 2).coerceAtMost(fr.ch - 1) * fr.cw + ((fx + 1) / 2).coerceAtMost(fr.cw - 1)
                    su += (fr.u[ci].toInt() and 0xFF) * wt
                    sv += (fr.v[ci].toInt() and 0xFF) * wt
                    wsum += wt
                }
                val half = wsum / 2
                outY[y * w + x] = ((s00 + half) / wsum).toByte()
                outY[y * w + x + 1] = ((s01 + half) / wsum).toByte()
                outY[(y + 1) * w + x] = ((s10 + half) / wsum).toByte()
                outY[(y + 1) * w + x + 1] = ((s11 + half) / wsum).toByte()
                outU[cy * ref.cw + cx] = ((su + half) / wsum).toByte()
                outV[cy * ref.cw + cx] = ((sv + half) / wsum).toByte()
                rowContribution += wsum
            }
            synchronized(lock) { contributed += rowContribution }
        }
        // Frames' worth of light in the average pixel (1 = nothing could be merged).
        val used = (contributed / 256.0 / (ref.cw.toLong() * ref.ch)).let { Math.round(it).toInt() }.coerceIn(1, same.size)
        return Result(Yuv(w, h, outY, outU, outV), refIndex, used, noise)
    }
}
