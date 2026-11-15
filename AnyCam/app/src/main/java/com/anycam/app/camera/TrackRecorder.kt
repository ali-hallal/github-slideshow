package com.anycam.app.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A test recording for live 3D measuring: small grey camera frames, the gyroscope and
 * accelerometer at full rate, all with the exact times the phone gives them, plus everything
 * the camera reports about its lens. Saved as one zip in `Download/AnyCam`, to be run through
 * motion-tracking code off the phone and checked against a known length.
 */
class TrackRecorder(private val context: Context) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val imuThread = HandlerThread("anycam-imu").apply { start() }
    private val writer = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val imu = StringBuilder(1 shl 20)
    private val frames = StringBuilder(1 shl 16)
    private val meta = StringBuilder(1 shl 16)
    private var zip: ZipOutputStream? = null
    private var out: OutputStream? = null
    private var uri: Uri? = null
    @Volatile var running = false
        private set
    var frameCount = 0
        private set
    private var startedAt = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val kind = if (e.sensor.type == Sensor.TYPE_GYROSCOPE) 'g' else 'a'
            synchronized(imu) { imu.append(kind).append(',').append(e.timestamp).append(',').append(e.values[0]).append(',').append(e.values[1]).append(',').append(e.values[2]).append('\n') }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start(c: CameraCharacteristics?, previewSize: Size?, viewW: Int, viewH: Int, frameW: Int, frameH: Int) {
        if (running) return
        val name = "ANYCAM_TRACK_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".zip"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/AnyCam")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val u = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("can't save in Downloads")
        uri = u
        val o = context.contentResolver.openOutputStream(u) ?: throw IllegalStateException("can't write")
        out = o
        zip = ZipOutputStream(o.buffered(1 shl 16))
        imu.setLength(0); frames.setLength(0); meta.setLength(0)
        frames.append("timestamp_ns,file\n")
        meta.append("timestamp_ns,focus_diopters,exposure_ns,rolling_shutter_skew_ns,frame_duration_ns\n")
        imu.append("sensor,timestamp_ns,x,y,z\n")
        frameCount = 0
        startedAt = SystemClock.elapsedRealtime()
        writeText("camera.json", describe(c, previewSize, viewW, viewH, frameW, frameH))
        val h = Handler(imuThread.looper)
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST, h) }
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST, h) }
        running = true
    }

    /** One viewfinder frame ([timestampNs]: the camera's own time for it). Skipped if the last is still being written. */
    fun frame(bmp: Bitmap, timestampNs: Long) {
        if (!running || !busy.compareAndSet(false, true)) return
        val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
        writer.execute {
            try {
                val w = copy.width
                val h = copy.height
                val px = IntArray(w * h)
                copy.getPixels(px, 0, w, 0, 0, w, h)
                copy.recycle()
                // Grey (as the trackers work), stored as JPEG to keep the file small.
                val grey = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                for (i in px.indices) {
                    val p = px[i]
                    val y = ((p shr 16 and 255) * 77 + (p shr 8 and 255) * 150 + (p and 255) * 29) shr 8
                    px[i] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
                }
                grey.setPixels(px, 0, w, 0, 0, w, h)
                val file = "frames/$timestampNs.jpg"
                zip?.let { z ->
                    z.putNextEntry(ZipEntry(file))
                    grey.compress(Bitmap.CompressFormat.JPEG, 90, z)
                    z.closeEntry()
                }
                grey.recycle()
                frames.append(timestampNs).append(',').append(file).append('\n')
                frameCount++
            } catch (e: Exception) {
                Log.w("AnyCam", "track frame", e)
            } finally {
                busy.set(false)
            }
        }
    }

    /** What the camera reported for a frame (focus moves the lens, which changes its figures). */
    fun frameInfo(timestampNs: Long, focus: Float?, exposureNs: Long?, skewNs: Long?, durationNs: Long?) {
        if (!running) return
        synchronized(meta) { meta.append(timestampNs).append(',').append(focus ?: "").append(',').append(exposureNs ?: "").append(',').append(skewNs ?: "").append(',').append(durationNs ?: "").append('\n') }
    }

    /** Finishes the file; its address, or null if it failed. */
    fun stop(done: (Uri?, Int) -> Unit) {
        if (!running) return
        running = false
        sensors.unregisterListener(listener)
        val seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
        writer.execute {
            val u = uri
            val ok = try {
                synchronized(imu) { writeText("imu.csv", imu.toString()) }
                writeText("frames.csv", frames.toString())
                synchronized(meta) { writeText("frame_info.csv", meta.toString()) }
                zip?.close()
                true
            } catch (e: Exception) {
                Log.e("AnyCam", "track save", e)
                false
            }
            zip = null
            out = null
            if (u != null) {
                if (ok) context.contentResolver.update(u, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                else try { context.contentResolver.delete(u, null, null) } catch (_: Exception) {}
            }
            done(if (ok) u else null, seconds)
        }
    }

    private fun writeText(name: String, text: String) {
        val z = zip ?: return
        z.putNextEntry(ZipEntry(name))
        z.write(text.toByteArray())
        z.closeEntry()
    }

    private fun describe(c: CameraCharacteristics?, preview: Size?, viewW: Int, viewH: Int, frameW: Int, frameH: Int): String {
        fun arr(v: FloatArray?) = v?.joinToString(",", "[", "]") ?: "null"
        val sb = StringBuilder("{\n")
        fun put(k: String, v: Any?) = sb.append("  \"").append(k).append("\": ").append(v ?: "null").append(",\n")
        fun str(v: Any?) = v?.let { "\"" + it.toString().replace("\"", "'") + "\"" }
        put("device", str(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL))
        put("android", android.os.Build.VERSION.SDK_INT)
        put("preview_size", str(preview))
        put("view_size", str("${viewW}x$viewH"))
        put("frame_size", str("${frameW}x$frameH"))
        put("frames_note", str("frames are the viewfinder as shown upright (portrait): the sensor image turned by sensor_orientation, scaled to frame_size"))
        if (c != null) {
            put("sensor_orientation", c.get(CameraCharacteristics.SENSOR_ORIENTATION))
            put("timestamp_source", c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)?.let { if (it == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) "\"realtime\"" else "\"unknown\"" })
            put("intrinsics_fx_fy_cx_cy_s", arr(c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)))
            put("distortion", arr(c.get(CameraCharacteristics.LENS_DISTORTION)))
            put("pose_rotation", arr(c.get(CameraCharacteristics.LENS_POSE_ROTATION)))
            put("pose_translation", arr(c.get(CameraCharacteristics.LENS_POSE_TRANSLATION)))
            put("pose_reference", c.get(CameraCharacteristics.LENS_POSE_REFERENCE))
            put("focal_lengths_mm", arr(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)))
            put("sensor_physical_size_mm", str(c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)))
            put("pixel_array", str(c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)))
            put("active_array", str(c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)))
            put("focus_calibration", c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION))
            put("motion_tracking_capability", c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(10))
        }
        put("imu_clock", str("elapsedRealtimeNanos"))
        put("realtime_minus_monotonic_ns", SystemClock.elapsedRealtimeNanos() - System.nanoTime())
        sb.setLength(sb.length - 2)
        return sb.append("\n}\n").toString()
    }

    fun release() {
        sensors.unregisterListener(listener)
        imuThread.quitSafely()
        writer.shutdown()
    }
}
