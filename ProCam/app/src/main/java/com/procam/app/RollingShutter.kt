package com.procam.app

/**
 * Rolling-shutter correction. Phone sensors read the picture out row by row over ~10–30 ms; if
 * the phone turns meanwhile, each row is shifted a little differently (straight lines lean or
 * wobble), so two frames can't line up everywhere at once. The gyroscope says how far the phone
 * had turned when each row was read, so each row can be put back where it belongs before frames
 * are merged.
 */
object RollingShutter {

    /**
     * Per-row correction (dx, dy interleaved, one pair per row of [height]) from [readoutPath]:
     * picture displacement (px) at evenly spaced moments from the first row's readout to the
     * last's. Rows are referred to the middle row (which stays put).
     */
    fun rowShifts(readoutPath: FloatArray, height: Int): FloatArray {
        val n = readoutPath.size / 2
        val out = FloatArray(2 * height)
        if (n < 2 || height < 2) return out
        fun at(f: Float, axis: Int): Float {
            val p = f * (n - 1)
            val i = p.toInt().coerceIn(0, n - 2)
            val t = p - i
            return readoutPath[2 * i + axis] * (1 - t) + readoutPath[2 * (i + 1) + axis] * t
        }
        val mx = at(0.5f, 0)
        val my = at(0.5f, 1)
        for (r in 0 until height) {
            val f = r.toFloat() / (height - 1)
            out[2 * r] = at(f, 0) - mx
            out[2 * r + 1] = at(f, 1) - my
        }
        return out
    }

    /** Largest correction in [shifts] (px): below a pixel there's nothing to correct. */
    fun extent(shifts: FloatArray): Float {
        var m = 0f
        for (v in shifts) m = maxOf(m, kotlin.math.abs(v))
        return m
    }
}
