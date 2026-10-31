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
        onView(withId(R.id.uiSimple)).perform(click())
        onView(withId(R.id.intentAuto)).check(matches(isDisplayed()))
        onView(withId(R.id.chipIso)).check(matches(androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility(androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE)))
        onView(withId(R.id.btnRaw)).check(matches(androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility(androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE)))
        assertTrue("Simple saved", !Prefs(context).uiPro)
        shootAndExpect("image/jpeg") { onView(withId(R.id.shutter)).perform(click()) }
        onView(withId(R.id.uiPro)).perform(click())
        onView(withId(R.id.chipIso)).check(matches(isDisplayed()))
    }

    @Test
    fun groupShutterKeepsOnePhotoFromBurst() {
        waitForCamera()
        onView(withId(R.id.uiSimple)).perform(click())
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
