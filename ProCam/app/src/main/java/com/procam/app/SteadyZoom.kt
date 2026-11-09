package com.procam.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.tanh

/**
 * Steadies the viewfinder at high zoom. At 10× a hand tremor of a fraction of a degree throws
 * the picture across the screen. The gyroscope says how far the phone turned; the preview is
 * shifted the opposite way by the same number of pixels, so the subject stays put. Slow,
 * deliberate movement (framing, panning) fades through after a moment.
 *
 * The picture on screen is always a little old (the camera, then the screen, take some tens of
 * milliseconds), and tremor changes direction several times a second: shifting an old picture
 * by where the phone points *now* shakes it more, not less. So every preview frame is shifted
 * by where the phone pointed at the moment that frame was taken ([shiftAt]).
 *
 * How a turn moves the picture ([model]) differs between phones — a lens with optical
 * stabilisation takes most of the tremor out by itself, for one. It starts from a sensible
 * guess and is then learnt from the viewfinder itself ([SteadyLearner]).
 *
 * The preview is enlarged by [margin] so there is picture to spare at the edges for the shift.
 */
class SteadyZoom(context: Context, private val onShift: (Float, Float) -> Unit) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyro: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** Steadying is on (high zoom, back camera). */
    var active = false
        set(value) {
            if (field == value) return
            field = value
            ax = 0f
            ay = 0f
            rx = 0f
            ry = 0f
            synchronized(ts) { n = 0 }
            if (!value) onShift(0f, 0f)
        }
    /** Radians per preview pixel at the current zoom. */
    var radPerPx = 0f
    /** Largest shift allowed (px), from the spare picture at the edges. */
    var maxShiftX = 0f
    var maxShiftY = 0f

    /** Share of the turn that reaches the picture (1 = no optical stabilisation): the guess until [model] is learnt. */
    @Volatile var gain = 1f
    /** Learnt: picture movement per expected movement (m00, m01, m10, m11; see [SteadyLearner]). */
    @Volatile var model: FloatArray? = null

    private var ax = 0f // accumulated pitch (around the phone's x axis), radians
    private var ay = 0f // accumulated yaw (around its y axis)
    private var last = 0L

    private var rx = 0f // the turn since steadying began, unfiltered (what the learner compares the picture with)
    private var ry = 0f

    // Recent accumulated turns, for looking up where the phone pointed when a frame was taken.
    private val ts = LongArray(HISTORY)
    private val hx = FloatArray(HISTORY)
    private val hy = FloatArray(HISTORY)
    private val hrx = FloatArray(HISTORY)
    private val hry = FloatArray(HISTORY)
    private var head = 0
    private var n = 0

    /**
     * The preview shift (px) for a frame taken at [timeNs] (gyroscope clock). Null if that moment
     * is not within the recent history (then the frame's clock is not the gyroscope's).
     */
    fun shiftAt(timeNs: Long): Pair<Float, Float>? {
        if (!active || radPerPx <= 0f) return null
        val (x, y) = synchronized(ts) { turnAt(ts, hx, hy, head, n, timeNs) } ?: return null
        val m = model
        return if (m == null) shift(x, y, radPerPx, gain, maxShiftX, maxShiftY) else shift(x, y, radPerPx, m, maxShiftX, maxShiftY)
    }

    /** The unfiltered turn (rad) the phone had made by [timeNs]; null outside the recent history. */
    fun turnAt(timeNs: Long): Pair<Float, Float>? =
        if (!active) null else synchronized(ts) { turnAt(ts, hrx, hry, head, n, timeNs) }

    val available get() = gyro != null

    fun start() {
        gyro?.let { sensors?.registerListener(this, it, SAMPLE_US) }
    }

    fun stop() {
        sensors?.unregisterListener(this)
        last = 0L
        ax = 0f
        ay = 0f
    }

    override fun onSensorChanged(e: SensorEvent) {
        val t = e.timestamp
        val dt = if (last == 0L) 0f else ((t - last) / 1e9f).coerceIn(0f, 0.1f)
        last = t
        if (!active || radPerPx <= 0f) return
        val (dx, dy) = step(ax, ay, e.values[0], e.values[1], dt)
        ax = dx
        ay = dy
        rx += e.values[0] * dt
        ry += e.values[1] * dt
        synchronized(ts) {
            ts[head] = t
            hx[head] = ax
            hy[head] = ay
            hrx[head] = rx
            hry[head] = ry
            head = (head + 1) % HISTORY
            if (n < HISTORY) n++
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** Preview enlargement while steadying (spare picture for the shift), at [MARGIN_FROM]× ... */
        const val MARGIN = 1.12f
        /** ... growing to this by [MARGIN_TO]×: the same tremor is a larger part of a narrower view. */
        const val MAX_MARGIN = 1.3f
        const val MARGIN_FROM = 4f
        const val MARGIN_TO = 16f

        /** Preview enlargement while steadying at this zoom (as the user sees it). */
        fun margin(zoom: Float): Float =
            MARGIN + (MAX_MARGIN - MARGIN) * ((zoom - MARGIN_FROM) / (MARGIN_TO - MARGIN_FROM)).coerceIn(0f, 1f)
        /** Movement lasting longer than about this (s) is treated as deliberate and let through. */
        const val TAU = 0.6f

        /** Gyroscope sampling (µs): 200 a second. */
        const val SAMPLE_US = 5000
        private const val HISTORY = 256
        /** A frame this much newer than the last gyroscope sample is not on the gyroscope's clock. */
        private const val MAX_AHEAD_NS = 150_000_000L

        /**
         * The accumulated turn at [timeNs], from a ring of [n] samples ending before [head]
         * (interpolated; the latest sample for a moment just after it). Null outside the history.
         */
        fun turnAt(ts: LongArray, xs: FloatArray, ys: FloatArray, head: Int, n: Int, timeNs: Long): Pair<Float, Float>? {
            if (n == 0) return null
            val size = ts.size
            val newest = (head - 1 + size) % size
            val oldest = (head - n + size) % size
            if (timeNs < ts[oldest] || timeNs > ts[newest] + MAX_AHEAD_NS) return null
            if (timeNs >= ts[newest]) return xs[newest] to ys[newest]
            var i = newest
            while (i != oldest) {
                val prev = (i - 1 + size) % size
                if (ts[prev] <= timeNs) {
                    val span = (ts[i] - ts[prev]).coerceAtLeast(1L)
                    val f = (timeNs - ts[prev]).toFloat() / span
                    return (xs[prev] + (xs[i] - xs[prev]) * f) to (ys[prev] + (ys[i] - ys[prev]) * f)
                }
                i = prev
            }
            return xs[oldest] to ys[oldest]
        }

        /** With optical stabilisation and no lens-shift reports: the lens is taken to remove 3/4 of the tremor. */
        const val OIS_GAIN = 0.25f
        /** Never less than this, whatever the lens reports. */
        const val MIN_GAIN = 0.1f

        /** Preview shift (px) that cancels the accumulated turn, as far as the spare picture allows. */
        fun shift(ax: Float, ay: Float, radPerPx: Float, gain: Float, maxX: Float, maxY: Float): Pair<Float, Float> =
            shift(ax, ay, radPerPx, floatArrayOf(0f, gain, gain, 0f), maxX, maxY)

        /** The same with a learnt [model] (picture movement per expected movement: m00, m01, m10, m11). */
        fun shift(ax: Float, ay: Float, radPerPx: Float, model: FloatArray, maxX: Float, maxY: Float): Pair<Float, Float> = Pair(
            soft(-(model[0] * ax + model[1] * ay) / radPerPx, maxX),
            soft(-(model[2] * ax + model[3] * ay) / radPerPx, maxY),
        )

        /**
         * Limits a shift to the spare picture gently: exact for small shifts, easing off towards
         * the limit, so the picture doesn't jerk when the spare picture runs out.
         */
        fun soft(v: Float, max: Float): Float {
            if (max <= 0f) return 0f
            val free = max * SOFT_FROM
            val a = abs(v)
            if (a <= free) return v
            val room = max - free
            return sign(v) * (free + room * tanh((a - free) / room))
        }
        private const val SOFT_FROM = 0.5f

        /**
         * One gyro sample: integrate the turn, then let it decay (a high-pass filter), so tremor is
         * cancelled but a deliberate pan carries the view along.
         * Returns the new accumulated (pitch, yaw).
         */
        fun step(ax: Float, ay: Float, wx: Float, wy: Float, dt: Float): Pair<Float, Float> {
            val k = exp(-dt / TAU)
            return Pair((ax + wx * dt) * k, (ay + wy * dt) * k)
        }
    }
}
