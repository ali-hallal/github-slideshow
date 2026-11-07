package com.procam.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Measures how much the user's hands are moving (gyroscope) and turns it into the slowest
 * shutter speed that will still give a sharp photo.
 */
class ShakeMeter(context: Context) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyro: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var lastTimestamp = 0L

    val available get() = gyro != null

    /** Smoothed rotation speed in rad/s. */
    @Volatile
    var omega = 0f
        private set

    fun start() {
        val g = gyro ?: return
        lastTimestamp = 0L
        sensors?.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensors?.unregisterListener(this)
        omega = 0f
    }

    // Recent gyro samples (timestamp ns, rotation speed across the picture rad/s) for per-frame blur.
    private val historyTs = LongArray(HISTORY)
    private val historyW = FloatArray(HISTORY)
    // The same samples per axis (rad/s, device axes), for the blur path.
    private val historyX = FloatArray(HISTORY)
    private val historyY = FloatArray(HISTORY)
    private var historyN = 0
    private var historyHead = 0

    /**
     * How far the phone turned (radians, across the picture) between [startNs] and
     * [startNs] + [durationNs] (camera timestamps, same clock as the gyroscope), or null if the
     * gyroscope history doesn't cover that time.
     */
    @Synchronized
    fun angleDuring(startNs: Long, durationNs: Long): Float? {
        if (historyN < 2) return null
        val end = startNs + max(durationNs, 1L)
        val ts = LongArray(historyN) { historyTs[(historyHead - historyN + it + HISTORY) % HISTORY] }
        val ws = FloatArray(historyN) { historyW[(historyHead - historyN + it + HISTORY) % HISTORY] }
        if (ts.first() > startNs || ts.last() < startNs) return null
        return turnedDuring(ts, ws, startNs, end)
    }

    /**
     * How the phone turned during [startNs]..[startNs]+[durationNs]: [steps]+1 points of the
     * accumulated turn (radians about the device's x and y axes, interleaved x0,y0,x1,y1…), or
     * null if the gyroscope history doesn't cover that time.
     */
    @Synchronized
    fun pathDuring(startNs: Long, durationNs: Long, steps: Int): FloatArray? {
        if (historyN < 2 || steps < 1) return null
        val ts = LongArray(historyN) { historyTs[(historyHead - historyN + it + HISTORY) % HISTORY] }
        if (ts.first() > startNs || ts.last() < startNs) return null
        val xs = FloatArray(historyN) { historyX[(historyHead - historyN + it + HISTORY) % HISTORY] }
        val ys = FloatArray(historyN) { historyY[(historyHead - historyN + it + HISTORY) % HISTORY] }
        val out = FloatArray(2 * (steps + 1))
        val dur = max(durationNs, 1L)
        for (k in 1..steps) {
            val a = startNs + dur * (k - 1) / steps
            val b = startNs + dur * k / steps
            out[2 * k] = out[2 * k - 2] + turnedDuring(ts, xs, a, b)
            out[2 * k + 1] = out[2 * k - 1] + turnedDuring(ts, ys, a, b)
        }
        return out
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        synchronized(this) {
            // Turning about the x/y axes moves the picture sideways; a twist (z) blurs the edges, about half as much.
            historyTs[historyHead] = event.timestamp
            historyW[historyHead] = sqrt(x * x + y * y) + 0.5f * kotlin.math.abs(z)
            historyX[historyHead] = x
            historyY[historyHead] = y
            historyHead = (historyHead + 1) % HISTORY
            if (historyN < HISTORY) historyN++
        }
        val w = sqrt(x * x + y * y + z * z)
        val dt = if (lastTimestamp == 0L) 0.02f else ((event.timestamp - lastTimestamp) / 1e9f).coerceIn(0.001f, 0.2f)
        lastTimestamp = event.timestamp
        // Rises fast (a jolt shows at once), settles over ~0.3 s.
        val tau = if (w > omega) 0.08f else 0.3f
        omega += (dt / (tau + dt)) * (w - omega)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    companion object {
        /** About 4 s of gyro samples at the game rate. */
        private const val HISTORY = 256

        /**
         * Integral of rotation speed [ws] (sampled at [ts]) over [start, end]: piecewise constant
         * between samples. Pure maths, for tests.
         */
        fun turnedDuring(ts: LongArray, ws: FloatArray, start: Long, end: Long): Float {
            var angle = 0.0
            for (i in ts.indices) {
                val a = ts[i]
                val b = if (i + 1 < ts.size) ts[i + 1] else end
                val lo = max(a, start)
                val hi = min(b, end)
                if (hi > lo) angle += ws[i] * (hi - lo) / 1e9
            }
            return angle.toFloat()
        }

        /** Radians per pixel of a 12 MP-wide photo (the scale blur is judged at). */
        fun radPerPx(focalMm: Float, sensorWidthMm: Float, zoom: Float): Float =
            if (focalMm <= 0f || sensorWidthMm <= 0f) 1.2f / REFERENCE_WIDTH_PX / zoom.coerceAtLeast(0.1f)
            else sensorWidthMm / (focalMm * zoom.coerceAtLeast(0.1f)) / REFERENCE_WIDTH_PX

        /** Blur we accept, in pixels of a 12 MP (4000 px wide) photo. */
        private const val BLUR_PX = 2f
        private const val REFERENCE_WIDTH_PX = 4000f
        /** Below this the phone is on a tripod or resting on something. */
        const val TRIPOD_OMEGA = 0.006f
        const val MAX_SAFE_NS = 30_000_000_000L

        /**
         * Longest exposure (ns) that keeps hand-shake blur under ~2 px.
         * Optical stabilisation (OIS) buys about two stops.
         */
        fun safeShutterNs(omega: Float, focalMm: Float, sensorWidthMm: Float, zoom: Float, ois: Boolean): Long {
            if (focalMm <= 0f || sensorWidthMm <= 0f) return MAX_SAFE_NS
            val radPerPx = sensorWidthMm / (focalMm * zoom.coerceAtLeast(0.1f)) / REFERENCE_WIDTH_PX
            val maxAngle = BLUR_PX * radPerPx * if (ois) 4f else 1f
            if (omega < 1e-5f) return MAX_SAFE_NS
            return (maxAngle / omega * 1e9).toLong().coerceAtMost(MAX_SAFE_NS)
        }
    }
}
