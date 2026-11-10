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
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
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
        .outerRule(GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
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
        assertEquals("Zebra 95", text(R.id.btnZebra))
        onView(withId(R.id.btnTimer)).perform(click())
        assertEquals("3s", text(R.id.btnTimer))
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
        onView(withText("Grid")).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
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
        onView(withId(R.id.intentGroup)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
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
        setSimple(false) // in Simple it only appears when the photo is at risk
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
        onView(withId(R.id.intentPortrait)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
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
            if (simple) onView(withId(R.id.intentPortrait)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click()) // the tallest Simple layout
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
        onView(withId(R.id.intentSilky)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
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
        onView(withId(R.id.intentNight)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
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
    fun superZoomRecoversRealDetail() {
        // A detailed "true" scene, photographed 6x at 1/4 resolution with hand-shake offsets and
        // noise (as JPEGs), plus 2 frames smeared by shake. Super zoom must rebuild it at 2x much
        // closer to the truth than enlarging a single shot.
        val w4 = 1600
        val h4 = 1200
        val rnd = java.util.Random(11)
        val tex = FloatArray(w4 * h4) { rnd.nextFloat() }
        repeat(2) { PortraitProcessor.boxBlur(tex, w4, h4, 3) }
        val truth4 = IntArray(w4 * h4) { i -> val x = i % w4; val y = i / w4
            var v = 60 + 140 * tex[i]
            if ((x / 24 + y / 24) % 2 == 0 && x in 200..700 && y in 200..700) v = 30f
            if (x in 900..1400 && y in 300..900 && ((x - 900) / 6) % 3 == 0) v = 230f
            v += 25f * kotlin.math.sin(x * x / 9000f) * kotlin.math.cos(y / 13f)
            v.toInt().coerceIn(0, 255) }
        fun boxDown(f: Int, ox: Int, oy: Int): SuperRes.Frame {
            val ow = w4 / f - 2
            val oh = h4 / f - 2
            return SuperRes.Frame(IntArray(ow * oh) { i -> val x = i % ow; val y = i / ow; var s = 0
                for (yy in 0 until f) for (xx in 0 until f) s += truth4[(y * f + f + oy + yy).coerceIn(0, h4 - 1) * w4 + (x * f + f + ox + xx).coerceIn(0, w4 - 1)]
                val v = s / (f * f); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }, ow, oh)
        }
        val shifts = listOf(0 to 0, 2 to 1, -1 to 3, 3 to -2, -2 to -1, 1 to 2)
        fun jpeg(f: SuperRes.Frame, k: Int, smear: Boolean): ByteArray {
            val noise = java.util.Random(k * 7919L)
            val px = IntArray(f.px.size) { i ->
                var v = f.px[i] and 0xFF
                if (smear) { val x = i % f.w; var s = 0; for (d in -3..3) s += f.px[i - x + (x + d).coerceIn(0, f.w - 1)] and 0xFF; v = s / 7 }
                val n = (v + noise.nextGaussian() * 6).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (n shl 16) or (n shl 8) or n }
            val bmp = android.graphics.Bitmap.createBitmap(px, f.w, f.h, android.graphics.Bitmap.Config.ARGB_8888)
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 97, out)
            return out.toByteArray()
        }
        val lowRes = shifts.map { (sx, sy) -> boxDown(4, sx, sy) }
        val frames = lowRes.mapIndexed { k, f -> jpeg(f, k, smear = false) } + listOf(jpeg(lowRes[1], 90, true), jpeg(lowRes[2], 91, true))
        // Enlarged 2x here, so the result sits exactly on the truth's pixel grid.
        val result = SuperZoomProcessor().process(frames, crop = 1f, quality = 97, maxScale = 2f)
        assertEquals("kept the 6 sharp frames", 6, result.merged)
        val out = android.graphics.BitmapFactory.decodeByteArray(result.jpeg, 0, result.jpeg.size)
        val outFrame = SuperRes.Frame(IntArray(out.width * out.height).also { out.getPixels(it, 0, out.width, 0, 0, out.width, out.height) }, out.width, out.height)
        // Compare with the truth on the grid of whichever frame was the reference (the sharpest).
        fun err(f: SuperRes.Frame) = shifts.minOf { (sx, sy) ->
            val t = boxDown(2, 2 + sx, 2 + sy)
            SuperRes.rmse(f, SuperRes.Frame(IntArray(f.w * f.h) { i -> t.px[(i / f.w).coerceIn(0, t.h - 1) * t.w + (i % f.w).coerceIn(0, t.w - 1)] }, f.w, f.h), 30)
        }
        val single0 = android.graphics.BitmapFactory.decodeByteArray(frames[0], 0, frames[0].size)
        val s0 = SuperRes.Frame(IntArray(single0.width * single0.height).also { single0.getPixels(it, 0, single0.width, 0, 0, single0.width, single0.height) }, single0.width, single0.height)
        val single = SuperRes.Frame(SuperRes.enlarge(s0, outFrame.w, outFrame.h), outFrame.w, outFrame.h)
        val eSingle = err(single)
        val eSuper = err(outFrame)
        Log.i(TAG, "super zoom detail: single enlarged error=$eSingle, super zoom error=$eSuper (${out.width}x${out.height})")
        assertTrue("super zoom recovers real detail ($eSingle -> $eSuper)", eSuper < eSingle * 0.8)
    }

    @Test
    fun zoomSliderAndSuperZoomCapture() {
        waitForCamera()
        setSimple(true)
        // Tapping the highlighted lens button opens the zoom slider.
        onView(withContentDescription("Zoom 1×")).perform(click())
        onView(withId(R.id.zoomBar)).check(matches(isDisplayed()))
        var hi = 0f
        var hw = 0f
        activityRule.scenario.onActivity { val d = it.findViewById<ZoomDial>(R.id.zoomDial); hi = d.hi; hw = d.hardwareMax }
        Log.i(TAG, "zoom dial: up to $hi (camera $hw)")
        assertTrue("dial reaches past the camera's own zoom: $hi vs $hw", hi > hw * 1.01f)
        // Drag the dial to the left (zoom in) all the way.
        onView(withId(R.id.zoomDial)).perform(dragAcross(0.95f, 0.05f))
        var v = 0f
        activityRule.scenario.onActivity { v = it.findViewById<ZoomDial>(R.id.zoomDial).value }
        if (v <= hw * 1.01f && visible(R.id.zoomBar)) {
            onView(withId(R.id.zoomDial)).perform(dragAcross(0.95f, 0.05f))
            activityRule.scenario.onActivity { v = it.findViewById<ZoomDial>(R.id.zoomDial).value }
        }
        assertTrue("dragged to super zoom: $v", v > hw * 1.01f)
        SystemClock.sleep(500)
        Log.i(TAG, "after slider: ${readout().replace("\n", " | ")}")
        val before = count("image/jpeg")
        val pressed = SystemClock.uptimeMillis()
        onView(withId(R.id.shutter)).perform(click())
        SystemClock.sleep(100)
        waitFor("shutter ready again", 20_000) { !shutterBusy() }
        val ready = SystemClock.uptimeMillis() - pressed
        waitFor("super zoom photo saved", 60_000) { count("image/jpeg") > before }
        val saved = SystemClock.uptimeMillis() - pressed
        Log.i(TAG, "super zoom timing: shutter ready after $ready ms, photo saved after $saved ms")
        assertTrue("shutter is freed before enhancement finishes ($ready vs $saved ms)", ready <= saved)
        SystemClock.sleep(1500)
        val zoomed = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("%_ZOOM%"), null,
        )?.use { it.count } ?: 0
        assertTrue("a _ZOOM photo was saved", zoomed > 0)
    }

    @Test
    fun freezePlanStopsMotion() {
        val isoR = android.util.Range(50, 6400)
        val expR = android.util.Range(10_000L, 1_000_000_000L)
        val max = 2_000_000L // 1/500 s
        val limit = 4_000_000L // 1/250 s
        // Daylight (auto: ISO 100, 1/220): 1/500 s with ISO raised to keep brightness.
        val day = MomentBuffer.freezePlan(100, 4_545_454L, max, limit, isoR, expR)
        Log.i(TAG, "freeze day: ISO ${day.first} ${Stops.shutterLabel(day.second)}")
        assertEquals(max, day.second)
        assertTrue(kotlin.math.abs(day.first * day.second / (100.0 * 4_545_454L) - 1) < 0.05)
        // Bright sun (auto already 1/4000): keep the faster shutter, lowest ISO.
        val sun = MomentBuffer.freezePlan(50, 250_000L, max, limit, isoR, expR)
        assertTrue(sun.second <= 250_000L)
        // Dim room (auto: ISO 1600, 1/30): ISO maxes out; shutter lengthens but never past 1/250.
        val dim = MomentBuffer.freezePlan(1600, 33_333_333L, max, limit, isoR, expR)
        Log.i(TAG, "freeze dim: ISO ${dim.first} ${Stops.shutterLabel(dim.second)}")
        assertEquals(6400, dim.first)
        assertTrue("never slower than 1/250", dim.second <= limit)
    }

    @Test
    fun freezeCapturesTheMoment() {
        waitForCamera()
        setSimple(true)
        onView(withId(R.id.intentFreeze)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
        if (backCameraHas(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
            waitFor("fast shutter") {
                val label = text(R.id.chipShutter)
                val denom = Regex("1/(\\d+)").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                denom >= 250
            }
            Log.i(TAG, "freeze shutter: ${text(R.id.chipShutter).replace("\n", " ")}")
        }
        SystemClock.sleep(1000) // let the moment buffer fill
        val before = count("image/jpeg")
        onView(withId(R.id.shutter)).perform(click())
        waitFor("action photo saved", 30_000) { count("image/jpeg") > before }
        SystemClock.sleep(1500)
        assertEquals("exactly one photo", before + 1, count("image/jpeg"))
        val action = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("%_ACTION%"), null,
        )?.use { it.count } ?: 0
        assertTrue("an _ACTION photo from the moment buffer", action > 0)
        // Leaving Freeze puts exposure back to automatic.
        onView(withId(R.id.intentAuto)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
        SystemClock.sleep(500)
        assertTrue(text(R.id.chipIso).contains("AUTO"))
    }

    @Test
    fun reviewShowsBeforeAfterAndDeletes() {
        waitForCamera()
        setSimple(true)
        onView(withId(R.id.intentNight)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
        val before = count("image/jpeg")
        onView(withId(R.id.shutter)).perform(click())
        waitFor("night photo saved", 60_000) { count("image/jpeg") > before }
        waitFor("finished", 20_000) { !shutterBusy() }
        SystemClock.sleep(1500)
        // Thumbnail → review screen with the before/after comparison.
        onView(withId(R.id.thumbnail)).perform(click())
        waitFor("review shows the comparison", 15_000) {
            try {
                onView(withContentDescription("Before and after comparison")).check(matches(isDisplayed()))
                true
            } catch (_: Throwable) {
                false
            }
        }
        onView(withText("Night")).check(matches(isDisplayed()))
        // The explanation shares a text block with the camera settings line.
        onView(withText(org.hamcrest.Matchers.containsString("Several shots merged"))).check(matches(isDisplayed()))
        // Delete it (with confirmation).
        val n = count("image/jpeg")
        onView(withText("Delete")).perform(click())
        onView(withText("Delete")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
        waitFor("photo deleted", 10_000) { count("image/jpeg") == n - 1 }
        Espresso.pressBack()
    }

    @Test
    fun photoKindsAreExplained() {
        assertEquals("Portrait", ReviewActivity.describe("PROCAM_1_PORTRAIT.jpg").first)
        assertEquals("Night", ReviewActivity.describe("PROCAM_1_NIGHT.jpg").first)
        assertEquals("Zoom", ReviewActivity.describe("PROCAM_1_ZOOM.jpg").first)
        assertEquals("Freeze", ReviewActivity.describe("PROCAM_1_ACTION.jpg").first)
        assertEquals("RAW", ReviewActivity.describe("PROCAM_1.dng").first)
        assertEquals("Photo", ReviewActivity.describe("PROCAM_1.jpg").first)
        assertEquals("HDR", ReviewActivity.describe("PROCAM_1_HDR.jpg").first)
    }

    @Test
    fun lensPlannerFindsAllLenses() {
        fun show(l: List<LensStop>) = l.joinToString { "${Stops.zoomLabel(it.zoom)}@${it.cameraId}" }
        // Phone exposing ultra-wide and 3x tele as separate cameras (Xiaomi/OnePlus style).
        val split = LensPlanner.plan("0", listOf(1f, 2f), android.util.Range(1f, 10f), false, listOf("2" to 0.6f, "3" to 3f))
        Log.i(TAG, "split lenses: ${show(split)}")
        assertEquals(listOf(0.6f, 1f, 2f, 3f, 5f, 10f), split.map { it.zoom })
        assertEquals("2", split.first { it.zoom == 0.6f }.cameraId)
        assertEquals("3", split.first { it.zoom == 3f }.cameraId)
        assertEquals("5x zooms from the tele lens", "3", split.first { it.zoom == 5f }.cameraId)
        // Pixel style: one logical camera that already covers 0.5x..30x with real lenses.
        val pixel = LensPlanner.plan("0", listOf(0.5f, 1f, 2f, 5f), android.util.Range(0.5f, 30f), true, emptyList())
        Log.i(TAG, "logical camera: ${show(pixel)}")
        assertEquals(listOf(0.5f, 1f, 2f, 5f, 10f), pixel.map { it.zoom })
        // Single-lens phone with 8x digital zoom: 1x, 2x, 5x.
        val single = LensPlanner.plan("0", listOf(1f, 2f), android.util.Range(1f, 8f), false, emptyList())
        assertEquals(listOf(1f, 2f, 5f), single.map { it.zoom })
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

    @Test
    fun hdrDecidesOnlyForBacklitScenes() {
        assertEquals("evenly lit", 0f, Hdr.stopsDarker(Hdr.Scene(clipped = 0.005f, dark = 0.3f)), 0f)
        assertEquals("bright but no shadows", 0f, Hdr.stopsDarker(Hdr.Scene(clipped = 0.2f, dark = 0.02f)), 0f)
        assertTrue("sky blown out over dark ground", Hdr.stopsDarker(Hdr.Scene(clipped = 0.08f, dark = 0.3f)) >= 1.5f)
        assertTrue("dark face against a window", Hdr.stopsDarker(Hdr.Scene(clipped = 0.02f, dark = 0.05f, faceVsFrame = 0.4f)) > 0f)
        assertEquals("well-lit face", 0f, Hdr.stopsDarker(Hdr.Scene(clipped = 0.02f, dark = 0.05f, faceVsFrame = 1.0f)), 0f)
        // The burst is shot darker by the requested stops, never with a shutter longer than auto's.
        val (iso, t) = Hdr.plan(400, 20_000_000L, 30_000_000L, 2f, Range(50, 3200), Range(100_000L, 1_000_000_000L))
        assertEquals(4.0, 400.0 * 20_000_000L / (iso.toDouble() * t), 0.1)
        assertTrue(t <= 20_000_000L)
    }

    @Test
    fun hdrToneMapKeepsSkyAndLiftsSubject() {
        // Backlit scene shot 2 stops dark: bright sky on top, a dark person in the middle.
        val w = 600
        val h = 450
        fun enc(v: Float): Int {
            val c = v.coerceIn(0f, 1f)
            val s = if (c <= 0.0031308f) c * 12.92f else 1.055f * Math.pow(c.toDouble(), 1 / 2.4).toFloat() - 0.055f
            return (s * 255).toInt().coerceIn(0, 255)
        }
        fun scene(x: Int, y: Int) = when {
            x in 225 until 375 && y >= 150 -> 0.03f
            y < 180 -> 0.92f
            else -> 0.12f
        }
        val dark = IntArray(w * h) { i -> val v = enc(scene(i % w, i / w) / 4f); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val normal = IntArray(w * h) { i -> val v = enc(scene(i % w, i / w)); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val hdr = dark.copyOf()
        Hdr.toneMap(hdr, w, h, 2f)
        fun mean(a: IntArray, x0: Int, x1: Int, y0: Int, y1: Int): Double {
            var s = 0.0
            for (y in y0 until y1) for (x in x0 until x1) s += a[y * w + x] and 0xFF
            return s / ((x1 - x0) * (y1 - y0))
        }
        val person = mean(hdr, 240, 360, 230, 430)
        Log.i(TAG, "hdr: person dark=${mean(dark, 240, 360, 230, 430)} hdr=$person normal=${mean(normal, 240, 360, 230, 430)}; " +
            "sky hdr=${mean(hdr, 0, 600, 0, 160)}")
        assertTrue("person brightened close to a normal exposure", person > mean(normal, 240, 360, 230, 430) * 0.85)
        assertTrue("sky not blown out", mean(hdr, 0, 600, 0, 160) < 248)
        val near = mean(hdr, 385, 400, 250, 420) / mean(normal, 385, 400, 250, 420)
        val far = mean(hdr, 500, 590, 250, 420) / mean(normal, 500, 590, 250, 420)
        assertEquals("no halo around the person", far, near, 0.05)
    }

    @Test
    fun diagnosticsReportDescribesPhone() {
        waitForCamera()
        val report = Diagnostics.build(context)
        Log.i(TAG, "diagnostics: ${report.length} chars\n${report.take(1500)}")
        assertTrue(report.contains(android.os.Build.MODEL))
        assertTrue(report.contains("Camera 0"))
        assertTrue("includes recent app log", report.contains("ProCam"))
        val uri = Diagnostics.save(context, report)
        assertTrue("saved to Downloads", uri != null)
        context.contentResolver.delete(uri!!, null, null)
    }

    @Test
    fun hdrCaptureSavesBalancedPhoto() {
        waitForCamera()
        setSimple(true)
        assumeTrue("needs manual exposure", backCameraHas(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR))
        context.getSharedPreferences("procam", android.content.Context.MODE_PRIVATE).edit().putBoolean("forceHdr", true).commit()
        waitFor("HDR badge") { visible(R.id.hdrBadge) }
        val before = count("image/jpeg")
        onView(withId(R.id.shutter)).perform(click())
        waitFor("HDR photo saved", 60_000) { count("image/jpeg") > before }
        waitFor("finished", 20_000) { !shutterBusy() }
        SystemClock.sleep(1500)
        assertEquals("exactly one photo", before + 1, count("image/jpeg"))
        val names = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("%_HDR%"), null,
        )?.use { it.count } ?: 0
        assertTrue("an _HDR photo was saved", names > 0)
        // Other intents turn HDR off.
        onView(withId(R.id.intentNight)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
        waitFor("HDR badge hidden") { !visible(R.id.hdrBadge) }
    }

    @Test
    fun videoHelpers() {
        val offered = arrayOf(android.util.Size(3840, 2160), android.util.Size(1920, 1080), android.util.Size(1440, 1080), android.util.Size(1280, 720))
        assertEquals(android.util.Size(1920, 1080), VideoRecorder.pickSize(offered, allow4k = false, fps = 30))
        val k4 = VideoRecorder.pickSize(offered, allow4k = true, fps = 30)
        assertTrue("16:9 size, 4K only if the encoder can: $k4", k4 == android.util.Size(3840, 2160) || k4 == android.util.Size(1920, 1080))
        assertEquals("1:05", VideoRecorder.durationLabel(65_000))
        assertEquals("1:00:01", VideoRecorder.durationLabel(3_601_000))
        val rate = VideoRecorder.bitRate(android.util.Size(1920, 1080), 30)
        assertTrue("1080p bit rate ~17 Mbit/s: $rate", rate in 14_000_000..20_000_000)
        assertEquals("Video", ReviewActivity.describe("PROCAM_1.mp4").first)
    }

    @Test
    fun videoRecordsAndSaves() {
        waitForCamera()
        setSimple(true)
        onView(withId(R.id.switchVideo)).perform(click())
        SystemClock.sleep(1500)
        waitForCamera()
        assertTrue("photo modes make way for video", !visible(R.id.intentScroll))
        assertTrue("hint explains recording", text(R.id.intentHint).contains("red button"))
        val before = videoCount()
        val pressedAt = SystemClock.uptimeMillis()
        onView(withId(R.id.shutter)).perform(click())
        waitFor("recording started") { visible(R.id.recTime) }
        val startedAt = SystemClock.uptimeMillis()
        assertTrue("photo/video switch hidden while recording", !visible(R.id.captureSwitch))
        SystemClock.sleep(3500)
        val secs = text(R.id.recTime).substringAfterLast(':').trim().toIntOrNull() ?: -1
        // A stalled emulator can make the wait longer than asked: compare with the time really passed.
        // (The recording can start well before the test notices it: bound it by the press.)
        val passed = ((SystemClock.uptimeMillis() - pressedAt) / 1000).toInt()
        assertTrue("timer counts: ${text(R.id.recTime)} after $passed s", secs in 3..passed + 1)
        val recordedFor = SystemClock.uptimeMillis() - startedAt
        onView(withId(R.id.shutter)).perform(click())
        waitFor("video saved", 15_000) { videoCount() > before }
        waitFor("recording UI gone") { !visible(R.id.recTime) && visible(R.id.captureSwitch) }
        // The saved file is a real, playable video of about the recorded length.
        val uri = latestVideo()!!
        val r = android.media.MediaMetadataRetriever()
        r.setDataSource(context, uri)
        val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
        val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
        val frame = r.getFrameAtTime(1_000_000)
        r.release()
        Log.i(TAG, "video: $uri ${ms} ms ${w}x$h frame=${frame?.width}x${frame?.height}")
        // At least as long as we saw it recording; a stalled emulator can show the timer late or
        // handle the stop late, so allow extra length.
        assertTrue("$ms ms long, recorded for about $recordedFor ms", ms in (recordedFor - 1500)..(recordedFor + 12000))
        assertEquals("16:9", 16f / 9f, maxOf(w, h).toFloat() / minOf(w, h), 0.05f)
        assertTrue("has a picture", frame != null)
        // Back to photos: the camera reopens for photos and the shutter takes one.
        onView(withId(R.id.switchPhoto)).perform(click())
        SystemClock.sleep(1500)
        waitForCamera()
        assertTrue("photo modes back", visible(R.id.intentScroll))
        shootAndExpect("image/jpeg") { onView(withId(R.id.shutter)).perform(click()) }
        context.contentResolver.delete(uri, null, null)
    }

    @Test
    fun reviewPlaysVideo() {
        waitForCamera()
        setSimple(true)
        onView(withId(R.id.switchVideo)).perform(click())
        SystemClock.sleep(1500)
        waitForCamera()
        val before = videoCount()
        onView(withId(R.id.shutter)).perform(click())
        waitFor("recording started") { visible(R.id.recTime) }
        SystemClock.sleep(2500)
        onView(withId(R.id.shutter)).perform(click())
        waitFor("video saved", 15_000) { videoCount() > before }
        waitFor("thumbnail back") { visible(R.id.thumbnail) }
        SystemClock.sleep(800)
        onView(withId(R.id.thumbnail)).perform(click())
        onView(withText("Video")).check(matches(isDisplayed()))
        onView(withContentDescription("Play video")).check(matches(isDisplayed()))
        Espresso.pressBack()
        latestVideo()?.let { context.contentResolver.delete(it, null, null) }
    }

    @Test
    fun memoryBudgetPicksProcessingSize() {
        val gb = 1_000_000_000L
        assertEquals("12 MP fits with 1 GB", 4000, Budget.maxSide(4000, 3000, 32, available = gb))
        val big = Budget.maxSide(8160, 6120, 32, available = gb)
        assertTrue("50 MP scaled to fit: $big", big in 5000..5800)
        assertEquals("never below the old size", 3000, Budget.maxSide(4000, 3000, 32, available = 200_000_000L))
        assertEquals("small photos untouched", 2000, Budget.maxSide(2000, 1500, 32, available = 100_000_000L))
    }

    @Test
    fun originalsStayWithinTheirSpace() {
        val dir = java.io.File(context.cacheDir, "prune-test").apply { deleteRecursively(); mkdirs() }
        val now = System.currentTimeMillis()
        for (k in 0 until 5) java.io.File(dir, "p$k.jpg").apply { writeBytes(ByteArray(1_000_000)); setLastModified(now - (5 - k) * 60_000L) }
        OriginalStore.prune(dir, 2_500_000L)
        assertEquals("newest two kept", listOf("p3.jpg", "p4.jpg"), dir.list()!!.sorted())
        OriginalStore.prune(dir, 10L)
        assertEquals("the newest is always kept", listOf("p4.jpg"), dir.list()!!.toList())
        dir.deleteRecursively()
    }

    @Test
    fun nightMergesAtFullResolution() {
        // A 12 MP burst (4000x3000) is merged at full size when the phone has the memory.
        val w = 4000
        val h = 3000
        val frames = (0 until 3).map { k ->
            val rnd = java.util.Random(k.toLong())
            val px = IntArray(w * h) { i ->
                val x = i % w
                val y = i / w
                val v = ((if ((x / 40 + y / 40) % 2 == 0) 70 else 170) + rnd.nextGaussian() * 12).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            val bmp = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
            java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it); bmp.recycle() }.toByteArray()
        }
        val expected = Budget.sideFor(frames[0], 32)
        val t0 = SystemClock.uptimeMillis()
        val result = NightProcessor().process(frames, iso = 800, quality = 90)
        val (ow, oh) = Budget.size(result.jpeg)
        Log.i(TAG, "full-res night: ${ow}x$oh (budget side $expected, heap ${Runtime.getRuntime().maxMemory() / 1_000_000} MB) " +
            "in ${SystemClock.uptimeMillis() - t0} ms, merged ${result.merged}")
        assertEquals(3, result.merged)
        assertTrue("at least the old size", maxOf(ow, oh) >= 3000)
        if (Runtime.getRuntime().maxMemory() >= 500_000_000L) assertEquals("full 12 MP", 4000, maxOf(ow, oh))
    }

    @Test
    fun slidingOnLensButtonsZooms() {
        waitForCamera()
        setSimple(true)
        // Slide left from the 1× button: zooms in smoothly and shows the dial.
        onView(withContentDescription("Zoom 1×")).perform(dragAcross(0.5f, 0.5f, dxDp = -180f))
        assertTrue("dial shown", visible(R.id.zoomBar))
        var shown = 0f
        activityRule.scenario.onActivity { val d = it.findViewById<ZoomDial>(R.id.zoomDial); shown = d.value * d.base }
        Log.i(TAG, "slid to ${shown}x")
        assertTrue("zoomed in by sliding: $shown", shown > 1.6f)
        // And back out to the right.
        onView(withId(R.id.zoomDial)).perform(dragAcross(0.2f, 0.95f))
        activityRule.scenario.onActivity { val d = it.findViewById<ZoomDial>(R.id.zoomDial); shown = d.value * d.base }
        assertTrue("zoomed back out: $shown", shown < 1.3f)
    }

    @Test
    fun holdShutterRecordsQuickVideo() {
        waitForCamera()
        setSimple(true)
        Prefs(context).holdForVideo = true
        val before = videoCount()
        val r = screenRect(R.id.shutter)
        val inst = InstrumentationRegistry.getInstrumentation()
        val t = SystemClock.uptimeMillis()
        inst.sendPointerSync(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, r.exactCenterX(), r.exactCenterY(), 0))
        try {
            waitFor("quick video recording", 25_000) { visible(R.id.recTime) }
            SystemClock.sleep(2500)
        } finally {
            val u = SystemClock.uptimeMillis()
            inst.sendPointerSync(MotionEvent.obtain(t, u, MotionEvent.ACTION_UP, r.exactCenterX(), r.exactCenterY(), 0))
        }
        waitFor("quick video saved", 15_000) { videoCount() > before }
        waitFor("back to photos", 15_000) { !Prefs(context).video && visible(R.id.intentScroll) }
        waitForCamera()
        latestVideo()?.let { context.contentResolver.delete(it, null, null) }
    }

    @Test
    fun lookShowsLiveAndIsAppliedToThePhoto() {
        waitForCamera()
        setSimple(true)
        onView(withId(R.id.btnLook)).perform(click())
        onView(withId(R.id.lookScroll)).check(matches(isDisplayed()))
        onView(withContentDescription("Look Mono")).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
        assertEquals("mono", Prefs(context).look)
        var live: String? = null
        activityRule.scenario.onActivity { live = it.findViewById<OverlayView>(R.id.overlay).look?.id }
        assertEquals("look shown live", "mono", live)
        shootAndExpect("image/jpeg") { onView(withId(R.id.shutter)).perform(click()) }
        // The look is applied in the background: wait for the photo to be marked.
        val uri = latestImage()!!
        waitFor("look applied", 20_000) { description(uri) == "Look: Mono" }
        val bmp = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, uri)) { d, _, _ ->
            d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            d.setTargetSampleSize(8)
        }
        var colour = 0L
        var n = 0
        for (y in 0 until bmp.height step 4) for (x in 0 until bmp.width step 4) {
            val p = bmp.getPixel(x, y)
            colour += abs(((p shr 16) and 0xFF) - ((p shr 8) and 0xFF)) + abs(((p shr 8) and 0xFF) - (p and 0xFF))
            n++
        }
        Log.i(TAG, "mono photo colourfulness ${colour.toDouble() / n}")
        assertTrue("photo is black and white", colour.toDouble() / n < 6.0)
        var name = ""
        context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) }
        assertTrue("unfiltered shot kept for before/after", OriginalStore.get(context, name) != null)
        assertTrue(ReviewActivity.describe(name).first.isNotEmpty())
    }

    @Test
    fun looksMath() {
        val natural = Looks.byId(Looks.NATURAL)
        assertTrue(natural.isNatural)
        assertEquals("unknown id falls back to Natural", Looks.NATURAL, Looks.byId("nope").id)
        val mono = Looks.byId("mono").matrix
        val r = mono[0] * 200 + mono[1] * 100 + mono[2] * 50 + mono[4]
        val g = mono[5] * 200 + mono[6] * 100 + mono[7] * 50 + mono[9]
        assertEquals("mono: equal channels", r, g, 0.01f)
        val film = Looks.byId("film").matrix
        assertTrue("film lifts blacks", film[4] > 10f)
        val noir = Looks.byId("noir")
        assertEquals("no darkening in the centre", 1f, Looks.vignetteFactor(noir, 0f), 0.001f)
        assertTrue("darker corners", Looks.vignetteFactor(noir, 1f) < 0.7f)
    }

    @Test
    fun cleanMergeRemovesPasserBy() {
        // 7 frames, hand shake between them, noise, and a dark "person" walking across.
        val w = 600
        val h = 400
        val n = 7
        val rnd = java.util.Random(3)
        fun scene(x: Int, y: Int) = (90 + 60 * kotlin.math.sin(x / 13.0) * kotlin.math.cos(y / 17.0) + (if ((x / 50 + y / 50) % 2 == 0) 30 else 0)).toInt()
        val shifts = listOf(0 to 0, 3 to -2, -2 to 1, 1 to 1, -3 to -1, 2 to 3, -1 to -2)
        val frames = (0 until n).map { k ->
            val (sx, sy) = shifts[k]
            IntArray(w * h) { i ->
                val ox = i % w + sx
                val oy = i / w + sy
                var v = scene(ox, oy)
                val px = 40 + k * 80
                if (ox in px until px + 60 && oy in 150 until 310) v = 25
                v = (v + rnd.nextGaussian() * 8).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        val ref = n / 2
        val offsets = (0 until n).map { k -> (shifts[ref].first - shifts[k].first) to (shifts[ref].second - shifts[k].second) }
        val out = IntArray(w * h)
        CleanProcessor.merge(frames, offsets, w, h, out)
        fun err(a: IntArray, x0: Int, x1: Int, y0: Int, y1: Int): Double {
            var e = 0.0
            var c = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                e += abs((a[y * w + x] and 0xFF) - scene(x + shifts[ref].first, y + shifts[ref].second).coerceIn(0, 255)); c++
            }
            return e / c
        }
        val person = err(out, 285, 335, 160, 300)
        val noise = err(out, 400, 560, 20, 120)
        Log.i(TAG, "clean: person area error ${err(frames[ref], 285, 335, 160, 300)} -> $person, background ${err(frames[ref], 400, 560, 20, 120)} -> $noise")
        assertTrue("passer-by removed: $person", person < 8)
        assertTrue("less noise: $noise vs ${err(frames[ref], 400, 560, 20, 120)}", noise < err(frames[ref], 400, 560, 20, 120) * 0.7)
    }

    @Test
    fun cleanCaptureSavesOnePhoto() {
        waitForCamera()
        setSimple(true)
        onView(withId(R.id.intentClean)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
        assertTrue("hint", text(R.id.intentHint).contains("passing through"))
        val before = count("image/jpeg")
        onView(withId(R.id.shutter)).perform(click())
        waitFor("clean photo saved", 90_000) { count("image/jpeg") > before }
        waitFor("finished", 20_000) { !shutterBusy() }
        SystemClock.sleep(1500)
        assertEquals("exactly one photo", before + 1, count("image/jpeg"))
        val names = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("%_CLEAN%"), null,
        )?.use { it.count } ?: 0
        assertTrue("a _CLEAN photo was saved", names > 0)
        assertEquals("Clean", ReviewActivity.describe("PROCAM_1_CLEAN.jpg").first)
    }

    @Test
    fun steadyZoomLeavesToTheLensWhatTheLensSteadies() {
        // A lens with optical stabilisation that takes out 70% of every turn (reported as lens
        // shifts with swapped axes and a flipped sign, as on real phones): the share is found from
        // the reports alone, and the viewfinder then cancels only the 30% that is left.
        val rnd = java.util.Random(3)
        val n = 75
        val focalPx = 3000f
        val gyro = FloatArray(2 * n)
        val lens = FloatArray(2 * n)
        for (i in 1 until n) {
            gyro[2 * i] = gyro[2 * i - 2] + (rnd.nextGaussian() * 0.0004).toFloat()
            gyro[2 * i + 1] = gyro[2 * i - 1] + (rnd.nextGaussian() * 0.0004).toFloat()
            lens[2 * i] = -0.7f * focalPx * gyro[2 * i + 1] + (rnd.nextGaussian() * 0.05).toFloat()
            lens[2 * i + 1] = 0.7f * focalPx * gyro[2 * i] + (rnd.nextGaussian() * 0.05).toFloat()
        }
        val share = MotionDeblur.oisShare(gyro, lens, focalPx)
        assertTrue("the lens's share of the steadying is found: $share", share != null && abs(share - 0.7f) < 0.05f)
        // A lens that doesn't follow the gyroscope tells nothing.
        val noise = FloatArray(2 * n) { (rnd.nextGaussian() * 3).toFloat() }
        assertTrue("unrelated lens movement is not trusted", MotionDeblur.oisShare(gyro, noise, focalPx) == null)
        // The viewfinder shift: all of the turn without stabilisation, only the leftover with it.
        val (fullX, fullY) = SteadyZoom.shift(0.002f, -0.001f, 0.0001f, 1f, 100f, 100f)
        val (leftX, leftY) = SteadyZoom.shift(0.002f, -0.001f, 0.0001f, 1f - share!!, 100f, 100f)
        assertEquals(10f, fullX, 0.01f)
        assertEquals(-20f, fullY, 0.01f)
        assertEquals(fullX * (1f - share), leftX, 0.01f)
        assertEquals(fullY * (1f - share), leftY, 0.01f)
        // Never further than the spare picture at the edges.
        assertEquals(-5f, SteadyZoom.shift(0.002f, 0f, 0.0001f, 1f, 5f, 5f).second, 0.001f)
    }

    @Test
    fun steadyZoomPlacesEachFrameByWhenItWasTaken() {
        // A ring of gyroscope readings every 5 ms (wrapped around): the turn is looked up at the
        // moment a preview frame was taken, not at the latest reading.
        val size = 8
        val ts = LongArray(size)
        val xs = FloatArray(size)
        val ys = FloatArray(size)
        var head = 0
        for (k in 0 until 11) { ts[head] = k * 5_000_000L; xs[head] = k * 0.001f; ys[head] = -k * 0.002f; head = (head + 1) % size }
        val mid = SteadyZoom.turnAt(ts, xs, ys, head, size, 32_500_000L)!!
        assertEquals("between two readings", 0.0065f, mid.first, 1e-5f)
        assertEquals(-0.013f, mid.second, 1e-5f)
        assertEquals("just after the latest reading", 0.010f, SteadyZoom.turnAt(ts, xs, ys, head, size, 60_000_000L)!!.first, 1e-6f)
        assertTrue("older than the history", SteadyZoom.turnAt(ts, xs, ys, head, size, 10_000_000L) == null)
        assertTrue("another clock altogether", SteadyZoom.turnAt(ts, xs, ys, head, size, 5_000_000_000L) == null)
    }

    /** A viewfinder frame of a fixed random texture, moved by (ox, oy) pixels. */
    private fun steadyScene(w: Int, h: Int, ox: Float, oy: Float, rnd: java.util.Random): IntArray {
        val size = 256
        val tex = FloatArray(size * size)
        val seed = java.util.Random(11)
        val raw = FloatArray(size * size) { seed.nextFloat() * 255f }
        for (y in 0 until size) for (x in 0 until size) {
            var sum = 0f
            for (j in -1..1) for (i in -1..1) sum += raw[((y + j + size) % size) * size + (x + i + size) % size]
            tex[y * size + x] = sum / 9f
        }
        fun at(i: Int, j: Int) = tex[((j % size + size) % size) * size + (i % size + size) % size]
        return IntArray(w * h) { k ->
            val fx = k % w - ox + 60f
            val fy = k / w - oy + 60f
            val x0 = kotlin.math.floor(fx).toInt()
            val y0 = kotlin.math.floor(fy).toInt()
            val ax = fx - x0
            val ay = fy - y0
            val v = at(x0, y0) * (1 - ax) * (1 - ay) + at(x0 + 1, y0) * ax * (1 - ay) + at(x0, y0 + 1) * (1 - ax) * ay + at(x0 + 1, y0 + 1) * ax * ay
            (v + rnd.nextGaussian().toFloat() * 2f).toInt().coerceIn(0, 255)
        }
    }

    @Test
    fun steadyZoomMeasuresHowFarThePictureMoved() {
        val rnd = java.util.Random(3)
        val w = 128
        val h = 160
        val a = steadyScene(w, h, 0f, 0f, rnd)
        for ((ox, oy) in listOf(0f to 0f, 3f to -2f, -7.3f to 11.6f)) {
            val d = SteadyLearner.measure(a, steadyScene(w, h, ox, oy, rnd), w, h)
            assertTrue("moved $ox, $oy: ${d?.joinToString()}", d != null && abs(d[0] - ox) < 0.25f && abs(d[1] - oy) < 0.25f)
        }
        assertTrue("further than can be followed: no answer", SteadyLearner.measure(a, steadyScene(w, h, 30f, 0f, rnd), w, h) == null)
        assertTrue("a blank wall: no answer", SteadyLearner.measure(IntArray(w * h) { 128 }, IntArray(w * h) { 128 }, w, h) == null)
    }

    /**
     * The steadying must work on any phone: whatever share of the tremor the lens takes out,
     * however old the picture is when shown, whichever way the axes run — learnt from the picture.
     */
    @Test
    fun steadyZoomLearnsThePhoneFromThePicture() {
        fun turn(tNs: Long): Pair<Float, Float> {
            val t = tNs / 1e9
            val x = 0.004 * sin(2 * PI * 0.5 * t + 1.0) + 0.0012 * sin(2 * PI * 4.3 * t + 2.0) + 0.0008 * sin(2 * PI * 8.1 * t + 0.3)
            val y = 0.003 * sin(2 * PI * 0.7 * t + 0.5) + 0.0010 * sin(2 * PI * 5.1 * t + 4.0) + 0.0009 * sin(2 * PI * 7.3 * t + 5.0)
            return x.toFloat() to y.toFloat()
        }
        // (what a turn really does to the picture, age of the picture on arrival, frames carry usable timestamps)
        val phones = listOf(
            Triple(floatArrayOf(0f, 1f, 1f, 0f), 85L, true),          // no stabilised lens
            Triple(floatArrayOf(0f, 0.3f, 0.3f, 0f), 60L, true),      // the lens takes out 70 %
            Triple(floatArrayOf(0f, 1f, 1f, 0f), 130L, false),        // slow pipeline, frames can't be timed
            Triple(floatArrayOf(0.8f, 0.1f, -0.1f, -0.8f), 50L, false), // axes the other way round
        )
        val w = 128
        val h = 160
        val radPerPx = 5.4e-5f
        val sx = 1080f / w
        val sy = 1350f / h
        for ((truth, lagMs, stamped) in phones) {
            val rnd = java.util.Random(7)
            val learner = SteadyLearner()
            var raw = 0.0
            var left = 0.0
            var prevRaw: Pair<Float, Float>? = null
            var prevLeft: Pair<Float, Float>? = null
            for (f in 0 until 150) {
                val taken = 1_000_000_000L + f * 33_333_333L
                val (tx, ty) = turn(taken)
                val dx = (truth[0] * tx + truth[1] * ty) / radPerPx
                val dy = (truth[2] * tx + truth[3] * ty) / radPerPx
                val arrival = taken + lagMs * 1_000_000L + (rnd.nextGaussian() * 3e6).toLong()
                val stamp = taken - 10_000_000L // start of exposure
                // Steady this frame with what is known so far (as the app does), and see what is left.
                val m = learner.model ?: floatArrayOf(0f, 1f, 1f, 0f)
                val clock = if (learner.clock >= 0) learner.clock else if (stamped) SteadyLearner.STAMP_CLOCK else SteadyLearner.ARRIVAL_CLOCK
                val (ux, uy) = turn(SteadyLearner.time(clock, stamp, arrival))
                val lx = dx - (m[0] * ux + m[1] * uy) / radPerPx
                val ly = dy - (m[2] * ux + m[3] * uy) / radPerPx
                if (f >= 90) {
                    prevRaw?.let { raw += hypot(dx - it.first, dy - it.second).toDouble().pow(2) }
                    prevLeft?.let { left += hypot(lx - it.first, ly - it.second).toDouble().pow(2) }
                }
                prevRaw = dx to dy
                prevLeft = lx to ly
                val turns = FloatArray(2 * SteadyLearner.CLOCKS.size) { i ->
                    if (SteadyLearner.CLOCKS[i / 2].stamped && !stamped) Float.NaN
                    else turn(SteadyLearner.time(i / 2, stamp, arrival)).let { if (i % 2 == 0) it.first else it.second }
                }
                learner.add(SteadyLearner.Sample(steadyScene(w, h, dx / sx, dy / sy, rnd), w, h, sx, sy, radPerPx, turns, arrival))
            }
            val m = learner.model
            val what = "truth ${truth.joinToString()}, ${lagMs} ms old, stamped $stamped: learnt ${m?.joinToString()} clock ${learner.clock}"
            assertTrue("learnt: $what", m != null && (0..3).all { abs(m[it] - truth[it]) < 0.12f })
            assertEquals("frames timed by their own timestamps where they have them: $what", stamped, SteadyLearner.CLOCKS[learner.clock].stamped)
            assertTrue("shake cut to under a third (${sqrt(raw)} -> ${sqrt(left)}): $what", left < raw / 9)
        }
        // A blank wall teaches nothing, and nothing is made up.
        val blank = SteadyLearner()
        for (f in 0 until 60) {
            val t = 1_000_000_000L + f * 33_333_333L
            val turns = FloatArray(2 * SteadyLearner.CLOCKS.size) { i -> turn(SteadyLearner.time(i / 2, t, t + 80_000_000L)).let { if (i % 2 == 0) it.first else it.second } }
            blank.add(SteadyLearner.Sample(IntArray(w * h) { 128 }, w, h, sx, sy, radPerPx, turns, t + 80_000_000L))
        }
        assertTrue("nothing learnt from a blank wall", blank.model == null)
        // Whatever is learnt, the picture is never thrown further than the phone turned.
        val wild = floatArrayOf(0f, 5f, 5f, 0f)
        SteadyLearner.limit(wild)
        assertTrue("limited: ${wild.joinToString()}", wild[1] <= 1.31f)
        // Shifts ease into the limit of the spare picture rather than hitting it.
        assertEquals("small shifts exact", 20f, SteadyZoom.soft(20f, 100f), 0.001f)
        assertTrue("eases off", SteadyZoom.soft(80f, 100f) in 60f..80f)
        assertTrue("never past the spare picture", SteadyZoom.soft(-500f, 100f) >= -100f)
        assertTrue("more spare picture at higher zoom", SteadyZoom.margin(20f) > SteadyZoom.margin(4f))
    }

    /** A dim zoom shot must not be pushed to the sensor's grainiest setting; what grain there is, is cleaned. */
    @Test
    fun superZoomKeepsGrainInCheck() {
        val isoR = android.util.Range(50, 16000)
        val expR = android.util.Range(100_000L, 16_000_000_000L)
        // Dim scene (auto: ISO 1600, 1/16 s), shaky at high zoom (safe 1/160 s): two stops faster, not ISO 16000.
        val (iso, exp) = SuperZoomProcessor.plan(1600, 62_500_000L, 9_000_000L, isoR, expR)
        assertEquals("sensitivity capped", SuperZoomProcessor.MAX_PLAN_ISO, iso)
        assertEquals("same brightness", 1600.0 * 62_500_000L, iso.toDouble() * exp, 1600.0 * 62_500_000L * 0.02)
        // Bright scene: nothing to raise.
        assertEquals(100, SuperZoomProcessor.plan(100, 2_000_000L, 9_000_000L, isoR, expR).first)
        // Colour speckle goes, brightness detail stays.
        val rnd = java.util.Random(1)
        val w = 201
        val h = 151
        val px = IntArray(w * h) { i ->
            val base = if (i % w < w / 2) 60 else 180
            fun n() = (base + rnd.nextGaussian() * 25).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (n() shl 16) or (n() shl 8) or n()
        }
        fun lum(v: Int) = 0.299 * ((v shr 16) and 255) + 0.587 * ((v shr 8) and 255) + 0.114 * (v and 255)
        fun speckle(p: IntArray): Double {
            var sum = 0.0
            var k = 0
            for (y in 10 until h - 10) for (x in 10 until w / 2 - 10) {
                val v = p[y * w + x]
                sum += (((v shr 16) and 255) - lum(v)).pow(2) + ((v and 255) - lum(v)).pow(2)
                k++
            }
            return sqrt(sum / k)
        }
        val f = SuperRes.Frame(px.copyOf(), w, h)
        SuperRes.smoothChroma(f, 3)
        assertTrue("colour speckle removed: ${speckle(px)} -> ${speckle(f.px)}", speckle(f.px) < speckle(px) / 5)
        assertTrue("brightness untouched", px.indices.all { abs(lum(px[it]) - lum(f.px[it])) < 1.5 })
    }

    /** Drag up on the viewfinder: brighter, with a badge that takes it back to automatic. */
    @Test
    fun dragOnViewfinderSetsBrightness() {
        waitForCamera()
        setSimple(true)
        fun drag(fy0: Float, fy1: Float) = androidx.test.espresso.action.GeneralSwipeAction(
            androidx.test.espresso.action.Swipe.SLOW,
            { v -> val p = IntArray(2); v.getLocationOnScreen(p); floatArrayOf(p[0] + v.width * 0.5f, p[1] + v.height * fy0) },
            { v -> val p = IntArray(2); v.getLocationOnScreen(p); floatArrayOf(p[0] + v.width * 0.5f, p[1] + v.height * fy1) },
            androidx.test.espresso.action.Press.FINGER,
        )
        onView(withId(R.id.textureView)).perform(drag(0.6f, 0.35f))
        waitFor("brighter") { visible(R.id.lockBadge) && text(R.id.lockBadge).startsWith("BRIGHTNESS +") }
        onView(withId(R.id.lockBadge)).perform(click())
        waitFor("back to automatic") { !visible(R.id.lockBadge) }
        onView(withId(R.id.textureView)).perform(drag(0.35f, 0.6f))
        waitFor("darker") { visible(R.id.lockBadge) && text(R.id.lockBadge).startsWith("BRIGHTNESS -") }
        onView(withId(R.id.lockBadge)).perform(click())
    }

    @Test
    fun steadyZoomFilterLetsPansThrough() {
        // Tremor: a short turn is held (cancelled on screen) ...
        var a = 0f to 0f
        repeat(5) { a = SteadyZoom.step(a.first, a.second, 0.2f, -0.1f, 0.02f) }
        assertTrue("short turn tracked: $a", a.first > 0.015f && a.second < -0.007f)
        // ... while a long steady pan fades through (the view follows the pan after a while).
        repeat(300) { a = SteadyZoom.step(a.first, a.second, 0f, 0f, 0.02f) }
        assertTrue("released after the movement stops: $a", abs(a.first) < 1e-3f && abs(a.second) < 1e-3f)
    }

    @Test
    fun steadyZoomEnlargesPreviewOnlyAtHighZoom() {
        val sm = context.getSystemService(android.hardware.SensorManager::class.java)
        assumeTrue("needs a gyroscope", sm.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE) != null)
        waitForCamera()
        setSimple(true)
        fun scale(): Float {
            var sx = 0f
            activityRule.scenario.onActivity {
                val m = it.findViewById<android.view.TextureView>(R.id.textureView).getTransform(null)
                val v = FloatArray(9)
                m.getValues(v)
                sx = v[android.graphics.Matrix.MSCALE_X]
            }
            return sx
        }
        assertEquals("1×: no steadying", 1f, scale(), 0.01f)
        onView(withContentDescription("Zoom 5×")).perform(click())
        waitFor("steadying at 5×") { abs(scale() - SteadyZoom.margin(5f)) < 0.02f }
        onView(withContentDescription("Zoom 1×")).perform(click())
        waitFor("back to normal at 1×") { abs(scale() - 1f) < 0.01f }
    }

    @Test
    fun lookStrengthAndMyLook() {
        val mono = Looks.byId("mono")
        val none = Looks.withStrength(mono, 0f)
        val identity = android.graphics.ColorMatrix().array
        for (i in 0 until 20) assertEquals("strength 0 = Natural", identity[i], none.matrix[i], 1e-4f)
        val half = Looks.withStrength(mono, 0.5f)
        assertEquals("halfway", (identity[1] + mono.matrix[1]) / 2, half.matrix[1], 1e-4f)
        val noir = Looks.byId("noir")
        assertEquals("vignette scales too", noir.vignette / 2, Looks.withStrength(noir, 0.5f).vignette, 1e-4f)
        // My look: warm makes grey reddish, cool makes it bluish.
        fun redMinusBlue(l: Looks.Look) = l.matrix[0] * 128 + l.matrix[1] * 128 + l.matrix[2] * 128 + l.matrix[4] -
            (l.matrix[10] * 128 + l.matrix[11] * 128 + l.matrix[12] * 128 + l.matrix[14])
        assertTrue(redMinusBlue(Looks.custom(Looks.Custom(warmth = 1f, fade = 0f, grain = 0f, vignette = 0f))) > 20)
        assertTrue(redMinusBlue(Looks.custom(Looks.Custom(warmth = -1f, fade = 0f, grain = 0f, vignette = 0f))) < -20)
    }

    @Test
    fun myLookEditorChangesTheLiveLook() {
        waitForCamera()
        setSimple(true)
        onView(withId(R.id.btnLook)).perform(click())
        onView(withContentDescription("Look My look")).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
        assertEquals(Looks.MINE, Prefs(context).look)
        onView(withId(R.id.lookTools)).check(matches(isDisplayed()))
        onView(withId(R.id.lookEdit)).check(matches(isDisplayed()))
        // Strength: half.
        onView(withId(R.id.lookStrength)).perform(tapAt(0.5f, 0.5f))
        assertTrue("strength about half: ${Prefs(context).lookStrength}", Prefs(context).lookStrength in 35..65)
        // Editor: warmth all the way up.
        onView(withId(R.id.lookEdit)).perform(click())
        onView(withContentDescription("My look Warmth")).perform(tapAt(0.98f, 0.5f))
        assertTrue("warmth saved: ${Prefs(context).customValue("myWarmth", 0)}", Prefs(context).customValue("myWarmth", 0) > 85)
        onView(withText("Done")).perform(click())
        var live: String? = null
        activityRule.scenario.onActivity { live = it.findViewById<OverlayView>(R.id.overlay).look?.id }
        assertEquals(Looks.MINE, live)
    }

    @Test
    fun videoLevelMath() {
        assertEquals(1f, VideoEffects.coverScale(1920, 1080, 0f), 1e-4f)
        val s = VideoEffects.coverScale(1920, 1080, 0.14f)
        assertTrue("about 24% enlargement at 8°: $s", s in 1.2f..1.28f)
        assertEquals("tilted 5° clockwise", Math.toRadians(5.0).toFloat(), VideoEffects.levelFor(5, 0, 0.14f), 1e-4f)
        assertEquals("tilted 5° anticlockwise", -Math.toRadians(5.0).toFloat(), VideoEffects.levelFor(355, 0, 0.14f), 1e-4f)
        assertEquals("relative to a landscape start", Math.toRadians(5.0).toFloat(), VideoEffects.levelFor(95, 90, 0.14f), 1e-4f)
        assertEquals("limited", 0.14f, VideoEffects.levelFor(40, 0, 0.14f), 1e-4f)
    }

    private fun recordClip(seconds: Long): android.net.Uri {
        val before = videoCount()
        onView(withId(R.id.shutter)).perform(click())
        waitFor("recording started", 20_000) { visible(R.id.recTime) }
        SystemClock.sleep(seconds * 1000)
        onView(withId(R.id.shutter)).perform(click())
        waitFor("video saved", 20_000) { videoCount() > before }
        waitFor("recording UI gone") { !visible(R.id.recTime) }
        return latestVideo()!!
    }

    @Test
    fun videoIsRecordedWithTheLook() {
        waitForCamera()
        setSimple(true)
        onView(withId(R.id.switchVideo)).perform(click())
        SystemClock.sleep(1500)
        waitForCamera()
        onView(withId(R.id.btnLook)).perform(click())
        onView(withContentDescription("Look Mono")).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
        onView(withId(R.id.btnLook)).perform(click())
        val uri = recordClip(3)
        val r = android.media.MediaMetadataRetriever()
        r.setDataSource(context, uri)
        val frame = r.getFrameAtTime(1_500_000)!!
        r.release()
        var colour = 0L
        var n = 0
        for (y in 0 until frame.height step 8) for (x in 0 until frame.width step 8) {
            val p = frame.getPixel(x, y)
            colour += abs(((p shr 16) and 0xFF) - ((p shr 8) and 0xFF)) + abs(((p shr 8) and 0xFF) - (p and 0xFF))
            n++
        }
        Log.i(TAG, "mono video colourfulness ${colour.toDouble() / n} (${frame.width}x${frame.height})")
        assertTrue("video frames are black and white", colour.toDouble() / n < 8.0)
        context.contentResolver.delete(uri, null, null)
    }

    @Test
    fun timelapseSpeedsUpTheClip() {
        waitForCamera()
        setSimple(true)
        onView(withId(R.id.switchVideo)).perform(click())
        SystemClock.sleep(1500)
        waitForCamera()
        onView(withId(R.id.kindTimelapse)).perform(click())
        SystemClock.sleep(1500)
        waitForCamera()
        assertTrue(text(R.id.intentHint).contains("Time-lapse"))
        val uri = recordClip(9)
        val r = android.media.MediaMetadataRetriever()
        r.setDataSource(context, uri)
        val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        r.release()
        Log.i(TAG, "time-lapse: 9 s recorded -> $ms ms")
        assertTrue("much shorter than the 9 s recorded: $ms ms", ms in 100..3000)
        context.contentResolver.delete(uri, null, null)
    }

    @Test
    fun levelHorizonEnlargesPreviewWhileRecording() {
        waitForCamera()
        setSimple(true)
        onView(withId(R.id.switchVideo)).perform(click())
        SystemClock.sleep(1500)
        waitForCamera()
        onView(withId(R.id.kindLevel)).perform(click())
        assertTrue(Prefs(context).levelVideo)
        val before = videoCount()
        onView(withId(R.id.shutter)).perform(click())
        waitFor("recording started", 20_000) { visible(R.id.recTime) }
        SystemClock.sleep(1500)
        var sx = 0f
        activityRule.scenario.onActivity {
            val v = FloatArray(9)
            it.findViewById<android.view.TextureView>(R.id.textureView).getTransform(null).getValues(v)
            sx = kotlin.math.hypot(v[android.graphics.Matrix.MSCALE_X].toDouble(), v[android.graphics.Matrix.MSKEW_Y].toDouble()).toFloat()
        }
        Log.i(TAG, "level: preview scale $sx while recording")
        assertTrue("preview shows the levelled (enlarged) framing: $sx", sx > 1.1f)
        onView(withId(R.id.shutter)).perform(click())
        waitFor("video saved", 20_000) { videoCount() > before }
        latestVideo()?.let { context.contentResolver.delete(it, null, null) }
    }

    @Test
    fun focusStackMakesEverythingSharp() {
        val w = 500
        val h = 400
        val n = 5
        val rnd = java.util.Random(1)
        val truth = FloatArray(w * h) { 128f + 90f * (rnd.nextFloat() - 0.5f) }
        val blurred = truth.copyOf().also { repeat(3) { _ -> PortraitProcessor.boxBlur(it, w, h, 3) } }
        // Each shot is sharp in one vertical band only (like focus at one distance).
        val frames = (0 until n).map { k ->
            IntArray(w * h) { i ->
                val v = (if ((i % w) * n / w == k) truth[i] else blurred[i]).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        val out = IntArray(w * h)
        FocusStackProcessor.stack(frames, frames.map { FocusStackProcessor.sharpness(it, w, h, 2) }, List(n) { 0 to 0 }, w, h, out)
        fun err(a: IntArray) = a.indices.sumOf { abs((a[it] and 0xFF) - truth[it].toInt().coerceIn(0, 255)).toDouble() } / a.size
        Log.i(TAG, "focus stack: single ${err(frames[0])} -> stacked ${err(out)}")
        assertTrue("stacked is sharp everywhere: ${err(out)} vs ${err(frames[0])}", err(out) < err(frames[0]) * 0.2)
        val plan = FocusStackProcessor.plan(5f, 10f, 5)
        assertTrue("near to far: ${plan.toList()}", plan.first() > plan.last() && plan.all { it in 0f..10f })
    }

    @Test
    fun lightTrailsKeepTheBrightest() {
        val w = 4
        val acc = IntArray(w * 1) { 0xFF101010.toInt() }
        val frame = intArrayOf(0xFF000000.toInt(), 0xFFFF8000.toInt(), 0xFF101010.toInt(), 0xFF00FF00.toInt())
        TrailsProcessor.lighten(acc, frame, w, 1, 0, 0)
        assertEquals(0xFF101010.toInt(), acc[0])
        assertEquals(0xFFFF8010.toInt(), acc[1])
        assertEquals(0xFF10FF10.toInt(), acc[3])
        // Exposure: same brightness as auto, frames up to 1/3 s.
        val (iso, t) = TrailsProcessor.plan(1600, 33_000_000L, Range(50, 3200), Range(100_000L, 1_000_000_000L), 333_000_000L)
        assertEquals(1600.0 * 33_000_000, iso.toDouble() * t, 1600.0 * 33_000_000 * 0.05)
        assertTrue(t <= 333_000_000L)
    }

    private fun captureAndExpect(intentId: Int, suffix: String, timeoutMs: Long = 90_000) {
        waitForCamera()
        setSimple(true)
        onView(withId(intentId)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
        val before = count("image/jpeg")
        onView(withId(R.id.shutter)).perform(click())
        waitFor("$suffix photo saved", timeoutMs) { count("image/jpeg") > before }
        waitFor("finished", 30_000) { !shutterBusy() }
        SystemClock.sleep(1500)
        assertEquals("exactly one photo", before + 1, count("image/jpeg"))
        val names = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("%$suffix%"), null,
        )?.use { it.count } ?: 0
        assertTrue("a $suffix photo was saved", names > 0)
    }

    @Test
    fun lightTrailsCaptureSavesStackedPhoto() {
        captureAndExpect(R.id.intentTrails, "_TRAILS")
        assertEquals("Light trails", ReviewActivity.describe("PROCAM_1_TRAILS.jpg").first)
    }

    @Test
    fun closeUpCaptureSavesStackedPhoto() {
        assumeTrue("needs manual focus", backCameraHas(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR))
        captureAndExpect(R.id.intentCloseup, "_CLOSEUP")
        assertEquals("Close-up", ReviewActivity.describe("PROCAM_1_CLOSEUP.jpg").first)
    }

    @Test
    fun autoMergesShotsInDimLight() {
        waitForCamera()
        setSimple(true)
        context.getSharedPreferences("procam", android.content.Context.MODE_PRIVATE).edit().putBoolean("forceLowLight", true).commit()
        val before = count("image/jpeg")
        onView(withId(R.id.shutter)).perform(click())
        waitFor("low-light photo saved", 60_000) { count("image/jpeg") > before }
        waitFor("finished", 20_000) { !shutterBusy() }
        SystemClock.sleep(1500)
        assertEquals("exactly one photo", before + 1, count("image/jpeg"))
        val names = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("%_LOWLIGHT%"), null,
        )?.use { it.count } ?: 0
        assertTrue("a _LOWLIGHT photo was saved", names > 0)
    }

    @Test
    fun zoomEnhanceMergesFullSizeTilesSeamlessly() {
        // 6 shots of a detailed scene, each shifted by a fraction of a pixel (hand shake) and noisy,
        // as JPEGs bigger than one tile: the merge must keep the full size, stitch tiles without
        // seams, and come out closer to the truth than a single shot.
        val w = 2300
        val h = 1700
        val rnd = java.util.Random(4)
        val tex = FloatArray((w + 8) * (h + 8)) { rnd.nextFloat() }
        PortraitProcessor.boxBlur(tex, w + 8, h + 8, 1)
        fun truth(x: Float, y: Float): Float {
            val xi = x.toInt().coerceIn(0, w + 6)
            val yi = y.toInt().coerceIn(0, h + 6)
            val fx = x - xi
            val fy = y - yi
            val a = tex[yi * (w + 8) + xi] * (1 - fx) + tex[yi * (w + 8) + xi + 1] * fx
            val b = tex[(yi + 1) * (w + 8) + xi] * (1 - fx) + tex[(yi + 1) * (w + 8) + xi + 1] * fx
            return 50f + 150f * (a * (1 - fy) + b * fy)
        }
        val shifts = listOf(0f to 0f, 0.5f to 0.25f, 1.25f to 0.75f, 0.75f to 1.5f, 1.5f to 0.5f, 0.25f to 1.25f, 1f to 1f)
        val jpegs = shifts.mapIndexed { k, (sx, sy) ->
            val noise = java.util.Random(k * 31L)
            val px = IntArray(w * h) { i ->
                val v = (truth(i % w + 2 + sx, i / w + 2 + sy) + noise.nextGaussian() * 8).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            val bmp = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
            java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it); bmp.recycle() }.toByteArray()
        }
        val result = SuperZoomProcessor().enhance(jpegs, 95)
        assertEquals("full width", w, result.width)
        assertEquals("full height", h, result.height)
        val out = android.graphics.BitmapFactory.decodeByteArray(result.jpeg, 0, result.jpeg.size)
        // The merge is aligned to the sharpest shot (its "before"): compare with that shot's view.
        val ref = jpegs.indexOfFirst { it === result.before }.coerceAtLeast(0)
        val (rx, ry) = shifts[ref]
        val single = android.graphics.BitmapFactory.decodeByteArray(jpegs[ref], 0, jpegs[ref].size)
        fun err(b: android.graphics.Bitmap): Double {
            var e = 0.0
            var n = 0
            for (y in 40 until h - 40 step 3) for (x in 40 until w - 40 step 3) {
                e += abs((b.getPixel(x, y) and 0xFF) - truth(x + 2f + rx, y + 2f + ry)); n++
            }
            return e / n
        }
        // Seams: the merged picture across a tile border is as good as inside a tile.
        fun errAt(b: android.graphics.Bitmap, x0: Int): Double {
            var e = 0.0
            var n = 0
            for (y in 100 until h - 100) for (x in x0 - 3..x0 + 3) {
                e += abs((b.getPixel(x, y) and 0xFF) - truth(x + 2f + rx, y + 2f + ry)); n++
            }
            return e / n
        }
        val merged = err(out)
        val one = err(single)
        Log.i(TAG, "zoom enhance: single $one -> merged $merged; at tile border ${errAt(out, 1024)} vs inside ${errAt(out, 600)}")
        assertTrue("merged closer to the truth: $merged vs $one", merged < one * 0.85)
        assertTrue("no seam at the tile border", errAt(out, 1024) < errAt(out, 600) * 1.3 + 0.5)
    }

    @Test
    fun zoomEnhanceHandles50MpAtHalfSize() {
        // Bigger than 16 MP: worked at half size instead of running out of memory.
        val w = 5000
        val h = 3400
        val rnd = java.util.Random(9)
        val base = IntArray(w * h) { i -> val v = (90 + 60 * kotlin.math.sin((i % w) / 7.0) * kotlin.math.cos((i / w) / 9.0)).toInt()
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val jpegs = (0 until 3).map {
            val px = IntArray(w * h) { i -> val v = ((base[i] and 0xFF) + rnd.nextInt(7) - 3).coerceIn(0, 255); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
            val bmp = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
            java.io.ByteArrayOutputStream().also { o -> bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, o); bmp.recycle() }.toByteArray()
        }
        val r = SuperZoomProcessor().enhance(jpegs, 90)
        Log.i(TAG, "50 MP enhance: ${r.width}x${r.height}")
        assertEquals(w / 2, r.width)
        assertEquals(h / 2, r.height)
    }

    @Test
    fun deconvolutionSharpensBetterThanUnsharpMask() {
        val w = 400
        val h = 300
        val rnd = java.util.Random(2)
        val tex = FloatArray(w * h) { rnd.nextFloat() }
        PortraitProcessor.boxBlur(tex, w, h, 1)
        val truth = FloatArray(w * h) { i ->
            var v = 60f + 140f * tex[i]
            if (((i % w) / 20 + (i / w) / 20) % 2 == 0 && i % w < 200) v = if (v > 128) 220f else 30f
            v
        }
        // Gaussian blur sigma 1 (lens + merge), then a little noise.
        val k = FloatArray(7) { val d = (it - 3).toFloat(); kotlin.math.exp(-d * d / 2f) }.let { a -> val sum = a.sum(); FloatArray(7) { a[it] / sum } }
        val t = FloatArray(w * h)
        val blurred = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) { var a = 0f; for (j in -3..3) a += k[j + 3] * truth[y * w + (x + j).coerceIn(0, w - 1)]; t[y * w + x] = a }
        for (y in 0 until h) for (x in 0 until w) { var a = 0f; for (j in -3..3) a += k[j + 3] * t[(y + j).coerceIn(0, h - 1) * w + x]; blurred[y * w + x] = a }
        val noise = java.util.Random(5)
        fun frame() = SuperRes.Frame(IntArray(w * h) { i ->
            val v = (blurred[i] + noise.nextGaussian().toFloat() * 1.5f).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }, w, h)
        fun err(f: SuperRes.Frame): Double {
            var e = 0.0
            for (y in 5 until h - 5) for (x in 5 until w - 5) e += abs((f.px[y * w + x] and 0xFF) - truth[y * w + x])
            return e / ((w - 10) * (h - 10))
        }
        val unsharp = frame().also { SuperRes.sharpen(it) }
        val deconv = frame().also { Deconvolve.sharpen(it) }
        Log.i(TAG, "sharpening: blurred ${err(frame())}, unsharp ${err(unsharp)}, deconvolve ${err(deconv)}")
        assertTrue("deconvolution closer to the truth: ${err(deconv)} vs ${err(unsharp)}", err(deconv) < err(unsharp) * 0.97)
    }

    @Test
    fun rawMergeBeatsSingleFrame() {
        val w = 200
        val h = 160
        val rnd = java.util.Random(7)
        val tex = FloatArray((w + 20) * (h + 20)) { rnd.nextFloat() }.also { PortraitProcessor.boxBlur(it, w + 20, h + 20, 1) }
        fun t(x: Float, y: Float): Float {
            val xi = kotlin.math.floor(x).toInt().coerceIn(0, w + 18)
            val yi = kotlin.math.floor(y).toInt().coerceIn(0, h + 18)
            val fx = x - xi
            val fy = y - yi
            val a = tex[yi * (w + 20) + xi] * (1 - fx) + tex[yi * (w + 20) + xi + 1] * fx
            val b = tex[(yi + 1) * (w + 20) + xi] * (1 - fx) + tex[(yi + 1) * (w + 20) + xi + 1] * fx
            return a * (1 - fy) + b * fy
        }
        fun scene(c: Int, x: Float, y: Float): Float {
            val d = t(x + 5, y + 5)
            return when (c) { 0 -> 0.15f + 0.6f * d; 1 -> 0.2f + 0.6f * d; else -> 0.1f + 0.5f * d }
        }
        val offs = listOf(0f to 0f, 0.5f to 0.25f, 1.25f to 0.75f, 0.75f to 1.5f, 1.5f to 0.5f, 0.25f to 1.25f)
        val frames = offs.mapIndexed { k, (dx, dy) ->
            val nz = java.util.Random(100L + k)
            ShortArray(w * h) { i ->
                val x = i % w
                val y = i / w
                val v = scene(RawMerge.channel(0, x, y), x - dx, y - dy)
                (64f + v * 959f + nz.nextGaussian().toFloat() * 22f).toInt().coerceIn(0, 1023).toShort()
            }
        }
        val ident = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        fun burst(fr: List<ShortArray>, o: List<Pair<Float, Float>>) =
            RawMerge.Burst(fr, w, h, 0, o, FloatArray(4) { 64f }, 1023f, floatArrayOf(1f, 1f, 1f), ident, null, null)
        fun err(out: FloatArray, s: Float): Double {
            val ow = (w * s).toInt()
            val oh = (h * s).toInt()
            var e = 0.0
            var n = 0
            for (y in (8 * s).toInt() until oh - (8 * s).toInt()) for (x in (8 * s).toInt() until ow - (8 * s).toInt()) for (c in 0..2) {
                e += abs(out[(y * ow + x) * 3 + c] - scene(c, (x + 0.5f) / s - 0.5f, (y + 0.5f) / s - 0.5f)); n++
            }
            return e / n * 255
        }
        // Alignment from the green previews.
        val previews = frames.map { RawMerge.greenPreview(burst(frames, offs), it) }
        val found = SuperRes.offsets(previews, 1).map { (it.dx * 2) to (it.dy * 2) }
        val single2 = err(RawMerge.mergeRegion(burst(listOf(frames[0]), listOf(0f to 0f)), 2f, 0, 0, 2 * w, 2 * h), 2f)
        val merged2 = err(RawMerge.mergeRegion(burst(frames, found), 2f, 0, 0, 2 * w, 2 * h), 2f)
        val single1 = err(RawMerge.mergeRegion(burst(listOf(frames[0]), listOf(0f to 0f)), 1f, 0, 0, w, h), 1f)
        val merged1 = err(RawMerge.mergeRegion(burst(frames, found), 1f, 0, 0, w, h), 1f)
        Log.i(TAG, "raw merge: offsets $found; 1x $single1 -> $merged1; 2x $single2 -> $merged2")
        assertTrue("1x: merged better than one frame ($merged1 vs $single1)", merged1 < single1 * 0.9)
        assertTrue("2x: merged better than one frame ($merged2 vs $single2)", merged2 < single2 * 0.8)
    }

    @Test
    fun colourAndToneMatchTheCamera() {
        val rnd = java.util.Random(3)
        val truth = floatArrayOf(1.15f, -0.1f, -0.05f, -0.05f, 1.1f, -0.05f, 0f, -0.2f, 1.2f)
        val ours = FloatArray(1200) { rnd.nextFloat() * 0.6f + 0.05f }
        val theirs = FloatArray(1200) { i -> val k = i / 3; val c = i % 3
            truth[c * 3] * ours[k * 3] + truth[c * 3 + 1] * ours[k * 3 + 1] + truth[c * 3 + 2] * ours[k * 3 + 2] }
        val m = RawMerge.fitColour(ours, theirs)
        for (i in 0 until 9) assertEquals("colour matrix [$i]", truth[i], m[i], 0.02f)
        val lin = FloatArray(200) { 0.001f * Math.pow(1.04, it.toDouble()).toFloat() }
        val tone = RawMerge.ToneFit(lin, FloatArray(200) { RawMerge.linearToSrgb(lin[it] * 1.8f).toFloat() })
        assertEquals(RawMerge.linearToSrgb(0.36f).toFloat(), tone.apply(0.2f), 3f)
    }

    @Test
    fun rawZoomPhotoLooksLikeTheCamerasOwn() {
        assumeTrue("needs RAW", backCameraHas(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW))
        waitForCamera()
        setSimple(true)
        Prefs(context).zoomEnhance = true
        Prefs(context).rawZoom = true
        onView(withContentDescription("Zoom 2×")).perform(click())
        SystemClock.sleep(800)
        val before = count("image/jpeg")
        val started = System.currentTimeMillis()
        onView(withId(R.id.shutter)).perform(click())
        waitFor("zoom photo saved", 150_000) { count("image/jpeg") > before }
        assertTrue("the RAW path made it", RawZoomProcessor.lastSucceededAt >= started)
        val uri = latestImage()!!
        var name = ""
        context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) }
        val original = OriginalStore.get(context, name)!!
        fun means(b: android.graphics.Bitmap): DoubleArray {
            val m = DoubleArray(3)
            var n = 0
            for (y in 0 until b.height step 4) for (x in 0 until b.width step 4) {
                val p = b.getPixel(x, y)
                m[0] += (p shr 16) and 0xFF; m[1] += (p shr 8) and 0xFF; m[2] += p and 0xFF; n++
            }
            return DoubleArray(3) { m[it] / n }
        }
        fun small(bytes: ByteArray) = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { d, info, _ ->
            d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            d.setTargetSampleSize(maxOf(1, maxOf(info.size.width, info.size.height) / 400))
        }
        val ours = means(android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
            d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            d.setTargetSampleSize(maxOf(1, maxOf(info.size.width, info.size.height) / 400))
        })
        val camera = means(small(original.readBytes()))
        Log.i(TAG, "raw zoom colours: ours ${ours.toList()} vs camera ${camera.toList()}")
        for (c in 0..2) assertEquals("channel $c brightness close to the camera's", camera[c], ours[c], 25.0)
    }

    @Test
    fun aiUpscalerRestoresDetailWithoutSeams() {
        // A detailed scene, shrunk 4× (as a far super zoom sees it), then enlarged back 4×.
        val w = 400
        val h = 300
        val rnd = java.util.Random(5)
        val tex = FloatArray(w * h) { rnd.nextFloat() }
        PortraitProcessor.boxBlur(tex, w, h, 2)
        val truth = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            var v = 70f + 260f * (tex[i] - 0.5f) + 60f
            if ((x / 24 + y / 24) % 2 == 0 && x < 200) v = if (v > 128) 215f else 40f
            val g = v.toInt().coerceIn(0, 255)
            (0xFF shl 24) or ((g * 0.9).toInt() shl 16) or (g shl 8) or ((g * 0.8).toInt())
        }
        val big = android.graphics.Bitmap.createBitmap(truth, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val smallBmp = PortraitProcessor.shrink(big, w / 4, h / 4)
        val small = IntArray((w / 4) * (h / 4)).also { smallBmp.getPixels(it, 0, w / 4, 0, 0, w / 4, h / 4) }
        val bilinear = android.graphics.Bitmap.createScaledBitmap(smallBmp, w, h, true)
        val bl = IntArray(w * h).also { bilinear.getPixels(it, 0, w, 0, 0, w, h) }
        val t0 = SystemClock.uptimeMillis()
        val ai = AiUpscaler(context).use { it.upscale(SuperRes.Frame(small, w / 4, h / 4), w, h) }
        val ms = SystemClock.uptimeMillis() - t0
        assertEquals(w, ai.w)
        assertEquals(h, ai.h)
        fun err(a: IntArray, x0: Int, x1: Int): Double {
            var e = 0.0
            var n = 0
            for (y in 8 until h - 8) for (x in x0 until x1) {
                e += abs(((a[y * w + x] shr 8) and 0xFF) - ((truth[y * w + x] shr 8) and 0xFF)); n++
            }
            return e / n
        }
        val eAi = err(ai.px, 8, w - 8)
        val eBl = err(bl, 8, w - 8)
        // Seams: pieces join every 52 input pixels = 208 output pixels.
        val seam = err(ai.px, 204, 212)
        val inside = err(ai.px, 150, 190)
        Log.i(TAG, "ai upscale: error AI $eAi vs bilinear $eBl; at seam $seam vs inside $inside; $ms ms")
        fun edges(a: IntArray): Double {
            var e = 0.0
            for (y in 8 until h - 8) for (x in 8 until w - 9) e += abs(((a[y * w + x + 1] shr 8) and 0xFF) - ((a[y * w + x] shr 8) and 0xFF))
            return e
        }
        val sharpAi = edges(ai.px) / edges(truth)
        val sharpBl = edges(bl) / edges(truth)
        Log.i(TAG, "ai upscale: edge strength vs truth AI $sharpAi, bilinear $sharpBl")
        assertTrue("AI restores edges: $sharpAi vs $sharpBl", sharpAi > sharpBl * 1.1)
        assertTrue("and stays about as accurate: $eAi vs $eBl", eAi < eBl * 1.15)
        assertTrue("no seam: $seam vs $inside", seam < inside * 1.4 + 1)
    }

    @Test
    fun aiSpeedTestRuns() {
        val (ms, photo) = AiUpscaler.speedTest(context)
        Log.i(TAG, "AI speed test: $ms ms; super zoom photo about $photo s")
        assertTrue(ms > 0)
    }

    @Test
    fun superZoomUsesAiDetail() {
        waitForCamera()
        setSimple(true)
        Prefs(context).aiDetail = Prefs.AI_ALL_SUPER
        onView(withContentDescription("Zoom 1×")).perform(click())
        onView(withId(R.id.zoomDial)).perform(dragAcross(0.95f, 0.05f))
        var hw = 0f
        var v = 0f
        activityRule.scenario.onActivity { val d = it.findViewById<ZoomDial>(R.id.zoomDial); hw = d.hardwareMax; v = d.value }
        // All the way into super zoom (the far end is where AI detail is meant for; a small area, quick).
        repeat(3) {
            activityRule.scenario.onActivity { val d = it.findViewById<ZoomDial>(R.id.zoomDial); v = d.value; hw = d.hi }
            if (v < hw * 0.99f && visible(R.id.zoomBar)) onView(withId(R.id.zoomDial)).perform(dragAcross(0.95f, 0.05f))
        }
        assertTrue("the zoom readout says AI: ${text(R.id.zoomIndicator)}", text(R.id.zoomIndicator).contains("AI"))
        AiUpscaler.lastRunMs = 0
        AiUpscaler.tooSlow = false
        AiUpscaler.budgetMs = Long.MAX_VALUE // an emulator may be slower than any phone
        val before = count("image/jpeg")
        onView(withId(R.id.shutter)).perform(click())
        waitFor("super zoom photo saved", 420_000) { count("image/jpeg") > before }
        assertTrue("the AI ran", AiUpscaler.lastRunMs > 0)
        Log.i(TAG, "super zoom AI took ${AiUpscaler.lastRunMs} ms")
    }

    @Test
    fun zoomEnhanceCaptureSavesFullSizePhoto() {
        waitForCamera()
        setSimple(true)
        Prefs(context).zoomEnhance = true
        onView(withContentDescription("Zoom 2×")).perform(click())
        SystemClock.sleep(800)
        val before = count("image/jpeg")
        onView(withId(R.id.shutter)).perform(click())
        waitFor("zoom photo saved", 120_000) { count("image/jpeg") > before }
        val uri = latestImage()!!
        var name = ""
        context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) }
        assertTrue("a _ZOOM photo: $name", name.contains("_ZOOM"))
    }

    @Test
    fun zoomShotUsesTheLensesRealPixels() {
        waitForCamera()
        setSimple(true)
        Prefs(context).zoomEnhance = true
        Prefs(context).rawZoom = false
        onView(withContentDescription("Zoom 2×")).perform(click())
        SystemClock.sleep(800)
        val before = count("image/jpeg")
        onView(withId(R.id.shutter)).perform(click())
        waitFor("zoom photo saved", 150_000) { count("image/jpeg") > before }
        assertTrue("shot at the lens's own zoom, cropped here: ${MainActivity.lastZoomShot}", MainActivity.lastZoomShot.contains("native crop 2.00"))
        val uri = latestImage()!!
        val size = context.contentResolver.openInputStream(uri)!!.use {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeStream(it, null, o)
            o.outWidth to o.outHeight
        }
        var name = ""
        context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) }
        assertTrue("a _ZOOM photo: $name", name.contains("_ZOOM"))
        assertTrue("rebuilt at full size, not a small crop: $size", maxOf(size.first, size.second) >= 1800)
    }

    @Test
    fun shakeProofShutterMaths() {
        val isoR = Range(50, 3200)
        val expR = Range(100_000L, 1_000_000_000L)
        // Auto wants 1/15 s; the hands hold 1/60 s: 4× faster, ISO 4× higher → merged burst.
        val p = Steady.plan(400, 66_000_000L, 16_000_000L, isoR, expR)!!
        assertEquals(16_000_000L, p.exposureNs)
        assertEquals(1650.0, p.iso.toDouble(), 20.0)
        assertTrue("big ISO raise → burst", p.burst)
        // Slightly too slow: a single faster shot is enough.
        val q = Steady.plan(400, 20_000_000L, 16_000_000L, isoR, expR)!!
        assertTrue("small raise → single shot", !q.burst)
        // Already fast enough: nothing to do.
        assertTrue(Steady.plan(400, 10_000_000L, 16_000_000L, isoR, expR) == null)
        // ISO can't go higher: as fast as it allows, still faster than auto.
        val r = Steady.plan(3000, 66_000_000L, 4_000_000L, isoR, expR)!!
        assertEquals(3200, r.iso)
        assertTrue(r.exposureNs < 66_000_000L)
    }

    @Test
    fun shakyFramesAreDropped() {
        assertEquals("steady ones kept", listOf(0, 2, 4), Steady.keep(listOf(0.3f, 2.5f, 0.4f, 5.0f, 0.6f), 2))
        assertEquals("at least minKeep, steadiest first", listOf(0, 1, 2), Steady.keep(listOf(3f, 4f, 2f, 9f), 3))
        assertEquals("unknown blur kept", listOf(0, 1, 2), Steady.keep(listOf(null, 0.5f, null), 2))
        // 0.1 rad/s for 50 ms = 0.005 rad.
        val ts = LongArray(20) { it * 10_000_000L }
        val ws = FloatArray(20) { 0.1f }
        assertEquals(0.005f, ShakeMeter.turnedDuring(ts, ws, 30_000_000L, 80_000_000L), 1e-5f)
    }

    @Test
    fun shakyHandsGiveASteadyMergedPhoto() {
        waitForCamera()
        setSimple(true)
        context.getSharedPreferences("procam", android.content.Context.MODE_PRIVATE).edit().putBoolean("forceShaky", true).commit()
        val before = count("image/jpeg")
        onView(withId(R.id.shutter)).perform(click())
        waitFor("steady photo saved", 60_000) { count("image/jpeg") > before }
        waitFor("finished", 20_000) { !shutterBusy() }
        SystemClock.sleep(1500)
        assertEquals("exactly one photo", before + 1, count("image/jpeg"))
        val names = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("%_STEADY%"), null,
        )?.use { it.count } ?: 0
        assertTrue("a _STEADY photo (fast shots merged)", names > 0)
        assertEquals("Steady", ReviewActivity.describe("PROCAM_1_STEADY.jpg").first)
    }

    @Test
    fun mergeLocksOntoTheSubject() {
        // The subject sways differently from the background: with the subject given, frames line up on it.
        val w = 800
        val h = 600
        val rnd = java.util.Random(5)
        val bgTex = FloatArray(w * h) { rnd.nextFloat() }.also { PortraitProcessor.boxBlur(it, w, h, 2) }
        val subTex = FloatArray(w * h) { rnd.nextFloat() }.also { PortraitProcessor.boxBlur(it, w, h, 1) }
        val bgShift = listOf(0 to 0, 2 to -1, -1 to 2, 3 to 1)
        val subShift = listOf(0 to 0, -9 to 6, 8 to -7, -5 to -10)
        val frames = (0 until 4).map { k ->
            SuperRes.Frame(IntArray(w * h) { i ->
                val x = i % w
                val y = i / w
                val sx = x + subShift[k].first
                val sy = y + subShift[k].second
                val v = if (sx in 300 until 500 && sy in 200 until 400) 30 + 200 * subTex[sy * w + sx]
                else 40 + 170 * bgTex[(y + bgShift[k].second).coerceIn(0, h - 1) * w + (x + bgShift[k].first).coerceIn(0, w - 1)]
                val c = v.toInt().coerceIn(0, 255)
                (0xFF shl 24) or (c shl 16) or (c shl 8) or c
            }, w, h)
        }
        val locked = SuperRes.offsets(frames, 2, SuperRes.Window(320, 220, 160, 160))
        for (k in 1 until 4) {
            assertEquals("subject x, frame $k", -subShift[k].first.toFloat(), locked[k].dx, 0.5f)
            assertEquals("subject y, frame $k", -subShift[k].second.toFloat(), locked[k].dy, 0.5f)
        }
        val free = SuperRes.offsets(frames, 2, null)
        assertTrue("without a subject, the background wins", abs(free[3].dx + 3f) < 1.5f)
    }

    @Test
    fun steadiestMomentReplacesAShakyPhoto() {
        context.getSharedPreferences("procam", android.content.Context.MODE_PRIVATE).edit().putBoolean("forceMomentRescue", true).commit()
        waitForCamera()
        setSimple(true)
        SystemClock.sleep(1500)
        assumeTrue("this camera can't stream full-size frames at video rate", CameraController.zslLastOpen)
        val before = count("image/jpeg")
        onView(withId(R.id.shutter)).perform(click())
        waitFor("photo saved", 30_000) { count("image/jpeg") > before }
        waitFor("steadied", 120_000) { latestImage()?.let { description(it) }?.startsWith("Steadiest moment") == true }
        assertEquals("still one photo", before + 1, count("image/jpeg"))
    }

    @Test
    fun gyroDeblurUndoesAKnownShake() {
        val w = 480
        val h = 360
        val rnd = java.util.Random(7)
        val tex = FloatArray(w * h) { rnd.nextFloat() }.also { PortraitProcessor.boxBlur(it, w, h, 1) }
        val truth = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            var v = 50 + 120 * tex[i]
            if ((x / 30 + y / 30) % 2 == 0 && x < 220) v = 25f
            if (x > 260 && ((x - 260) / 5) % 3 == 0) v = 230f
            val c = v.toInt()
            (0xFF shl 24) or (c shl 16) or (c shl 8) or c
        }
        val path = FloatArray(2 * 25) { i -> val t = (i / 2) / 24f; if (i % 2 == 0) 11f * t else 4f * kotlin.math.sin(t * 3f) }
        val shaken = MotionDeblur.blur(SuperRes.Frame(truth, w, h), path)
        fun err(p: IntArray): Double {
            var s = 0.0
            var n = 0
            for (y in 20 until h - 20) for (x in 20 until w - 20) { s += abs((p[y * w + x] and 0xFF) - (truth[y * w + x] and 0xFF)); n++ }
            return s / n
        }
        val choice = MotionDeblur.choose(shaken, MotionDeblur.variants(path), w / 2, h / 2, patch = 300)
        assertTrue("a kernel fits the shaken photo", choice != null)
        val out = IntArray(w * h)
        MotionDeblur.deblur(shaken, choice!!.path) { x, y, tw, th, p -> for (yy in 0 until th) System.arraycopy(p, yy * tw, out, (y + yy) * w + x, tw) }
        assertTrue("sharper: ${err(out)} vs ${err(shaken.px)}", err(out) < err(shaken.px) * 0.85)
        assertTrue("a photo that didn't shake is left alone", MotionDeblur.choose(SuperRes.Frame(truth, w, h), MotionDeblur.variants(path), w / 2, h / 2, patch = 300) == null)
    }

    @Test
    fun oisLeftoverShakeIsFound() {
        val rnd = java.util.Random(2)
        val n = 76
        val cal = FloatArray(2 * n)
        for (i in 1 until n) {
            cal[2 * i] = cal[2 * i - 2] + (rnd.nextGaussian() * 2e-4).toFloat()
            cal[2 * i + 1] = cal[2 * i - 1] + (rnd.nextGaussian() * 2e-4).toFloat()
        }
        val calO = FloatArray(2 * n) { i -> val k = i / 2; if (i % 2 == 0) -900f * cal[2 * k + 1] else 900f * cal[2 * k] }
        val m = 25
        val g = FloatArray(2 * m) { i -> (if (i % 2 == 0) 0.004f else 0.001f) * (i / 2) / (m - 1) }
        // The lens follows only up to 0.5 px.
        val o = FloatArray(2 * m) { i -> val k = i / 2; (if (i % 2 == 0) -900f * g[2 * k + 1] else 900f * g[2 * k]).coerceIn(-0.5f, 0.5f) }
        val r = MotionDeblur.oisResidual(cal, calO, g, o)!!
        assertEquals(0.004f - 0.5f / 900, r[2 * m - 2], 5e-5f)
        assertEquals(0.001f - 0.5f / 900, r[2 * m - 1], 5e-5f)
        assertTrue("a lens that doesn't follow the gyro isn't trusted",
            MotionDeblur.oisResidual(cal, FloatArray(2 * n) { rnd.nextGaussian().toFloat() }, g, o) == null)
    }

    @Test
    fun shakyPhotoIsDeblurredInTheApp() {
        context.getSharedPreferences("procam", android.content.Context.MODE_PRIVATE).edit()
            .putFloat("forceShakePx", 10f).putBoolean("steadyMoment", false)
            .putBoolean("autoHdr", false).putBoolean("lowLightMerge", false).commit()
        waitForCamera()
        setSimple(true)
        val before = count("image/jpeg")
        onView(withId(R.id.shutter)).perform(click())
        waitFor("photo saved", 30_000) { count("image/jpeg") > before }
        try {
            waitFor("deblurred", 180_000) { latestImage()?.let { description(it) }?.startsWith("Shake undone") == true }
        } catch (e: AssertionError) {
            throw AssertionError("${e.message}; description=${latestImage()?.let { description(it) }}; app: ${MotionDeblur.note}", e)
        }
        assertEquals("still one photo", before + 1, count("image/jpeg"))
    }

    @Test
    fun rollingShutterRowsPutBack() {
        val w = 800
        val h = 600
        val rnd = java.util.Random(4)
        val tex = FloatArray(w * h) { rnd.nextFloat() }.also { PortraitProcessor.boxBlur(it, w, h, 2) }
        // While each frame was read out, the phone turned: rows shifted line by line, differently per frame.
        val paths = listOf(floatArrayOf(-4f, 0f, 4f, 0f), floatArrayOf(5f, -2f, -5f, 2f), floatArrayOf(-2f, 3f, 2f, -3f))
        val frames = paths.map { p ->
            val sh = RollingShutter.rowShifts(p, h)
            val y = ByteArray(w * h) { i ->
                val x = i % w
                val r = i / w
                val sx = (x - kotlin.math.round(sh[2 * r]).toInt()).coerceIn(0, w - 1)
                val sy = (r - kotlin.math.round(sh[2 * r + 1]).toInt()).coerceIn(0, h - 1)
                (40 + 180 * tex[sy * w + sx]).toInt().toByte()
            }
            val uv = ByteArray((w / 2) * (h / 2)) { 128.toByte() }
            MomentBuffer.Frame(w, h, y, uv, uv.copyOf(), w, 1, w / 2, 1)
        }
        val plain = TileMerge.alignmentCost(frames.map { TileMerge.YuvSource(it) }, null)
        val fixed = TileMerge.alignmentCost(frames.indices.map { TileMerge.YuvSource(frames[it], RollingShutter.rowShifts(paths[it], h)) }, null)
        assertTrue("rows put back line up: $fixed vs $plain", fixed < plain * 0.3)
        val wrong = TileMerge.alignmentCost(frames.indices.map { TileMerge.YuvSource(frames[it], RollingShutter.rowShifts(MotionDeblur.variants(paths[it])[1], h)) }, null)
        assertTrue("the wrong way round is worse, so it's never chosen: $wrong vs $plain", wrong > plain)
    }

    @Test
    fun zoomMergeHandlesATiltAndBeatsOneFrame() {
        // 5 frames of a detailed scene at 1/3 resolution, hand-shake offsets, the phone turning
        // a little between them (old one-offset-per-frame merge doubles the picture), noise.
        val big = 900
        val rnd = java.util.Random(11)
        val tex = FloatArray(big * big) { rnd.nextFloat() }.also { PortraitProcessor.boxBlur(it, big, big, 3) }
        fun truth(x: Float, y: Float): Float {
            val xi = x.toInt().coerceIn(0, big - 1)
            val yi = y.toInt().coerceIn(0, big - 1)
            var v = 60 + 140 * tex[yi * big + xi]
            if ((xi / 24 + yi / 24) % 2 == 0 && xi < 450) v = 30f
            return v
        }
        val f = 3
        val n = big / f - 4
        val shifts = listOf(0f to 0f, 1.3f to 0.7f, -0.6f to 2.1f, 2.2f to -1.4f, -1.7f to -0.9f)
        val frames = shifts.mapIndexed { k, (sx, sy) ->
            val ang = 0.004f * k
            val nr = java.util.Random(k * 7L + 1)
            SuperRes.Frame(IntArray(n * n) { i ->
                val x = i % n
                val y = i / n
                var s = 0f
                for (yy in 0 until f) for (xx in 0 until f) {
                    val lx = x + (xx + 0.5f) / f - n / 2f
                    val ly = y + (yy + 0.5f) / f - n / 2f
                    val rx = lx * kotlin.math.cos(ang) - ly * kotlin.math.sin(ang) + n / 2f + sx
                    val ry = lx * kotlin.math.sin(ang) + ly * kotlin.math.cos(ang) + n / 2f + sy
                    s += truth((rx + 2) * f, (ry + 2) * f)
                }
                val v = (s / (f * f) + nr.nextGaussian() * 5).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }, n, n)
        }
        val on = n * f
        val truthFrame = SuperRes.Frame(IntArray(on * on) { i ->
            val v = truth(i % on + 2f * f + 0.5f, i / on + 2f * f + 0.5f).toInt()
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }, on, on)
        val offs = SuperRes.offsets(frames, 2).map { it.dx to it.dy }
        val single = SuperRes.Frame(SuperRes.enlarge(frames[0], on, on), on, on)
        val old = SuperRes.merge(frames, offs, IntArray(frames.size) { 60 }, f.toFloat())
        val new = KernelMerge.merge(frames, offs, f.toFloat())
        val eSingle = SuperRes.rmse(single, truthFrame, 30)
        val eOld = SuperRes.rmse(old, truthFrame, 30)
        val eNew = SuperRes.rmse(new, truthFrame, 30)
        Log.i(TAG, "zoom merge error: single $eSingle, old $eOld, new $eNew (${KernelMerge.lastTimings})")
        assertTrue("new merge beats the old one with a tilt: $eNew vs $eOld", eNew < eOld * 0.8)
        assertTrue("and isn't worse than one frame: $eNew vs $eSingle", eNew < eSingle * 1.05)
    }

    // ------------------------------------------------------------------ helpers

    /** The app's recent log lines matching [pattern] (for failure messages). */
    private fun appLog(pattern: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("logcat -d -v time -t 400 ProCam:V *:S")
        val text = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
        val re = Regex(pattern, RegexOption.IGNORE_CASE)
        return text.lines().filter { re.containsMatchIn(it) }.takeLast(30).joinToString("\n")
    }

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

    private fun latestImage(): android.net.Uri? = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.MIME_TYPE} = ?", arrayOf("Pictures/ProCam%", "image/jpeg"),
        "${MediaStore.Images.Media._ID} DESC",
    )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null }

    private fun description(uri: android.net.Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { androidx.exifinterface.media.ExifInterface(it).getAttribute(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_DESCRIPTION) }
    } catch (e: Exception) {
        null
    }

    /** Drag across [v] from fraction (fx0, fy) to (fx1, fy) of its size; dxDp adds an extra offset in dp to the end point. */
    private fun dragAcross(fx0: Float, fx1: Float, fy: Float = 0.5f, dxDp: Float = 0f) = androidx.test.espresso.action.GeneralSwipeAction(
        androidx.test.espresso.action.Swipe.SLOW,
        CoordinatesProvider { v: View ->
            val xy = IntArray(2)
            v.getLocationOnScreen(xy)
            floatArrayOf(xy[0] + v.width * fx0, xy[1] + v.height * fy)
        },
        CoordinatesProvider { v: View ->
            val xy = IntArray(2)
            v.getLocationOnScreen(xy)
            floatArrayOf(xy[0] + v.width * fx1 + dxDp * v.resources.displayMetrics.density, xy[1] + v.height * fy)
        },
        Press.FINGER,
    )

    private fun videoCount(): Int = context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID),
        "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("Movies/ProCam%"), null,
    )?.use { it.count } ?: 0

    private fun latestVideo(): android.net.Uri? = context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID),
        "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("Movies/ProCam%"),
        "${MediaStore.Video.Media._ID} DESC",
    )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null }

    private fun visible(id: Int): Boolean {
        var v = false
        activityRule.scenario.onActivity { v = it.findViewById<View>(id).visibility == View.VISIBLE }
        return v
    }

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
