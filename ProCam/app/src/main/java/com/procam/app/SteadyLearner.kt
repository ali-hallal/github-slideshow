package com.procam.app

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Learns, on the phone in the user's hand, how a turn of the phone moves the picture.
 *
 * Steadying the viewfinder from the gyroscope needs to know three things that differ from phone
 * to phone: how much of a turn the lens's own stabilisation already takes out, how old the
 * picture is by the time it is shown, and how the gyroscope's axes map onto the screen. Rather
 * than assume them, they are measured: small copies of successive viewfinder frames are compared
 * to see how far the picture really moved ([measure]), and that movement is fitted against what
 * the gyroscope reported over the same interval — once for each of several possible ages of the
 * picture ([CLOCKS]). The best-fitting age and its 2×2 turn→picture matrix are what the
 * steadying then uses. A least-squares fit can only reduce the movement it was fitted to, so a
 * poor fit steadies less, never shakes more.
 *
 * Not thread safe: one thread adds samples; [model] and [clock] may be read from any.
 */
class SteadyLearner {

    /**
     * One viewfinder frame. [gray] is a small grey copy ([w]×[h]); [sx], [sy] = screen pixels per
     * copy pixel; [radPerPx] = the turn that one screen pixel stands for; [turns] = the phone's
     * accumulated turn (x, y; rad) at the frame's moment by each of [CLOCKS] (NaN = unknown).
     */
    class Sample(val gray: IntArray, val w: Int, val h: Int, val sx: Float, val sy: Float, val radPerPx: Float, val turns: FloatArray, val arrivalNs: Long)

    /** Picture movement (screen px) per expected movement (px): m00, m01, m10, m11. Null until learnt. */
    @Volatile var model: FloatArray? = null
        private set
    /** Index into [CLOCKS] of the best-fitting picture age; -1 until learnt. */
    @Volatile var clock = -1
        private set
    /** Pairs of frames learnt from so far (for deciding how often to look). */
    @Volatile var pairs = 0
        private set

    private var prev: Sample? = null
    private val k = CLOCKS.size
    // Per clock: weighted sums for the fit D = M·u (u = expected movement, D = measured).
    private val n = DoubleArray(k)
    private val sxx = DoubleArray(k); private val sxy = DoubleArray(k); private val syy = DoubleArray(k)
    private val dxx = DoubleArray(k); private val dxy = DoubleArray(k) // Σ Dx·ux, Σ Dx·uy
    private val dyx = DoubleArray(k); private val dyy = DoubleArray(k) // Σ Dy·ux, Σ Dy·uy
    private val sdd = DoubleArray(k)

    fun reset() {
        prev = null
        model = null
        clock = -1
        pairs = 0
        for (a in arrayOf(n, sxx, sxy, syy, dxx, dxy, dyx, dyy, sdd)) a.fill(0.0)
    }

    /** Start from what was learnt before (it keeps being checked against the picture). */
    fun restore(m: FloatArray, clock: Int) {
        if (m.size != 4 || clock !in CLOCKS.indices) return
        model = m.copyOf().also { limit(it) }
        this.clock = clock
    }

    /** Forget the last frame (zoom changed, viewfinder paused): the next one starts a new pair. */
    fun breakPair() {
        prev = null
    }

    fun add(s: Sample) {
        val p = prev
        prev = s
        if (p == null || p.w != s.w || p.h != s.h || p.radPerPx != s.radPerPx) return
        if (s.arrivalNs - p.arrivalNs > MAX_PAIR_NS) return
        val d = measure(p.gray, s.gray, s.w, s.h) ?: return
        val mx = d[0] * s.sx
        val my = d[1] * s.sy
        for (c in 0 until k) {
            val ux = (s.turns[2 * c] - p.turns[2 * c]) / s.radPerPx
            val uy = (s.turns[2 * c + 1] - p.turns[2 * c + 1]) / s.radPerPx
            if (ux.isNaN() || uy.isNaN()) continue
            n[c] = n[c] * FORGET + 1
            sxx[c] = sxx[c] * FORGET + ux * ux; sxy[c] = sxy[c] * FORGET + ux * uy; syy[c] = syy[c] * FORGET + uy * uy
            dxx[c] = dxx[c] * FORGET + mx * ux; dxy[c] = dxy[c] * FORGET + mx * uy
            dyx[c] = dyx[c] * FORGET + my * ux; dyy[c] = dyy[c] * FORGET + my * uy
            sdd[c] = sdd[c] * FORGET + mx * mx + my * my
        }
        pairs++
        solve()
    }

