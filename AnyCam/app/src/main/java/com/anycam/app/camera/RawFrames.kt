package com.anycam.app.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import com.anycam.app.core.RawFrame
import com.anycam.app.core.RawInfo
import com.anycam.app.core.Shading
import java.nio.ByteOrder

/** Copies a sensor frame out of the camera's buffer (rows may be padded). */
fun Image.toRawFrame(): RawFrame {
    val w = width and 1.inv()
    val h = height and 1.inv()
    val out = ShortArray(w * h)
    val plane = planes[0]
    val shorts = plane.buffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
    val rowShorts = plane.rowStride / 2
    for (r in 0 until h) {
        shorts.position(r * rowShorts)
        shorts.get(out, r * w, w)
    }
    return RawFrame(w, h, out)
}

/**
 * What the camera says about a sensor frame: its filter layout, black and white readings,
 * white balance, colour matrix and lens fall-off map. All of it comes from the phone for the
 * shot in question; where a phone leaves something out, a neutral value stands in (the look
 * fitted from the phone's own picture then makes up the difference).
 */
fun rawInfo(c: CameraCharacteristics, result: TotalCaptureResult?): RawInfo {
    val cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
    val dynamic = result?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
    val pattern = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
    val black = FloatArray(4) { pos ->
        dynamic?.getOrNull(pos) ?: pattern?.getOffsetForIndex(pos % 2, pos / 2)?.toFloat() ?: 0f
    }
    val white = (result?.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
    val gains = result?.get(CaptureResult.COLOR_CORRECTION_GAINS)
    val wb = if (gains != null) floatArrayOf(gains.red, (gains.greenEven + gains.greenOdd) / 2f, gains.blue) else floatArrayOf(2f, 1f, 1.6f)
    // Gains are relative: green is the reference.
    val green = wb[1].takeIf { it > 0f } ?: 1f
    for (i in 0..2) wb[i] = (wb[i] / green).coerceIn(0.2f, 8f)
    val transform = result?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
    val ccm = if (transform != null) FloatArray(9) { transform.getElement(it % 3, it / 3).toFloat() } else RawInfo.IDENTITY.copyOf()
    val map = result?.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
    val shading = if (map != null && map.columnCount >= 2 && map.rowCount >= 2) {
        val g = FloatArray(map.columnCount * map.rowCount * 3)
        for (row in 0 until map.rowCount) for (col in 0 until map.columnCount) {
            val o = (row * map.columnCount + col) * 3
            g[o] = map.getGainFactor(0, col, row)
            g[o + 1] = (map.getGainFactor(1, col, row) + map.getGainFactor(2, col, row)) / 2f
            g[o + 2] = map.getGainFactor(3, col, row)
        }
        Shading(map.columnCount, map.rowCount, g)
    } else null
    // The sensor's stated noise for this shot (one pair per filter position; they differ little).
    val profile = result?.get(CaptureResult.SENSOR_NOISE_PROFILE)
    val noiseS = profile?.takeIf { it.isNotEmpty() }?.map { it.first.toFloat() }?.average()?.toFloat()
    val noiseO = profile?.takeIf { it.isNotEmpty() }?.map { it.second.toFloat() }?.average()?.toFloat()
    return RawInfo(cfa, black, white, wb, ccm, shading, noiseS, noiseO)
}
