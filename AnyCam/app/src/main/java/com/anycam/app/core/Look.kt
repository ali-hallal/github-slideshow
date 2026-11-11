package com.anycam.app.core

/**
 * The look of a photo: its brightness curve and its colour rendering.
 *
 * Developed straight from the sensor, a picture is accurate but flat — every phone maker adds
 * a contrast curve and its own taste in colour on top. Rather than invent one, the look is
 * measured from the phone's own picture of the same scene: one ordinary frame is taken with
 * the burst, and the curve and colour mapping that take the developed picture to that frame
 * are fitted ([fit]). The merged photo then looks like the phone's photos, with the detail
 * and low noise of the merged sensor data.
 *
 * [gain]: for each brightness 0..255, how much to brighten. [colour]: a 3×4 mapping (row by
 * row) from red, green, blue and 1 to the final red, green, blue.
 */
class Look(val gain: FloatArray, val colour: FloatArray) {

    companion object {
        /** No look: the picture as developed. */
        val PLAIN = Look(FloatArray(256) { 1f }, floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f))

        const val GRID_W = 64
        const val GRID_H = 48

        /** The phone's picture on the same coarse grid as [Develop.coarse] (red, green, blue per cell, 0..255). */
        fun coarse(phone: Yuv, gw: Int = GRID_W, gh: Int = GRID_H): FloatArray {
            val out = FloatArray(gw * gh * 3)
            for (gy in 0 until gh) for (gx in 0 until gw) {
                val x0 = gx * phone.cw / gw
                val x1 = ((gx + 1) * phone.cw / gw).coerceAtLeast(x0 + 1)
                val y0 = gy * phone.ch / gh
                val y1 = ((gy + 1) * phone.ch / gh).coerceAtLeast(y0 + 1)
                var sy = 0f
                var su = 0f
                var sv = 0f
                var n = 0
                for (y in y0 until minOf(y1, phone.ch)) for (x in x0 until minOf(x1, phone.cw)) {
                    sy += phone.y[(y * 2) * phone.w + x * 2].toInt() and 0xFF
                    su += (phone.u[y * phone.cw + x].toInt() and 0xFF) - 128
                    sv += (phone.v[y * phone.cw + x].toInt() and 0xFF) - 128
                    n++
                }
                if (n == 0) continue
                val yy = sy / n
                val u = su / n
                val v = sv / n
                val o = (gy * gw + gx) * 3
                out[o] = yy + 1.402f * v
                out[o + 1] = yy - 0.344136f * u - 0.714136f * v
                out[o + 2] = yy + 1.772f * u
            }
            return out
        }

