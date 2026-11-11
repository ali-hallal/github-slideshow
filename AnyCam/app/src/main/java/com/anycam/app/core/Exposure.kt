package com.anycam.app.core

import kotlin.math.roundToInt

/**
 * How to expose the shots of a burst.
 *
 * Left alone, a camera in dim light uses a slow shutter (1/15 s, 1/8 s...) to keep noise down,
 * and hands and people blur. A burst that will be merged can do better: each shot is taken
 * faster, at a higher sensitivity — sharper and noisier — and the merge takes the noise back
 * out. The brightness stays what auto exposure chose.
 */
object Exposure {

    class Plan(val iso: Int, val exposureNs: Long, val frames: Int)

    /**
     * [aeIso], [aeExposureNs]: what auto exposure is using now. [isoMin]..[isoMax] and
     * [exposureMin]..[exposureMax]: what the sensor can do. Null if auto exposure's own shutter
     * is already fast enough (the shots are then taken as auto exposure has them).
     */
    fun plan(aeIso: Int, aeExposureNs: Long, isoMin: Int, isoMax: Int, exposureMin: Long, exposureMax: Long): Plan? {
        if (aeIso <= 0 || aeExposureNs <= FAST_ENOUGH_NS) return null
        val light = aeIso.toDouble() * aeExposureNs
        // Up to [MAX_SPEED_UP] times faster, but no faster than needed.
        var t = (aeExposureNs / MAX_SPEED_UP).coerceAtLeast(FAST_ENOUGH_NS).coerceIn(exposureMin, exposureMax)
        var iso = (light / t).roundToInt()
        // The top of a sensor's range is grain no merge cleans: stop at a usable sensitivity
        // (or where auto exposure already is) and give the rest back to the shutter.
        val top = maxOf(minOf(isoMax, USABLE_ISO), minOf(isoMax, aeIso))
        if (iso > top) {
            iso = top
            t = (light / iso).toLong().coerceIn(exposureMin, exposureMax)
        }
        iso = iso.coerceIn(isoMin, isoMax)
        if (t >= aeExposureNs) return null
        return Plan(iso, t, Geometry.burstFrames(iso))
    }

    /** A shutter this fast (1/60 s) holds steady in the hand: nothing to gain below it. */
    const val FAST_ENOUGH_NS = 16_666_667L
    private const val MAX_SPEED_UP = 4L
    const val USABLE_ISO = 3200
}
