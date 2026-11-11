package com.anycam.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The photo engine, checked on made-up scenes where the right answer is known. Runs on any computer. */
class CoreTest {
    private val size = 1024
    private val rnd = Random(5)

    /** A scene of small soft-edged blocks: texture a camera could resolve. */
    private val scene = FloatArray(size * size).also { sc ->
        val raw = FloatArray(size * size) { rnd.nextFloat() * 200 + 20 }
        for (y in 0 until size) for (x in 0 until size) {
            var s = 0f
            for (j in -2..2) for (i in -2..2) s += raw[(((y + j) / 6 * 6 + size) % size) * size + ((x + i) / 6 * 6 + size) % size]
            sc[y * size + x] = s / 25
        }
    }

    /** One shot of the scene: moved by ([ox], [oy]), with sensor [noise], and optionally a bright square at [mover]. */
    private fun shot(w: Int, h: Int, ox: Int, oy: Int, noise: Float, mover: Int? = null): Yuv {
        val y = ByteArray(w * h) { i ->
            val px = i % w
            val py = i / w
            var v = scene[((py + oy + 100) % size) * size + (px + ox + 100) % size]
            if (mover != null && px in mover until mover + 60 && py in 200 until 260) v = 240f
            (v + rnd.nextGaussian().toFloat() * noise).roundToInt().coerceIn(0, 255).toByte()
        }
        val u = ByteArray(w * h / 4) { (128 + rnd.nextGaussian() * noise).roundToInt().coerceIn(0, 255).toByte() }
        val v = ByteArray(w * h / 4) { (128 + rnd.nextGaussian() * noise).roundToInt().coerceIn(0, 255).toByte() }
        return Yuv(w, h, y, u, v)
    }

