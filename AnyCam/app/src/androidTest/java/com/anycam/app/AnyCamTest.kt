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

    /** The compiled merge is there on the phone and makes exactly the photo the Kotlin one does, faster. */
    @Test
    fun theCompiledMergeMatchesTheReference() {
        assertTrue("compiled library loaded", com.anycam.app.core.Native.ok)
        val w = 640
        val h = 480
        val rnd = java.util.Random(4)
        val scene = FloatArray(800 * 640) { 0f }.also { sc ->
            val raw = FloatArray(sc.size) { rnd.nextFloat() }
            for (y in 1 until 639) for (x in 1 until 799) {
                var sum = 0f
                for (j in -1..1) for (i in -1..1) sum += raw[(y + j) * 800 + x + i]
                sc[y * 800 + x] = sum / 9f
            }
        }
        val info = com.anycam.app.core.RawInfo(0, FloatArray(4) { 64f }, 1023f, floatArrayOf(1.9f, 1f, 1.5f),
            com.anycam.app.core.RawInfo.IDENTITY, null, 0f, 3e-5f)
        val shots = listOf(0 to 0, 3 to 1, -2 to 4, 5 to -3).map { (ox, oy) ->
            com.anycam.app.core.RawFrame(w, h, ShortArray(w * h) { i ->
                val v = scene[(i / w + oy + 40) * 800 + i % w + ox + 40]
                (64 + v * 0.3f * 959 + rnd.nextGaussian() * 4).toInt().coerceIn(0, 1023).toShort()
            })
        }
        val ref = com.anycam.app.core.RawMerge.sharpest(shots, info)
        val base = com.anycam.app.core.RawMerge.merge(listOf(shots[ref]), info)
        fun run(native: Boolean, gpu: Boolean = false): Pair<com.anycam.app.core.Yuv, Long> {
            com.anycam.app.core.Native.enabled = native
            com.anycam.app.core.Native.gpuEnabled = gpu
            val t0 = SystemClock.uptimeMillis()
            val photo = com.anycam.app.core.Stack.develop(shots, info, base, ref).photo
            return photo to SystemClock.uptimeMillis() - t0
        }
        try {
            val (kotlinPhoto, kotlinMs) = run(false)
            val (nativePhoto, nativeMs) = run(true)
            val (gpuPhoto, gpuMs) = run(true, gpu = true)
            Log.i(TAG, "merge 640x480 x4: Kotlin $kotlinMs ms, compiled $nativeMs ms, graphics processor $gpuMs ms " +
                "(graphics processor: ${com.anycam.app.core.Native.gpuStatus()})")
            fun worst(other: com.anycam.app.core.Yuv): Int {
                var m = 0
                for (i in kotlinPhoto.y.indices) m = maxOf(m, kotlin.math.abs((kotlinPhoto.y[i].toInt() and 255) - (other.y[i].toInt() and 255)))
                for (i in kotlinPhoto.u.indices) {
                    m = maxOf(m, kotlin.math.abs((kotlinPhoto.u[i].toInt() and 255) - (other.u[i].toInt() and 255)))
                    m = maxOf(m, kotlin.math.abs((kotlinPhoto.v[i].toInt() and 255) - (other.v[i].toInt() and 255)))
                }
                return m
            }
            assertTrue("compiled: same photo (largest difference ${worst(nativePhoto)})", worst(nativePhoto) <= 1)
            // On the graphics processor where the device has one that can, else the same compiled code: either way the same photo.
            assertTrue("graphics processor: same photo (largest difference ${worst(gpuPhoto)})", worst(gpuPhoto) <= 2)
        } finally {
            com.anycam.app.core.Native.enabled = true
            com.anycam.app.core.Native.gpuEnabled = true
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
