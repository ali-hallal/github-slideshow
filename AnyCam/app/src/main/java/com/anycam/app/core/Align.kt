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
    private const val CAP = 32           // the most one pixel's difference may count

    /** Shift of each tile ([dx], [dy] in pixels: reference (x, y) matches frame (x+dx, y+dy)) and how well it matched. */
    class Field(
        val cols: Int, val rows: Int, val dx: IntArray, val dy: IntArray, val cost: FloatArray,
        /** The part of a pixel beyond [dx], [dy] (-0.5..0.5), from how the match falls off either side of the best. */
        val subX: FloatArray = FloatArray(dx.size), val subY: FloatArray = FloatArray(dy.size),
    )

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
                                // Capped: a thing that moved between shots differs hugely wherever the
                                // frame is put, and mustn't decide where the rest of it goes.
                                val d = abs((ref.px[a + x].toInt() and 0xFF) - (frame.px[b + x].toInt() and 0xFF))
                                sad += if (d > CAP) CAP else d
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
        val subX = FloatArray(cols * rows)
        val subY = FloatArray(cols * rows)
        val span = 2 * FINE_REACH + 1
        Parallel.rows(rows) { r ->
            val grid = FloatArray(span * span)
            for (c in 0 until cols) {
                grid.fill(Float.NaN)
                var bestOx = 0
                var bestOy = 0
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
                                    val d = abs((ref.px[y * ref.w + x].toInt() and 0xFF) - (frame.px[fy * frame.w + fx].toInt() and 0xFF))
                                    sad += if (d > CAP) CAP else d
                                    n++
                                }
                                x += 2
                            }
                        }
                        y += 2
                    }
                    if (n < TILE * TILE / 16) continue
                    grid[(oy + FINE_REACH) * span + ox + FINE_REACH] = sad.toFloat() / n
                    // Per-pixel cost, with a slight pull towards the hand movement (flat tiles).
                    val score = sad * 4096 / n + (abs(ox) + abs(oy)) * 24
                    if (score < best) { best = score; bx = sx; by = sy; bestSad = sad; bestN = n; bestOx = ox; bestOy = oy }
                }
                val i = r * cols + c
                dx[i] = bx
                dy[i] = by
                // Between whole pixels: where the lowest point of the costs either side of the best lies.
                if (best != Long.MAX_VALUE && abs(bestOx) < FINE_REACH && abs(bestOy) < FINE_REACH) {
                    val at = (bestOy + FINE_REACH) * span + bestOx + FINE_REACH
                    val mid = grid[at]
                    fun part(lo: Float, hi: Float): Float {
                        if (lo.isNaN() || hi.isNaN()) return 0f
                        val curve = lo + hi - 2 * mid
                        return if (curve <= 1e-4f) 0f else ((lo - hi) / (2 * curve)).coerceIn(-0.5f, 0.5f)
                    }
                    subX[i] = part(grid[at - 1], grid[at + 1])
                    subY[i] = part(grid[at - span], grid[at + span])
                }
                cost[i] = if (best == Long.MAX_VALUE) Float.MAX_VALUE else bestSad.toFloat() / bestN
            }
        }
        // Hand movement changes smoothly across a frame. A tile that disagrees with its neighbours
        // by more than a pixel has locked onto something else (a repeat of a pattern, a thing
        // that moved): it takes the neighbours' middle value.
        if (cols >= 3 && rows >= 3) {
            val mx = dx.copyOf()
            val my = dy.copyOf()
            val near = IntArray(9)
            for (r in 0 until rows) for (c in 0 until cols) {
                fun middle(a: IntArray): Int {
                    var n = 0
                    for (j in -1..1) for (i in -1..1) {
                        val rr = r + j
                        val cc = c + i
                        if (rr in 0 until rows && cc in 0 until cols && cost[rr * cols + cc] < Float.MAX_VALUE) near[n++] = a[rr * cols + cc]
                    }
                    if (n == 0) return a[r * cols + c]
                    java.util.Arrays.sort(near, 0, n)
                    return near[n / 2]
                }
                val i = r * cols + c
                val ex = middle(dx)
                val ey = middle(dy)
                if (abs(dx[i] - ex) > 1 || abs(dy[i] - ey) > 1) {
                    mx[i] = ex
                    my[i] = ey
                    subX[i] = 0f
                    subY[i] = 0f
                }
            }
            System.arraycopy(mx, 0, dx, 0, dx.size)
            System.arraycopy(my, 0, dy, 0, dy.size)
        }
        return Field(cols, rows, dx, dy, cost, subX, subY)
    }
}
