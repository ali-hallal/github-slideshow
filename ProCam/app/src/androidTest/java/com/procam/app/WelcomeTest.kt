package com.procam.app

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** First launch shows the 3-page guide; finishing it starts the camera and it doesn't come back. */
@RunWith(AndroidJUnit4::class)
class WelcomeTest {

    private val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(GrantPermissionRule())
        .around(ClearPrefsRule(pro = false, onboarded = false))
        .around(activityRule)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun guideShowsOnceThenCamera() {
        onView(withText("Say what you want to shoot")).check(matches(isDisplayed()))
        screenshot("00a_welcome_modes")
        onView(withText("Next")).perform(click())
        onView(withText("Hold steady, get it sharp")).check(matches(isDisplayed()))
        screenshot("00b_welcome_steady")
        onView(withText("Next")).perform(click())
        onView(withText("Compare, share, or go Pro")).check(matches(isDisplayed()))
        screenshot("00c_welcome_review")
        onView(withText("Start shooting")).perform(click())
        assertTrue("guide marked as seen", Prefs(context).onboarded)
        // The camera starts once the guide closes.
        val end = SystemClock.uptimeMillis() + 20_000
        var readout = ""
        while (SystemClock.uptimeMillis() < end) {
            activityRule.scenario.onActivity { readout = it.findViewById<TextView>(R.id.readout).text.toString() }
            if (readout.contains("ISO")) break
            SystemClock.sleep(200)
        }
        assertTrue("camera running after the guide: $readout", readout.contains("ISO"))
    }

    private fun GrantPermissionRule() = androidx.test.rule.GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private fun screenshot(name: String) {
        SystemClock.sleep(400)
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val w = minOf(480, bmp.width)
        val small = Bitmap.createScaledBitmap(bmp, w, bmp.height * w / bmp.width, true)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/procam-screens")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        context.contentResolver.openOutputStream(uri)?.use { small.compress(Bitmap.CompressFormat.JPEG, 70, it) }
    }
}