        /**
         * The look that takes [mine] (the developed picture, coarse) to [phone] (the phone's own
         * picture of the same scene, coarse; same grid). Falls back towards [PLAIN] where the
         * two don't give enough to go on.
         */
        fun fit(mine: FloatArray, phone: FloatArray): Look {
            val n = minOf(mine.size, phone.size) / 3
            if (n < 64) return PLAIN
            fun lum(a: FloatArray, i: Int) = 0.299f * a[i * 3] + 0.587f * a[i * 3 + 1] + 0.114f * a[i * 3 + 2]

            // 1. Brightness curve: the k-th darkest cell of mine should become as bright as the
            //    k-th darkest of the phone's (so it doesn't matter if the two are slightly shifted).
            val a = FloatArray(n) { lum(mine, it) }.also { it.sort() }
            val b = FloatArray(n) { lum(phone, it) }.also { it.sort() }
            val knots = 32
            val from = FloatArray(knots + 1) { a[(it.toLong() * (n - 1) / knots).toInt()] }
            val to = FloatArray(knots + 1) { b[(it.toLong() * (n - 1) / knots).toInt()] }
            val gain = FloatArray(256)
            for (v in 0..255) {
                val target = when {
                    v <= from[0] -> if (from[0] > 1f) to[0] * v / from[0] else to[0]
                    v >= from[knots] -> to[knots] + (v - from[knots]) * ((255f - to[knots]) / (255f - from[knots]).coerceAtLeast(1f))
                    else -> {
                        var k = 0
                        while (k < knots - 1 && from[k + 1] < v) k++
                        val span = (from[k + 1] - from[k]).coerceAtLeast(0.001f)
                        to[k] + (to[k + 1] - to[k]) * (v - from[k]) / span
                    }
                }
                gain[v] = if (v == 0) 1f else (target / v).coerceIn(0.25f, 4f)
            }
            gain[0] = gain[1]
            // Smooth the curve a little: a few cells shouldn't put kinks in it.
            repeat(2) {
                val copy = gain.copyOf()
                for (v in 1..254) gain[v] = (copy[v - 1] + 2 * copy[v] + copy[v + 1]) / 4
            }

            // 2. Colour: the 3×4 mapping that best takes my (curve-applied) colours to the phone's,
            //    held towards "no change" so a scene of one colour can't produce a wild mapping.
            val xtx = Array(4) { DoubleArray(4) }
            val xty = Array(3) { DoubleArray(4) }
            var used = 0.0
            val x = DoubleArray(4)
            for (i in 0 until n) {
                val g = gain[lum(mine, i).toInt().coerceIn(0, 255)]
                var clipped = false
                for (c in 0..2) {
                    x[c] = (mine[i * 3 + c] * g).toDouble()
                    val p = phone[i * 3 + c]
                    if (p < 4f || p > 250f || x[c] < 2.0 || x[c] > 252.0) clipped = true
                }
                // Cells at the very ends of the range say less (their true value may lie beyond),
                // but in a scene of strong colours they are most of what there is.
                val wt = if (clipped) CLIPPED_WEIGHT else 1.0
                x[3] = 255.0
                for (r in 0..3) for (c in 0..3) xtx[r][c] += wt * x[r] * x[c]
                for (ch in 0..2) for (c in 0..3) xty[ch][c] += wt * phone[i * 3 + ch] * x[c]
                used += wt
            }
            if (used < n / 32.0) return Look(gain, PLAIN.colour)
            val hold = HOLD * used * 255.0 * 255.0
            val colour = FloatArray(12)
            for (ch in 0..2) {
                val m = Array(4) { r -> DoubleArray(5) { c -> if (c < 4) xtx[r][c] + (if (r == c) hold else 0.0) else xty[ch][r] + (if (r == ch) hold else 0.0) } }
                val solved = solve(m) ?: return Look(gain, PLAIN.colour)
                for (c in 0..2) colour[ch * 4 + c] = solved[c].toFloat()
                colour[ch * 4 + 3] = (solved[3] * 255.0).toFloat()
            }
            return Look(gain, colour)
        }

        private const val HOLD = 0.0005
        private const val CLIPPED_WEIGHT = 0.3

        /**
         * How far two pictures of the same scene are apart (coarse grids as from [coarse]):
         * the average difference per colour value, 0..255. Used to check a developed photo
         * against the phone's own picture before trusting it.
         */
        fun distance(a: FloatArray, b: FloatArray): Float {
            val n = minOf(a.size, b.size)
            if (n == 0) return Float.MAX_VALUE
            var sum = 0.0
            for (i in 0 until n) sum += kotlin.math.abs(a[i] - b[i])
            return (sum / n).toFloat()
        }

        /** A developed photo further than this from the phone's own picture isn't trusted. */
        const val TRUSTED_WITHIN = 16f

        /** Solves the 4×4 system in the augmented matrix [m]; null if it can't be. */
        private fun solve(m: Array<DoubleArray>): DoubleArray? {
            for (col in 0..3) {
                var pivot = col
                for (r in col + 1..3) if (kotlin.math.abs(m[r][col]) > kotlin.math.abs(m[pivot][col])) pivot = r
                if (kotlin.math.abs(m[pivot][col]) < 1e-9) return null
                val t = m[col]; m[col] = m[pivot]; m[pivot] = t
                for (r in 0..3) {
                    if (r == col) continue
                    val f = m[r][col] / m[col][col]
                    for (c in col..4) m[r][c] -= f * m[col][c]
                }
            }
            return DoubleArray(4) { m[it][4] / m[it][it] }
        }
    }
}
