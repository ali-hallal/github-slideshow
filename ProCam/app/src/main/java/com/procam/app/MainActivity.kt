package com.procam.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

class MainActivity : Activity(), CameraController.Listener, SensorEventListener {

    private enum class Param { ISO, SHUTTER, FOCUS, WB, EV }

    private lateinit var root: View
    private lateinit var topArea: View
    private lateinit var bottomPanel: View
    private lateinit var textureView: AutoFitTextureView
    private lateinit var overlay: OverlayView
    private lateinit var shutterFlash: View
    private lateinit var histogram: HistogramView
    private lateinit var readout: TextView
    private lateinit var btnFlash: TextView
    private lateinit var btnTimer: TextView
    private lateinit var btnGrid: TextView
    private lateinit var btnLevel: TextView
    private lateinit var btnRaw: TextView
    private lateinit var sliderLabel: TextView
    private lateinit var slider: SeekBar
    private lateinit var chips: Map<Param, TextView>
    private lateinit var modeAuto: TextView
    private lateinit var modePro: TextView
    private lateinit var thumbnail: ImageView
    private lateinit var shutter: ShutterButton
    private lateinit var btnSwitch: TextView
    private lateinit var countdownText: TextView

    private lateinit var camera: CameraController
    private lateinit var sensorManager: SensorManager
    private lateinit var orientationListener: OrientationEventListener

    private val mainHandler = Handler(Looper.getMainLooper())
    private var caps: CameraCaps? = null
    private var rawBlocked = false
    private var settings = ShotSettings()
    private var cameraIds = emptyList<String>()
    private var cameraIndex = 0

    private var isoSteps = listOf(100)
    private var shutterSteps = listOf(16_666_667L)
    private var evSteps = listOf(0)
    private var wbSteps = listOf(WhiteBalance.AUTO)
    private var selected = Param.EV

    private var timerSeconds = 0
    private var deviceOrientation = 0
    private var uiRotation = 0
    private var liveIso: Int? = null
    private var liveExposure: Long? = null
    private var liveFocus: Float? = null
    private var lastPhoto: Uri? = null
    private var capturing = false
    private var countdownRemaining = 0
    private var permissionRequested = false
    private var resumed = false
    private var updatingSlider = false
    private val gravity = FloatArray(3)
    private val histBitmap: Bitmap = Bitmap.createBitmap(96, 72, Bitmap.Config.ARGB_8888)

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goEdgeToEdge()
        bindViews()

