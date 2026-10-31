package com.procam.app

import android.graphics.Rect
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.SizeF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.util.Range
import android.util.Rational
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class FlashMode(val label: String) {
    OFF("FLASH OFF"),
    AUTO("FLASH AUTO"),
    ON("FLASH ON"),
    TORCH("TORCH"),
}

enum class WhiteBalance(val label: String, val awbMode: Int) {
    AUTO("AWB", CameraMetadata.CONTROL_AWB_MODE_AUTO),
    DAYLIGHT("Daylight", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("Cloudy", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    SHADE("Shade", CameraMetadata.CONTROL_AWB_MODE_SHADE),
    TUNGSTEN("Tungsten", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT("Fluoresc.", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
    WARM_FLUORESCENT("Warm Fl.", CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT),
    TWILIGHT("Twilight", CameraMetadata.CONTROL_AWB_MODE_TWILIGHT),
}

/** Everything the user can dial in. Immutable: the UI makes copies and hands them to the camera thread. */
data class ShotSettings(
    /** PRO mode. Within PRO, ISO and/or shutter can still be left on auto (shutter / ISO priority). */
    val manualExposure: Boolean = false,
    val isoAuto: Boolean = false,
    val shutterAuto: Boolean = false,
    val iso: Int = 100,
    val exposureNs: Long = 16_666_667L,
    val evIndex: Int = 0,
    val manualFocus: Boolean = false,
    val focusDiopters: Float = 0f,
    val wb: WhiteBalance = WhiteBalance.AUTO,
    val flash: FlashMode = FlashMode.OFF,
    val raw: Boolean = false,
    val zoom: Float = 1f,
) {
    /** True when the camera's own auto exposure should run (AUTO mode, or PRO with both ISO and shutter on auto). */
    val cameraAe get() = !manualExposure || (isoAuto && shutterAuto)

    /** Shutter priority or ISO priority: one value fixed, the other driven by ProCam's own meter. */
    val priorityMode get() = manualExposure && (isoAuto != shutterAuto)
}

/** Output options that need the camera session to be rebuilt when they change. */
data class OutputPrefs(
    val aspect169: Boolean = false,
    val maxResolution: Boolean = true,
    val heif: Boolean = false,
    val jpegQuality: Int = 95,
    val mirrorFront: Boolean = false,
)

/** What a given camera can do. */
data class CameraCaps(
    val id: String,
    val facing: Int,
    val sensorOrientation: Int,
    val manualSensor: Boolean,
    val raw: Boolean,
    val isoRange: Range<Int>?,
    val exposureRange: Range<Long>?,
    val minFocusDistance: Float,
    val evRange: Range<Int>,
    val evStep: Rational,
    val hasFlash: Boolean,
    val afModes: IntArray,
    val awbModes: IntArray,
    val activeArray: Rect,
    val maxAfRegions: Int,
    val maxAeRegions: Int,
    val aperture: Float?,
    val focalLength: Float?,
    /** Zoom ratio range. Below 1 means an ultra-wide lens is reachable through zoom. */
    val zoomRange: Range<Float>,
    /** True if zoom uses CONTROL_ZOOM_RATIO (Android 11+, switches physical lenses); false = crop zoom. */
    val zoomRatioApi: Boolean,
    /** Zoom ratios of the phone's real lenses (e.g. 0.5, 1, 3, 5), shown as lens buttons. */
    val lensStops: List<Float>,
    /** Best face-detection mode the camera offers (OFF if none). */
    val faceDetectMode: Int,
    /** Camera can prioritise faces for focus/exposure/white balance by itself. */
    val facePriorityScene: Boolean,
) {
    val isFront get() = facing == CameraCharacteristics.LENS_FACING_FRONT
    val canManualFocus get() = manualSensor && minFocusDistance > 0f

    companion object {
        fun from(id: String, c: CameraCharacteristics, manager: CameraManager): CameraCaps {
            val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val ratioRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            } else null
            val zoomRange = ratioRange
                ?: Range(1f, (c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).coerceAtLeast(1f))
            return CameraCaps(
                id = id,
                facing = c.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK,
                sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                manualSensor = capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
                raw = capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW),
                isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                minFocusDistance = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
                evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: Rational(1, 3),
                hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0),
                awbModes = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0),
                activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000),
                maxAfRegions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
                maxAeRegions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
                aperture = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull(),
                focalLength = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull(),
                zoomRange = zoomRange,
                zoomRatioApi = ratioRange != null,
                lensStops = lensStops(c, manager, zoomRange),
                faceDetectMode = (c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) ?: IntArray(0))
                    .maxOrNull() ?: CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF,
                facePriorityScene = (c.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES) ?: IntArray(0))
                    .contains(CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY),
            )
        }

        /** Finds the zoom ratio of each physical lens behind a logical multi-camera. */
        private fun lensStops(c: CameraCharacteristics, manager: CameraManager, range: Range<Float>): List<Float> {
            val stops = sortedSetOf(1f)
            val main = fieldOfViewScale(c)
            if (main != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                for (pid in c.physicalCameraIds) {
                    val physical = try {
                        manager.getCameraCharacteristics(pid)
                    } catch (e: Exception) {
                        continue
                    }
                    val scale = fieldOfViewScale(physical) ?: continue
                    val ratio = roundStop(scale / main)
                    if (ratio >= range.lower - 0.01f && ratio <= range.upper + 0.01f) stops += ratio
                }
            }
            if (stops.size == 1 && range.upper >= 2f) stops += 2f
            return stops.toList()
        }

        /** Focal length relative to sensor diagonal: proportional to how "zoomed in" a lens is. */
        private fun fieldOfViewScale(c: CameraCharacteristics): Float? {
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
            val size: SizeF = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            val diagonal = kotlin.math.sqrt(size.width * size.width + size.height * size.height)
            return if (diagonal > 0f) focal / diagonal else null
        }

        private fun roundStop(r: Float): Float = when {
            r < 0.95f -> (r * 10).roundToInt() / 10f
            r < 1.15f -> 1f
            else -> (r * 2).roundToInt() / 2f
        }
    }
}