    private fun solve() {
        var best = -1
        var bestLeft = Double.MAX_VALUE
        var bestM: FloatArray? = null
        var currentLeft = Double.MAX_VALUE
        var currentM: FloatArray? = null
        for (c in 0 until k) {
            if (n[c] < MIN_PAIRS) continue
            val det = sxx[c] * syy[c] - sxy[c] * sxy[c]
            val weakest = ((sxx[c] + syy[c]) - sqrt((sxx[c] - syy[c]) * (sxx[c] - syy[c]) + 4 * sxy[c] * sxy[c])) / 2
            // The phone must have turned both ways enough to tell (screen px per frame, weakest direction).
            if (det <= 0 || weakest / n[c] < MIN_MOVE * MIN_MOVE) continue
            val m00 = (dxx[c] * syy[c] - dxy[c] * sxy[c]) / det
            val m01 = (dxy[c] * sxx[c] - dxx[c] * sxy[c]) / det
            val m10 = (dyx[c] * syy[c] - dyy[c] * sxy[c]) / det
            val m11 = (dyy[c] * sxx[c] - dyx[c] * sxy[c]) / det
            val left = (sdd[c] - (m00 * dxx[c] + m01 * dxy[c] + m10 * dyx[c] + m11 * dyy[c])).coerceAtLeast(0.0)
            // How surely the matrix is known (standard error of its entries).
            if (sqrt(left / (2 * n[c] * weakest)) > MAX_ERROR) continue
            val perPair = left / n[c]
            val m = floatArrayOf(m00.toFloat(), m01.toFloat(), m10.toFloat(), m11.toFloat())
            if (perPair < bestLeft) { bestLeft = perPair; best = c; bestM = m }
            if (c == clock) { currentLeft = perPair; currentM = m }
        }
        if (best < 0) return // nothing sure enough right now: keep what was learnt
        // Stay with the current age unless another fits clearly better.
        var useClock = best
        var useM = bestM!!
        if (currentM != null && bestLeft > currentLeft * SWITCH) { useClock = clock; useM = currentM }
        limit(useM)
        val old = model
        model = if (old == null || useClock != clock) useM else FloatArray(4) { old[it] * 0.8f + useM[it] * 0.2f }
        clock = useClock
    }

