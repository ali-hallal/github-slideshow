package com.procam.app

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Send diagnostics": a plain-text report of the phone, every camera and what ProCam did
 * recently (its own log lines: exposure plans, processing times, lens detection...). No photos,
 * no location. Saved to Downloads; the user chooses whether to share it.
 */
object Diagnostics {

    fun build(context: Context): String = buildString {
        val pm = context.packageManager
        val version = try {
            pm.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        appendLine("ProCam diagnostics · ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}")
        appendLine("App version: $version")
        appendLine()
        appendLine("== Phone ==")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) appendLine("Chip: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        appendLine("CPU cores: ${Runtime.getRuntime().availableProcessors()}")
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        appendLine("RAM: ${mem.totalMem / (1024 * 1024)} MB · app memory limit ${am?.largeMemoryClass ?: 0} MB")
        appendLine()
        appendLine("== Cameras ==")
        append(cameras(context))
        appendLine()
        appendLine("== Settings ==")
        val prefs = context.getSharedPreferences("procam", Context.MODE_PRIVATE)
        prefs.all.toSortedMap().forEach { (k, v) -> appendLine("$k = $v") }
        appendLine()
        appendLine("== Recent ProCam log ==")
        append(recentLog())
    }

    private fun cameras(context: Context): String = buildString {
        val manager = context.getSystemService(CameraManager::class.java) ?: return@buildString
        for (id in manager.cameraIdList) {
            try {
                val c = manager.getCameraCharacteristics(id)
                val caps = CameraCaps.from(id, c, manager)
                val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                    else -> "?"
                }
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                fun maxSize(fmt: Int) = map?.getOutputSizes(fmt)?.maxByOrNull { it.width.toLong() * it.height }?.toString() ?: "-"
                val physical = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) c.physicalCameraIds.joinToString(",") else ""
                appendLine("Camera $id: ${if (caps.isFront) "front" else "back"} · level $level · sensor ${caps.sensorOrientation}°")
                appendLine("  manual exposure=${caps.manualSensor} RAW=${caps.raw} OIS=${caps.ois} flash=${caps.hasFlash}")
                appendLine("  zoom ${caps.zoomRange} (ratio API ${caps.zoomRatioApi}) · lens stops ${caps.lensStops} · multi-camera ${caps.multiCamera} [$physical]")
                appendLine("  ISO ${caps.isoRange} · shutter ${caps.exposureRange} ns · focal ${caps.focalLength} mm · f/${caps.aperture} · sensor width ${caps.sensorWidthMm} mm")
                appendLine("  slow motion: ${caps.slowMotion?.let { "${it.first.width}x${it.first.height} @ ${it.second} fps" } ?: "not offered"}")
                try {
                    val map = c.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    map?.highSpeedVideoSizes?.forEach { sz -> appendLine("    high speed $sz: ${map.getHighSpeedVideoFpsRangesFor(sz).joinToString()}") }
                } catch (_: Exception) {
                }
                appendLine("  faces mode ${caps.faceDetectMode} · face priority ${caps.facePriorityScene} · min focus ${caps.minFocusDistance}")
                appendLine("  max JPEG ${maxSize(ImageFormat.JPEG)} · max YUV ${maxSize(ImageFormat.YUV_420_888)} · max RAW ${maxSize(ImageFormat.RAW_SENSOR)}")
            } catch (e: Exception) {
                appendLine("Camera $id: error ${e.message}")
            }
        }
    }

    /** This app's own recent log lines (apps may read their own logs). */
    private fun recentLog(): String = try {
        val proc = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}", "-t", "1500")
            .redirectErrorStream(true).start()
        val keep = Regex("ProCam|SuperZoom|Night|Portrait|GroupShutter|Review|AndroidRuntime|CameraDevice|CameraCaptureSession")
        proc.inputStream.bufferedReader().readLines().filter { keep.containsMatchIn(it) }.takeLast(600).joinToString("\n")
    } catch (e: Exception) {
        "(log unavailable: ${e.message})"
    }

    /** Saves the report to Downloads/ProCam and returns its address for sharing. */
    fun save(context: Context, report: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "procam-diagnostics-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/ProCam")
        }
        return try {
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray()) }
            uri
        } catch (e: Exception) {
            null
        }
    }
}
