package com.procam.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Starts every test from default settings (ProCam remembers settings between launches). */
class ClearPrefsRule(private val pro: Boolean = true, private val onboarded: Boolean = true) : ExternalResource() {
    override fun before() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences("procam", Context.MODE_PRIVATE).edit().clear().putBoolean("uiPro", pro).putBoolean("onboarded", onboarded)
            // Off in tests: a slow emulator can stretch a tap past the hold time (one test turns it on).
            .putBoolean("holdForVideo", false)
            // Zoom enhance makes every zoomed photo a background burst; its own tests turn it on.
            .putBoolean("zoomEnhance", false).commit()
        // Dismiss any system dialog (e.g. "System UI isn't responding") that would steal focus.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS").close()
    }
}
