package com.anycam.app.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds the four corners of a card or a sheet of paper around a point the user tapped.
 *
 * The picture is made small and soft (so print on the card doesn't break it up), the area of
 * about the same colour as around the tap is grown outwards, and the outline of that area is
 * reduced to the four-cornered shape that covers it best.
 */
object CardFinder {
    /** Working width: small enough to be instant, large enough for corners within a pixel or two. */
    private const val WORK = 360

    /**
     * [argb]: the picture ([w] × [h]); [tx], [ty]: the tap. The corners (x, y pairs, in the
     * picture's pixels, going round), or null if nothing card-like is there.
     */
    fun find(argb: IntArray, w: Int, h: Int, tx: Float, ty: Float): FloatArray? {
        if (w < 8 || h < 8) return null
        val s = max(1, (w + WORK - 1) / WORK)
        val sw = w / s
        val sh = h / s
        // Small copy, three channels.
        val r = FloatArray(sw * sh); val g = FloatArray(sw * sh); val b = FloatArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            var rr = 0; var gg = 0; var bb = 0
            for (dy in 0 until s) for (dx in 0 until s) {
                val p = argb[(y * s + dy) * w + x * s + dx]
                rr += p shr 16 and 255; gg += p shr 8 and 255; bb += p and 255
            }
            val n = (s * s).toFloat()
            val i = y * sw + x
            r[i] = rr / n; g[i] = gg / n; b[i] = bb / n
        }
        val soft = max(1, sw / 120)
        for (c in listOf(r, g, b)) { blur(c, sw, sh, soft); blur(c, sw, sh, soft) }
        val sx = (tx / s).toInt().coerceIn(0, sw - 1)
        val sy = (ty / s).toInt().coerceIn(0, sh - 1)
        // The colour around the tap.
        var mr = 0f; var mg = 0f; var mb = 0f; var n = 0
        for (y in max(0, sy - 2)..min(sh - 1, sy + 2)) for (x in max(0, sx - 2)..min(sw - 1, sx + 2)) {
            val i = y * sw + x; mr += r[i]; mg += g[i]; mb += b[i]; n++
        }
        mr /= n; mg /= n; mb /= n
        // Grow while the colour stays close to it (and changes little from step to step).
        val inside = BooleanArray(sw * sh)
        val queue = IntArray(sw * sh)
        var head = 0; var tail = 0
        val start = sy * sw + sx
        inside[start] = true; queue[tail++] = start
        val far = 34f
        while (head < tail) {
            val i = queue[head++]
            val x = i % sw; val y = i / sw
            for (k in 0..3) {
                val nx = x + DX[k]; val ny = y + DY[k]
                if (nx < 0 || ny < 0 || nx >= sw || ny >= sh) continue
                val j = ny * sw + nx
                if (inside[j]) continue
                if (dist(r[j], g[j], b[j], mr, mg, mb) > far) continue
                inside[j] = true; queue[tail++] = j
            }
        }
        // The region stops early inside the soft edge. The colour just outside it, then the edge
        // moved to where the colour is closer to the outside than to the card: halfway through
        // the softened edge, which is where the real edge is.
        var or = 0f; var og = 0f; var ob = 0f; var on = 0
        val ring = 3 * soft + 2
        for (i in 0 until sw * sh) if (!inside[i]) {
            val x = i % sw; val y = i / sw
            var near = false
            loop@ for (dy in -ring..ring step ring) for (dx in -ring..ring step ring) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until sw && yy in 0 until sh && inside[yy * sw + xx]) { near = true; break@loop }
            }
            if (near) { or += r[i]; og += g[i]; ob += b[i]; on++ }
        }
        if (on > 0) {
            or /= on; og /= on; ob /= on
            var frontier = (0 until tail).map { queue[it] }.filter { i ->
                val x = i % sw; val y = i / sw
                (0..3).any { k -> val nx = x + DX[k]; val ny = y + DY[k]; nx in 0 until sw && ny in 0 until sh && !inside[ny * sw + nx] }
            }
            repeat(3 * soft + 2) {
                val next = ArrayList<Int>()
                for (i in frontier) {
                    val x = i % sw; val y = i / sw
                    for (k in 0..3) {
                        val nx = x + DX[k]; val ny = y + DY[k]
                        if (nx < 0 || ny < 0 || nx >= sw || ny >= sh) continue
                        val j = ny * sw + nx
                        if (inside[j]) continue
                        if (dist(r[j], g[j], b[j], mr, mg, mb) < dist(r[j], g[j], b[j], or, og, ob)) {
                            inside[j] = true; queue[tail++] = j; next += j
                        }
                    }
                }
                frontier = next
            }
        }
        val area = tail
        if (area < sw * sh / 400 || area > sw * sh * 7 / 10) return null
        // Its outline, then the convex hull of it.
        val edge = ArrayList<Long>()
        for (i in 0 until sw * sh) if (inside[i]) {
            val x = i % sw; val y = i / sw
            if (x == 0 || y == 0 || x == sw - 1 || y == sh - 1 || !inside[i - 1] || !inside[i + 1] || !inside[i - sw] || !inside[i + sw])
                edge += (x.toLong() shl 32) or y.toLong()
        }
        val hull = hull(edge.map { doubleArrayOf((it shr 32).toDouble() + 0.5, (it and 0xFFFFFFFFL).toDouble() + 0.5) })
        if (hull.size < 4) return null
        val rough = toFour(hull) ?: return null
        // Its corners are rounded (by the softening): straight lines fitted to each side meet where the real corners are.
        val quad = sharpen(rough, edge.map { doubleArrayOf((it shr 32).toDouble() + 0.5, (it and 0xFFFFFFFFL).toDouble() + 0.5) }) ?: rough
        // A card fills its four-cornered outline nearly completely; a blob doesn't.
        val quadArea = abs(shoelace(quad))
        if (quadArea <= 0 || area / quadArea < 0.85) return null
        return FloatArray(8) { (quad[it / 2][it % 2] * s).toFloat() }
    }

    private val DX = intArrayOf(1, -1, 0, 0)
    private val DY = intArrayOf(0, 0, 1, -1)

    private fun dist(r1: Float, g1: Float, b1: Float, r2: Float, g2: Float, b2: Float): Float {
        val dr = r1 - r2; val dg = g1 - g2; val db = b1 - b2
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun blur(c: FloatArray, w: Int, h: Int, rad: Int) {
        val tmp = FloatArray(c.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f; var n = 0
            for (d in -rad..rad) { val xx = x + d; if (xx in 0 until w) { sum += c[y * w + xx]; n++ } }
            tmp[y * w + x] = sum / n
        }
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f; var n = 0
            for (d in -rad..rad) { val yy = y + d; if (yy in 0 until h) { sum += tmp[yy * w + x]; n++ } }
            c[y * w + x] = sum / n
        }
    }

    /** Convex hull, counter-clockwise (monotone chain). */
    internal fun hull(points: List<DoubleArray>): List<DoubleArray> {
        val p = points.distinctBy { it[0] to it[1] }.sortedWith(compareBy({ it[0] }, { it[1] }))
        if (p.size < 3) return p
        fun cross(o: DoubleArray, a: DoubleArray, b: DoubleArray) = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        val lower = ArrayList<DoubleArray>()
        for (q in p) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], q) <= 0) lower.removeAt(lower.size - 1); lower += q }
        val upper = ArrayList<DoubleArray>()
        for (q in p.asReversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], q) <= 0) upper.removeAt(upper.size - 1); upper += q }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    /** The hull reduced to four corners: corners whose removal loses the least area go first. */
    internal fun toFour(hull: List<DoubleArray>): List<DoubleArray>? {
        val pts = ArrayList(hull)
        while (pts.size > 4) {
            var best = -1
            var bestLoss = Double.MAX_VALUE
            for (i in pts.indices) {
                val a = pts[(i - 1 + pts.size) % pts.size]; val b = pts[i]; val c = pts[(i + 1) % pts.size]
                val loss = abs((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])) / 2
                if (loss < bestLoss) { bestLoss = loss; best = i }
            }
            pts.removeAt(best)
        }
        return if (pts.size == 4) pts else null
    }

    /** Each side refitted to the outline points along its middle part; corners where neighbouring sides meet. */
    internal fun sharpen(q: List<DoubleArray>, outline: List<DoubleArray>): List<DoubleArray>? {
        val lines = ArrayList<DoubleArray>() // a, b, c with a·x + b·y = c, (a, b) unit
        for (i in 0 until 4) {
            val p = q[i]; val e = q[(i + 1) % 4]
            val dx = e[0] - p[0]; val dy = e[1] - p[1]
            val len = sqrt(dx * dx + dy * dy)
            if (len < 4) return null
            val ux = dx / len; val uy = dy / len
            val tol = max(2.0, len * 0.04)
            var sx = 0.0; var sy = 0.0; var n = 0
            val near = ArrayList<DoubleArray>()
            for (o in outline) {
                val t = ((o[0] - p[0]) * ux + (o[1] - p[1]) * uy) / len
                if (t < 0.15 || t > 0.85) continue
                val d = abs(-(o[0] - p[0]) * uy + (o[1] - p[1]) * ux)
                if (d > tol) continue
                near += o; sx += o[0]; sy += o[1]; n++
            }
            if (n < 5) return null
            val mx = sx / n; val my = sy / n
            var sxx = 0.0; var sxy = 0.0; var syy = 0.0
            for (o in near) { val a = o[0] - mx; val b = o[1] - my; sxx += a * a; sxy += a * b; syy += b * b }
            // Direction of the line: the main axis of the points.
            val angle = 0.5 * kotlin.math.atan2(2 * sxy, sxx - syy)
            val nx = -kotlin.math.sin(angle); val ny = kotlin.math.cos(angle)
            lines += doubleArrayOf(nx, ny, nx * mx + ny * my)
        }
        val out = ArrayList<DoubleArray>()
        for (i in 0 until 4) {
            val l1 = lines[(i + 3) % 4]; val l2 = lines[i]
            val det = l1[0] * l2[1] - l1[1] * l2[0]
            if (abs(det) < 1e-6) return null
            val x = (l1[2] * l2[1] - l1[1] * l2[2]) / det
            val y = (l1[0] * l2[2] - l1[2] * l2[0]) / det
            // A refit corner far from the rough one means the fit went wrong: keep the rough shape.
            if (kotlin.math.hypot(x - q[i][0], y - q[i][1]) > 0.25 * kotlin.math.hypot(q[i][0] - q[(i + 2) % 4][0], q[i][1] - q[(i + 2) % 4][1])) return null
            out += doubleArrayOf(x, y)
        }
        return out
    }

    private fun shoelace(q: List<DoubleArray>): Double {
        var a = 0.0
        for (i in q.indices) { val p = q[i]; val n = q[(i + 1) % q.size]; a += p[0] * n[1] - n[0] * p[1] }
        return a / 2
    }
}
