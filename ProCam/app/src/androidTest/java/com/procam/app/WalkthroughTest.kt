package com.procam.app

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * A manual-style walkthrough: uses the app step by step like a person would and saves a
 * screenshot after every step (plus the photos it took) to Download/procam-screens so they
 * can be reviewed by eye.
 */
@RunWith(AndroidJUnit4::class)
class WalkthroughTest {

    private val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        .around(ClearPrefsRule(pro = false))
        .around(activityRule)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var step = 0

    @Test
    fun walkthrough() {
        waitUntil(20_000) { readout().contains("ISO") }
        SystemClock.sleep(1500)
        screenshot("simple_auto")

        // Simple mode → Group smart shutter.
        onView(withId(R.id.intentGroup)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
        SystemClock.sleep(800)
        screenshot("simple_group")
        onView(withId(R.id.shutter)).perform(click())
        SystemClock.sleep(600)
        screenshot("group_waiting")
        waitForIdleShutter(20_000)
        SystemClock.sleep(1500)
        screenshot("group_done")
        savePhoto("photo_group")

        // Portrait (the emulator scene has no person, so this shows the UI and the fallback).
        onView(withId(R.id.intentPortrait)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
        SystemClock.sleep(2000)
        screenshot("simple_portrait")
        onView(withId(R.id.shutter)).perform(click())
        SystemClock.sleep(700)
        screenshot("portrait_processing")
        waitForIdleShutter(30_000)
        SystemClock.sleep(800)
        screenshot("portrait_done")
        // Zoom slider + super zoom (Auto).
        onView(withId(R.id.intentAuto)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
        SystemClock.sleep(500)
        tryClick("Zoom 1×")
        SystemClock.sleep(300)
        // Slide straight away (the slider hides itself after a few seconds; screenshots are slow).
        onView(withId(R.id.zoomSlider)).perform(tapAt(0.8f, 0.5f))
        SystemClock.sleep(300)
        screenshot("super_zoom_preview")
        onView(withId(R.id.shutter)).perform(click())
        SystemClock.sleep(1500)
        screenshot("super_zoom_enhancing")
        waitForIdleShutter(60_000)
        SystemClock.sleep(800)
        savePhoto("photo_super_zoom")
        // Back to 1x with the lens button (the slider hides itself after a few seconds).
        tryClick("Zoom 1×")
        SystemClock.sleep(800)

        // Freeze: fast shutter + moment buffer.
        onView(withId(R.id.intentFreeze)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
        SystemClock.sleep(1500)
        screenshot("simple_freeze")
        onView(withId(R.id.shutter)).perform(click())
        waitForIdleShutter(30_000)
        SystemClock.sleep(2000)
        savePhoto("photo_freeze")
        onView(withId(R.id.intentAuto)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
        SystemClock.sleep(500)

        // Night: 6-8 shots merged into one.
        onView(withId(R.id.intentNight)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
        SystemClock.sleep(1500)
        screenshot("simple_night")
        onView(withId(R.id.shutter)).perform(click())
        SystemClock.sleep(400)
        screenshot("night_hold_still")
        SystemClock.sleep(2500)
        screenshot("night_merging")
        waitForIdleShutter(60_000)
        SystemClock.sleep(800)
        screenshot("night_done")
        savePhoto("photo_night")
        // Review screen: before / after of the Night photo.
        onView(withId(R.id.thumbnail)).perform(click())
        SystemClock.sleep(2500)
        screenshot("review_night")
        androidx.test.espresso.Espresso.pressBack()
        SystemClock.sleep(1500)
        // Silky: blend a burst into a long-exposure look.
        onView(withId(R.id.intentSilky)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
        SystemClock.sleep(1500)
        screenshot("simple_silky")
        onView(withId(R.id.shutter)).perform(click())
        SystemClock.sleep(600)
        screenshot("silky_capturing")
        waitForIdleShutter(90_000)
        SystemClock.sleep(800)
        screenshot("silky_done")
        onView(withId(R.id.intentAuto)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())

        // Video: record a few seconds, then the review screen.
        onView(withId(R.id.intentVideo)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
        SystemClock.sleep(3000)
        screenshot("simple_video")
        onView(withId(R.id.shutter)).perform(click())
        SystemClock.sleep(3200)
        screenshot("video_recording")
        onView(withId(R.id.shutter)).perform(click())
        SystemClock.sleep(3000)
        screenshot("video_saved")
        onView(withId(R.id.thumbnail)).perform(click())
        SystemClock.sleep(2500)
        screenshot("review_video")
        androidx.test.espresso.Espresso.pressBack()
        SystemClock.sleep(1500)
        onView(withId(R.id.intentAuto)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
        SystemClock.sleep(2500)

        // Auto HDR (forced on here, as the emulator scene isn't backlit): badge, capture, review.
        context.getSharedPreferences("procam", android.content.Context.MODE_PRIVATE).edit().putBoolean("forceHdr", true).commit()
        SystemClock.sleep(2000)
        screenshot("hdr_badge")
        onView(withId(R.id.shutter)).perform(click())
        SystemClock.sleep(500)
        screenshot("hdr_hold_still")
        waitForIdleShutter(60_000)
        SystemClock.sleep(1500)
        savePhoto("photo_hdr")
        onView(withId(R.id.thumbnail)).perform(click())
        SystemClock.sleep(2500)
        screenshot("review_hdr")
        androidx.test.espresso.Espresso.pressBack()
        SystemClock.sleep(1500)
        context.getSharedPreferences("procam", android.content.Context.MODE_PRIVATE).edit().putBoolean("forceHdr", false).commit()

        // On to the Pro controls.
        onView(withId(R.id.btnMode)).perform(click())
        SystemClock.sleep(1000)
        screenshot("launch_auto_mode")

        onView(withId(R.id.textureView)).perform(tapAt(0.35f, 0.45f))
        SystemClock.sleep(400)
        screenshot("tap_to_focus_ring")

        onView(withId(R.id.chipEv)).perform(click())
        onView(withId(R.id.slider)).perform(tapAt(0.85f, 0.5f))
        SystemClock.sleep(1200)
        screenshot("ev_plus")

        onView(withId(R.id.shutter)).perform(click())
        SystemClock.sleep(150)
        screenshot("shutter_pressed")
        waitForIdleShutter()
        SystemClock.sleep(1500)
        screenshot("after_auto_photo_thumbnail")
        savePhoto("photo_auto")

        onView(withId(R.id.modePro)).perform(click())
        SystemClock.sleep(800)
        screenshot("pro_mode")

        onView(withId(R.id.chipShutter)).perform(click())
        onView(withId(R.id.slider)).perform(tapAt(0.15f, 0.5f))
        SystemClock.sleep(1200)
        screenshot("pro_fast_shutter_dark")

        onView(withId(R.id.slider)).perform(tapAt(0.8f, 0.5f))
        SystemClock.sleep(1200)
        screenshot("pro_slow_shutter_bright")

        onView(withId(R.id.chipIso)).perform(click())
        onView(withId(R.id.slider)).perform(tapAt(0.3f, 0.5f))
        SystemClock.sleep(1200)
        screenshot("pro_iso")

        onView(withId(R.id.chipFocus)).perform(click())
        onView(withId(R.id.slider)).perform(tapAt(0.3f, 0.5f))
        SystemClock.sleep(1000)
        screenshot("manual_focus")

        onView(withId(R.id.chipWb)).perform(click())
        onView(withId(R.id.slider)).perform(tapAt(0.7f, 0.5f))
        SystemClock.sleep(1200)
        screenshot("white_balance")

        onView(withId(R.id.shutter)).perform(click())
        waitForIdleShutter()
        SystemClock.sleep(1500)
        savePhoto("photo_pro")

        // Shutter priority: fixed shutter, ISO on auto, meter visible.
        onView(withId(R.id.chipFocus)).perform(click())
        onView(withId(R.id.slider)).perform(tapAt(0.0f, 0.5f)) // back to AF
        onView(withId(R.id.chipShutter)).perform(click())
        onView(withId(R.id.slider)).perform(tapAt(0.55f, 0.5f))
        onView(withId(R.id.chipIso)).perform(click())
        onView(withId(R.id.slider)).perform(tapAt(0.0f, 0.5f))
        SystemClock.sleep(2500)
        screenshot("pro_shutter_priority_meter")

        onView(withId(R.id.btnZebra)).perform(click())
        onView(withId(R.id.btnZebra)).perform(click())
        SystemClock.sleep(1200)
        screenshot("zebra_100")

        onView(withId(R.id.modeAuto)).perform(click())
        tryClick("Zoom 2×")
        SystemClock.sleep(1200)
        screenshot("zoom_2x")
        tryClick("Zoom 1×")

        onView(withId(R.id.textureView)).perform(androidx.test.espresso.action.ViewActions.longClick())
        SystemClock.sleep(1200)
        screenshot("ae_af_lock")

        onView(withId(R.id.btnFlash)).perform(click())
        onView(withId(R.id.btnRaw)).perform(click())
        SystemClock.sleep(800)
        screenshot("toggles_flash_raw")

        onView(withId(R.id.btnSettings)).perform(click())
        SystemClock.sleep(1000)
        screenshot("settings_screen")
        onView(withText("Send diagnostics")).perform(androidx.test.espresso.action.ViewActions.scrollTo())
        SystemClock.sleep(600)
        screenshot("settings_help")
        androidx.test.espresso.Espresso.pressBack()
        waitUntil(15_000) { readout().contains("ISO") }

        onView(withId(R.id.btnTimer)).perform(click())
        onView(withId(R.id.shutter)).perform(click())
        SystemClock.sleep(1300)
        screenshot("self_timer_countdown")
        waitForIdleShutter(20_000)
        SystemClock.sleep(1500)

        onView(withId(R.id.btnSwitch)).perform(click())
        waitUntil(15_000) { readout().contains("FRONT") && readout().contains("ISO") }
        SystemClock.sleep(1500)
        screenshot("front_camera")
        onView(withId(R.id.btnTimer)).perform(click())
        onView(withId(R.id.btnTimer)).perform(click()) // back to OFF
        onView(withId(R.id.shutter)).perform(click())
        waitForIdleShutter()
        SystemClock.sleep(1500)
        savePhoto("photo_front")
    }

    // ------------------------------------------------------------------ helpers

    private fun tryClick(description: String) {
        try {
            onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription(description)).perform(click())
        } catch (_: Throwable) {
        }
    }

    private fun screenshot(name: String) {
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        save(bmp, "%02d_%s".format(++step, name))
    }

    /** Saves the most recent JPEG ProCam took, decoded with its EXIF rotation applied. */
    private fun savePhoto(name: String) {
        val uri = latestJpeg() ?: return
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = 480f / maxOf(info.size.width, info.size.height)
            decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
        }
        save(bmp, "%02d_%s".format(++step, name))
    }

    private fun latestJpeg(): Uri? = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.MIME_TYPE} = ?",
        arrayOf("Pictures/ProCam%", "image/jpeg"),
        "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC",
    )?.use { c ->
        if (c.moveToFirst()) Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0).toString()) else null
    }

    private fun save(source: Bitmap, name: String) {
        val width = minOf(480, source.width)
        val bmp = Bitmap.createScaledBitmap(source, width, source.height * width / source.width, true)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/procam-screens")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it) }
    }

    private fun readout(): String {
        var t = ""
        activityRule.scenario.onActivity { t = it.findViewById<TextView>(R.id.readout).text.toString() }
        return t
    }

    private fun waitForIdleShutter(timeoutMs: Long = 15_000) {
        SystemClock.sleep(300)
        waitUntil(timeoutMs) {
            var busy = true
            activityRule.scenario.onActivity { busy = it.findViewById<ShutterButton>(R.id.shutter).busy }
            !busy
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end && !condition()) SystemClock.sleep(200)
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
}
