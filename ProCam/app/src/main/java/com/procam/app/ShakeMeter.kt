package com.procam.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        val w = sqrt(x * x + y * y + z * z)
        val dt = if (lastTimestamp == 0L) 0.02f else ((event.timestamp - lastTimestamp) / 1e9f).coerceIn(0.001f, 0.2f)
        lastTimestamp = event.timestamp
        // Rises fast (a jolt shows at once), settles over ~0.3 s.
        val tau = if (w > omega) 0.08f else 0.3f
        omega += (dt / (tau + dt)) * (w - omega)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    companion object {
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
