package com.procam.app

import android.graphics.BitmapFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How big a photo can be processed with the memory the app has: full resolution when it fits,
 * otherwise as large as fits (phones differ a lot: 256 MB to 1 GB+ for one app).
 */
object Budget {

    /** Bytes the app can still allocate, roughly. */
    fun available(): Long {
        val rt = Runtime.getRuntime()
        return rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
    }

    /**
     * Longest side to process a [w]×[h] photo at, when processing needs [bytesPerPixel] of working
     * memory: the full size if it fits in 70 % of [available], else scaled down to fit — but never
     * below [floor] (the old fixed size, which every supported phone handles).
     */
    fun maxSide(w: Int, h: Int, bytesPerPixel: Int, available: Long = available(), floor: Int = FLOOR): Int {
        val longest = max(w, h)
        if (longest <= 0) return floor
        val allowedPixels = available * 0.7 / bytesPerPixel
        val s = min(1.0, sqrt(allowedPixels / (w.toDouble() * h)))
        return max(min(longest, floor), (longest * s).toInt())
    }

    /** Width and height of a JPEG/HEIF from its header (no decoding). */
    fun size(bytes: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return Pair(o.outWidth, o.outHeight)
    }

    /** Longest side to decode [bytes] at for processing needing [bytesPerPixel]. */
    fun sideFor(bytes: ByteArray, bytesPerPixel: Int): Int {
        val (w, h) = size(bytes)
        return maxSide(w, h, bytesPerPixel)
    }

    const val FLOOR = 3000
}
