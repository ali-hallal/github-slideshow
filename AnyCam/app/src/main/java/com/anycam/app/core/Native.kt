package com.anycam.app.core

/**
 * The heaviest steps of [Stack], compiled (src/main/cpp). Kotlin keeps doing the same work
 * wherever the compiled library isn't there, so it is never required: [ok] says whether it
 * loaded.
 */
object Native {
    val ok: Boolean = try {
        System.loadLibrary("anycam")
        true
    } catch (e: Throwable) {
        false
    }

    /** May be switched off (to compare with the Kotlin path, or if it ever misbehaves). */
    @Volatile var enabled = true

    val use get() = ok && enabled

    @JvmStatic external fun build(
        w: Int, h: Int, pw: Int, cols: Int, tileSize: Int,
        light: FloatArray, frames: Array<ShortArray>, trust: Array<ByteArray>, place: Array<IntArray>,
        busy: ByteArray, green: ShortArray, greenWeights: Array<FloatArray>, colourWeights: FloatArray,
        colourAt: IntArray, besideIsRed: BooleanArray, black: FloatArray, scale: Float, gain: FloatArray,
        shCols: Int, shRows: Int, shading: FloatArray?,
        ccm: FloatArray, gamma: FloatArray, lookGain: FloatArray, lookColour: FloatArray,
        fill: Float, busyFill: Float, steps: Int,
        outY: ByteArray, outU: ByteArray, outV: ByteArray,
    ): Double

    @JvmStatic external fun refine(
        w: Int, h: Int, cols: Int, rows: Int, size: Int, dx: IntArray, dy: IntArray, cost: FloatArray,
        ref: ShortArray, frame: ShortArray, greyOf: FloatArray, cap: Float, out: FloatArray,
    )

    /** The tile search of [Align.tiles] (before its neighbour check): fills dx, dy, cost, subX, subY. */
    @JvmStatic external fun tiles(
        refW: Int, refH: Int, ref: ByteArray, frameW: Int, frameH: Int, frame: ByteArray, gx: Int, gy: Int,
        tile: Int, reach: Int, cap: Int,
        dx: IntArray, dy: IntArray, cost: FloatArray, subX: FloatArray, subY: FloatArray,
    )

    @JvmStatic external fun trust(
        w: Int, h: Int, pw: Int, ph: Int, cols: Int, tileSize: Int, frameOk: Boolean, tileLimit: Float,
        refSoft: ByteArray, frameSoft: ByteArray, dx: IntArray, dy: IntArray, cost: FloatArray, exact: FloatArray,
        ref: ShortArray, frame: ShortArray, greyOf: FloatArray, sampleNoise: FloatArray, out: ByteArray,
    )
}
