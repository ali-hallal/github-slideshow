package com.anycam.app.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Range
import android.util.Rational

/** What one camera can do, read from what the phone reports about it (nothing assumed). */
class CameraInfo(
    val id: String,
    val front: Boolean,
    val sensorOrientation: Int,
    val activeArray: Rect,
    /** Zoom the phone handles itself (switching lenses where it has several), or null on older phones. */
    val zoomRatio: Range<Float>?,
    /** Zoom by cropping, always available. */
    val maxCropZoom: Float,
    val evRange: Range<Int>,
    val evStep: Rational,
    val hasFlash: Boolean,
    val continuousFocus: Boolean,
    val touchFocus: Boolean,
    val focusRegions: Boolean,
    val meterRegions: Boolean,
    val canLockExposure: Boolean,
    val canLockWhiteBalance: Boolean,
    /** The sensor takes an ISO and shutter time directly (null where the phone only does automatic). */
    val isoRange: Range<Int>?,
    val exposureRange: Range<Long>?,
) {
    val minZoom get() = zoomRatio?.lower ?: 1f
    val maxZoom get() = zoomRatio?.upper ?: maxCropZoom

    companion object {
        fun from(id: String, c: CameraCharacteristics): CameraInfo {
            val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
            val manual = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
            val zoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
            return CameraInfo(
                id = id,
                front = c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT,
                sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 0, 0),
                zoomRatio = zoomRatio,
                maxCropZoom = (c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).coerceAtLeast(1f),
                evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
                evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: Rational(1, 3),
                hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                continuousFocus = afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE),
                touchFocus = afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO),
                focusRegions = (c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0,
                meterRegions = (c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0,
                canLockExposure = c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true,
                canLockWhiteBalance = c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true,
                isoRange = if (manual) c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) else null,
                exposureRange = if (manual) c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) else null,
            )
        }
    }
}
