package com.procam.app

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Fighting hand-shake blur.
 *
 * 1. [plan]: Auto's shutter may be slower than shaking hands can hold (indoors, evening, zoomed).
 *    Then the shot is taken with a faster shutter and higher ISO; if that's a lot more ISO,
 *    a few such shots are merged so the extra noise goes away again.
 * 2. [keep]: in a burst, the gyroscope says how much the phone moved during each frame's
 *    exposure; frames that moved too much are dropped before merging.
 */
object Steady {

    class Plan(val iso: Int, val exposureNs: Long, val burst: Boolean)

    /** ISO raised more than this: merge a burst to take the extra noise away. */
    const val BURST_ABOVE = 1.4f
    /** Blur (px of a 12 MP photo) that still looks sharp. */
    const val SHARP_PX = 1.5f

    /**
     * Exposure for a sharp shot, or null if auto exposure's shutter ([aeExposureNs]) is already
     * fast enough for the hands ([safeNs]).
     */
    fun plan(aeIso: Int, aeExposureNs: Long, safeNs: Long, isoRange: android.util.Range<Int>, exposureRange: android.util.Range<Long>): Plan? {
        if (aeExposureNs <= safeNs * 1.15) return null
        val product = aeIso.toDouble() * aeExposureNs
        var t = max(safeNs, exposureRange.lower).coerceAtMost(exposureRange.upper)
        var iso = (product / t).roundToInt()
        if (iso > isoRange.upper) {
            // Can't go that bright: as fast as the ISO allows (still faster than auto).
            iso = isoRange.upper
            t = (product / iso).toLong().coerceIn(exposureRange.lower, exposureRange.upper)
        }
        iso = iso.coerceIn(isoRange.lower, isoRange.upper)
        if (t >= aeExposureNs) return null
        return Plan(iso, t, iso >= aeIso * BURST_ABOVE)
    }

    /**
     * Which frames to keep, from each frame's blur in pixels (null = unknown, kept): those within
     * twice the steadiest frame's blur (and anything under [SHARP_PX]); at least [minKeep],
     * steadiest first if more had to be added. Indices in original order.
     */
    fun keep(blurPx: List<Float?>, minKeep: Int): List<Int> {
        val known = blurPx.withIndex().filter { it.value != null }
        if (known.size < 2) return blurPx.indices.toList()
        val best = known.minOf { it.value!! }
        val limit = max(SHARP_PX, best * 2f)
        val kept = blurPx.indices.filter { blurPx[it] == null || blurPx[it]!! <= limit }.toMutableList()
        if (kept.size < minKeep) {
            for (i in blurPx.indices.sortedBy { blurPx[it] ?: 0f }) {
                if (kept.size >= minKeep) break
                if (i !in kept) kept += i
            }
        }
        return kept.sorted()
    }
}