/** Photographic stop tables and label formatting. */
object Stops {
    private val ISO = intArrayOf(
        25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250,
        1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600,
    )

    private val SHUTTER_SECONDS = doubleArrayOf(
        1 / 16000.0, 1 / 12800.0, 1 / 10000.0, 1 / 8000.0, 1 / 6400.0, 1 / 5000.0, 1 / 4000.0,
        1 / 3200.0, 1 / 2500.0, 1 / 2000.0, 1 / 1600.0, 1 / 1250.0, 1 / 1000.0, 1 / 800.0,
        1 / 640.0, 1 / 500.0, 1 / 400.0, 1 / 320.0, 1 / 250.0, 1 / 200.0, 1 / 160.0, 1 / 125.0,
        1 / 100.0, 1 / 80.0, 1 / 60.0, 1 / 50.0, 1 / 40.0, 1 / 30.0, 1 / 25.0, 1 / 20.0, 1 / 15.0,
        1 / 13.0, 1 / 10.0, 1 / 8.0, 1 / 6.0, 1 / 5.0, 1 / 4.0, 0.3, 0.4, 0.5, 0.6, 0.8, 1.0, 1.3,
        1.6, 2.0, 2.5, 3.2, 4.0, 5.0, 6.0, 8.0, 10.0, 13.0, 15.0, 20.0, 25.0, 30.0,
    )

    fun isoSteps(range: Range<Int>?): List<Int> {
        if (range == null) return listOf(100)
        val list = ISO.filter { it > range.lower && it < range.upper }.toMutableList()
        list.add(0, range.lower)
        list.add(range.upper)
        return list.distinct()
    }

    fun shutterSteps(range: Range<Long>?): List<Long> {
        if (range == null) return listOf(16_666_667L)
        return SHUTTER_SECONDS.map { (it * 1e9).toLong() }
            .filter { it >= range.lower && it <= range.upper }
            .ifEmpty { listOf(range.lower, range.upper) }
    }

    fun evSteps(range: Range<Int>): List<Int> = (range.lower..range.upper).toList().ifEmpty { listOf(0) }

    fun shutterLabel(ns: Long): String {
        val s = ns / 1e9
        return when {
            s >= 0.3 && abs(s - s.roundToInt()) < 0.05 -> "${s.roundToInt()}\""
            s >= 0.3 -> String.format(Locale.US, "%.1f\"", s)
            else -> "1/${(1 / s).roundToInt()}"
        }
    }

    fun evLabel(index: Int, step: Rational): String {
        val v = index * step.toFloat()
        return if (abs(v) < 0.05f) "±0" else String.format(Locale.US, "%+.1f", v)
    }

    fun zoomLabel(z: Float): String = when {
        abs(z - z.roundToInt()) < 0.05f -> "${z.roundToInt()}×"
        else -> String.format(Locale.US, "%.1f×", z)
    }

    fun focusLabel(diopters: Float): String = when {
        diopters <= 0.01f -> "∞"
        1f / diopters < 1f -> "${(100f / diopters).roundToInt()}cm"
        1f / diopters > 20f -> "∞"
        else -> String.format(Locale.US, "%.1fm", 1f / diopters)
    }

    fun <T : Comparable<T>> nearestIndex(list: List<T>, value: T, distance: (T, T) -> Double): Int {
        if (list.isEmpty()) return 0
        return list.indices.minByOrNull { distance(list[it], value) } ?: 0
    }
}
