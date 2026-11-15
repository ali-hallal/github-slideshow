package com.anycam.app.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Measuring with the camera, two ways that work on every phone:
 *
 * - **Against a known object** ([Plane]): the four corners of something of known size (a bank
 *   card, a sheet of A4) are marked in a photo. That fixes how the flat surface it lies on maps to
 *   the picture, even seen at an angle, so any two points on that surface can be measured.
 * - **By aiming** ([Aim]): from the phone's height above the floor and how far it tilts down,
 *   the distance to the point it aims at on the floor; from there, heights and floor lengths.
 */
object Measure {
    /** Things of a size known everywhere, width × height in millimetres. */
    enum class Reference(val label: String, val wMm: Double, val hMm: Double) {
        CARD("Bank card", 85.60, 53.98),
        A4("A4 sheet", 297.0, 210.0),
        LETTER("Letter sheet", 279.4, 215.9),
    }

    /**
     * The surface a known rectangle lies on. [corners]: its four corners in the picture (x, y
     * pairs), in order around it, starting from either end of a long side.
     */
    class Plane(corners: DoubleArray, ref: Reference) {
        private val toMm: DoubleArray

        init {
            require(corners.size == 8)
            // The marked corners may go round either way and start at any corner: take the side
            // from the first to the second as the long one if it looks longer in the picture.
            val side01 = hypot(corners[2] - corners[0], corners[3] - corners[1]) + hypot(corners[6] - corners[4], corners[7] - corners[5])
            val side12 = hypot(corners[4] - corners[2], corners[5] - corners[3]) + hypot(corners[0] - corners[6], corners[1] - corners[7])
            val (w, h) = if (side01 >= side12) ref.wMm to ref.hMm else ref.hMm to ref.wMm
            toMm = homography(corners, doubleArrayOf(0.0, 0.0, w, 0.0, w, h, 0.0, h))
        }

        /** A point of the picture, on the surface, in millimetres. */
        fun mm(x: Double, y: Double): DoubleArray {
            val m = toMm
            val d = m[6] * x + m[7] * y + 1.0
            return doubleArrayOf((m[0] * x + m[1] * y + m[2]) / d, (m[3] * x + m[4] * y + m[5]) / d)
        }

        /** The distance between two points of the picture, on the surface, in millimetres. */
        fun distance(x1: Double, y1: Double, x2: Double, y2: Double): Double {
            val a = mm(x1, y1)
            val b = mm(x2, y2)
            return hypot(a[0] - b[0], a[1] - b[1])
        }
    }

    /** The 3×3 mapping (last entry 1) taking four points [from] to four points [to]. */
    fun homography(from: DoubleArray, to: DoubleArray): DoubleArray {
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = from[2 * i]; val y = from[2 * i + 1]
            val u = to[2 * i]; val v = to[2 * i + 1]
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        // Gaussian elimination with partial pivoting.
        for (c in 0 until 8) {
            val p = (c until 8).maxByOrNull { abs(a[it][c]) }!!
            require(abs(a[p][c]) > 1e-12) { "the four corners don't make a shape (three in a line?)" }
            val t = a[c]; a[c] = a[p]; a[p] = t
            for (r in 0 until 8) if (r != c) {
                val f = a[r][c] / a[c][c]
                if (f != 0.0) for (k in c..8) a[r][k] -= f * a[c][k]
            }
        }
        return DoubleArray(8) { a[it][8] / a[it][it] }
    }

    /**
     * Measuring by aiming, from [heightM]: the camera's height above the floor. Angles are
     * how far the camera looks below the horizon ([down], radians; negative = above it) and
     * which way it faces ([heading], radians, any fixed reference).
     */
    object Aim {
        /** Looking at least this far down (about 2°) to reach the floor at a sensible distance. */
        const val MIN_DOWN = 0.035

        /** How far along the floor to the point aimed at, or null if aiming at or above the horizon. */
        fun distance(heightM: Double, down: Double): Double? =
            if (down < MIN_DOWN) null else heightM / tan(down)

        /** The height of the point aimed at ([down]) above the floor, on something standing [distanceM] away. */
        fun heightAt(heightM: Double, distanceM: Double, down: Double): Double = heightM - distanceM * tan(down)

        /** Where the point aimed at lies on the floor (metres, relative to the phone), or null. */
        fun floorPoint(heightM: Double, down: Double, heading: Double): DoubleArray? {
            val d = distance(heightM, down) ?: return null
            return doubleArrayOf(d * sin(heading), d * cos(heading))
        }

        /**
         * How far the camera looks below the horizon, and its heading, from the phone's
         * orientation ([r]: the 3×3 rotation matrix Android's rotation sensors give, row by row).
         * The back camera looks out of the back of the phone (its −z axis).
         */
        fun angles(r: FloatArray): DoubleArray {
            // The world direction of the device's -z axis: minus the third column.
            val x = -r[2].toDouble()
            val y = -r[5].toDouble()
            val z = -r[8].toDouble()
            val down = -kotlin.math.asin(z.coerceIn(-1.0, 1.0))
            val heading = atan2(x, y)
            return doubleArrayOf(down, heading)
        }

        fun length(a: DoubleArray, b: DoubleArray) = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]))
    }

    /** "12.3 cm", "1.25 m". */
    fun label(mm: Double): String = when {
        mm < 1000 -> String.format(java.util.Locale.US, "%.1f cm", mm / 10)
        else -> String.format(java.util.Locale.US, "%.2f m", mm / 1000)
    }
}