    companion object {
        /** A candidate for when a frame was taken: its own timestamp plus an offset, or its arrival minus a delay. */
        class Clock(val stamped: Boolean, val offsetNs: Long)

        val CLOCKS: List<Clock> = (0L..30L step 10).map { Clock(true, it * 1_000_000L) } +
            (20L..160L step 10).map { Clock(false, -it * 1_000_000L) }

        /** Before anything is learnt: the frame's own timestamp ... */
        const val STAMP_CLOCK = 0
        /** ... or, where frames aren't on the gyroscope's clock, a typical delay (70 ms). */
        const val ARRIVAL_CLOCK = 9

        fun time(clock: Int, stampNs: Long, arrivalNs: Long): Long =
            CLOCKS[clock].let { (if (it.stamped) stampNs else arrivalNs) + it.offsetNs }

        /** Width of the grey copies of the viewfinder. */
        const val COPY_WIDTH = 128
        private const val FORGET = 0.985
        private const val MIN_PAIRS = 25.0
        private const val MIN_MOVE = 1.5
        private const val MAX_ERROR = 0.1
        private const val SWITCH = 0.9
        private const val MAX_PAIR_NS = 110_000_000L
        /** Never steady by more than this many times the expected movement. */
        private const val MAX_GAIN = 1.3f
        private const val COARSE_REACH = 4

        /** Scales [m] down if it would move the picture more than [MAX_GAIN] times the expected amount. */
        fun limit(m: FloatArray) {
            // Largest singular value of the 2×2 matrix.
            val a = m[0] * m[0] + m[1] * m[1] + m[2] * m[2] + m[3] * m[3]
            val det = m[0] * m[3] - m[1] * m[2]
            val top = sqrt((a + sqrt((a * a - 4 * det * det).coerceAtLeast(0f))) / 2)
            if (top > MAX_GAIN) for (i in 0..3) m[i] *= MAX_GAIN / top
        }

        /**
         * How far the picture moved from [a] to [b] (grey, [w]×[h]): (dx, dy) in pixels, such that
         * what was at (x, y) in [a] is at (x+dx, y+dy) in [b]. Null if it can't be told (no
         * texture, or moved further than the search reaches: about ±16 px).
         */
        fun measure(a: IntArray, b: IntArray, w: Int, h: Int): FloatArray? {
            if (w < 32 || h < 32) return null
            val w1 = w / 2; val h1 = h / 2
            val a1 = half(a, w, h); val b1 = half(b, w, h)
            val w2 = w1 / 2; val h2 = h1 / 2
            val a2 = half(a1, w1, h1); val b2 = half(b1, w1, h1)
            val grid = DoubleArray(9)
            val c = search(a2, b2, w2, h2, 0, 0, COARSE_REACH, null)
            if (abs(c[0]) == COARSE_REACH || abs(c[1]) == COARSE_REACH) return null
            val m = search(a1, b1, w1, h1, 2 * c[0], 2 * c[1], 1, null)
            val f = search(a, b, w, h, 2 * m[0], 2 * m[1], 1, grid)
            if (f[0] != 2 * m[0] || f[1] != 2 * m[1]) {
                // The best lies off-centre: centre the 3×3 costs on it for the sub-pixel step.
                search(a, b, w, h, f[0], f[1], 1, grid)
            }
            val best = grid[4]
            val curveX = grid[3] + grid[5] - 2 * best
            val curveY = grid[1] + grid[7] - 2 * best
            // Featureless (or one-directional) picture: no reliable answer.
            if (curveX < MIN_CURVE || curveY < MIN_CURVE) return null
            val subX = ((grid[3] - grid[5]) / (2 * curveX)).coerceIn(-1.0, 1.0)
            val subY = ((grid[1] - grid[7]) / (2 * curveY)).coerceIn(-1.0, 1.0)
            return floatArrayOf((f[0] + subX).toFloat(), (f[1] + subY).toFloat())
        }

        /** Mean squared difference per pixel that a one-pixel move must make to count as texture. */
        private const val MIN_CURVE = 4.0

        private fun half(src: IntArray, w: Int, h: Int): IntArray {
            val w2 = w / 2; val h2 = h / 2
            val out = IntArray(w2 * h2)
            for (y in 0 until h2) for (x in 0 until w2) {
                val i = 2 * y * w + 2 * x
                out[y * w2 + x] = (src[i] + src[i + 1] + src[i + w] + src[i + w + 1] + 2) shr 2
            }
            return out
        }

        /** Best whole-pixel move within [reach] of ([cx], [cy]); [grid] (if given, reach 1) gets the 3×3 costs. */
        private fun search(a: IntArray, b: IntArray, w: Int, h: Int, cx: Int, cy: Int, reach: Int, grid: DoubleArray?): IntArray {
            val inset = maxOf(abs(cx), abs(cy)) + reach + 1
            var bestX = cx; var bestY = cy
            var best = Double.MAX_VALUE
            for (dy in cy - reach..cy + reach) for (dx in cx - reach..cx + reach) {
                var sum = 0L
                var count = 0
                var y = inset
                while (y < h - inset) {
                    var i = y * w + inset
                    val end = y * w + w - inset
                    val o = dy * w + dx
                    while (i < end) {
                        val d = a[i] - b[i + o]
                        sum += d * d
                        i++
                    }
                    count += w - 2 * inset
                    y++
                }
                if (count <= 0) return intArrayOf(cx, cy)
                val cost = sum.toDouble() / count
                if (grid != null) grid[(dy - cy + 1) * 3 + (dx - cx + 1)] = cost
                if (cost < best) { best = cost; bestX = dx; bestY = dy }
            }
            return intArrayOf(bestX, bestY)
        }
    }
}