        camera = CameraController(this, this)
        cameraIds = camera.listCameras()
        sensorManager = getSystemService(SensorManager::class.java)
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                deviceOrientation = orientation
                rotateUi(orientation)
            }
        }

        setupListeners()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        orientationListener.enable()
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        mainHandler.post(histogramTick)
        if (hasCameraPermission()) {
            startCamera()
        } else if (!permissionRequested) {
            permissionRequested = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onPause() {
        resumed = false
        cancelCountdown()
        camera.close()
        capturing = false
        shutter.busy = false
        orientationListener.disable()
        sensorManager.unregisterListener(this)
        mainHandler.removeCallbacks(histogramTick)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            cameraIds = camera.listCameras()
            startCamera()
        } else {
            toast(getString(R.string.permission_needed))
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Volume keys / hardware camera key act as a shutter release, like a real camera.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_CAMERA
        ) {
            if (event.repeatCount == 0) onShutterPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hasCameraPermission() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (!resumed || !hasCameraPermission()) return
        val texture = textureView.surfaceTexture
        if (!textureView.isAvailable || texture == null) return // surfaceTextureListener will call back
        if (cameraIds.isEmpty()) cameraIds = camera.listCameras()
        if (cameraIds.isEmpty()) {
            toast("No camera found")
            return
        }
        cameraIndex = cameraIndex.coerceIn(0, cameraIds.size - 1)
        camera.open(cameraIds[cameraIndex], texture, settings)
    }

    // ------------------------------------------------------------------ setup

    @Suppress("DEPRECATION")
    private fun goEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        topArea = findViewById(R.id.topArea)
        bottomPanel = findViewById(R.id.bottomPanel)
        textureView = findViewById(R.id.textureView)
        overlay = findViewById(R.id.overlay)
        shutterFlash = findViewById(R.id.shutterFlash)
        histogram = findViewById(R.id.histogram)
        readout = findViewById(R.id.readout)
        btnFlash = findViewById(R.id.btnFlash)
        btnTimer = findViewById(R.id.btnTimer)
        btnGrid = findViewById(R.id.btnGrid)
        btnLevel = findViewById(R.id.btnLevel)
        btnRaw = findViewById(R.id.btnRaw)
        sliderLabel = findViewById(R.id.sliderLabel)
        slider = findViewById(R.id.slider)
        chips = mapOf(
            Param.ISO to findViewById(R.id.chipIso),
            Param.SHUTTER to findViewById(R.id.chipShutter),
            Param.FOCUS to findViewById(R.id.chipFocus),
            Param.WB to findViewById(R.id.chipWb),
            Param.EV to findViewById(R.id.chipEv),
        )
        modeAuto = findViewById(R.id.modeAuto)
        modePro = findViewById(R.id.modePro)
        thumbnail = findViewById(R.id.thumbnail)
        shutter = findViewById(R.id.shutter)
        btnSwitch = findViewById(R.id.btnSwitch)
        countdownText = findViewById(R.id.countdown)

        val topBase = topArea.paddingTop
        val bottomBase = bottomPanel.paddingBottom
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            topArea.setPadding(topArea.paddingLeft, topBase + top, topArea.paddingRight, topArea.paddingBottom)
            bottomPanel.setPadding(bottomPanel.paddingLeft, bottomPanel.paddingTop, bottomPanel.paddingRight, bottomBase + bottom)
            insets
        }
    }

    private fun setupListeners() {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = startCamera()
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (textureView.width == 0 || textureView.height == 0) return false
                overlay.showFocus(e.x, e.y)
                camera.focusAt(e.x / textureView.width, e.y / textureView.height)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                camera.resetFocus()
                toast("Continuous AF")
            }
        })
        textureView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) v.performClick()
            gestures.onTouchEvent(event)
        }

        btnFlash.setOnClickListener {
            if (caps?.hasFlash != true) {
                toast(getString(R.string.no_flash)); return@setOnClickListener
            }
            val values = FlashMode.values()
            settings = settings.copy(flash = values[(settings.flash.ordinal + 1) % values.size])
            pushSettings()
        }
        btnTimer.setOnClickListener {
            timerSeconds = when (timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }
            refreshUi()
        }
        btnGrid.setOnClickListener { overlay.gridEnabled = !overlay.gridEnabled; refreshUi() }
        btnLevel.setOnClickListener { overlay.levelEnabled = !overlay.levelEnabled; refreshUi() }
        btnRaw.setOnClickListener {
            if (caps?.raw != true || rawBlocked) {
                toast(getString(R.string.no_raw)); return@setOnClickListener
            }
            settings = settings.copy(raw = !settings.raw)
            pushSettings()
            toast(if (settings.raw) "RAW + JPEG" else "JPEG only")
        }

        modeAuto.setOnClickListener { setProMode(false) }
        modePro.setOnClickListener { setProMode(true) }

        chips.forEach { (param, chip) ->
            chip.setOnClickListener {
                if ((param == Param.ISO || param == Param.SHUTTER) && !settings.manualExposure) setProMode(true)
                if (param == Param.EV && settings.manualExposure) setProMode(false)
                selected = param
                refreshUi()
            }
        }

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && !updatingSlider) onSlider(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        shutter.setOnClickListener { onShutterPressed() }
        thumbnail.setOnClickListener { openLastPhoto() }
        btnSwitch.setOnClickListener {
            if (cameraIds.size < 2) return@setOnClickListener
            cameraIndex = (cameraIndex + 1) % cameraIds.size
            settings = settings.copy(manualFocus = false, raw = false)
            rawBlocked = false
            btnSwitch.animate().rotationBy(180f).setDuration(250).start()
            startCamera()
        }
    }

    // ------------------------------------------------------------------ controls

    private fun setProMode(pro: Boolean) {
        val c = caps ?: return
        if (pro == settings.manualExposure) return
        if (pro) {
            if (!c.manualSensor) {
                toast(getString(R.string.no_manual)); return
            }
            // Start from whatever auto-exposure was doing so the image doesn't jump.
            val iso = liveIso?.let { live -> isoSteps[Stops.nearestIndex(isoSteps, live) { a, b -> abs(a - b).toDouble() }] }
                ?: settings.iso
            val exp = liveExposure?.let { live -> shutterSteps[nearestShutter(live)] } ?: settings.exposureNs
            settings = settings.copy(manualExposure = true, iso = iso, exposureNs = exp)
            if (selected == Param.EV) selected = Param.SHUTTER
        } else {
            settings = settings.copy(manualExposure = false)
            if (selected == Param.ISO || selected == Param.SHUTTER) selected = Param.EV
        }
        pushSettings()
    }

    private fun onSlider(progress: Int) {
        val c = caps ?: return
        settings = when (selected) {
            Param.ISO -> settings.copy(iso = isoSteps[progress.coerceIn(isoSteps.indices)])
            Param.SHUTTER -> settings.copy(exposureNs = shutterSteps[progress.coerceIn(shutterSteps.indices)])
            Param.FOCUS ->
                if (progress == 0) settings.copy(manualFocus = false)
                else settings.copy(
                    manualFocus = true,
                    focusDiopters = c.minFocusDistance * (FOCUS_STEPS - progress) / (FOCUS_STEPS - 1f),
                )
            Param.WB -> settings.copy(wb = wbSteps[progress.coerceIn(wbSteps.indices)])
            Param.EV -> settings.copy(evIndex = evSteps[progress.coerceIn(evSteps.indices)])
        }
        pushSettings()
    }

    private fun pushSettings() {
        camera.update(settings)
        refreshUi()
    }

    private fun nearestShutter(ns: Long) =
        Stops.nearestIndex(shutterSteps, ns) { a, b -> abs(Math.log(a.toDouble() / b.toDouble())) }

    // ------------------------------------------------------------------ capture

    private fun onShutterPressed() {
        if (countdownRemaining > 0) {
            cancelCountdown(); return
        }
        if (capturing || caps == null) return
        if (timerSeconds > 0) startCountdown() else capture()
    }

    private fun capture() {
        capturing = true
        shutter.busy = true
        camera.takePicture(deviceOrientation)
    }

    private val countdownTick = object : Runnable {
        override fun run() {
            if (countdownRemaining <= 0) {
                countdownText.visibility = View.GONE
                capture()
                return
            }
            countdownText.text = countdownRemaining.toString()
            countdownText.visibility = View.VISIBLE
            countdownText.scaleX = 1.4f
            countdownText.scaleY = 1.4f
            countdownText.animate().scaleX(1f).scaleY(1f).setDuration(300).start()
            countdownRemaining--
            mainHandler.postDelayed(this, 1000)
        }
    }

    private fun startCountdown() {
        countdownRemaining = timerSeconds
        mainHandler.post(countdownTick)
    }

    private fun cancelCountdown() {
        countdownRemaining = 0
        mainHandler.removeCallbacks(countdownTick)
        countdownText.visibility = View.GONE
    }

    private fun openLastPhoto() {
        val uri = lastPhoto ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_viewer))
        }
    }

    // ------------------------------------------------------------------ CameraController.Listener

    override fun onCameraOpened(caps: CameraCaps, previewSize: Size) {
        this.caps = caps
        if (caps.sensorOrientation % 180 == 90) {
            textureView.setAspectRatio(previewSize.height, previewSize.width)
        } else {
            textureView.setAspectRatio(previewSize.width, previewSize.height)
        }

        isoSteps = Stops.isoSteps(caps.isoRange)
        shutterSteps = Stops.shutterSteps(caps.exposureRange)
        evSteps = Stops.evSteps(caps.evRange)
        wbSteps = WhiteBalance.values().filter { caps.awbModes.contains(it.awbMode) }.ifEmpty { listOf(WhiteBalance.AUTO) }

        settings = settings.copy(
            manualExposure = settings.manualExposure && caps.manualSensor,
            iso = isoSteps[Stops.nearestIndex(isoSteps, settings.iso) { a, b -> abs(a - b).toDouble() }],
            exposureNs = shutterSteps[nearestShutter(settings.exposureNs)],
            evIndex = settings.evIndex.coerceIn(caps.evRange.lower, caps.evRange.upper),
            manualFocus = settings.manualFocus && caps.canManualFocus,
            wb = if (settings.wb in wbSteps) settings.wb else WhiteBalance.AUTO,
            flash = if (caps.hasFlash) settings.flash else FlashMode.OFF,
            raw = settings.raw && caps.raw,
        )
        camera.update(settings)
        refreshUi()
    }

    override fun onShutter() {
        shutterFlash.alpha = 1f
        shutterFlash.animate().alpha(0f).setDuration(180).start()
    }

    override fun onCaptureFinished(success: Boolean) {
        capturing = false
        shutter.busy = false
        if (!success) toast("Capture failed")
    }

    override fun onPhotoSaved(uri: Uri, mimeType: String) {
        if (mimeType != "image/jpeg" && lastPhoto != null) return
        lastPhoto = uri
        Thread {
            val bmp = try {
                contentResolver.loadThumbnail(uri, Size(200, 200), null)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (bmp != null) {
                    thumbnail.setImageBitmap(bmp)
                    thumbnail.scaleX = 0.6f
                    thumbnail.scaleY = 0.6f
                    thumbnail.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                }
            }
        }.start()
    }

    override fun onLiveStats(iso: Int?, exposureNs: Long?, focusDiopters: Float?, afState: Int?) {
        liveIso = iso
        liveExposure = exposureNs
        liveFocus = focusDiopters
        overlay.setAfState(afState)
        updateReadout()
    }

    override fun onRawUnavailable() {
        rawBlocked = true
        if (settings.raw) {
            settings = settings.copy(raw = false)
            toast(getString(R.string.raw_unavailable))
        }
        refreshUi()
    }

    override fun onCameraError(message: String) {
        toast(message)
    }

    // ------------------------------------------------------------------ sensors / histogram

    override fun onSensorChanged(event: SensorEvent) {
        val alpha = 0.85f
        for (i in 0..2) gravity[i] = alpha * gravity[i] + (1 - alpha) * event.values[i]
        val (x, y, z) = gravity
        val roll = Math.toDegrees(atan2(-x.toDouble(), y.toDouble())).toFloat()
        val flat = abs(z) > 8.5f
        overlay.setRoll(roll, flat)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private val histogramTick = object : Runnable {
        override fun run() {
            if (textureView.isAvailable && caps != null) {
                try {
                    textureView.getBitmap(histBitmap)
                    histogram.update(histBitmap)
                } catch (_: Exception) {
                }
            }
            mainHandler.postDelayed(this, HISTOGRAM_INTERVAL_MS)
        }
    }

    private fun rotateUi(orientation: Int) {
        val snapped = ((orientation + 45) / 90 * 90) % 360
        val target = -snapped.toFloat()
        if (target.roundToInt() == uiRotation) return
        uiRotation = target.roundToInt()
        // Rotate the round controls so they read upright in landscape, like a real camera.
        for (v in listOf(thumbnail, countdownText)) {
            var delta = (target - v.rotation) % 360
            if (delta > 180) delta -= 360
            if (delta < -180) delta += 360
            v.animate().rotation(v.rotation + delta).setDuration(250).start()
        }
    }

    // ------------------------------------------------------------------ UI state

    private fun refreshUi() {
        val c = caps
        val pro = settings.manualExposure

        btnFlash.text = if (c?.hasFlash == false) "NO FLASH" else settings.flash.label
        btnFlash.alpha = if (c?.hasFlash == false) 0.4f else 1f
        btnTimer.text = if (timerSeconds == 0) "TIMER OFF" else "TIMER ${timerSeconds}s"
        setActive(btnGrid, overlay.gridEnabled)
        setActive(btnLevel, overlay.levelEnabled)
        setActive(btnRaw, settings.raw)
        btnRaw.alpha = if (c?.raw == true && !rawBlocked) 1f else 0.4f

        setActive(modeAuto, !pro)
        setActive(modePro, pro)
        modePro.alpha = if (c?.manualSensor == false) 0.4f else 1f
        shutter.pro = pro

        chips.getValue(Param.ISO).text = "ISO\n${if (pro) settings.iso.toString() else "AUTO"}"
        chips.getValue(Param.SHUTTER).text = "SHUTTER\n${if (pro) Stops.shutterLabel(settings.exposureNs) else "AUTO"}"
        chips.getValue(Param.FOCUS).text = "FOCUS\n${if (settings.manualFocus) Stops.focusLabel(settings.focusDiopters) else "AF"}"
        chips.getValue(Param.WB).text = "WB\n${settings.wb.label}"
        chips.getValue(Param.EV).text = "EV\n${if (pro) "—" else Stops.evLabel(settings.evIndex, c?.evStep ?: android.util.Rational(1, 3))}"
        chips.forEach { (param, chip) ->
            chip.setTextColor(getColor(if (param == selected) R.color.accent else R.color.white))
        }
        chips.getValue(Param.FOCUS).alpha = if (c?.canManualFocus == false) 0.4f else 1f

        configureSlider()
        updateReadout()
    }

    private fun configureSlider() {
        val c = caps
        updatingSlider = true
        var enabled = c != null
        val label: String
        when (selected) {
            Param.ISO -> {
                slider.max = isoSteps.size - 1
                slider.progress = Stops.nearestIndex(isoSteps, settings.iso) { a, b -> abs(a - b).toDouble() }
                enabled = enabled && settings.manualExposure
                label = "ISO ${settings.iso}"
            }
            Param.SHUTTER -> {
                slider.max = shutterSteps.size - 1
                slider.progress = nearestShutter(settings.exposureNs)
                enabled = enabled && settings.manualExposure
                label = "Shutter ${Stops.shutterLabel(settings.exposureNs)}"
            }
            Param.FOCUS -> {
                slider.max = FOCUS_STEPS
                val min = c?.minFocusDistance ?: 0f
                slider.progress = if (!settings.manualFocus || min <= 0f) 0
                else (FOCUS_STEPS - (settings.focusDiopters / min) * (FOCUS_STEPS - 1)).roundToInt().coerceIn(1, FOCUS_STEPS)
                enabled = enabled && c?.canManualFocus == true
                label = if (settings.manualFocus) "Focus ${Stops.focusLabel(settings.focusDiopters)}  ·  slide left for AF"
                else "Autofocus  ·  slide right for manual focus"
            }
            Param.WB -> {
                slider.max = wbSteps.size - 1
                slider.progress = wbSteps.indexOf(settings.wb).coerceAtLeast(0)
                enabled = enabled && wbSteps.size > 1
                label = "White balance: ${settings.wb.label}"
            }
            Param.EV -> {
                slider.max = evSteps.size - 1
                slider.progress = evSteps.indexOf(settings.evIndex).coerceAtLeast(0)
                enabled = enabled && !settings.manualExposure && evSteps.size > 1
                label = "Exposure compensation ${Stops.evLabel(settings.evIndex, c?.evStep ?: android.util.Rational(1, 3))} EV"
            }
        }
        slider.isEnabled = enabled
        slider.alpha = if (enabled) 1f else 0.35f
        sliderLabel.text = label
        updatingSlider = false
    }

    private fun updateReadout() {
        val c = caps
        val parts = mutableListOf<String>()
        liveIso?.let { parts += "ISO $it" }
        liveExposure?.let { parts += Stops.shutterLabel(it) }
        c?.aperture?.let { parts += String.format(Locale.US, "f/%.1f", it) }
        c?.focalLength?.let { parts += String.format(Locale.US, "%.1fmm", it) }
        val line1 = parts.joinToString("  ")
        val line2 = buildString {
            append(if (settings.manualExposure) "M" else "P")
            append(if (settings.manualFocus) "  MF ${Stops.focusLabel(settings.focusDiopters)}" else "  AF")
            liveFocus?.takeIf { !settings.manualFocus && it > 0f }?.let { append(" ${Stops.focusLabel(it)}") }
            append(if (settings.raw) "  RAW+JPG" else "  JPG")
            if (c != null) append(if (c.isFront) "  FRONT" else "  BACK")
        }
        readout.text = "$line1\n$line2"
    }

    private fun setActive(v: TextView, active: Boolean) {
        v.setTextColor(getColor(if (active) R.color.accent else R.color.white))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQUEST_CAMERA = 1
        private const val FOCUS_STEPS = 100
        private const val HISTOGRAM_INTERVAL_MS = 200L
    }
}
