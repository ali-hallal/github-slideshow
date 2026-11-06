package com.procam.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.exp

/**
 * Steadies the viewfinder at high zoom. At 10× a hand tremor of a fraction of a degree throws
 * the picture across the screen. The gyroscope says how far the phone turned; the preview is
 * shifted the opposite way by the same number of pixels, so the subject stays put. Slow,
 * deliberate movement (framing, panning) fades through after a moment.
 *
 * Phone held upright (portrait), back camera. The preview is enlarged by [MARGIN] so there is
 * picture to spare at the edges for the shift.
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
            if (!value) onShift(0f, 0f)
        }
    /** Radians per preview pixel at the current zoom. */
    var radPerPx = 0f
    /** Largest shift allowed (px), from the spare picture at the edges. */
    var maxShiftX = 0f
    var maxShiftY = 0f

    private var ax = 0f // accumulated pitch (around the phone's x axis), radians
    private var ay = 0f // accumulated yaw (around its y axis)
    private var last = 0L

    val available get() = gyro != null

    fun start() {
        gyro?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
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
        onShift(
            (-ay / radPerPx).coerceIn(-maxShiftX, maxShiftX),
            (-ax / radPerPx).coerceIn(-maxShiftY, maxShiftY),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** Preview enlargement while steadying (spare picture for the shift). */
        const val MARGIN = 1.12f
        /** Movement lasting longer than about this (s) is treated as deliberate and let through. */
        const val TAU = 0.6f

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
