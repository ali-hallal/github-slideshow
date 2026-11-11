package com.anycam.app

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.anycam.app.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/** The app on a real (emulated) phone: the camera opens, photos are taken, merged and saved. */
@RunWith(AndroidJUnit4::class)
class AnyCamTest {
    private val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(Manifest.permission.CAMERA)).around(activityRule)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aPhotoIsTakenMergedAndSaved() {
        waitFor("camera ready") { MainActivity.ready }
        SystemClock.sleep(1500) // let exposure settle
        screen("01_viewfinder")
        val before = photos()
        val taken = MainActivity.photoCount
        onView(withId(R.id.shutter)).perform(click())
        waitFor("photo saved", 40_000) { MainActivity.photoCount > taken && photos() > before }
        screen("02_photo_taken")
        assertTrue("more than one shot went into it (${MainActivity.lastMerged})", MainActivity.lastMerged >= 1)
        // The saved photo opens and is a real picture.
        val (w, h) = newestPhotoSize()
        Log.i(TAG, "saved photo ${w}x$h, merged ${MainActivity.lastMerged}")
        assertTrue("a full-size photo ($w x $h)", w >= 600 && h >= 400)
        keepNewestPhoto("05_the_photo")
        // And the camera is ready for the next one.
        val again = MainActivity.photoCount
        onView(withId(R.id.shutter)).perform(click())
        waitFor("second photo", 40_000) { MainActivity.photoCount > again }
    }

    @Test
    fun zoomButtonsAndCameraSwitchWork() {
        waitFor("camera ready") { MainActivity.ready }
        var hasTwo = false
        activityRule.scenario.onActivity { hasTwo = it.findViewById<android.view.ViewGroup>(R.id.zoomRow).childCount > 1 }
        if (hasTwo) {
            onView(withContentDescription("Zoom 2×")).perform(click())
            SystemClock.sleep(800)
            screen("03_zoom_2x")
            var label = ""
            activityRule.scenario.onActivity {
                val row = it.findViewById<android.view.ViewGroup>(R.id.zoomRow)
                label = (0 until row.childCount).map { i -> (row.getChildAt(i) as android.widget.TextView).text.toString() }.joinToString(" ")
            }
            assertTrue("the 2× button is lit: $label", label.contains("2×"))
        }
        var switchShown = false
        activityRule.scenario.onActivity { switchShown = it.findViewById<android.view.View>(R.id.switchCamera).visibility == android.view.View.VISIBLE }
        if (switchShown) {
            onView(withId(R.id.switchCamera)).perform(click())
            SystemClock.sleep(300)
            waitFor("other camera ready") { MainActivity.ready }
            SystemClock.sleep(1200)
            screen("04_front_camera")
            val taken = MainActivity.photoCount
            onView(withId(R.id.shutter)).perform(click())
            waitFor("selfie saved", 40_000) { MainActivity.photoCount > taken }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun photos(): Int = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("Pictures/AnyCam%"), null,
    )?.use { it.count } ?: 0

    private fun newestPhotoSize(): Pair<Int, Int> {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("Pictures/AnyCam%"), "${MediaStore.Images.Media._ID} DESC",
        )?.use { c ->
            if (c.moveToFirst()) {
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                return o.outWidth to o.outHeight
            }
        }
        fail("no photo found")
        return 0 to 0
    }

    /** A small copy of the newest photo next to the screenshots. */
    private fun keepNewestPhoto(name: String) {
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("Pictures/AnyCam%"), "${MediaStore.Images.Media._ID} DESC",
            )?.use { c ->
                if (!c.moveToFirst()) return
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                val bmp = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
                } ?: return
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "$name.jpg")
                    put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/anycam-screens")
                }
                val out = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                context.contentResolver.openOutputStream(out)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "couldn't keep the photo", e)
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            SystemClock.sleep(150)
        }
        screen("timeout_${what.replace(' ', '_')}")
        fail("timed out waiting for: $what")
    }

    /** A picture of the screen, kept for looking at after the run. */
    private fun screen(name: String) {
        try {
            val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
            val small = Bitmap.createScaledBitmap(bmp, 480, 480 * bmp.height / bmp.width, true)
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "anycam-screens")
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$name.jpg")
                put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/anycam-screens")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            context.contentResolver.openOutputStream(uri)?.use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            Log.i(TAG, "screen $name saved under $dir")
        } catch (e: Exception) {
            Log.w(TAG, "screenshot failed", e)
        }
    }

    private companion object {
        const val TAG = "AnyCamTest"
    }
}
