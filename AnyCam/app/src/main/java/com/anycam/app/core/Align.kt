package com.anycam.app.core

import kotlin.math.abs

/**
 * Finds how each part of a frame moved against a reference frame.
 *
 * Hands move between the shots of a burst, and things in the scene move too, so one shift for
 * the whole picture is not enough: the picture is cut into tiles and each tile gets its own
 * shift. A coarse search on a small copy finds the hand movement; each tile then refines it at
 * full size.
 */
object Align {
    /** Tile size in pixels. */
    const val TILE = 64
    private const val SMALL = 4          // the small copy is this many times smaller
    private const val COARSE_REACH = 12  // small-copy pixels: ±48 px of hand movement
    private const val FINE_REACH = 3     // full-size pixels around the coarse answer, per tile

    /** Shift of each tile ([dx], [dy] in pixels: reference (x, y) matches frame (x+dx, y+dy)) and how well it matched. */
    class Field(val cols: Int, val rows: Int, val dx: IntArray, val dy: IntArray, val cost: FloatArray)

    /** A brightness plane at 1/[SMALL] size (box average). */
    fun small(f: Plane): Plane {
        val sw = f.w / SMALL
        val sh = f.h / SMALL
        val out = ByteArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            var sum = 0
            for (j in 0 until SMALL) {
                val row = (y * SMALL + j) * f.w + x * SMALL
                for (i in 0 until SMALL) sum += f.px[row + i].toInt() and 0xFF
            }
            out[y * sw + x] = (sum / (SMALL * SMALL)).toByte()
        }
        return Plane(sw, sh, out)
    }

    fun small(f: Yuv): Plane = small(Plane(f.w, f.h, f.y))

    class Plane(val w: Int, val h: Int, val px: ByteArray)

    /** How sharp a frame is (more = sharper): the strength of its fine edges on the small copy. */
    fun sharpness(p: Plane): Double {
        var sum = 0L
        for (y in 1 until p.h - 1) for (x in 1 until p.w - 1) {
            val i = y * p.w + x
            val c = p.px[i].toInt() and 0xFF
            val gx = (p.px[i + 1].toInt() and 0xFF) - c
            val gy = (p.px[i + p.w].toInt() and 0xFF) - c
            sum += gx * gx + gy * gy
        }
        return sum.toDouble() / ((p.w - 2) * (p.h - 2)).coerceAtLeast(1)
    }

    /** The whole-picture shift of [frame] against [ref] (small copies), in full-size pixels. */
    fun global(ref: Plane, frame: Plane): IntArray {
        var bestX = 0
        var bestY = 0
        var best = Long.MAX_VALUE
        val inset = COARSE_REACH + 1
        // A coarse pass on every other pixel and every other shift, then single steps around it.
        for (pass in 0..1) {
            val step = if (pass == 0) 2 else 1
            val cx = bestX
            val cy = bestY
            val reach = if (pass == 0) COARSE_REACH else 1
            var dy = cy - reach
            while (dy <= cy + reach) {
                var dx = cx - reach
                while (dx <= cx + reach) {
                    if (abs(dx) <= COARSE_REACH && abs(dy) <= COARSE_REACH) {
                        var sad = 0L
                        var y = inset
                        while (y < ref.h - inset) {
                            val a = y * ref.w
                            val b = (y + dy) * frame.w + dx
                            var x = inset
                            while (x < ref.w - inset) {
                                sad += abs((ref.px[a + x].toInt() and 0xFF) - (frame.px[b + x].toInt() and 0xFF))
                                x += 2
                            }
                            y += 2
                        }
                        // Prefer the smaller movement when two fit equally (flat scenes).
                        sad = sad * 64 + abs(dx) + abs(dy)
                        if (sad < best) { best = sad; bestX = dx; bestY = dy }
                    }
                    dx += step
                }
                dy += step
            }
        }
        return intArrayOf(bestX * SMALL, bestY * SMALL)
    }

    /** Per-tile shifts of [frame] against [ref], starting from the whole-picture shift [g]. */
    fun tiles(ref: Yuv, frame: Yuv, g: IntArray): Field = tiles(Plane(ref.w, ref.h, ref.y), Plane(frame.w, frame.h, frame.y), g)

    fun tiles(ref: Plane, frame: Plane, g: IntArray): Field {
        val cols = (ref.w + TILE - 1) / TILE
        val rows = (ref.h + TILE - 1) / TILE
        val dx = IntArray(cols * rows)
        val dy = IntArray(cols * rows)
        val cost = FloatArray(cols * rows)
        Parallel.rows(rows) { r ->
            for (c in 0 until cols) {
                val x0 = c * TILE
                val y0 = r * TILE
                val x1 = minOf(ref.w, x0 + TILE)
                val y1 = minOf(ref.h, y0 + TILE)
                var best = Long.MAX_VALUE
                var bx = g[0]
                var by = g[1]
                var bestSad = 0L
                var bestN = 1
                for (oy in -FINE_REACH..FINE_REACH) for (ox in -FINE_REACH..FINE_REACH) {
                    val sx = g[0] + ox
                    val sy = g[1] + oy
                    var sad = 0L
                    var n = 0
                    var y = y0
                    while (y < y1) {
                        val fy = y + sy
                        if (fy in 0 until frame.h) {
                            var x = x0
                            while (x < x1) {
                                val fx = x + sx
                                if (fx in 0 until frame.w) {
                                    sad += abs((ref.px[y * ref.w + x].toInt() and 0xFF) - (frame.px[fy * frame.w + fx].toInt() and 0xFF))
                                    n++
                                }
                                x += 2
                            }
                        }
                        y += 2
                    }
                    if (n < TILE * TILE / 16) continue
                    // Per-pixel cost, with a slight pull towards the hand movement (flat tiles).
                    val score = sad * 4096 / n + (abs(ox) + abs(oy)) * 24
                    if (score < best) { best = score; bx = sx; by = sy; bestSad = sad; bestN = n }
                }
                val i = r * cols + c
                dx[i] = bx
                dy[i] = by
                cost[i] = if (best == Long.MAX_VALUE) Float.MAX_VALUE else bestSad.toFloat() / bestN
            }
        }
        return Field(cols, rows, dx, dy, cost)
    }
}
