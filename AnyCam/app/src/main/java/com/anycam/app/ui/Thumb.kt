package com.anycam.app.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import com.anycam.app.core.Yuv

/** A small picture of a camera frame, for the thumbnail. */
object Thumb {
    /** About [size] pixels on its short side, turned [rotation] degrees clockwise. */
    fun of(f: Yuv, rotation: Int, size: Int): Bitmap {
        val step = (minOf(f.w, f.h) / size).coerceAtLeast(1) and 1.inv() or 1
        val tw = f.w / step
        val th = f.h / step
        val px = IntArray(tw * th)
        for (ty in 0 until th) for (tx in 0 until tw) {
            val x = tx * step
            val y = ty * step
            val yy = f.y[y * f.w + x].toInt() and 0xFF
            val ci = (y / 2) * f.cw + x / 2
            val u = (f.u[ci].toInt() and 0xFF) - 128
            val v = (f.v[ci].toInt() and 0xFF) - 128
            val r = (yy + 1.402f * v).toInt().coerceIn(0, 255)
            val g = (yy - 0.344136f * u - 0.714136f * v).toInt().coerceIn(0, 255)
            val b = (yy + 1.772f * u).toInt().coerceIn(0, 255)
            px[ty * tw + tx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bmp = Bitmap.createBitmap(px, tw, th, Bitmap.Config.ARGB_8888)
        if (rotation == 0) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, tw, th, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    }
}
