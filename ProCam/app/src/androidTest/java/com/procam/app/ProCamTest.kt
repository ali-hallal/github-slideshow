package com.procam.app

import android.Manifest
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Rational
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * End-to-end tests: launch the real app on a device/emulator camera, take photos and
 * check they land in the gallery.
 */
@RunWith(AndroidJUnit4::class)
class ProCamTest {

    private val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.CAMERA))
        .around(ClearPrefsRule())
        .around(activityRule)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // ------------------------------------------------------------------ tests

    @Test
    fun previewStartsAndReportsLiveExposure() {
        waitForCamera()
        val text = readout()
        Log.i(TAG, "readout: $text")
        assertTrue("readout should show live ISO: $text", text.contains("ISO"))
    }

    @Test
    fun autoModeCaptureSavesJpeg() {
        waitForCamera()
        shootAndExpect("image/jpeg") { onView(withId(R.id.shutter)).perform(click()) }
    }

    @Test
    fun volumeKeyTakesPicture() {
        waitForCamera()
        shootAndExpect("image/jpeg") {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)
        }
    }

    @Test
    fun exposureCompensationSliderChangesEv() {
        waitForCamera()
        onView(withId(R.id.chipEv)).perform(click())
        val before = text(R.id.sliderLabel)
        onView(withId(R.id.slider)).perform(tapAt(0.95f, 0.5f))
        val after = text(R.id.sliderLabel)
        Log.i(TAG, "EV: '$before' -> '$after'")
        assertTrue("EV label should change ($before -> $after)", before != after)
        shootAndExpect("image/jpeg") { onView(withId(R.id.shutter)).perform(click()) }
    }

    @Test
    fun proModeManualExposureCapture() {
        assumeTrue("camera has no MANUAL_SENSOR", backCameraHas(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR))
        waitForCamera()
        onView(withId(R.id.modePro)).perform(click())
        assertTrue("ISO chip should leave AUTO", !text(R.id.chipIso).contains("AUTO"))

        onView(withId(R.id.chipShutter)).perform(click())
        val before = text(R.id.sliderLabel)
        assertTrue("slider should control shutter: $before", before.startsWith("Shutter"))
        onView(withId(R.id.slider)).perform(tapAt(0.2f, 0.5f))
        val after = text(R.id.sliderLabel)
        Log.i(TAG, "shutter: '$before' -> '$after'")
        assertTrue("shutter should change ($before -> $after)", before != after)

        onView(withId(R.id.chipIso)).perform(click())
        onView(withId(R.id.slider)).perform(tapAt(0.8f, 0.5f))
        Log.i(TAG, "iso: ${text(R.id.sliderLabel)}")

        shootAndExpect("image/jpeg") { onView(withId(R.id.shutter)).perform(click()) }

        onView(withId(R.id.modeAuto)).perform(click())
        assertTrue(text(R.id.chipIso).contains("AUTO"))
    }

    @Test
    fun manualFocusAndWhiteBalance() {
        waitForCamera()
        onView(withId(R.id.chipWb)).perform(click())
        onView(withId(R.id.slider)).perform(tapAt(0.6f, 0.5f))
        Log.i(TAG, "wb: ${text(R.id.sliderLabel)}")

        if (backCameraHas(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
            onView(withId(R.id.chipFocus)).perform(click())
            onView(withId(R.id.slider)).perform(tapAt(0.5f, 0.5f))
            val label = text(R.id.sliderLabel)
            Log.i(TAG, "focus: $label")
            assertTrue("manual focus label: $label", label.startsWith("Focus"))
        }
        shootAndExpect("image/jpeg") { onView(withId(R.id.shutter)).perform(click()) }
    }

    @Test
    fun rawCaptureSavesDngAndJpeg() {
        assumeTrue("camera has no RAW", backCameraHas(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW))
        waitForCamera()
        onView(withId(R.id.btnRaw)).perform(click())
        SystemClock.sleep(500)
        val jpegs = count("image/jpeg")
        shootAndExpect("image/x-adobe-dng") { onView(withId(R.id.shutter)).perform(click()) }
        waitFor("JPEG next to DNG") { count("image/jpeg") > jpegs }
    }

    @Test
    fun tapToFocusAndLongPressDoNotCrash() {
        waitForCamera()
        onView(withId(R.id.textureView)).perform(tapAt(0.5f, 0.5f))
        SystemClock.sleep(1500)
        onView(withId(R.id.textureView)).perform(tapAt(0.3f, 0.6f))
        SystemClock.sleep(1000)
        onView(withId(R.id.textureView)).perform(longClick())
        SystemClock.sleep(500)
        shootAndExpect("image/jpeg") { onView(withId(R.id.shutter)).perform(click()) }
    }

    @Test
    fun flashTimerZebraToggles() {
        waitForCamera()
        repeat(4) { onView(withId(R.id.btnFlash)).perform(click()) }
        onView(withId(R.id.btnZebra)).perform(click())
        assertEquals("ZEBRA 95", text(R.id.btnZebra))
        onView(withId(R.id.btnTimer)).perform(click())
        assertEquals("TIMER 3s", text(R.id.btnTimer))
        // 3 second self-timer.
        shootAndExpect("image/jpeg", timeoutMs = 20_000) { onView(withId(R.id.shutter)).perform(click()) }
    }

    @Test
    fun zoomWithLensButton() {
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.first {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        val range = manager.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        assumeTrue("no zoom", range != null && range.upper >= 2f)
        waitForCamera()
        assertTrue("1× shown", readout().contains("1×"))
        onView(withContentDescription("Zoom 2×")).perform(click())
        waitFor("2× zoom") { readout().contains("2×") }
        shootAndExpect("image/jpeg") { onView(withId(R.id.shutter)).perform(click()) }
        onView(withContentDescription("Zoom 1×")).perform(click())
        waitFor("back to 1×") { readout().contains("1×") }
    }

    @Test
    fun shutterPriorityModeWithMeter() {
        assumeTrue("camera has no MANUAL_SENSOR", backCameraHas(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR))
        waitForCamera()
        onView(withId(R.id.modePro)).perform(click())
        onView(withId(R.id.meter)).check(matches(isDisplayed()))
        // ISO dial fully left = auto ISO, i.e. shutter priority.
        onView(withId(R.id.chipIso)).perform(click())
        onView(withId(R.id.slider)).perform(tapAt(0.0f, 0.5f))
        val label = text(R.id.sliderLabel)
        Log.i(TAG, "priority: $label")
        assertTrue("ISO auto: $label", label.startsWith("ISO auto"))
        waitFor("S mode in readout") { readout().lines().getOrNull(1)?.startsWith("S ") == true }
        SystemClock.sleep(2000)
        Log.i(TAG, "after metering: ${text(R.id.chipIso).replace('\n', ' ')} / ${readout().replace("\n", " | ")}")
        shootAndExpect("image/jpeg") { onView(withId(R.id.shutter)).perform(click()) }
    }

    @Test
    fun settingsScreenOpensAndChangesSetting() {
        waitForCamera()
        onView(withId(R.id.btnSettings)).perform(click())
        onView(withText("Settings")).check(matches(isDisplayed()))
        onView(withText("Grid")).perform(click())
        assertTrue("grid saved as off", !Prefs(context).grid)
        Espresso.pressBack()
        waitForCamera()
        shootAndExpect("image/jpeg") { onView(withId(R.id.shutter)).perform(click()) }
    }

    @Test
    fun switchCameraAndCapture() {
        waitForCamera()
        val cameras = context.getSystemService(CameraManager::class.java).cameraIdList.size
        assumeTrue("only one camera", cameras > 1)
        onView(withId(R.id.btnSwitch)).perform(click())
        waitFor("front camera") { readout().contains("FRONT") }
        waitFor("live stats on new camera") { readout().contains("ISO") }
        shootAndExpect("image/jpeg") { onView(withId(R.id.shutter)).perform(click()) }
        onView(withId(R.id.btnSwitch)).perform(click())
        waitFor("back camera") { readout().contains("BACK") }
    }

    @Test
    fun survivesPauseResume() {
        waitForCamera()
        repeat(2) {
            activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            SystemClock.sleep(500)
            activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            waitForCamera()
        }
        shootAndExpect("image/jpeg") { onView(withId(R.id.shutter)).perform(click()) }
    }

    @Test
    fun simpleModeHidesProControls() {
        waitForCamera()
        setSimple(true)
        onView(withId(R.id.intentAuto)).check(matches(isDisplayed()))
        onView(withId(R.id.chipIso)).check(matches(androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility(androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE)))
        onView(withId(R.id.btnRaw)).check(matches(androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility(androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE)))
        assertTrue("Simple saved", !Prefs(context).uiPro)
        shootAndExpect("image/jpeg") { onView(withId(R.id.shutter)).perform(click()) }
        setSimple(false)
        onView(withId(R.id.chipIso)).check(matches(isDisplayed()))
    }

    @Test
    fun groupShutterKeepsOnePhotoFromBurst() {
        waitForCamera()
        setSimple(true)
        onView(withId(R.id.intentGroup)).perform(click())
        val before = count("image/jpeg")
        onView(withId(R.id.shutter)).perform(click())
        // The emulator scene has no faces: ProCam gives up waiting quickly, takes a burst of 3
        // and keeps exactly one.
        waitFor("group photo saved", 25_000) { count("image/jpeg") > before }
        waitFor("group finished", 15_000) { !shutterBusy() }
        SystemClock.sleep(2000)
        assertEquals("exactly one photo kept from the burst", before + 1, count("image/jpeg"))
    }

    @Test
    fun shakeMeterMath() {
        // Typical main camera: 6.8 mm lens on a 9.8 mm wide sensor, normal hand shake 0.02 rad/s.
        val safe = ShakeMeter.safeShutterNs(0.02f, 6.8f, 9.8f, zoom = 1f, ois = false)
        Log.i(TAG, "safe shutter: ${Stops.shutterLabel(safe)}")
        assertTrue("about 1/30 s: ${Stops.shutterLabel(safe)}", safe in 20_000_000L..60_000_000L)
        // Zooming in 3x needs a 3x faster shutter; OIS allows 4x slower; shakier hands need faster.
        val zoomed = ShakeMeter.safeShutterNs(0.02f, 6.8f, 9.8f, zoom = 3f, ois = false)
        assertTrue(abs(zoomed * 3 - safe) < safe / 50)
        assertTrue(ShakeMeter.safeShutterNs(0.02f, 6.8f, 9.8f, 1f, ois = true) > safe * 3)
        assertTrue(ShakeMeter.safeShutterNs(0.08f, 6.8f, 9.8f, 1f, ois = false) < safe / 3)
        // Still phone: any shutter is fine.
        assertEquals(ShakeMeter.MAX_SAFE_NS, ShakeMeter.safeShutterNs(0f, 6.8f, 9.8f, 1f, false))
    }

    @Test
    fun shakeMeterShows() {
        waitForCamera()
        val manager = context.getSystemService(android.hardware.SensorManager::class.java)
        assumeTrue("no gyroscope", manager.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE) != null)
        waitFor("shake meter visible") {
            var shown = false
            activityRule.scenario.onActivity { shown = it.findViewById<View>(R.id.shakeView).visibility == View.VISIBLE }
            shown
        }
        var label = ""
        activityRule.scenario.onActivity { label = it.findViewById<View>(R.id.shakeView).contentDescription.toString() }
        Log.i(TAG, "shake meter: $label")
        assertTrue("phone lying still reads steady: $label", label.contains("steady", ignoreCase = true))
    }

    @Test
    fun portraitBlurKeepsPersonSharpWithoutHalo() {
        // Synthetic photo: fine black/white stripes (background) with a solid red "person" in the middle.
        val w = 800
        val h = 600
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            val person = x in 300 until 500 && y in 150 until 450
            bmp.setPixel(x, y, if (person) android.graphics.Color.RED else if ((x / 4) % 2 == 0) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
        }
        val mw = 80
        val mh = 60
        val mask = FloatArray(mw * mh) { i -> val x = i % mw * 10; val y = i / mw * 10; if (x in 300 until 500 && y in 150 until 450) 1f else 0f }
        PortraitProcessor.renderPortrait(bmp, PortraitProcessor.Mask(mask, mw, mh), strength = 1)

        assertEquals("person untouched", android.graphics.Color.RED, bmp.getPixel(400, 300))
        // Background stripes are blurred to grey.
        var minL = 255
        var maxL = 0
        for (x in 20 until 120) {
            val l = android.graphics.Color.green(bmp.getPixel(x, 300))
            minL = minOf(minL, l); maxL = maxOf(maxL, l)
        }
        Log.i(TAG, "background range after blur: $minL..$maxL")
        assertTrue("stripes blurred ($minL..$maxL)", maxL - minL < 60)
        // No red halo bleeding into the background next to the person.
        val near = bmp.getPixel(540, 300)
        Log.i(TAG, "next to person: r=${android.graphics.Color.red(near)} g=${android.graphics.Color.green(near)}")
        assertTrue("no halo", android.graphics.Color.red(near) - android.graphics.Color.green(near) < 30)
    }

    @Test
    fun portraitWithoutPersonSavesNormalPhoto() {
        waitForCamera()
        setSimple(true)
        onView(withId(R.id.intentPortrait)).perform(click())
        onView(withId(R.id.blurStrong)).perform(click())
        assertEquals(2, Prefs(context).portraitStrength)
        val before = count("image/jpeg")
        onView(withId(R.id.shutter)).perform(click())
        waitFor("photo saved", 30_000) { count("image/jpeg") > before }
        waitFor("finished", 15_000) { !shutterBusy() }
        SystemClock.sleep(1500)
        assertEquals("exactly one photo", before + 1, count("image/jpeg"))
    }

    @Test
    fun controlsDoNotCoverThePicture() {
        waitForCamera()
        for (simple in listOf(true, false)) {
            setSimple(simple)
            if (simple) onView(withId(R.id.intentPortrait)).perform(click()) // the tallest Simple layout
            SystemClock.sleep(800)
            val picture = screenRect(R.id.textureView)
            val panel = screenRect(R.id.bottomPanel)
            val lens = screenRect(R.id.lensRow)
            Log.i(TAG, "simple=$simple picture=$picture panel=$panel lens=$lens")
            assertTrue("controls overlap the picture (simple=$simple): picture=$picture panel=$panel", panel.top >= picture.bottom - 2)
            if (lens.height() > 0) {
                assertTrue("lens buttons sit on the picture: $lens in $picture", lens.bottom <= picture.bottom && lens.top >= picture.top)
            }
        }
    }

    @Test
    fun nightMergeReducesNoise() {
        // 6 noisy, hand-shaken copies of a scene, as JPEGs, like a real burst.
        val w = 900
        val h = 675
        val rnd = java.util.Random(7)
        val scene = IntArray(w * h) { i -> val x = i % w; val y = i / w
            (120 + 50 * kotlin.math.sin(x / 31.0) * kotlin.math.cos(y / 19.0) + if ((x / 60 + y / 45) % 2 == 0) 25 else -25).toInt() }
        val shifts = listOf(0 to 0, 9 to -6, -14 to 4, 5 to 11, -7 to -10, 13 to 8)
        val frames = shifts.map { (sx, sy) ->
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val px = IntArray(w * h) { i -> val x = i % w; val y = i / w
                val v = (scene[(y + sy).coerceIn(0, h - 1) * w + (x + sx).coerceIn(0, w - 1)] + rnd.nextGaussian() * 18).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
            bmp.setPixels(px, 0, w, 0, 0, w, h)
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 97, out)
            out.toByteArray()
        }
        val result = NightProcessor().process(frames, iso = 3200, quality = 95)
        assertEquals("all frames merged", frames.size, result.merged)
        // Measure noise as roughness in a flat-ish patch: neighbour differences, single vs merged.
        fun roughness(jpeg: ByteArray, ox: Int, oy: Int): Double {
            val b = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            var s = 0.0; var n = 0
            for (y in 300 until 380) for (x in 400 until 480) {
                val a = android.graphics.Color.green(b.getPixel(x + ox, y + oy))
                val c = android.graphics.Color.green(b.getPixel(x + ox + 1, y + oy))
                s += (a - c) * (a - c); n++
            }
            return kotlin.math.sqrt(s / n)
        }
        val ref = shifts[result.reference]
        val single = roughness(frames[result.reference], 0, 0)
        val merged = roughness(result.jpeg, 0, 0)
        Log.i(TAG, "night roughness single=$single merged=$merged (ref shift $ref)")
        assertTrue("merge should be much cleaner ($single -> $merged)", merged < single / 1.6)
    }

    @Test
    fun nightPlanKeepsExposureRight() {
        val isoR = android.util.Range(100, 6400)
        val expR = android.util.Range(10_000L, 1_000_000_000L)
        fun product(p: Triple<Int, Long, Int>) = p.first.toDouble() * p.second
        // Bright daylight (auto: ISO 100, 1/220 s), very steady hands: must NOT use a long shutter.
        val day = NightProcessor.plan(100, 4_545_454L, ShakeMeter.MAX_SAFE_NS, isoR, expR)
        Log.i(TAG, "day plan: $day")
        assertTrue("daylight exposure unchanged", kotlin.math.abs(product(day) / (100.0 * 4_545_454L) - 1.0) < 0.05)
        // Dim room (auto: ISO 1600, 1/15 s), hands sharp to 1/30: shorter shutter, more ISO, ~1.6x brighter.
        val room = NightProcessor.plan(1600, 66_666_667L, 33_333_333L, isoR, expR)
        Log.i(TAG, "room plan: $room")
        assertTrue("shutter within hand limit", room.second <= 33_333_333L)
        assertTrue("brighter than auto", product(room) / (1600.0 * 66_666_667L) in 1.4..1.8)
    }

    @Test
    fun silkyBlendSmoothsMotionKeepsSceneSharp() {
        // 12 hand-shaken frames: a still textured scene with a bright "wave" moving along the bottom.
        val w = 900
        val h = 675
        val scene = IntArray(w * h) { i -> val x = i % w; val y = i / w
            if (y >= 420) 90 + x / 30 // plain "water" with a gentle gradient
            else (110 + 50 * kotlin.math.sin(x / 29.0) * kotlin.math.cos(y / 41.0) + if ((x / 60 + y / 45) % 2 == 0) 30 else -30).toInt() }
        val frames = (0 until 12).map { k ->
            val sx = (k * 7) % 11 - 5
            val sy = (k * 5) % 9 - 4
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val px = IntArray(w * h) { i -> val x = i % w; val y = i / w
                val X = (x + sx).coerceIn(0, w - 1); val Y = (y + sy).coerceIn(0, h - 1)
                val v = if (Y in 450 until 600 && X in (150 + 45 * k) until (180 + 45 * k)) 250 else scene[Y * w + X]
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
            bmp.setPixels(px, 0, w, 0, 0, w, h)
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 97, out)
            out.toByteArray()
        }
        val result = NightProcessor().process(frames, iso = 100, quality = 95, silky = true)
        assertEquals(12, result.merged)
        val b = android.graphics.BitmapFactory.decodeByteArray(result.jpeg, 0, result.jpeg.size)
        // Still part (top) stays sharp: close to the clean scene (shifted like the reference frame).
        val rs = (result.reference * 7) % 11 - 5
        val rsy = (result.reference * 5) % 9 - 4
        var err = 0.0; var n = 0
        for (y in 100 until 350 step 3) for (x in 100 until 800 step 3) {
            err += kotlin.math.abs(android.graphics.Color.green(b.getPixel(x, y)) - scene[(y + rsy) * w + (x + rs)]); n++
        }
        // Streak along the wave's path: brighter than the scene everywhere, without gaps.
        val lift = (160 until 640 step 20).map { x -> android.graphics.Color.green(b.getPixel(x, 525)) - scene[(525 + rsy) * w + x + rs] }
        Log.i(TAG, "silky: background error ${err / n}, streak lift $lift")
        assertTrue("still scene stays sharp (${err / n})", err / n < 6)
        assertTrue("smooth streak without gaps: $lift", lift.all { it >= 3 })
        assertTrue("even streak: $lift", lift.max() - lift.min() < 25)
    }

    @Test
    fun silkyCaptureSavesBlendedPhoto() {
        waitForCamera()
        setSimple(true)
        onView(withId(R.id.intentSilky)).perform(click())
        onView(withId(R.id.blurSoft)).perform(click()) // "Softer": 8 frames
        assertEquals(0, Prefs(context).silkyStrength)
        val before = count("image/jpeg")
        onView(withId(R.id.shutter)).perform(click())
        waitFor("silky photo saved", 90_000) { count("image/jpeg") > before }
        waitFor("finished", 20_000) { !shutterBusy() }
        SystemClock.sleep(1500)
        assertEquals("exactly one photo", before + 1, count("image/jpeg"))
        val silky = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("%_SILKY%"), null,
        )?.use { it.count } ?: 0
        assertTrue("a _SILKY photo was saved", silky > 0)
    }

    @Test
    fun nightCaptureSavesMergedPhoto() {
        waitForCamera()
        setSimple(true)
        onView(withId(R.id.intentNight)).perform(click())
        val before = count("image/jpeg")
        onView(withId(R.id.shutter)).perform(click())
        waitFor("night photo saved", 60_000) { count("image/jpeg") > before }
        waitFor("finished", 20_000) { !shutterBusy() }
        SystemClock.sleep(1500)
        assertEquals("exactly one photo", before + 1, count("image/jpeg"))
        val names = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("%_NIGHT%"), null,
        )?.use { it.count } ?: 0
        assertTrue("a _NIGHT photo was saved", names > 0)
    }

    @Test
    fun stopTables() {
        val iso = Stops.isoSteps(Range(100, 3200))
        assertEquals(100, iso.first())
        assertEquals(3200, iso.last())
        assertTrue(iso.contains(400))
        val shutter = Stops.shutterSteps(Range(100_000L, 1_000_000_000L))
        assertTrue(shutter.all { it in 100_000L..1_000_000_000L })
        assertEquals("1/125", Stops.shutterLabel(8_000_000L))
        assertEquals("1\"", Stops.shutterLabel(1_000_000_000L))
        assertEquals("0.5\"", Stops.shutterLabel(500_000_000L))
        assertEquals("±0", Stops.evLabel(0, Rational(1, 3)))
        assertEquals("+1.0", Stops.evLabel(3, Rational(1, 3)))
        assertEquals("∞", Stops.focusLabel(0f))
        assertEquals("50cm", Stops.focusLabel(2f))
    }

    // ------------------------------------------------------------------ helpers

    /** Switches between Simple and Pro with the top-bar button. */
    private fun setSimple(simple: Boolean) {
        if (Prefs(context).uiPro == simple) onView(withId(R.id.btnMode)).perform(click())
        assertEquals(!simple, Prefs(context).uiPro)
    }

    /** Screen rectangle of a view. */
    private fun screenRect(id: Int): android.graphics.Rect {
        val r = android.graphics.Rect()
        activityRule.scenario.onActivity { it.findViewById<View>(id).getGlobalVisibleRect(r) }
        return r
    }

    private fun waitForCamera() {
        waitFor("camera to open", 20_000) { readout().contains("BACK") || readout().contains("FRONT") }
        waitFor("live preview stats", 20_000) { readout().contains("ISO") }
        waitFor("shutter ready") { !shutterBusy() }
    }

    private fun shootAndExpect(mime: String, timeoutMs: Long = 15_000, press: () -> Unit) {
        waitFor("shutter ready") { !shutterBusy() }
        val before = count(mime)
        press()
        waitFor("a new $mime in Pictures/ProCam", timeoutMs) { count(mime) > before }
        waitFor("capture to finish") { !shutterBusy() }
        Log.i(TAG, "saved $mime (${count(mime)} total)")
    }

    private fun count(mime: String): Int = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.MIME_TYPE} = ?",
        arrayOf("Pictures/ProCam%", mime),
        null,
    )?.use { it.count } ?: 0

    private fun backCameraHas(capability: Int): Boolean {
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return false
        val caps = manager.getCameraCharacteristics(id).get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        return caps?.contains(capability) == true
    }

    private fun readout() = text(R.id.readout)

    private fun text(id: Int): String {
        var t = ""
        activityRule.scenario.onActivity { t = it.findViewById<TextView>(id).text.toString() }
        return t
    }

    private fun shutterBusy(): Boolean {
        var busy = false
        activityRule.scenario.onActivity { busy = it.findViewById<ShutterButton>(R.id.shutter).busy }
        return busy
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            SystemClock.sleep(200)
        }
        fail("Timed out waiting for $what (readout='${readout()}')")
    }

    private fun tapAt(fx: Float, fy: Float) = GeneralClickAction(
        Tap.SINGLE,
        CoordinatesProvider { v: View ->
            val xy = IntArray(2)
            v.getLocationOnScreen(xy)
            floatArrayOf(xy[0] + v.width * fx, xy[1] + v.height * fy)
        },
        Press.FINGER,
        InputDevice.SOURCE_UNKNOWN,
        MotionEvent.BUTTON_PRIMARY,
    )

    companion object {
        private const val TAG = "ProCamTest"
    }
}
