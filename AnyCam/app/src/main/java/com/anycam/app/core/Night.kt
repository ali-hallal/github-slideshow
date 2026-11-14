package com.anycam.app.core

/**
 * How much to brighten a photo taken in the dark.
 *
 * Measured on the reference shot's light (0 = black, 1 = full, white balanced): where the
 * middle of the picture sits far below a normal photo's, it is raised towards [TARGET] — a
 * night picture that looks like night but shows what was there — by at most [MOST] (three
 * stops; more would only show noise). Scenes that are lit normally are left as they are.
 */
object Night {
    /** The middle of the picture, as light, after lifting (about 60 of 255 on screen). */
    const val TARGET = 0.045f
    /** Lifting starts only below this (about 30 of 255 on screen). */
    const val DARK = 0.015f
    const val MOST = 8f

    fun lift(m: RawMerge.Merged): Float {
        val mid = middle(m)
        if (mid <= 0f || mid >= DARK) return 1f
        return (TARGET / mid).coerceIn(1f, MOST)
    }

    /** [m] made [times] brighter in place, as if developed with [RawInfo.brighter] (light stops at full). */
    fun brighten(m: RawMerge.Merged, times: Float) {
        val l = m.light
        for (i in l.indices) { val v = l[i] * times; l[i] = if (v > 1f) 1f else v }
    }

    /** The median light of the picture (sampled sparsely: a few hundred thousand values). */
    fun middle(m: RawMerge.Merged): Float {
        val step = maxOf(1, (m.w.toLong() * m.h / 200_000L).toInt())
        val values = ArrayList<Float>(220_000)
        var i = 0
        val total = m.w * m.h
        while (i < total) {
            val x = i % m.w
            val y = i / m.w
            values += m.light[(y + 2) * m.stride + x + 2]
            i += step
        }
        if (values.isEmpty()) return 0f
        values.sort()
        return values[values.size / 2]
    }
}
