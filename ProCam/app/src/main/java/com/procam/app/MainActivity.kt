package com.procam.app

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : Activity(), CameraController.Listener, SensorEventListener {

    private enum class Param { ISO, SHUTTER, FOCUS, WB, EV }

    private lateinit var root: View
    private lateinit var topArea: View
    private lateinit var topBar: View
    private lateinit var previewContainer: View
    private lateinit var bottomPanel: View
    private lateinit var textureView: AutoFitTextureView
    private lateinit var overlay: OverlayView
    private lateinit var shutterFlash: View
    private lateinit var histogram: HistogramView
    private lateinit var meter: MeterView
    private lateinit var readout: TextView
    private lateinit var lockBadge: TextView
    private lateinit var btnFlash: TextView
    private lateinit var btnTimer: TextView
    private lateinit var btnRaw: TextView
    private lateinit var btnZebra: TextView
    private lateinit var btnSettings: TextView
    private lateinit var lensRow: LinearLayout
    private lateinit var sliderLabel: TextView
    private lateinit var slider: SeekBar
    private lateinit var chips: Map<Param, TextView>
    private lateinit var modeAuto: TextView
    private lateinit var modePro: TextView
    private lateinit var thumbnail: ImageView
    private lateinit var shutter: ShutterButton
    private lateinit var btnSwitch: TextView
    private lateinit var countdownText: TextView
    private lateinit var infoRow: View
    private lateinit var groupStatus: TextView
    private lateinit var intentScroll: View
    private lateinit var intentAuto: TextView
    private lateinit var intentGroup: TextView
    private lateinit var intentHint: TextView
    private lateinit var chipScroll: View
    private lateinit var modeRow: View
    private lateinit var uiSimple: TextView
    private lateinit var uiPro: TextView

    private lateinit var prefs: Prefs
    private lateinit var camera: CameraController
    private lateinit var sensorManager: SensorManager
    private lateinit var orientationListener: OrientationEventListener
    private lateinit var locationTracker: LocationTracker
    private val sound = MediaActionSound()

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

    // Frame analysis (histogram, meter, zebra, peaking)
    private val analyzer = FrameAnalyzer()
    private var analysisBitmap: Bitmap? = null
    private var analysisPhase = 0
    /** Brightness the camera's own auto exposure aims for, learned while in AUTO. */
    private var aeTarget = 0.18f
    private var meterEv: Float? = null
    private var zoomAnimator: ValueAnimator? = null

    // Group smart shutter
    private val groupShutter = GroupShutter()
    private var groupBitmap: Bitmap? = null
    private var groupTick = 0
    private var groupWaiting = false
    private var groupProcessing = false
    private var groupStart = 0L
    private var noFaceSince = 0L
    private var readyStreak = 0

    private val simple get() = !prefs.uiPro
    private val groupMode get() = simple && prefs.intent == "GROUP"

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goEdgeToEdge()
        bindViews()

        prefs = Prefs(this)
        settings = prefs.loadShot()
        camera = CameraController(this, this)
        cameraIds = camera.primaryCameras()
        sensorManager = getSystemService(SensorManager::class.java)
        locationTracker = LocationTracker(this) { camera.setLocation(it) }
        sound.load(MediaActionSound.SHUTTER_CLICK)
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
        overlay.gridEnabled = prefs.grid
        overlay.levelEnabled = prefs.level
        orientationListener.enable()
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (prefs.location) locationTracker.start() else locationTracker.stop()
        mainHandler.post(analysisTick)
        if (hasCameraPermission()) {
            startCamera()
        } else if (!permissionRequested) {
            permissionRequested = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
        refreshUi()
    }

    override fun onPause() {
        resumed = false
        cancelCountdown()
        cancelGroup()
        groupProcessing = false
        overlay.setFaces(emptyList())
        zoomAnimator?.cancel()
        camera.close()
        capturing = false
        shutter.busy = false
        orientationListener.disable()
        sensorManager.unregisterListener(this)
        locationTracker.stop()
        mainHandler.removeCallbacks(analysisTick)
        prefs.saveShot(settings)
        // The camera is closed now: ignore the shutter and clear live data until it reopens.
        caps = null
        liveIso = null
        liveExposure = null
        liveFocus = null
        readout.text = ""
        overlay.setAnalysis(null)
        super.onPause()
    }

    override fun onDestroy() {
        sound.release()
        groupShutter.close()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            cameraIds = camera.primaryCameras()
            startCamera()
        } else {
            toast(getString(R.string.permission_needed))
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_CAMERA) {
            if (event.repeatCount == 0) onShutterPressed()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            when (prefs.volumeKeys) {
                0 -> {
                    if (event.repeatCount == 0) onShutterPressed()
                    return true
                }
                1 -> {
                    val factor = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1.15f else 1 / 1.15f
                    setZoom(settings.zoom * factor)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hasCameraPermission() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (!resumed || !hasCameraPermission()) return
        val texture = textureView.surfaceTexture
        if (!textureView.isAvailable || texture == null) return // surfaceTextureListener will call back
        if (cameraIds.isEmpty()) cameraIds = camera.primaryCameras()
        if (cameraIds.isEmpty()) {
            toast("No camera found")
            return
        }
        cameraIndex = cameraIndex.coerceIn(0, cameraIds.size - 1)
        Log.i(TAG, "startCamera index=$cameraIndex ids=$cameraIds")
        camera.open(cameraIds[cameraIndex], texture, settings, prefs.output())
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
        topBar = findViewById(R.id.topBar)
        previewContainer = findViewById(R.id.previewContainer)
        bottomPanel = findViewById(R.id.bottomPanel)
        textureView = findViewById(R.id.textureView)
        overlay = findViewById(R.id.overlay)
        shutterFlash = findViewById(R.id.shutterFlash)
        histogram = findViewById(R.id.histogram)
        meter = findViewById(R.id.meter)
        readout = findViewById(R.id.readout)
        lockBadge = findViewById(R.id.lockBadge)
        btnFlash = findViewById(R.id.btnFlash)
        btnTimer = findViewById(R.id.btnTimer)
        btnRaw = findViewById(R.id.btnRaw)
        btnZebra = findViewById(R.id.btnZebra)
        btnSettings = findViewById(R.id.btnSettings)
        lensRow = findViewById(R.id.lensRow)
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
        infoRow = findViewById(R.id.infoRow)
        groupStatus = findViewById(R.id.groupStatus)
        intentScroll = findViewById(R.id.intentScroll)
        intentAuto = findViewById(R.id.intentAuto)
        intentGroup = findViewById(R.id.intentGroup)
        intentHint = findViewById(R.id.intentHint)
        chipScroll = findViewById(R.id.chipScroll)
        modeRow = findViewById(R.id.modeRow)
        uiSimple = findViewById(R.id.uiSimple)
        uiPro = findViewById(R.id.uiPro)

        // Preview starts right below the top bar (not hidden behind it)...
        topBar.addOnLayoutChangeListener { v, _, _, _, bottom, _, _, _, _ ->
            val lp = previewContainer.layoutParams as FrameLayout.LayoutParams
            if (lp.topMargin != bottom) {
                lp.topMargin = bottom
                previewContainer.post { previewContainer.layoutParams = lp }
            }
        }
        // ...and the grid/level/focus overlay and shutter flash cover exactly the preview.
        textureView.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            for (v in listOf<View>(overlay, shutterFlash)) {
                val lp = v.layoutParams as FrameLayout.LayoutParams
                if (lp.width != w || lp.height != h) {
                    lp.width = w
                    lp.height = h
                    v.post { v.layoutParams = lp }
                }
            }
        }

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
                showBadge("FOCUS POINT")
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (textureView.width == 0 || textureView.height == 0) return
                overlay.showFocus(e.x, e.y)
                camera.lockAt(e.x / textureView.width, e.y / textureView.height)
                showBadge("AE/AF LOCK")
                haptic(textureView, HapticFeedbackConstants.LONG_PRESS)
            }
        })
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomAnimator?.cancel()
                setZoom(settings.zoom * detector.scaleFactor)
                return true
            }
        })
        textureView.setOnTouchListener { v, event ->
            pinch.onTouchEvent(event)
            if (!pinch.isInProgress && event.pointerCount == 1) gestures.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }

        lockBadge.setOnClickListener {
            camera.resetFocus()
            lockBadge.visibility = View.GONE
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
            prefs.timerSeconds = when (prefs.timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }
            refreshUi()
        }
        btnRaw.setOnClickListener {
            if (caps?.raw != true || rawBlocked) {
                toast(getString(R.string.no_raw)); return@setOnClickListener
            }
            settings = settings.copy(raw = !settings.raw)
            pushSettings()
            toast(if (settings.raw) "RAW + ${photoLabel()}" else "${photoLabel()} only")
        }
        btnZebra.setOnClickListener {
            prefs.zebra = (prefs.zebra + 1) % 3
            toast(when (prefs.zebra) { 1 -> "Zebra 95 %"; 2 -> "Zebra 100 %"; else -> "Zebra off" })
            refreshUi()
        }
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        uiSimple.setOnClickListener { setUiPro(false) }
        uiPro.setOnClickListener { setUiPro(true) }
        intentAuto.setOnClickListener { setIntent("AUTO") }
        intentGroup.setOnClickListener { setIntent("GROUP") }

        modeAuto.setOnClickListener { setProMode(false) }
        modePro.setOnClickListener { setProMode(true) }

        chips.forEach { (param, chip) ->
            chip.setOnClickListener {
                if ((param == Param.ISO || param == Param.SHUTTER) && !settings.manualExposure) setProMode(true)
                selected = param
                haptic(chip, HapticFeedbackConstants.VIRTUAL_KEY)
                refreshUi()
            }
        }

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && !updatingSlider) {
                    haptic(seekBar, HapticFeedbackConstants.CLOCK_TICK)
                    onSlider(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        shutter.setOnClickListener { onShutterPressed() }
        thumbnail.setOnClickListener { openLastPhoto() }
        btnSwitch.setOnClickListener {
            if (cameraIds.size < 2) return@setOnClickListener
            cameraIndex = (cameraIndex + 1) % cameraIds.size
            settings = settings.copy(manualFocus = false, raw = false, zoom = 1f)
            rawBlocked = false
            lockBadge.visibility = View.GONE
            btnSwitch.animate().rotationBy(180f).setDuration(250).start()
            startCamera()
        }
    }

    // ------------------------------------------------------------------ controls

    private fun setUiPro(pro: Boolean) {
        if (pro == prefs.uiPro) return
        prefs.uiPro = pro
        cancelGroup()
        overlay.setFaces(emptyList())
        if (!pro) {
            // Simple mode: the camera handles exposure and focus.
            settings = settings.copy(manualExposure = false, manualFocus = false)
            selected = Param.EV
        }
        haptic(if (pro) uiPro else uiSimple, HapticFeedbackConstants.VIRTUAL_KEY)
        pushSettings()
    }

    private fun setIntent(intent: String) {
        if (intent == prefs.intent) return
        prefs.intent = intent
        cancelGroup()
        overlay.setFaces(emptyList())
        haptic(intentRowView(intent), HapticFeedbackConstants.VIRTUAL_KEY)
        refreshUi()
    }

    private fun intentRowView(intent: String) = if (intent == "GROUP") intentGroup else intentAuto

    private fun updateUiMode() {
        val pro = prefs.uiPro
        fun show(v: View, on: Boolean) {
            v.visibility = if (on) View.VISIBLE else View.GONE
        }
        // Simple: intents + hint. Pro: dials, chips, histogram, meter, RAW and zebra.
        show(intentScroll, !pro)
        show(intentHint, !pro)
        show(sliderLabel, pro)
        show(slider, pro)
        show(chipScroll, pro)
        show(modeRow, pro)
        show(infoRow, pro)
        show(btnRaw, pro)
        show(btnZebra, pro)
        uiSimple.setBackgroundResource(if (pro) 0 else R.drawable.segment_selected_bg)
        uiPro.setBackgroundResource(if (pro) R.drawable.segment_selected_bg else 0)
        uiSimple.setTextColor(getColor(if (pro) R.color.dim else R.color.white))
        uiPro.setTextColor(getColor(if (pro) R.color.white else R.color.dim))

        val group = prefs.intent == "GROUP"
        for ((v, on) in listOf(intentAuto to !group, intentGroup to group)) {
            v.setBackgroundResource(if (on) R.drawable.pill_selected_bg else R.drawable.pill_bg)
            v.setTextColor(getColor(if (on) R.color.black else R.color.white))
        }
        intentHint.text = if (group) {
            "Press once · ProCam waits until everyone's eyes are open, takes $GROUP_BURST shots and keeps the best"
        } else {
            "ProCam sets everything for you · it finds faces and focuses on them"
        }
    }

    private fun setProMode(pro: Boolean) {
        val c = caps ?: return
        if (pro == settings.manualExposure) return
        if (pro) {
            if (!c.manualSensor) {
                toast(getString(R.string.no_manual)); return
            }
            // Start from whatever auto exposure was doing so the image doesn't jump.
            val iso = liveIso?.let { live -> isoSteps[nearestIso(live)] } ?: settings.iso
            val exp = liveExposure?.let { live -> shutterSteps[nearestShutter(live)] } ?: settings.exposureNs
            settings = settings.copy(manualExposure = true, isoAuto = false, shutterAuto = false, iso = iso, exposureNs = exp)
            if (selected == Param.EV) selected = Param.SHUTTER
        } else {
            settings = settings.copy(manualExposure = false)
            if (selected == Param.ISO || selected == Param.SHUTTER) selected = Param.EV
        }
        haptic(modePro, HapticFeedbackConstants.VIRTUAL_KEY)
        pushSettings()
    }

    private fun onSlider(progress: Int) {
        val c = caps ?: return
        val before = settings
        settings = when (selected) {
            // In PRO, position 0 of the ISO / shutter dials is "A" (auto) → shutter / ISO priority.
            Param.ISO ->
                if (progress == 0) settings.copy(isoAuto = true)
                else settings.copy(isoAuto = false, iso = isoSteps[(progress - 1).coerceIn(isoSteps.indices)])
            Param.SHUTTER ->
                if (progress == 0) settings.copy(shutterAuto = true)
                else settings.copy(shutterAuto = false, exposureNs = shutterSteps[(progress - 1).coerceIn(shutterSteps.indices)])
            Param.FOCUS ->
                if (progress == 0) settings.copy(manualFocus = false)
                else settings.copy(
                    manualFocus = true,
                    focusDiopters = c.minFocusDistance * (FOCUS_STEPS - progress) / (FOCUS_STEPS - 1f),
                )
            Param.WB -> settings.copy(wb = wbSteps[progress.coerceIn(wbSteps.indices)])
            Param.EV -> settings.copy(evIndex = evSteps[progress.coerceIn(evSteps.indices)])
        }
        if (before.cameraAe && !settings.cameraAe) {
            // Leaving camera auto exposure: start the auto-driven value from what AE was using.
            if (settings.isoAuto) liveIso?.let { settings = settings.copy(iso = it) }
            if (settings.shutterAuto) liveExposure?.let { settings = settings.copy(exposureNs = it) }
        }
        pushSettings()
    }

    private fun setZoom(z: Float) {
        val c = caps ?: return
        val clamped = z.coerceIn(c.zoomRange.lower, c.zoomRange.upper)
        if (abs(clamped - settings.zoom) < 0.001f) return
        settings = settings.copy(zoom = clamped)
        camera.update(settings)
        updateLensRow()
        updateReadout()
    }

    private fun animateZoom(target: Float) {
        zoomAnimator?.cancel()
        zoomAnimator = ValueAnimator.ofFloat(settings.zoom, target).apply {
            duration = 220
            addUpdateListener { setZoom(it.animatedValue as Float) }
            start()
        }
    }

    private fun pushSettings() {
        camera.update(settings)
        refreshUi()
    }

    private fun nearestIso(iso: Int) = Stops.nearestIndex(isoSteps, iso) { a, b -> abs(ln(a.toDouble() / b)) }

    private fun nearestShutter(ns: Long) =
        Stops.nearestIndex(shutterSteps, ns) { a, b -> abs(ln(a.toDouble() / b.toDouble())) }

    // ------------------------------------------------------------------ capture

    private fun onShutterPressed() {
        if (countdownRemaining > 0) {
            cancelCountdown(); return
        }
        if (groupWaiting) {
            cancelGroup(); return
        }
        if (capturing || groupProcessing || caps == null) return
        haptic(shutter, HapticFeedbackConstants.VIRTUAL_KEY)
        if (prefs.timerSeconds > 0) startCountdown() else afterCountdown()
    }

    /** What the shutter does once any self-timer has run. */
    private fun afterCountdown() {
        if (groupMode) startGroup() else capture()
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
                afterCountdown()
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
        countdownRemaining = prefs.timerSeconds
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
        Log.i(TAG, "camera opened id=${caps.id} front=${caps.isFront} faces=${caps.faceDetectMode} facePriority=${caps.facePriorityScene}")
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
            manualExposure = settings.manualExposure && caps.manualSensor && prefs.uiPro,
            iso = caps.isoRange?.let { settings.iso.coerceIn(it.lower, it.upper) } ?: settings.iso,
            exposureNs = caps.exposureRange?.let { settings.exposureNs.coerceIn(it.lower, it.upper) } ?: settings.exposureNs,
            evIndex = settings.evIndex.coerceIn(caps.evRange.lower, caps.evRange.upper),
            manualFocus = settings.manualFocus && caps.canManualFocus && prefs.uiPro,
            wb = if (settings.wb in wbSteps) settings.wb else WhiteBalance.AUTO,
            flash = if (caps.hasFlash) settings.flash else FlashMode.OFF,
            raw = settings.raw && caps.raw,
            zoom = settings.zoom.coerceIn(caps.zoomRange.lower, caps.zoomRange.upper),
        )
        camera.update(settings)
        buildLensRow()
        refreshUi()
    }

    override fun onShutter() {
        if (prefs.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
        shutterFlash.alpha = 1f
        shutterFlash.animate().alpha(0f).setDuration(180).start()
    }

    override fun onCaptureFinished(success: Boolean) {
        if (groupProcessing && success) return // wait for the burst to be scored and saved
        if (!success) {
            groupProcessing = false
            groupStatus.visibility = View.GONE
        }
        capturing = false
        shutter.busy = false
        if (!success) toast("Capture failed")
    }

    override fun onPhotoSaved(uri: Uri, mimeType: String) {
        if (mimeType == "image/x-adobe-dng" && lastPhoto != null) return
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

    override fun onFaces(faces: List<RectF>) {
        // In Group mode the smarter ML Kit boxes (green = ready) are drawn instead.
        if (!groupMode) overlay.setFaces(faces)
    }

    override fun onBurstCaptured(frames: List<ByteArray>, mimeType: String) {
        if (!groupProcessing) return
        groupStatus.text = "Picking the best shot…"
        Thread {
            val pick = groupShutter.pickBest(frames)
            val ext = if (mimeType == "image/heic") "heic" else "jpg"
            val uri = MediaSaver.save(this, "${MediaSaver.newBaseName()}.$ext", mimeType, writer = { it.write(frames[pick.index]) })
            runOnUiThread {
                groupProcessing = false
                capturing = false
                shutter.busy = false
                groupStatus.visibility = View.GONE
                if (uri != null) onPhotoSaved(uri, mimeType) else toast(getString(R.string.save_failed))
                toast(
                    when {
                        pick.faces > 0 && pick.ready == pick.faces ->
                            "Kept the best of ${frames.size} · everyone's eyes open"
                        pick.faces > 0 -> "Kept the best of ${frames.size} · ${pick.ready} of ${pick.faces} ready"
                        else -> "Kept the sharpest of ${frames.size}"
                    },
                )
            }
        }.start()
    }

    // ------------------------------------------------------------------ group smart shutter

    private fun startGroup() {
        val now = SystemClock.uptimeMillis()
        groupWaiting = true
        groupStart = now
        noFaceSince = now
        readyStreak = 0
        shutter.busy = true
        showGroupStatus("Looking for faces…")
    }

    private fun cancelGroup() {
        if (!groupWaiting) return
        groupWaiting = false
        shutter.busy = false
        groupStatus.visibility = View.GONE
    }

    private fun onGroupPeople(people: List<GroupShutter.Person>) {
        if (!groupMode) return
        overlay.setFaces(
            people.map { it.box },
            people.map { if (it.ready) OverlayView.FACE_READY else OverlayView.FACE_WAITING }.toIntArray(),
        )
        if (!groupWaiting) return
        val now = SystemClock.uptimeMillis()
        val total = people.size
        val ready = people.count { it.ready }
        if (total == 0) {
            readyStreak = 0
            // Nobody in the shot: don't keep the user waiting.
            if (now - noFaceSince > NO_FACE_GIVE_UP_MS) fireGroup() else showGroupStatus("Looking for faces…")
            return
        }
        noFaceSince = now
        readyStreak = if (ready == total) readyStreak + 1 else 0
        when {
            readyStreak >= READY_FRAMES -> fireGroup()
            now - groupStart > GROUP_TIMEOUT_MS -> fireGroup()
            ready == total -> showGroupStatus("Everyone's ready…")
            else -> {
                val waiting = people.filter { !it.ready }
                val why = if (waiting.any { !it.eyesOpen }) "eyes open" else "look at the camera"
                showGroupStatus("Waiting for ${total - ready} of $total · $why")
            }
        }
    }

    private fun fireGroup() {
        if (!groupWaiting) return
        groupWaiting = false
        groupProcessing = true
        capturing = true
        showGroupStatus("Hold still…")
        camera.takeBurst(deviceOrientation, GROUP_BURST)
    }

    private fun showGroupStatus(text: String) {
        groupStatus.text = text
        groupStatus.visibility = View.VISIBLE
    }

    override fun onCameraError(message: String) {
        toast(message)
    }

    // ------------------------------------------------------------------ sensors / analysis

    override fun onSensorChanged(event: SensorEvent) {
        val alpha = 0.85f
        for (i in 0..2) gravity[i] = alpha * gravity[i] + (1 - alpha) * event.values[i]
        val (x, y, z) = gravity
        val roll = Math.toDegrees(atan2(-x.toDouble(), y.toDouble())).toFloat()
        overlay.setRoll(roll, abs(z) > 8.5f)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    /** ~7 times a second: snapshot the viewfinder, update histogram, meter, zebra/peaking and priority AE. */
    private val analysisTick = object : Runnable {
        override fun run() {
            mainHandler.postDelayed(this, ANALYSIS_INTERVAL_MS)
            val c = caps ?: return
            if (!textureView.isAvailable || textureView.width == 0) return
            val w = ANALYSIS_WIDTH
            val h = (w * textureView.height / textureView.width).coerceAtLeast(1)
            var bmp = analysisBitmap
            if (bmp == null || bmp.width != w || bmp.height != h) {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                analysisBitmap = bmp
            }
            try {
                textureView.getBitmap(bmp)
            } catch (_: Exception) {
                return
            }
            val zebra = when (prefs.zebra) { 1 -> 242; 2 -> 252; else -> 0 }
            val peaking = when (prefs.peaking) {
                0 -> settings.manualFocus
                1 -> true
                else -> false
            }
            analysisPhase = (analysisPhase + 1) % 8
            val marks = analyzer.analyze(bmp, zebra, peaking, analysisPhase)
            histogram.setData(analyzer)
            overlay.setAnalysis(if (marks) analyzer.overlay else null)
            updateMeter(c)
            if (groupMode) analyzeGroup()
        }
    }

    /** Runs the face/eyes detector on a viewfinder snapshot about 3 times a second. */
    private fun analyzeGroup() {
        if (groupWaiting && SystemClock.uptimeMillis() - groupStart > GROUP_TIMEOUT_MS + 1000) fireGroup()
        if (++groupTick % 2 != 0) return
        val w = GROUP_ANALYSIS_WIDTH
        val h = (w * textureView.height / textureView.width).coerceAtLeast(1)
        var bmp = groupBitmap
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            groupBitmap = bmp
        }
        try {
            textureView.getBitmap(bmp)
        } catch (_: Exception) {
            return
        }
        // The detector works on a copy so the next snapshot can't change pixels under it.
        groupShutter.analyzePreview(bmp.copy(Bitmap.Config.ARGB_8888, false)) { onGroupPeople(it) }
    }

    private fun evBias(c: CameraCaps) = settings.evIndex * c.evStep.toFloat()

    private fun updateMeter(c: CameraCaps) {
        val mean = analyzer.meanLinear
        if (mean < 1e-4f) return
        if (settings.cameraAe) {
            // Learn what "correctly exposed" looks like on this phone from its own auto exposure.
            val neutral = mean / 2f.pow(evBias(c))
            aeTarget = (0.9f * aeTarget + 0.1f * neutral).coerceIn(0.05f, 0.45f)
        }
        val bias = if (settings.priorityMode) evBias(c) else 0f
        val ev = (ln(mean / (aeTarget * 2f.pow(bias))) / ln(2f))
        meterEv = ev
        meter.ev = if (settings.manualExposure && !settings.cameraAe) ev else null
        if (settings.priorityMode && !capturing) runPriorityAe(c, ev)
    }

    /** Shutter priority (ISO auto) or ISO priority (shutter auto), driven by the meter. */
    private fun runPriorityAe(c: CameraCaps, ev: Float) {
        if (abs(ev) < 0.15f) return
        val factor = 2.0.pow(-ev * 0.7).toFloat()
        val old = settings
        settings = if (settings.isoAuto) {
            val r = c.isoRange ?: return
            settings.copy(iso = (settings.iso * factor).roundToInt().coerceIn(r.lower, r.upper))
        } else {
            val r = c.exposureRange ?: return
            val maxAuto = min(r.upper, MAX_AUTO_SHUTTER_NS)
            settings.copy(exposureNs = (settings.exposureNs * factor).toLong().coerceIn(r.lower, maxAuto))
        }
        if (settings != old) {
            camera.update(settings)
            updateChips()
            if (selected == Param.ISO || selected == Param.SHUTTER) sliderLabel.text = sliderLabelText()
        }
    }

    private fun rotateUi(orientation: Int) {
        val snapped = ((orientation + 45) / 90 * 90) % 360
        val target = -snapped.toFloat()
        if (target.roundToInt() == uiRotation) return
        uiRotation = target.roundToInt()
        // Rotate the round controls so they read upright in landscape, like a real camera.
        val views = mutableListOf<View>(thumbnail, countdownText, btnSwitch)
        for (i in 0 until lensRow.childCount) views += lensRow.getChildAt(i)
        for (v in views) {
            var delta = (target - v.rotation) % 360
            if (delta > 180) delta -= 360
            if (delta < -180) delta += 360
            v.animate().rotation(v.rotation + delta).setDuration(250).start()
        }
    }

    // ------------------------------------------------------------------ lens buttons

    private fun buildLensRow() {
        lensRow.removeAllViews()
        val c = caps ?: return
        val size = (40 * resources.displayMetrics.density).toInt()
        val margin = (6 * resources.displayMetrics.density).toInt()
        for (stop in c.lensStops) {
            val tv = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                setBackgroundResource(R.drawable.circle_bg)
                tag = stop
                contentDescription = "Zoom ${Stops.zoomLabel(stop)}"
                rotation = uiRotation.toFloat()
                setOnClickListener {
                    haptic(this, HapticFeedbackConstants.VIRTUAL_KEY)
                    animateZoom(stop)
                }
            }
            lensRow.addView(tv, LinearLayout.LayoutParams(size, size).apply { setMargins(margin, 0, margin, 0) })
        }
        lensRow.visibility = if (c.lensStops.size > 1 || c.zoomRange.upper > 1.01f) View.VISIBLE else View.GONE
        updateLensRow()
    }

    private fun updateLensRow() {
        val stops = caps?.lensStops ?: return
        // The active lens is the largest stop at or below the current zoom.
        val active = stops.lastOrNull { it <= settings.zoom + 0.01f } ?: stops.first()
        for (i in 0 until lensRow.childCount) {
            val tv = lensRow.getChildAt(i) as TextView
            val stop = tv.tag as Float
            if (stop == active) {
                tv.text = Stops.zoomLabel(settings.zoom)
                tv.setTextColor(getColor(R.color.accent))
            } else {
                tv.text = Stops.zoomLabel(stop).removeSuffix("×")
                tv.setTextColor(getColor(R.color.white))
            }
        }
    }

    // ------------------------------------------------------------------ UI state

    private fun refreshUi() {
        updateUiMode()
        val c = caps
        val pro = settings.manualExposure

        btnFlash.text = if (c?.hasFlash == false) "NO FLASH" else settings.flash.label
        btnFlash.alpha = if (c?.hasFlash == false) 0.4f else 1f
        btnTimer.text = if (prefs.timerSeconds == 0) "TIMER OFF" else "TIMER ${prefs.timerSeconds}s"
        setActive(btnRaw, settings.raw)
        btnRaw.alpha = if (c?.raw == true && !rawBlocked) 1f else 0.4f
        setActive(btnZebra, prefs.zebra != 0)
        btnZebra.text = when (prefs.zebra) { 1 -> "ZEBRA 95"; 2 -> "ZEBRA 100"; else -> "ZEBRA" }

        setActive(modeAuto, !pro)
        setActive(modePro, pro)
        modePro.alpha = if (c?.manualSensor == false) 0.4f else 1f
        shutter.pro = pro
        meter.visibility = if (pro && prefs.uiPro) View.VISIBLE else View.GONE

        updateChips()
        configureSlider()
        updateLensRow()
        updateReadout()
    }

    private fun updateChips() {
        val c = caps
        val pro = settings.manualExposure
        val evEnabled = settings.cameraAe || settings.priorityMode
        chips.getValue(Param.ISO).text = "ISO\n" + when {
            !pro -> "AUTO"
            settings.isoAuto -> "A ${settings.iso}"
            else -> settings.iso.toString()
        }
        chips.getValue(Param.SHUTTER).text = "SHUTTER\n" + when {
            !pro -> "AUTO"
            settings.shutterAuto -> "A ${Stops.shutterLabel(settings.exposureNs)}"
            else -> Stops.shutterLabel(settings.exposureNs)
        }
        chips.getValue(Param.FOCUS).text = "FOCUS\n${if (settings.manualFocus) Stops.focusLabel(settings.focusDiopters) else "AF"}"
        chips.getValue(Param.WB).text = "WB\n${settings.wb.label}"
        chips.getValue(Param.EV).text = "EV\n${if (evEnabled) Stops.evLabel(settings.evIndex, c?.evStep ?: Rational(1, 3)) else "—"}"
        chips.forEach { (param, chip) ->
            chip.setTextColor(getColor(if (param == selected) R.color.accent else R.color.white))
        }
        chips.getValue(Param.FOCUS).alpha = if (c?.canManualFocus == false) 0.4f else 1f
        chips.getValue(Param.EV).alpha = if (evEnabled) 1f else 0.4f
    }

    private fun configureSlider() {
        val c = caps
        updatingSlider = true
        var enabled = c != null
        when (selected) {
            Param.ISO -> {
                slider.max = isoSteps.size
                slider.progress = if (settings.isoAuto) 0 else nearestIso(settings.iso) + 1
                enabled = enabled && settings.manualExposure
            }
            Param.SHUTTER -> {
                slider.max = shutterSteps.size
                slider.progress = if (settings.shutterAuto) 0 else nearestShutter(settings.exposureNs) + 1
                enabled = enabled && settings.manualExposure
            }
            Param.FOCUS -> {
                slider.max = FOCUS_STEPS
                val min = c?.minFocusDistance ?: 0f
                slider.progress = if (!settings.manualFocus || min <= 0f) 0
                else (FOCUS_STEPS - (settings.focusDiopters / min) * (FOCUS_STEPS - 1)).roundToInt().coerceIn(1, FOCUS_STEPS)
                enabled = enabled && c?.canManualFocus == true
            }
            Param.WB -> {
                slider.max = wbSteps.size - 1
                slider.progress = wbSteps.indexOf(settings.wb).coerceAtLeast(0)
                enabled = enabled && wbSteps.size > 1
            }
            Param.EV -> {
                slider.max = evSteps.size - 1
                slider.progress = evSteps.indexOf(settings.evIndex).coerceAtLeast(0)
                enabled = enabled && (settings.cameraAe || settings.priorityMode) && evSteps.size > 1
            }
        }
        slider.isEnabled = enabled
        slider.alpha = if (enabled) 1f else 0.35f
        sliderLabel.text = sliderLabelText()
        updatingSlider = false
    }

    private fun sliderLabelText(): String {
        val step = caps?.evStep ?: Rational(1, 3)
        return when (selected) {
            Param.ISO -> if (settings.isoAuto) "ISO auto (shutter priority) · ${settings.iso}" else "ISO ${settings.iso}  ·  slide left for auto"
            Param.SHUTTER -> if (settings.shutterAuto) "Shutter auto (ISO priority) · ${Stops.shutterLabel(settings.exposureNs)}"
            else "Shutter ${Stops.shutterLabel(settings.exposureNs)}  ·  slide left for auto"
            Param.FOCUS -> if (settings.manualFocus) "Focus ${Stops.focusLabel(settings.focusDiopters)}  ·  slide left for AF"
            else "Autofocus  ·  slide right for manual focus"
            Param.WB -> "White balance: ${settings.wb.label}"
            Param.EV -> "Exposure compensation ${Stops.evLabel(settings.evIndex, step)} EV"
        }
    }

    private fun updateReadout() {
        val c = caps
        val parts = mutableListOf<String>()
        liveIso?.let { parts += "ISO $it" }
        liveExposure?.let { parts += Stops.shutterLabel(it) }
        c?.aperture?.let { parts += String.format(Locale.US, "f/%.1f", it) }
        if (c != null) parts += Stops.zoomLabel(settings.zoom)
        val line1 = parts.joinToString("  ")
        val line2 = buildString {
            append(
                when {
                    !settings.manualExposure -> "P"
                    settings.isoAuto && settings.shutterAuto -> "P"
                    settings.isoAuto -> "S"
                    settings.shutterAuto -> "ISO-P"
                    else -> "M"
                },
            )
            append(if (settings.manualFocus) "  MF ${Stops.focusLabel(settings.focusDiopters)}" else "  AF")
            liveFocus?.takeIf { !settings.manualFocus && it > 0f }?.let { append(" ${Stops.focusLabel(it)}") }
            append(if (settings.raw) "  RAW+${photoLabel()}" else "  ${photoLabel()}")
            if (c != null) append(if (c.isFront) "  FRONT" else "  BACK")
        }
        readout.text = "$line1\n$line2"
    }

    private fun photoLabel() = if (prefs.heif) "HEIF" else "JPG"

    private fun showBadge(text: String) {
        lockBadge.text = "$text  ✕"
        lockBadge.visibility = View.VISIBLE
    }

    private fun setActive(v: TextView, active: Boolean) {
        v.setTextColor(getColor(if (active) R.color.accent else R.color.white))
    }

    private fun haptic(v: View, type: Int) {
        if (prefs.haptics) v.performHapticFeedback(type)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "ProCam"
        private const val REQUEST_CAMERA = 1
        private const val GROUP_BURST = 3
        private const val GROUP_ANALYSIS_WIDTH = 480
        private const val READY_FRAMES = 2
        private const val GROUP_TIMEOUT_MS = 6000L
        private const val NO_FACE_GIVE_UP_MS = 1500L
        private const val FOCUS_STEPS = 100
        private const val ANALYSIS_INTERVAL_MS = 150L
        private const val ANALYSIS_WIDTH = 270
        private const val MAX_AUTO_SHUTTER_NS = 500_000_000L // 1/2 s
    }
}
