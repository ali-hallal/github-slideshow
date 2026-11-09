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

        val limits = listOf(100, 300, 1000)
        row(Option("Before/after storage", "ProCam keeps the unenhanced shot of Night, HDR, Portrait… photos so you can compare. Oldest are removed past this size.",
            listOf("100 MB", "300 MB", "1 GB"), { limits.indexOf(prefs.originalsMb).coerceAtLeast(0) },
            { prefs.originalsMb = limits[it]; Thread { OriginalStore.trim(this); runOnUiThread { updateStorageLine() } }.start() }))
        storageLine = TextView(this).apply {
            textSize = 14f
            setTextColor(getColor(R.color.accent))
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setOnClickListener { confirmClearOriginals() }
        }
        list.addView(storageLine)
        updateStorageLine()

        section("Video")
        row(Option("Video quality", "4K is sharper but uses about 3× the space (1080p if your phone can't)",
            listOf("1080p", "4K"), { if (prefs.video4k) 1 else 0 }, { prefs.video4k = it == 1 }))
        row(Option("Stabilization", "Smooths out shaky hands while walking (the view is slightly narrower)",
            listOf("Off", "On"), { if (prefs.stabilize) 1 else 0 }, { prefs.stabilize = it == 1 }))

        section("Viewfinder")
        row(Option("Grid", "Rule-of-thirds lines",
            listOf("Off", "On"), { if (prefs.grid) 1 else 0 }, { prefs.grid = it == 1 }))
        row(Option("Level", "Horizon indicator, turns green when straight",
            listOf("Off", "On"), { if (prefs.level) 1 else 0 }, { prefs.level = it == 1 }))
        row(Option("Zoom enhance", "From 1.5× zoom, photos are merged from several shots for more detail (takes a moment)",
            listOf("Off", "On"), { if (prefs.zoomEnhance) 1 else 0 }, { prefs.zoomEnhance = it == 1 }))
        row(Option("Zoom from real pixels", "Zoomed photos are taken at the lens's own zoom and enlarged by the app from several shots' real sensor pixels, instead of the camera's stretched digital zoom",
            listOf("Off", "On"), { if (prefs.nativeZoom) 1 else 0 }, { prefs.nativeZoom = it == 1 }))
        row(Option("AI detail (super zoom)", "An on-device AI restores edges and texture in super zoom photos (takes a few seconds, in the background)",
            listOf("Off", "From halfway into super zoom", "All super zoom"), { prefs.aiDetail.coerceIn(0, 2) }, { prefs.aiDetail = it }))
        list.addView(TextView(this).apply {
            text = "Test AI speed on this phone"
            textSize = 14f
            setTextColor(getColor(R.color.accent))
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setOnClickListener { testAiSpeed() }
        })
        row(Option("Zoom detail from RAW", "Zoom enhance merges the sensor's raw data (more detail, less noise) where the phone allows it",
            listOf("Off", "On"), { if (prefs.rawZoom) 1 else 0 }, { prefs.rawZoom = it == 1 }))
        row(Option("Low-light merge", "In dim rooms, Auto merges a few shots for cleaner, less grainy photos",
            listOf("Off", "On"), { if (prefs.lowLightMerge) 1 else 0 }, { prefs.lowLightMerge = it == 1 }))
        row(Option("Auto HDR", "In Auto, balance bright skies and dark subjects automatically (shows an HDR badge)",
            listOf("Off", "On"), { if (prefs.autoHdr) 1 else 0 }, { prefs.autoHdr = it == 1 }))
        row(Option("Steady zoom view", "At 4× and beyond, the picture on screen is held steady against hand shake",
            listOf("Off", "On"), { if (prefs.steadyZoom) 1 else 0 }, { prefs.steadyZoom = it == 1 }))
        row(Option("Focus peaking", "Highlights sharp edges in red",
            listOf("Manual focus only", "Always", "Off"), { prefs.peaking }, { prefs.peaking = it }))
        row(Option("Zebra stripes", "Stripes over areas that are too bright",
            listOf("Off", "95 %", "100 % (clipped)"), { prefs.zebra }, { prefs.zebra = it }))

        section("Controls")
        row(Option("Volume buttons", "What the volume keys do in ProCam",
            listOf("Take photo", "Zoom", "Volume"), { prefs.volumeKeys }, { prefs.volumeKeys = it }))
        row(Option("Hold shutter for video", "In photo mode, hold the shutter to record a quick video; let go to stop",
            listOf("Off", "On"), { if (prefs.holdForVideo) 1 else 0 }, { prefs.holdForVideo = it == 1 }))
        row(Option("Undo shake blur", "Uses the gyroscope's record of how your hands moved to undo the blur in a shaky photo",
            listOf("Off", "On"), { if (prefs.motionDeblur) 1 else 0 }, { prefs.motionDeblur = it == 1 }))
        row(Option("Steadiest moment", "Keeps the last moment before you press; if your photo shakes, it's rebuilt from the steadiest frames (phones that support it)",
            listOf("Off", "On"), { if (prefs.steadyMoment) 1 else 0 }, { prefs.steadyMoment = it == 1 }))
        row(Option("Shake-proof shutter", "When your hands shake, Auto uses a faster shutter (and merges a few shots to keep it clean) so photos come out sharp",
            listOf("Off", "On"), { if (prefs.steadyShutter) 1 else 0 }, { prefs.steadyShutter = it == 1 }))
        row(Option("Steady shot", "If your hands are shaking, wait a moment for them to settle before taking the photo",
            listOf("Off", "On"), { if (prefs.steadyShot) 1 else 0 }, { prefs.steadyShot = it == 1 }))
        row(Option("Shutter sound", "May be forced on in some countries",
            listOf("Off", "On"), { if (prefs.shutterSound) 1 else 0 }, { prefs.shutterSound = it == 1 }))
        row(Option("Haptics", "Vibrate on shutter and dial steps",
            listOf("Off", "On"), { if (prefs.haptics) 1 else 0 }, { prefs.haptics = it == 1 }))

        section("Help")
        list.addView(TextView(this).apply {
            text = "Send diagnostics"
            textSize = 16f
            setTextColor(getColor(R.color.white))
            setPadding(dp(24), dp(16), dp(24), dp(4))
            setOnClickListener { sendDiagnostics() }
        })
        list.addView(TextView(this).apply {
            text = "A text report about your phone's cameras and what ProCam did recently. No photos or location."
            textSize = 12f
            setTextColor(getColor(R.color.dim))
            setPadding(dp(24), 0, dp(24), dp(12))
        })
        list.addView(TextView(this).apply {
            text = "Show the welcome guide"
            textSize = 16f
            setTextColor(getColor(R.color.white))
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setOnClickListener { startActivity(android.content.Intent(this@SettingsActivity, WelcomeActivity::class.java)) }
        })
        list.addView(TextView(this).apply {
            text = "Open-source licences"
            textSize = 16f
            setTextColor(getColor(R.color.white))
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setOnClickListener { showLicences() }
        })
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

    private var storageLine: TextView? = null

    private fun updateStorageLine() {
        val line = storageLine ?: return
        Thread {
            val bytes = OriginalStore.usedBytes(this)
            val n = OriginalStore.count(this)
            runOnUiThread {
                line.text = if (n == 0) "No originals kept right now"
                else "Originals use ${(bytes + 500_000) / 1_000_000} MB ($n photos) · tap to free up"
            }
        }.start()
    }

    private fun confirmClearOriginals() {
        if (OriginalStore.count(this) == 0) return
        android.app.AlertDialog.Builder(this)
            .setTitle("Free up space?")
            .setMessage("Your photos stay in the gallery. Only the before/after comparison and changing a portrait's blur afterwards go away.")
            .setPositiveButton("Free up") { _, _ ->
                Thread { OriginalStore.clear(this); runOnUiThread { updateStorageLine() } }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun testAiSpeed() {
        Toast.makeText(this, "Testing the AI…", Toast.LENGTH_SHORT).show()
        Thread {
            val message = try {
                val (ms, photoSeconds) = AiUpscaler.speedTest(this)
                "Enlarged a 256×192 test picture in $ms ms.\n\nA typical super zoom photo would take about " +
                    String.format(java.util.Locale.US, "%.0f", photoSeconds) + " s on this phone (in the background; you can keep shooting)."
            } catch (e: Throwable) {
                android.util.Log.e("ProCam", "AI speed test", e)
                "The AI couldn't run on this phone: ${e.message}"
            }
            android.util.Log.i("ProCam", "AI speed test: $message")
            runOnUiThread {
                android.app.AlertDialog.Builder(this).setTitle("AI speed").setMessage(message).setPositiveButton("OK", null).show()
            }
        }.start()
    }

    private fun showLicences() {
        val text = try {
            "AI detail model: compact Real-ESRGAN (realesr-general-x4v3 and realesr-general-wdn-x4v3), " +
                "blended, pruned and quantized for ProCam.\n\n" + assets.open("LICENSE-Real-ESRGAN.txt").bufferedReader().readText()
        } catch (e: Exception) {
            "Licence text unavailable"
        }
        android.app.AlertDialog.Builder(this).setTitle("Open-source licences").setMessage(text).setPositiveButton("OK", null).show()
    }

    private fun sendDiagnostics() {
        Toast.makeText(this, "Preparing diagnostics…", Toast.LENGTH_SHORT).show()
        Thread {
            val report = Diagnostics.build(this)
            val uri = Diagnostics.save(this, report)
            runOnUiThread {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "ProCam diagnostics")
                if (uri != null) {
                    send.putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    Toast.makeText(this, "Saved in Downloads/ProCam", Toast.LENGTH_SHORT).show()
                } else {
                    send.putExtra(android.content.Intent.EXTRA_TEXT, report.take(90_000))
                }
                startActivity(android.content.Intent.createChooser(send, "Send diagnostics"))
            }
        }.start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_LOCATION = 2
    }
}
