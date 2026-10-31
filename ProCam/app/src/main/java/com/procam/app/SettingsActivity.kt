package com.procam.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Settings screen, built in code: each row cycles through its options when tapped. */
class SettingsActivity : Activity() {

    private class Option(
        val title: String,
        val summary: String,
        val labels: List<String>,
        val get: () -> Int,
        val set: (Int) -> Unit,
    )

    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private val valueViews = mutableMapOf<Option, TextView>()
    private var pendingLocationRow: Option? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.black))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(8))
        }
        header.addView(TextView(this).apply {
            text = "←"
            textSize = 24f
            setTextColor(getColor(R.color.white))
            setPadding(dp(12), dp(4), dp(16), dp(4))
            contentDescription = "Back"
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "Settings"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.white))
        })
        root.addView(header)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }

        buildRows()
    }

    private fun buildRows() {
        section("Photo")
        row(Option("Format", "HEIF files are about half the size of JPEG (if your phone supports it)",
            listOf("JPEG", "HEIF"), { if (prefs.heif) 1 else 0 }, { prefs.heif = it == 1 }))
        row(Option("Aspect ratio", "4:3 uses the whole sensor",
            listOf("4:3", "16:9"), { if (prefs.aspect169) 1 else 0 }, { prefs.aspect169 = it == 1 }))
        row(Option("Resolution", "Standard is about 12 MP: smaller files, cleaner on high-MP sensors",
            listOf("Maximum", "Standard (12 MP)"), { if (prefs.maxResolution) 0 else 1 }, { prefs.maxResolution = it == 0 }))
        val qualities = listOf(85, 95, 100)
        row(Option("Quality", "Compression quality for JPEG/HEIF",
            listOf("Standard (85)", "High (95)", "Maximum (100)"),
            { qualities.indexOf(prefs.jpegQuality).coerceAtLeast(0) }, { prefs.jpegQuality = qualities[it] }))
        row(Option("Mirror selfies", "Save front-camera photos the way the preview shows them",
            listOf("Off", "On"), { if (prefs.mirrorFront) 1 else 0 }, { prefs.mirrorFront = it == 1 }))
        val locationRow = Option("Save location", "Write GPS position into photos",
            listOf("Off", "On"), { if (prefs.location) 1 else 0 }, { enableLocation(it == 1) })
        row(locationRow)
        pendingLocationRow = locationRow

        section("Viewfinder")
        row(Option("Grid", "Rule-of-thirds lines",
            listOf("Off", "On"), { if (prefs.grid) 1 else 0 }, { prefs.grid = it == 1 }))
        row(Option("Level", "Horizon indicator, turns green when straight",
            listOf("Off", "On"), { if (prefs.level) 1 else 0 }, { prefs.level = it == 1 }))
        row(Option("Focus peaking", "Highlights sharp edges in red",
            listOf("Manual focus only", "Always", "Off"), { prefs.peaking }, { prefs.peaking = it }))
        row(Option("Zebra stripes", "Stripes over areas that are too bright",
            listOf("Off", "95 %", "100 % (clipped)"), { prefs.zebra }, { prefs.zebra = it }))

        section("Controls")
        row(Option("Volume buttons", "What the volume keys do in ProCam",
            listOf("Take photo", "Zoom", "Volume"), { prefs.volumeKeys }, { prefs.volumeKeys = it }))
        row(Option("Shutter sound", "May be forced on in some countries",
            listOf("Off", "On"), { if (prefs.shutterSound) 1 else 0 }, { prefs.shutterSound = it == 1 }))
        row(Option("Haptics", "Vibrate on shutter and dial steps",
            listOf("Off", "On"), { if (prefs.haptics) 1 else 0 }, { prefs.haptics = it == 1 }))

        section("")
        list.addView(TextView(this).apply {
            text = "Reset all settings"
            textSize = 16f
            setTextColor(getColor(R.color.accent))
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setOnClickListener {
                prefs.resetAll()
                valueViews.forEach { (o, v) -> v.text = o.labels[o.get().coerceIn(o.labels.indices)] }
                Toast.makeText(this@SettingsActivity, "Settings reset", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun section(title: String) {
        list.addView(TextView(this).apply {
            text = title.uppercase()
            textSize = 12f
            letterSpacing = 0.1f
            setTextColor(getColor(R.color.accent))
            setPadding(dp(24), dp(20), dp(24), dp(6))
        })
    }

    private fun row(o: Option) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = o.title
            textSize = 16f
            setTextColor(getColor(R.color.white))
        })
        texts.addView(TextView(this).apply {
            text = o.summary
            textSize = 12f
            setTextColor(getColor(R.color.dim))
        })
        container.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val value = TextView(this).apply {
            textSize = 14f
            setTextColor(getColor(R.color.accent))
            gravity = Gravity.END
            setPadding(dp(12), 0, 0, 0)
            text = o.labels[o.get().coerceIn(o.labels.indices)]
        }
        container.addView(value)
        valueViews[o] = value
        container.setOnClickListener {
            val next = (o.get() + 1) % o.labels.size
            o.set(next)
            value.text = o.labels[o.get().coerceIn(o.labels.indices)]
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }
        list.addView(container)
        list.addView(View(this).apply { setBackgroundColor(0x22FFFFFF) }, LinearLayout.LayoutParams(-1, 1))
    }

    private fun enableLocation(on: Boolean) {
        if (!on) {
            prefs.location = false
            return
        }
        val granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        prefs.location = true
        if (!granted) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQUEST_LOCATION,
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCATION) return
        if (grantResults.none { it == PackageManager.PERMISSION_GRANTED }) {
            prefs.location = false
            pendingLocationRow?.let { o -> valueViews[o]?.text = o.labels[o.get()] }
            Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_LOCATION = 2
    }
}
