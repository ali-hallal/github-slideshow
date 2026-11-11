package com.anycam.app.core

/**
 * One camera frame in the form every Android camera can deliver: brightness ([y], one byte per
 * pixel) and colour ([u], [v], one byte per 2×2 pixels). [w] and [h] are even.
 */
class Yuv(val w: Int, val h: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray) {
    val cw get() = w / 2
    val ch get() = h / 2

    init {
        require(w % 2 == 0 && h % 2 == 0) { "even size expected: $w x $h" }
        require(y.size == w * h && u.size == cw * ch && v.size == cw * ch) { "plane sizes don't match $w x $h" }
    }

    /** NV21 bytes (brightness, then V and U interleaved): what Android's JPEG encoder takes. */
    fun toNv21(): ByteArray {
        val out = ByteArray(w * h + 2 * cw * ch)
        System.arraycopy(y, 0, out, 0, w * h)
        var o = w * h
        for (i in 0 until cw * ch) {
            out[o++] = v[i]
            out[o++] = u[i]
        }
        return out
    }
}
