package com.anycam.app.camera

import android.media.Image
import com.anycam.app.core.Yuv
import java.nio.ByteBuffer

/** Copies a camera frame out of the camera's buffers (rows may be padded, colour samples interleaved). */
fun Image.toYuv(): Yuv {
    val w = width and 1.inv()
    val h = height and 1.inv()
    val y = ByteArray(w * h)
    copyRows(planes[0].buffer, planes[0].rowStride, 1, w, h, y)
    val cw = w / 2
    val ch = h / 2
    val u = ByteArray(cw * ch)
    val v = ByteArray(cw * ch)
    copyRows(planes[1].buffer, planes[1].rowStride, planes[1].pixelStride, cw, ch, u)
    copyRows(planes[2].buffer, planes[2].rowStride, planes[2].pixelStride, cw, ch, v)
    return Yuv(w, h, y, u, v)
}

private fun copyRows(src: ByteBuffer, rowStride: Int, pixelStride: Int, w: Int, h: Int, out: ByteArray) {
    val buffer = src.duplicate()
    if (pixelStride == 1) {
        for (r in 0 until h) {
            buffer.position(r * rowStride)
            buffer.get(out, r * w, w)
        }
        return
    }
    // Interleaved samples: read the row, keep every [pixelStride]-th byte. (The last row can be
    // shorter than the others: buffers end at the last sample.)
    val row = ByteArray((w - 1) * pixelStride + 1)
    for (r in 0 until h) {
        buffer.position(r * rowStride)
        val n = minOf(row.size, buffer.remaining())
        buffer.get(row, 0, n)
        var i = 0
        val o = r * w
        for (c in 0 until w) {
            out[o + c] = row[i]
            i += pixelStride
        }
    }
}
