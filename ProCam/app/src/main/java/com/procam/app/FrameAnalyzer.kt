package com.procam.app

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.pow

/**
 * Analyses small snapshots of the viewfinder: histogram, average brightness (for the
 * exposure meter and shutter/ISO priority), zebra stripes and focus peaking.
 */
class FrameAnalyzer {

    val bins = 64
    val red = IntArray(bins)
    val green = IntArray(bins)
    val blue = IntArray(bins)
    val luma = IntArray(bins)

    /** Mean scene brightness in linear light (0..1). */
    var meanLinear = 0f
        private set

    /** Zebra/peaking marks, same size as the analysed frame; transparent where nothing to show. */
    var overlay: Bitmap? = null
        private set

    private var pixels = IntArray(0)
    private var lumaPx = IntArray(0)
    private var overlayPx = IntArray(0)

    /**
     * @param zebraThreshold 8-bit luma at or above which zebra stripes are drawn, or 0 for off.
     * @param peaking draw focus peaking on sharp edges.
     * @return true if [overlay] has marks to draw.
     */
    fun analyze(src: Bitmap, zebraThreshold: Int, peaking: Boolean, phase: Int): Boolean {
        val w = src.width
        val h = src.height
        val n = w * h
        if (pixels.size != n) {
            pixels = IntArray(n)
            lumaPx = IntArray(n)
            overlayPx = IntArray(n)
        }
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        red.fill(0); green.fill(0); blue.fill(0); luma.fill(0)
        var sum = 0.0
        for (i in 0 until n) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val l = (r * 77 + g * 150 + b * 29) shr 8
            lumaPx[i] = l
            red[r shr 2]++
            green[g shr 2]++
            blue[b shr 2]++
            luma[l shr 2]++
            sum += LINEAR[l]
        }
        meanLinear = (sum / n).toFloat()

        if (zebraThreshold <= 0 && !peaking) return false

        var marks = false
        overlayPx.fill(0)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val l = lumaPx[i]
                if (zebraThreshold > 0 && l >= zebraThreshold) {
                    if (((x + y + phase) / 4) % 2 == 0) {
                        overlayPx[i] = ZEBRA_COLOR
                        marks = true
                    }
                } else if (peaking) {
                    val grad = abs(lumaPx[i + 1] - lumaPx[i - 1]) + abs(lumaPx[i + w] - lumaPx[i - w])
                    if (grad > PEAKING_THRESHOLD) {
                        overlayPx[i] = PEAKING_COLOR
                        marks = true
                    }
                }
            }
        }
        var bmp = overlay
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            overlay = bmp
        }
        bmp.setPixels(overlayPx, 0, w, 0, 0, w, h)
        return marks
    }

    companion object {
        private const val ZEBRA_COLOR = 0xC0000000.toInt()
        private const val PEAKING_COLOR = 0xFFFF2D55.toInt()
        private const val PEAKING_THRESHOLD = 64

        /** sRGB 8-bit to linear light. */
        private val LINEAR = FloatArray(256) { i ->
            val c = i / 255f
            if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }
    }
}