    private fun error(a: Yuv, b: Yuv, x0: Int = 60, x1: Int = a.w - 60, y0: Int = 60, y1: Int = a.h - 60): Double {
        var s = 0.0
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val d = (a.y[y * a.w + x].toInt() and 255) - (b.y[y * a.w + x].toInt() and 255)
            s += d * d
            n++
        }
        return sqrt(s / n)
    }

    @Test
    fun aBurstFromShakyHandsBecomesOneCleanPhoto() {
        val w = 640
        val h = 480
        for (noise in listOf(6f, 15f)) {
            // Hand movement between shots, up to 30 pixels.
            val moves = listOf(0 to 0, 3 to -2, -5 to 4, 17 to 9, -2 to -30, 1 to 1)
            val frames = moves.map { (ox, oy) -> shot(w, h, ox, oy, noise) }
            val result = Merge.merge(frames)
            val (rx, ry) = moves[result.reference]
            val truth = shot(w, h, rx, ry, 0f)
            val single = error(frames[result.reference], truth)
            val merged = error(result.photo, truth)
            assertTrue("noise $noise: merged $merged should be under half of one shot's $single", merged < single * 0.5)
            assertEquals("every shot was used", frames.size, result.used)
            var colour = 0.0
            for (i in result.photo.u.indices) colour += ((result.photo.u[i].toInt() and 255) - 128.0).pow(2)
            assertTrue("colour speckle goes too", sqrt(colour / result.photo.u.size) < noise * 0.5)
        }
    }

    @Test
    fun somethingMovingThroughLeavesNoGhosts() {
        val w = 640
        val h = 480
        val places = listOf(100, 180, 260, 340, 420)
        val frames = places.map { shot(w, h, 0, 0, 6f, it) }
        val result = Merge.merge(frames)
        // The square is where it was in the reference shot, and nowhere else.
        val truth = shot(w, h, 0, 0, 0f, places[result.reference])
        assertTrue("no ghosts where the square was in the other shots", error(result.photo, truth, 90, 480, 190, 270) < 6.5)
        assertTrue("and the rest is still cleaned", error(result.photo, truth) < 4.0)
    }

    @Test
    fun oneShotPassesThroughUntouched() {
        val frame = shot(320, 240, 0, 0, 5f)
        assertSame(frame, Merge.merge(listOf(frame)).photo)
    }

    @Test
    fun framesPackForTheJpegEncoder() {
        val f = Yuv(4, 2, ByteArray(8) { it.toByte() }, byteArrayOf(50, 51), byteArrayOf(70, 71))
        val nv21 = f.toNv21()
        assertEquals(12, nv21.size)
        // Brightness first, then V and U taking turns.
        assertEquals(listOf<Byte>(70, 50, 71, 51), nv21.drop(8))
    }

    @Test
    fun aTapLandsOnTheRightPartOfTheSensor() {
        // Back camera (sensor turned 90°): the top of the upright view is the sensor's left edge.
        assertEquals(0.1f to 0.5f, Geometry.viewToSensor(0.5f, 0.1f, 90, false))
        assertEquals(0.5f to 0.9f, Geometry.viewToSensor(0.1f, 0.5f, 90, false))
        // Front camera (270°, mirrored view).
        assertEquals(0.9f to 0.5f, Geometry.viewToSensor(0.5f, 0.1f, 270, true))
        // Upright photos however the phone is held.
        assertEquals(90, Geometry.photoRotation(90, 0, false))
        assertEquals(180, Geometry.photoRotation(90, 90, false))
        assertEquals(270, Geometry.photoRotation(270, 0, true))
        assertEquals(180, Geometry.photoRotation(270, 90, true))
    }

    @Test
    fun dimLightGetsFasterShotsNotBlur() {
        // Bright: auto exposure is already fast, nothing changes.
        assertTrue(Exposure.plan(100, 4_000_000L, 50, 6400, 100_000L, 1_000_000_000L) == null)
        // Dim room (ISO 400, 1/15 s): four times faster, same brightness, more shots.
        val room = Exposure.plan(400, 66_666_667L, 50, 6400, 100_000L, 1_000_000_000L)!!
        assertEquals(16_666_667L, room.exposureNs)
        assertEquals(1600, room.iso)
        assertTrue(room.frames >= Geometry.burstFrames(400))
        // Very dim (ISO 1600, 1/8 s): sensitivity stops where grain would win; the shutter gives the rest.
        val dark = Exposure.plan(1600, 125_000_000L, 50, 16000, 100_000L, 1_000_000_000L)!!
        assertEquals(Exposure.USABLE_ISO, dark.iso)
        assertEquals(1600.0 * 125_000_000L, dark.iso.toDouble() * dark.exposureNs, 1600.0 * 125_000_000L * 0.02)
        assertTrue(dark.exposureNs < 125_000_000L)
        // Auto exposure already at the sensor's limit: nothing to trade.
        assertTrue(Exposure.plan(3200, 100_000_000L, 50, 3200, 100_000L, 1_000_000_000L) == null)
    }

    @Test
    fun detailIsBroughtOutWithoutSharpeningGrain() {
        val w = 320
        val h = 240
        // A soft edge down the middle, and a flat noisy area either side.
        val clean = ByteArray(w * h) { i ->
            val x = i % w
            (when (x) { in 0..156 -> 80; 157 -> 84; 158 -> 96; 159 -> 120; 160 -> 144; 161 -> 156; else -> 160 }).toByte()
        }
        val noisy = ByteArray(w * h) { (((clean[it].toInt() and 255) + rnd.nextGaussian() * 0.7).roundToInt().coerceIn(0, 255)).toByte() }
        val grey = ByteArray(w * h / 4) { 128.toByte() }
        val before = Yuv(w, h, noisy, grey, grey)
        val after = Detail.sharpen(before, 0.7f)
        fun steepest(p: Yuv): Double {
            var s = 0.0
            for (y in 10 until h - 10) s += ((p.y[y * w + 160].toInt() and 255) - (p.y[y * w + 158].toInt() and 255))
            return s / (h - 20)
        }
        fun grain(p: Yuv): Double {
            var s = 0.0
            var n = 0
            for (y in 10 until h - 10) for (x in 10 until 140) {
                s += ((p.y[y * w + x].toInt() and 255) - 80.0).pow(2)
                n++
            }
            return sqrt(s / n)
        }
        assertTrue("the edge is steeper: ${steepest(before)} -> ${steepest(after)}", steepest(after) > steepest(before) * 1.1)
        assertTrue("flat areas no grainier: ${grain(before)} -> ${grain(after)}", grain(after) < grain(before) * 1.15)
        // No fringes: nothing brighter or darker than the picture already had.
        assertTrue(after.y.maxOf { it.toInt() and 255 } <= before.y.maxOf { it.toInt() and 255 })
        assertTrue(after.y.minOf { it.toInt() and 255 } >= before.y.minOf { it.toInt() and 255 })
        assertSame("colour is untouched", before.u, after.u)
    }

    @Test
    fun zoomButtonsAndBurstLengthFollowThePhone() {
        assertEquals(listOf(1f, 2f, 5f, 10f), Geometry.zoomStops(1f, 10f))
        assertEquals(listOf(0.6f, 1f, 2f, 5f), Geometry.zoomStops(0.6f, 8f))
        assertEquals(listOf(1f), Geometry.zoomStops(1f, 1f))
        // Dimmer scenes get more shots; a phone that doesn't say gets a middling burst.
        assertTrue(Geometry.burstFrames(100) < Geometry.burstFrames(400))
        assertTrue(Geometry.burstFrames(400) < Geometry.burstFrames(3200))
        assertEquals(4, Geometry.burstFrames(null))
        // Never more frames than memory holds, never fewer than one.
        assertEquals(7, Geometry.framesThatFit(7, 12_000_000L, 512L * 1024 * 1024))
        assertEquals(2, Geometry.framesThatFit(7, 50_000_000L, 512L * 1024 * 1024))
        assertEquals(1, Geometry.framesThatFit(7, 200_000_000L, 256L * 1024 * 1024))
    }
}
