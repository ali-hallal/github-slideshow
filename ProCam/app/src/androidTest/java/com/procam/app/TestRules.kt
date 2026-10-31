package com.procam.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Starts every test from default settings (ProCam remembers settings between launches). */
class ClearPrefsRule : ExternalResource() {
    override fun before() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences("procam", Context.MODE_PRIVATE).edit().clear().commit()
        // Dismiss any system dialog (e.g. "System UI isn't responding") that would steal focus.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS").close()
    }
}
