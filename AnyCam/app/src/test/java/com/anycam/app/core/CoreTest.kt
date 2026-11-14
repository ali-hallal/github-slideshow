package com.anycam.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
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
    fun theDarkGetsLongerShotsAndIsLifted() {
        // Auto exposure out of sensitivity at 1/14 s: a stabilised lens holds 1/5 s, brighter.
        val p = Exposure.night(12800, 71_000_000L, 100, 12800, 1_000_000_000L, stabilised = true)!!
        assertEquals(Exposure.NIGHT_STABILISED_NS, p.exposureNs)
        assertTrue(p.iso * p.exposureNs > 12800.0 * 71_000_000L * 2)
        assertTrue(p.frames >= 5)
        // Dark but within reach: the same light, at a lower sensitivity (less noise).
        val q = Exposure.night(3200, 66_000_000L, 100, 12800, 1_000_000_000L, stabilised = true)!!
        assertEquals(Exposure.NIGHT_STABILISED_NS / 2, q.exposureNs)
        assertTrue(q.iso < 3200)
        assertEquals(3200.0 * 66_000_000L, q.iso.toDouble() * q.exposureNs, 3200.0 * 66_000_000L * 0.02)
        // No steadying: no longer than 1/10 s.
        assertEquals(Exposure.NIGHT_NS, Exposure.night(12800, 71_000_000L, 100, 12800, 1_000_000_000L, stabilised = false)!!.exposureNs)
        // Not dark: nothing.
        assertNull(Exposure.night(400, 33_000_000L, 100, 12800, 1_000_000_000L, stabilised = true))
        assertNull(Exposure.night(3200, 20_000_000L, 100, 12800, 1_000_000_000L, stabilised = true))

        // A dark picture is raised towards the target, a normal one is left alone, never past the most.
        fun flat(v: Float) = RawMerge.Merged(40, 30, FloatArray(44 * 34) { v }, 0, 1, 0f)
        assertEquals(1f, Night.lift(flat(0.2f)), 0f)
        assertEquals(Night.TARGET / 0.01f, Night.lift(flat(0.01f)), 1e-3f)
        assertEquals(Night.MOST, Night.lift(flat(0.0005f)), 0f)
        val m = flat(0.3f); Night.brighten(m, 4f)
        assertEquals(1f, m.light[44 * 5 + 5], 0f)
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
        // Very dim (ISO 800, 1/8 s): sensitivity stops where grain would win; the shutter gives the rest.
        val dark = Exposure.plan(800, 125_000_000L, 50, 16000, 100_000L, 1_000_000_000L)!!
        assertEquals(Exposure.USABLE_ISO, dark.iso)
        assertEquals(800.0 * 125_000_000L, dark.iso.toDouble() * dark.exposureNs, 800.0 * 125_000_000L * 0.02)
        assertTrue(dark.exposureNs < 125_000_000L)
        // A stabilised lens holds 1/15 s: a dim room is shot as auto exposure has it.
        assertTrue(Exposure.plan(400, 66_666_667L, 50, 6400, 100_000L, 1_000_000_000L, stabilised = true) == null)
        // Darker still (1/8 s): only down to 1/15 s, not four times faster.
        val steadied = Exposure.plan(800, 125_000_000L, 50, 6400, 100_000L, 1_000_000_000L, stabilised = true)!!
        assertEquals(Exposure.STABILISED_FAST_ENOUGH_NS, steadied.exposureNs)
        assertEquals(1500, steadied.iso)
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

    // ------------------------------------------------------------------ sensor (RAW) bursts

    private val ccm = floatArrayOf(1.6f, -0.4f, -0.2f, -0.3f, 1.5f, -0.2f, -0.1f, -0.5f, 1.6f)
    private val wb = floatArrayOf(2.0f, 1.0f, 1.6f)
    private val black = 64f
    private val white = 1023f

    private fun inverse(m: FloatArray): FloatArray {
        val d = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        return floatArrayOf(
            (m[4] * m[8] - m[5] * m[7]) / d, (m[2] * m[7] - m[1] * m[8]) / d, (m[1] * m[5] - m[2] * m[4]) / d,
            (m[5] * m[6] - m[3] * m[8]) / d, (m[0] * m[8] - m[2] * m[6]) / d, (m[2] * m[3] - m[0] * m[5]) / d,
            (m[3] * m[7] - m[4] * m[6]) / d, (m[1] * m[6] - m[0] * m[7]) / d, (m[0] * m[4] - m[1] * m[3]) / d,
        )
    }

    /** A colourful true picture (red, green, blue 0..255 per pixel): smooth areas with soft edges. */
    private fun truePicture(w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 3
            val bx = (x / 40) % 5
            val by = (y / 40) % 4
            out[o] = 40f + 40f * bx + 15f * kotlin.math.sin(x / 9f)
            out[o + 1] = 60f + 45f * by + 15f * kotlin.math.cos(y / 11f)
            out[o + 2] = 200f - 30f * bx - 20f * by + 10f * kotlin.math.sin((x + y) / 13f)
        }
        // Soften, so edges are what a lens would give.
        val soft = out.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) for (c in 0..2) {
            var sum = 0f
            for (j in -1..1) for (i in -1..1) sum += out[((y + j) * w + x + i) * 3 + c]
            soft[(y * w + x) * 3 + c] = (sum / 9f).coerceIn(5f, 250f)
        }
        return soft
    }

    /** What the sensor records of [truth] moved by ([ox], [oy]): one filtered sample per pixel, with [noise]. */
    private fun sensorShot(truth: FloatArray, tw: Int, w: Int, h: Int, ox: Int, oy: Int, noise: Float, cfa: Int = 0): RawFrame {
        val inv = inverse(ccm)
        val info = RawInfo(cfa, FloatArray(4) { black }, white, wb, ccm)
        val px = ShortArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val o = ((y + oy + 20) * tw + x + ox + 20) * 3
            val lin = FloatArray(3) { c ->
                val v = truth[o + c] / 255f
                if (v <= 0.04045f) v / 12.92f else Math.pow((v + 0.055) / 1.055, 2.4).toFloat()
            }
            val c = info.colour((y and 1) * 2 + (x and 1))
            val cam = (inv[c * 3] * lin[0] + inv[c * 3 + 1] * lin[1] + inv[c * 3 + 2] * lin[2]) / wb[c]
            px[y * w + x] = (cam * (white - black) + black + rnd.nextGaussian().toFloat() * noise).roundToInt().coerceIn(0, 1023).toShort()
        }
        return RawFrame(w, h, px)
    }

    private fun pictureError(photo: Yuv, truth: FloatArray, tw: Int, ox: Int, oy: Int): Double {
        var s = 0.0
        var n = 0
        for (y in 16 until photo.h - 16) for (x in 16 until photo.w - 16) {
            val o = ((y + oy + 20) * tw + x + ox + 20) * 3
            val lum = 0.299f * truth[o] + 0.587f * truth[o + 1] + 0.114f * truth[o + 2]
            s += abs((photo.y[y * photo.w + x].toInt() and 255) - lum)
            n++
        }
        return s / n
    }

    @Test
    fun sensorBurstsDevelopIntoTheTruePicture() {
        val w = 320
        val h = 240
        val tw = 400
        val truth = truePicture(tw, 320)
        for (cfa in 0..3) {
            val info = RawInfo(cfa, FloatArray(4) { black }, white, wb, ccm)
            val moves = listOf(0 to 0, 2 to -4, -6 to 2, 3 to 1, 0 to 8)
            val shots = moves.map { (ox, oy) -> sensorShot(truth, tw, w, h, ox, oy, 12f, cfa) }
            val merged = RawMerge.merge(shots, info)
            val photo = Develop.develop(merged, info)
            val (rx, ry) = moves[merged.reference]
            val one = Develop.develop(RawMerge.merge(listOf(shots[merged.reference]), info), info)
            val errMerged = pictureError(photo, truth, tw, rx, ry)
            val errOne = pictureError(one, truth, tw, rx, ry)
            assertTrue("filter layout $cfa: merged $errMerged is close to the truth", errMerged < 4.0)
            assertTrue("filter layout $cfa: and cleaner than one shot ($errOne -> $errMerged)", errMerged < errOne * 0.75)
            // Colours come out right too (a red-filter mix-up would show here).
            var colour = 0.0
            var n = 0
            for (cy in 10 until photo.ch - 10) for (cx in 10 until photo.cw - 10) {
                val o = ((cy * 2 + ry + 20) * tw + cx * 2 + rx + 20) * 3
                val u = -0.168736f * truth[o] - 0.331264f * truth[o + 1] + 0.5f * truth[o + 2] + 128
                val v = 0.5f * truth[o] - 0.418688f * truth[o + 1] - 0.081312f * truth[o + 2] + 128
                colour += abs((photo.u[cy * photo.cw + cx].toInt() and 255) - u) + abs((photo.v[cy * photo.cw + cx].toInt() and 255) - v)
                n += 2
            }
            assertTrue("filter layout $cfa: colour error ${colour / n}", colour / n < 5.0)
        }
    }

    /**
     * Fine texture (cloth, print) with the shots a pixel off each other: laid on top of each
     * other they would blur it. The merge must keep what one shot shows.
     */
    @Test
    fun fineDetailSurvivesTheMerge() {
        val w = 480
        val h = 360
        val tw = 560
        // Threads three pixels wide at changing angles, and thin dark bars like small print.
        val grey = FloatArray(tw * 440) { i ->
            val x = i % tw
            val y = i / tw
            val t = if (((x / 48) + (y / 48)) % 2 == 0) kotlin.math.sin((x + 0.6f * y) * 2.1f) else kotlin.math.sin((y - 0.5f * x) * 2.1f)
            (150f + 28f * t + (if ((x / 3) % 7 == 0 && y % 96 < 20) -70f else 0f)).coerceIn(10f, 245f)
        }
        val truth = FloatArray(grey.size * 3) { grey[it / 3] }
        val noise = 3f
        // The sensor's own noise figure, as a camera reports it.
        val info = RawInfo(0, FloatArray(4) { black }, white, wb, ccm, null, 0f, (noise / (white - black)).pow(2))
        val moves = listOf(0 to 0, 1 to 0, 3 to -1, -2 to 5, 5 to 3)
        val shots = moves.map { (ox, oy) -> sensorShot(truth, tw, w, h, ox, oy, noise) }
        val merged = RawMerge.merge(shots, info)
        val (rx, ry) = moves[merged.reference]
        val one = Develop.develop(RawMerge.merge(listOf(shots[merged.reference]), info), info)
        val all = Develop.develop(merged, info)
        val errOne = pictureError(one, truth, tw, rx, ry)
        val errAll = pictureError(all, truth, tw, rx, ry)
        assertTrue("merged ($errAll) keeps the detail one shot has ($errOne)", errAll < errOne * 1.1)
    }

    // A scene at twice the sensor's resolution, so shots can be moved by half a pixel as hands
    // do: cells of weave-like threads each at its own angle, small print, and calm areas.
    private fun mix(a: Int, b: Int): Int {
        var v = a * 374761393 + b * 668265263
        v = (v xor (v ushr 13)) * 1274126177
        return (v xor (v ushr 16)) and 0x7fffffff
    }
    private fun kind(x: Int, y: Int) = mix(x / 96, y / 96) % 3
    private fun fineScene(tw: Int, th: Int) = FloatArray(tw * th) { i ->
        val x = i % tw
        val y = i / tw
        val hc = mix(x / 96 + 1000, y / 96)
        val angle = (hc % 628) / 100f
        val freq = 0.45f + (hc % 35) / 100f
        val base = 110f + (mix(x / 96, y / 96 + 500) % 90)
        val t = if (kind(x, y) == 2) 0f else kotlin.math.sin((x * kotlin.math.cos(angle) + y * kotlin.math.sin(angle)) * freq)
        (base + 28f * t + (if (kind(x, y) == 1 && (x / 6) % 5 == 0 && y % 96 in 20..50) -60f else 0f)).coerceIn(10f, 245f)
    }
    /** What a sensor pixel sees: 2×2 of the scene; ([ox], [oy]) in half pixels. */
    private fun seen(t: FloatArray, tw: Int, x: Int, y: Int, ox: Int, oy: Int): Float {
        val o = (2 * y + oy + 40) * tw + 2 * x + ox + 40
        return (t[o] + t[o + 1] + t[o + tw] + t[o + tw + 1]) / 4f
    }
    private fun fineShot(t: FloatArray, tw: Int, w: Int, h: Int, ox: Int, oy: Int, noise: Float): RawFrame {
        val info = RawInfo(0, FloatArray(4) { black }, white, wb, RawInfo.IDENTITY)
        return RawFrame(w, h, ShortArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val v = seen(t, tw, x, y, ox, oy) / 255f
            val lin = if (v <= 0.04045f) v / 12.92f else Math.pow((v + 0.055) / 1.055, 2.4).toFloat()
            (lin / wb[info.colour((y and 1) * 2 + (x and 1))] * (white - black) + black + rnd.nextGaussian().toFloat() * noise).roundToInt().coerceIn(0, 1023).toShort()
        })
    }
    private fun fineError(p: Yuv, t: FloatArray, tw: Int, ox: Int, oy: Int, calm: Boolean?): Double {
        var s = 0.0
        var n = 0
        for (y in 16 until p.h - 16) for (x in 16 until p.w - 16) {
            if (calm != null && (kind(2 * x + ox + 40, 2 * y + oy + 40) == 2) != calm) continue
            s += abs((p.y[y * p.w + x].toInt() and 255) - seen(t, tw, x, y, ox, oy))
            n++
        }
        return s / n
    }

    /**
     * The burst as hands really give it: every shot moved by some pixels and a half. Laying
     * the samples where they were must clean the calm areas thoroughly and still not lose the
     * fine detail one shot has.
     */
    @Test
    fun aHandheldSensorBurstIsCleanerAndKeepsItsDetail() {
        val w = 480
        val h = 360
        val tw = 1100
        val scene = fineScene(tw, 860)
        val noise = 10f
        val info = RawInfo(0, FloatArray(4) { black }, white, wb, RawInfo.IDENTITY, null, 0f, (noise / (white - black)).pow(2))
        val moves = listOf(0 to 0, 3 to 1, -5 to 2, 2 to -7, 9 to 5)
        val shots = moves.map { (ox, oy) -> fineShot(scene, tw, w, h, ox, oy, noise) }
        val ref = RawMerge.sharpest(shots, info)
        val (rx, ry) = moves[ref]
        val base = RawMerge.merge(listOf(shots[ref]), info)
        val one = Develop.develop(base, info)
        val stacked = Stack.develop(shots, info, base, ref)
        val calmOne = fineError(one, scene, tw, rx, ry, true)
        val calmAll = fineError(stacked.photo, scene, tw, rx, ry, true)
        val fineOne = fineError(one, scene, tw, rx, ry, false)
        val fineAll = fineError(stacked.photo, scene, tw, rx, ry, false)
        assertTrue("calm areas much cleaner: $calmOne -> $calmAll", calmAll < calmOne * 0.6)
        assertTrue("fine detail kept: $fineOne -> $fineAll", fineAll < fineOne * 1.2)
        assertTrue("better overall", fineError(stacked.photo, scene, tw, rx, ry, null) < fineError(one, scene, tw, rx, ry, null))
        // One frame alone comes out as that frame developed.
        val alone = Stack.develop(listOf(shots[ref]), info, base, 0)
        assertTrue(fineError(alone.photo, scene, tw, rx, ry, null) < fineError(one, scene, tw, rx, ry, null) * 1.05)
    }

    /** The same on a colourful scene, with something moving through it. */
    @Test
    fun aSensorBurstKeepsColoursTrueAndLeavesNoGhosts() {
        val w = 320
        val h = 240
        val tw = 400
        val truth = truePicture(tw, 320)
        val info = RawInfo(0, FloatArray(4) { black }, white, wb, ccm, null, 0f, (8f / (white - black)).pow(2))
        val moves = listOf(0 to 0, 2 to -4, -6 to 2, 3 to 1, 1 to 8)
        val shots = moves.map { (ox, oy) -> sensorShot(truth, tw, w, h, ox, oy, 8f) }
        val ref = RawMerge.sharpest(shots, info)
        val (rx, ry) = moves[ref]
        val base = RawMerge.merge(listOf(shots[ref]), info)
        val stacked = Stack.develop(shots, info, base, ref).photo
        val one = Develop.develop(base, info)
        val errOne = pictureError(one, truth, tw, rx, ry)
        val errAll = pictureError(stacked, truth, tw, rx, ry)
        assertTrue("cleaner than one shot: $errOne -> $errAll", errAll < errOne * 0.8)
        var colour = 0.0
        var n = 0
        for (cy in 10 until stacked.ch - 10) for (cx in 10 until stacked.cw - 10) {
            val o = ((cy * 2 + ry + 20) * tw + cx * 2 + rx + 20) * 3
            val u = -0.168736f * truth[o] - 0.331264f * truth[o + 1] + 0.5f * truth[o + 2] + 128
            val v = 0.5f * truth[o] - 0.418688f * truth[o + 1] - 0.081312f * truth[o + 2] + 128
            colour += abs((stacked.u[cy * stacked.cw + cx].toInt() and 255) - u) + abs((stacked.v[cy * stacked.cw + cx].toInt() and 255) - v)
            n += 2
        }
        assertTrue("colours true: ${colour / n}", colour / n < 5.0)
        // A bright block in a different place in every other shot must not show up.
        val moving = shots.mapIndexed { k, f ->
            if (k == ref) f else RawFrame(w, h, f.px.copyOf().also { px ->
                for (y in 100 until 140) for (x in 60 + 40 * k until 100 + 40 * k) px[y * w + x] = 1000
            })
        }
        val withMovement = Stack.develop(moving, info, base, ref).photo
        assertTrue("no ghosts: ${pictureError(withMovement, truth, tw, rx, ry)}", pictureError(withMovement, truth, tw, rx, ry) < errOne)
    }

    @Test
    fun theDevelopedPhotoTakesOnThePhonesLook() {
        val w = 320
        val h = 240
        val tw = 400
        val truth = truePicture(tw, 320)
        val info = RawInfo(0, FloatArray(4) { black }, white, wb, ccm)
        val merged = RawMerge.merge(listOf(sensorShot(truth, tw, w, h, 0, 0, 4f), sensorShot(truth, tw, w, h, 2, 2, 4f)), info)
        val ref = if (merged.reference == 0) 0 else 2
        // The phone's own picture of the scene: more contrast, richer colour.
        fun phoneLook(r: Float, g: Float, b: Float): FloatArray {
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            val curve = 255f * Math.pow((lum / 255.0), 0.8).toFloat() // brighter mid-tones
            val k = curve / lum.coerceAtLeast(1f)
            val l2 = lum * k
            return floatArrayOf(l2 + (r * k - l2) * 1.3f, l2 + (g * k - l2) * 1.3f, l2 + (b * k - l2) * 1.3f)
        }
        val py = ByteArray(w * h)
        val pu = ByteArray(w * h / 4)
        val pv = ByteArray(w * h / 4)
        for (y in 0 until h) for (x in 0 until w) {
            val o = ((y + ref + 20) * tw + x + ref + 20) * 3
            val c = phoneLook(truth[o], truth[o + 1], truth[o + 2])
            py[y * w + x] = (0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]).roundToInt().coerceIn(0, 255).toByte()
            if (x % 2 == 0 && y % 2 == 0) {
                pu[(y / 2) * (w / 2) + x / 2] = (-0.168736f * c[0] - 0.331264f * c[1] + 0.5f * c[2] + 128).roundToInt().coerceIn(0, 255).toByte()
                pv[(y / 2) * (w / 2) + x / 2] = (0.5f * c[0] - 0.418688f * c[1] - 0.081312f * c[2] + 128).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        val phone = Yuv(w, h, py, pu, pv)
        val look = Look.fit(Develop.coarse(merged, info, Look.GRID_W, Look.GRID_H), Look.coarse(phone))
        val plain = Develop.develop(merged, info)
        val styled = Develop.develop(merged, info, look)
        fun distance(a: Yuv): Double {
            var s = 0.0
            for (i in a.y.indices) s += abs((a.y[i].toInt() and 255) - (phone.y[i].toInt() and 255))
            var c = 0.0
            for (i in a.u.indices) c += abs((a.u[i].toInt() and 255) - (phone.u[i].toInt() and 255)) + abs((a.v[i].toInt() and 255) - (phone.v[i].toInt() and 255))
            return s / a.y.size + c / a.u.size
        }
        assertTrue("with the look it matches the phone's picture: ${distance(plain)} -> ${distance(styled)}", distance(styled) < distance(plain) * 0.4)
        // (Brightness plus colour error per pixel; the phone's picture clips its strongest blues, which no curve can follow.)
        assertTrue("closely: ${distance(styled)}", distance(styled) < 12.0)
        // Nothing to compare with: the picture as developed.
        assertSame(Look.PLAIN, Look.fit(FloatArray(30), FloatArray(30)))
        // The check before a developed photo is trusted: close to the phone's picture, or not.
        assertTrue(Look.distance(Look.coarse(styled), Look.coarse(phone)) < Look.TRUSTED_WITHIN)
    }

    /**
     * A phone that says nothing useful about its sensor (no white balance, no colour matrix) and
     * a scene of strong, clipped colours: the look taken from the phone's picture must still set
     * the colours right — and where it can't, the photo must be recognised as not trustworthy.
     */
    @Test
    fun aSilentPhoneAndStrongColoursStillComeOutRight() {
        val w = 320
        val h = 240
        // Flat areas of strong colour, some at the very top of the range.
        val colours = listOf(floatArrayOf(255f, 255f, 255f), floatArrayOf(224f, 255f, 100f), floatArrayOf(70f, 224f, 112f),
            floatArrayOf(165f, 120f, 105f), floatArrayOf(128f, 90f, 160f), floatArrayOf(255f, 255f, 190f), floatArrayOf(20f, 12f, 8f))
        fun at(x: Int, y: Int) = colours[((x / 46) + (y / 40) * 3) % colours.size]
        val truth = FloatArray(400 * 320 * 3)
        for (y in 0 until 320) for (x in 0 until 400) for (c in 0..2) truth[(y * 400 + x) * 3 + c] = at(x, y)[c]
        // The sensor as it is (green-heavy, unbalanced), and a phone that reports neutral values for it.
        val shot = sensorShot(truth, 400, w, h, 0, 0, 3f)
        val silent = RawInfo(0, FloatArray(4) { black }, white, floatArrayOf(1f, 1f, 1f), RawInfo.IDENTITY.copyOf())
        val merged = RawMerge.merge(listOf(shot), silent)
        val py = ByteArray(w * h)
        val pu = ByteArray(w * h / 4)
        val pv = ByteArray(w * h / 4)
        for (y in 0 until h) for (x in 0 until w) {
            val c = at(x + 20, y + 20)
            py[y * w + x] = (0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]).roundToInt().coerceIn(0, 255).toByte()
            if (x % 2 == 0 && y % 2 == 0) {
                pu[(y / 2) * (w / 2) + x / 2] = (-0.168736f * c[0] - 0.331264f * c[1] + 0.5f * c[2] + 128).roundToInt().coerceIn(0, 255).toByte()
                pv[(y / 2) * (w / 2) + x / 2] = (0.5f * c[0] - 0.418688f * c[1] - 0.081312f * c[2] + 128).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        val phone = Yuv(w, h, py, pu, pv)
        val plain = Develop.develop(merged, silent)
        val look = Look.fit(Develop.coarse(merged, silent, Look.GRID_W, Look.GRID_H), Look.coarse(phone))
        val styled = Develop.develop(merged, silent, look)
        val before = Look.distance(Look.coarse(plain), Look.coarse(phone))
        val after = Look.distance(Look.coarse(styled), Look.coarse(phone))
        assertTrue("undeveloped colours are far off and recognised as such: $before", before > Look.TRUSTED_WITHIN)
        assertTrue("the phone's look sets them right: $before -> $after", after < Look.TRUSTED_WITHIN)
    }

    @Test
    fun clarityLiftsLocalContrastWithoutClipping() {
        val w = 400
        val h = 300
        // A soft step from grey to lighter grey, and a near-white area.
        val y = ByteArray(w * h) { i ->
            val x = i % w
            (when { x < 150 -> 110; x < 250 -> 150; else -> 248 }).toByte()
        }
        val grey = ByteArray(w * h / 4) { 128.toByte() }
        val before = Yuv(w, h, y, grey, grey)
        val after = Detail.clarity(before)
        fun at(p: Yuv, x: Int) = p.y[150 * w + x].toInt() and 255
        assertTrue("the step stands out more: ${at(before, 152) - at(before, 147)} -> ${at(after, 152) - at(after, 147)}",
            at(after, 152) - at(after, 147) > at(before, 152) - at(before, 147))
        assertTrue("far from any edge, nothing changes", at(after, 50) == at(before, 50))
        assertTrue("near-white isn't pushed into clipping", after.y.maxOf { it.toInt() and 255 } < 255)
        assertSame(before.u, after.u)
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
