package com.procam.app

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
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
import android.provider.MediaStore
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
    private lateinit var thumbProgress: View
    private lateinit var shutter: ShutterButton
    private lateinit var btnSwitch: TextView
    private lateinit var countdownText: TextView
    private lateinit var infoRow: View
    private lateinit var groupStatus: TextView
    private lateinit var intentScroll: View
    private lateinit var intentAuto: TextView
    private lateinit var intentGroup: TextView
    private lateinit var intentClean: TextView
    private lateinit var intentTrails: TextView
    private lateinit var intentCloseup: TextView
    private lateinit var trailsPreview: ImageView
    private lateinit var switchPhoto: TextView
    private lateinit var switchVideo: TextView
    private lateinit var captureSwitch: View
    private lateinit var btnLook: TextView
    private lateinit var lookScroll: View
    private lateinit var lookRow: LinearLayout
    private lateinit var lookTools: View
    private lateinit var videoKinds: View
    private lateinit var kindNormal: TextView
    private lateinit var kindTimelapse: TextView
    private lateinit var kindSlowmo: TextView
    private lateinit var kindLevel: TextView
    // Level horizon while recording.
    private var levelActive = false
    private var levelAngle = 0f
    private var levelScale = 1f
    private var levelStartedAt = 0
    private var phoneRoll = 0f
    private lateinit var lookStrength: SeekBar
    private lateinit var lookEdit: TextView
    private lateinit var recTime: TextView
    private lateinit var intentHint: TextView
    private lateinit var chipScroll: View
    private lateinit var modeRow: View
    private lateinit var btnMode: TextView
    private lateinit var shakeView: ShakeView
    private lateinit var intentPortrait: TextView
    private lateinit var intentNight: TextView
    private lateinit var intentSilky: TextView
    private lateinit var intentFreeze: TextView
    private lateinit var portraitRow: View
    private lateinit var blurButtons: List<TextView>

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
    // Lenses: extra back cameras (separate lens hardware), the zoom buttons, and which lens is open.
    private var extraLenses: List<Pair<String, Float>> = emptyList()
    private var mainBackCaps: CameraCaps? = null
    private var lensCameraId: String? = null
    private var lensBase = 1f
    private var stops: List<LensStop> = emptyList()
    private lateinit var zoomIndicator: TextView
    private lateinit var zoomBar: View
    private lateinit var zoomDial: ZoomDial
    /** Super zoom: extra crop beyond the camera's own maximum zoom (1 = none). */
    private var extraCrop = 1f
    private val superZoom = SuperZoomProcessor()
    private var zoomProcessing = false
    /** Super-zoom photos still being enhanced in the background. */
    private var pendingEnhance = 0
    private val enhancer = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var lastLocation: android.location.Location? = null
    // Sliding on the lens buttons zooms (with the dial shown).
    private var scrubbing = false
    private var scrubDownX = 0f
    // Looks (filters)
    private var looksOpen = false
    private val lookExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var pendingLooks = 0
    // Hold the shutter in photo mode = quick video.
    private var quickVideo = false
    private var quickVideoReleased = false
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

    // Shake meter / steady shot
    private lateinit var shake: ShakeMeter
    private lateinit var steady: SteadyZoom
    private var steadyDx = 0f
    private var steadyDy = 0f
    private var steadyWaitStart = 0L
    private var pressRatio = Float.MAX_VALUE

    // Portrait
    private val portrait = PortraitProcessor()
    private var portraitProcessing = false
    private var lastFaceSeen = 0L

    private val simple get() = !prefs.uiPro
    /** Photo intent in effect (none while in video). */
    private fun photoIntent(i: String) = simple && !prefs.video && prefs.intent == i
    private val groupMode get() = photoIntent("GROUP")
    private val portraitMode get() = photoIntent("PORTRAIT")
    private val nightMode get() = photoIntent("NIGHT")
    private val silkyMode get() = photoIntent("SILKY")
    private val freezeMode get() = photoIntent("FREEZE")
    private val cleanMode get() = photoIntent("CLEAN")
    private val trailsMode get() = photoIntent("TRAILS")
    private val closeupMode get() = photoIntent("CLOSEUP")
    private var closeupProcessing = false
    private val focusStack = FocusStackProcessor()
    private var trailsProcessing = false
    private var trailsLive: IntArray? = null
    private var trailsLiveW = 0
    private var trailsLiveH = 0
    private var cleanProcessing = false
    private val clean = CleanProcessor()
    private val videoMode get() = prefs.video

    // Video
    /** The camera is currently open for video (it reopens when switching between photo and video). */
    private var openedForVideo = false
    /** The camera is keeping full-size frames for the steadiest moment. */
    private var openedZsl = false
    private var openedKind = OutputPrefs.VIDEO_NORMAL
    private var recording = false
    private var recordingStartedAt = 0L
    private var videoStarting = false
    private var freezeProcessing = false
    /** Freeze's fast-shutter exposure is active (so it can be undone when leaving Freeze). */
    private var freezeExposureOn = false
    /** The running merge is Silky (keep motion) rather than Night. */
    private var mergeSilky = false
    /** The running merge is HDR, shot this many stops darker (0 = not HDR). */
    private var mergeHdrStops = 0f
    /** The running merge is Auto's quiet low-light merge (same brightness, less grain). */
    private var mergeIndoor = false
    /** ... specifically shaky hands: faster shots merged (named Steady). */
    private var mergeSteady = false
    /** The running zoom burst is zoom enhance (within the camera's range), not super zoom. */
    private var zoomEnhancing = false
    // Auto HDR: how many stops darker to shoot right now (0 = off), with hysteresis.
    private var hdrStops = 0f
    private var hdrOnTicks = 0
    private var hdrOffTicks = 0
    private var lastFaces: List<RectF> = emptyList()
    private lateinit var hdrBadge: TextView

    // Night
    private val night = NightProcessor()
    private var nightProcessing = false
    private var nightIso = 800

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goEdgeToEdge()
        bindViews()

        prefs = Prefs(this)
        if (prefs.intent == "VIDEO") {
            // Video used to be one of the modes; it's now the PHOTO | VIDEO switch.
            prefs.intent = "AUTO"
            prefs.video = true
        }
        settings = prefs.loadShot()
        camera = CameraController(this, this)
        cameraIds = camera.primaryCameras()
        sensorManager = getSystemService(SensorManager::class.java)
        shake = ShakeMeter(this)
        steady = SteadyZoom(this) { dx, dy ->
            steadyDx = dx
            steadyDy = dy
            applyPreviewCrop()
        }
        locationTracker = LocationTracker(this) {
            lastLocation = it
            camera.setLocation(it)
        }
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
        // First launch: a short guide before anything else (including the camera permission prompt).
        if (!prefs.onboarded) startActivity(Intent(this, WelcomeActivity::class.java))
        PortraitProcessor.prefetchSubjects(this)
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
        shake.start()
        steady.start()
        mainHandler.post(analysisTick)
        if (!prefs.onboarded) return // the welcome guide is on top; continue when it closes
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
        portraitProcessing = false
        nightProcessing = false
        zoomProcessing = false
        freezeProcessing = false
        cleanProcessing = false
        trailsProcessing = false
        closeupProcessing = false
        stopTrailsLive()
        videoStarting = false
        overlay.setFaces(emptyList())
        zoomAnimator?.cancel()
        camera.close() // saves a recording in progress
        capturing = false
        shutter.busy = false
        orientationListener.disable()
        sensorManager.unregisterListener(this)
        locationTracker.stop()
        shake.stop()
        steady.stop()
        steady.active = false
        mainHandler.removeCallbacks(steadyTick)
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
        enhancer.execute { ai?.close() }
        lookExecutor.shutdown()
        sound.release()
        groupShutter.close()
        portrait.close()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO) {
            // With or without the microphone: start the recording the user asked for.
            if (videoStarting && resumed) camera.startRecording(deviceOrientation, hasAudioPermission())
            else videoStarting = false
            return
        }
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
        if (extraLenses.isEmpty()) extraLenses = camera.extraBackLenses(cameraIds[0])
        cameraIndex = cameraIndex.coerceIn(0, cameraIds.size - 1)
        val id = if (cameraIndex == 0) lensCameraId ?: cameraIds[0] else cameraIds[cameraIndex]
        Log.i(TAG, "startCamera index=$cameraIndex id=$id ids=$cameraIds extra lenses=$extraLenses")
        openedForVideo = videoMode
        openedKind = prefs.videoKind
        openedZsl = zslWanted()
        camera.open(id, texture, settings, prefs.output().copy(video = openedForVideo, zsl = openedZsl))
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
        thumbProgress = findViewById(R.id.thumbProgress)
        shutter = findViewById(R.id.shutter)
        btnSwitch = findViewById(R.id.btnSwitch)
        countdownText = findViewById(R.id.countdown)
        zoomIndicator = findViewById(R.id.zoomIndicator)
        zoomBar = findViewById(R.id.zoomBar)
        zoomDial = findViewById(R.id.zoomDial)
        infoRow = findViewById(R.id.infoRow)
        groupStatus = findViewById(R.id.groupStatus)
        intentScroll = findViewById(R.id.intentScroll)
        intentAuto = findViewById(R.id.intentAuto)
        intentGroup = findViewById(R.id.intentGroup)
        intentClean = findViewById(R.id.intentClean)
        intentTrails = findViewById(R.id.intentTrails)
        intentCloseup = findViewById(R.id.intentCloseup)
        trailsPreview = findViewById(R.id.trailsPreview)
        switchPhoto = findViewById(R.id.switchPhoto)
        switchVideo = findViewById(R.id.switchVideo)
        captureSwitch = findViewById(R.id.captureSwitch)
        btnLook = findViewById(R.id.btnLook)
        lookScroll = findViewById(R.id.lookScroll)
        lookRow = findViewById(R.id.lookRow)
        lookTools = findViewById(R.id.lookTools)
        videoKinds = findViewById(R.id.videoKinds)
        kindNormal = findViewById(R.id.kindNormal)
        kindTimelapse = findViewById(R.id.kindTimelapse)
        kindSlowmo = findViewById(R.id.kindSlowmo)
        kindLevel = findViewById(R.id.kindLevel)
        lookStrength = findViewById(R.id.lookStrength)
        lookEdit = findViewById(R.id.lookEdit)
        recTime = findViewById(R.id.recTime)
        intentHint = findViewById(R.id.intentHint)
        chipScroll = findViewById(R.id.chipScroll)
        modeRow = findViewById(R.id.modeRow)
        btnMode = findViewById(R.id.btnMode)
        shakeView = findViewById(R.id.shakeView)
        hdrBadge = findViewById(R.id.hdrBadge)
        intentPortrait = findViewById(R.id.intentPortrait)
        intentNight = findViewById(R.id.intentNight)
        intentSilky = findViewById(R.id.intentSilky)
        intentFreeze = findViewById(R.id.intentFreeze)
        portraitRow = findViewById(R.id.portraitRow)
        blurButtons = listOf(findViewById(R.id.blurSoft), findViewById(R.id.blurMedium), findViewById(R.id.blurStrong))

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
            positionLensRow()
            for (v in listOf<View>(overlay, shutterFlash, trailsPreview)) {
                val lp = v.layoutParams as FrameLayout.LayoutParams
                if (lp.width != w || lp.height != h) {
                    lp.width = w
                    lp.height = h
                    v.post { v.layoutParams = lp }
                }
            }
        }

        // The controls panel changes height between Simple and Pro: keep the lens buttons above it.
        bottomPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionLensRow() }

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
                camera.focusAt(unzoomX(e.x), unzoomY(e.y))
                rememberTap(unzoomX(e.x), unzoomY(e.y))
                showBadge("FOCUS POINT")
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (textureView.width == 0 || textureView.height == 0) return
                overlay.showFocus(e.x, e.y)
                camera.lockAt(unzoomX(e.x), unzoomY(e.y))
                rememberTap(unzoomX(e.x), unzoomY(e.y))
                showBadge("AE/AF LOCK")
                haptic(textureView, HapticFeedbackConstants.LONG_PRESS)
            }
        })
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomAnimator?.cancel()
                setZoom(cameraZoom() * detector.scaleFactor)
                showZoomBar()
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

        btnMode.setOnClickListener { setUiPro(!prefs.uiPro) }
        intentAuto.setOnClickListener { setIntent("AUTO") }
        intentGroup.setOnClickListener { setIntent("GROUP") }
        intentClean.setOnClickListener { setIntent("CLEAN") }
        intentTrails.setOnClickListener { setIntent("TRAILS") }
        intentCloseup.setOnClickListener { setIntent("CLOSEUP") }
        switchPhoto.setOnClickListener { setVideo(false) }
        switchVideo.setOnClickListener { setVideo(true) }
        btnLook.setOnClickListener { toggleLooks() }
        lookStrength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.lookStrength = progress
                applyLiveLook()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        lookEdit.setOnClickListener { editMyLook() }
        kindNormal.setOnClickListener { setVideoKind(OutputPrefs.VIDEO_NORMAL) }
        kindTimelapse.setOnClickListener { setVideoKind(OutputPrefs.VIDEO_TIMELAPSE) }
        kindSlowmo.setOnClickListener { setVideoKind(OutputPrefs.VIDEO_SLOWMO) }
        kindLevel.setOnClickListener {
            prefs.levelVideo = !prefs.levelVideo
            haptic(kindLevel, HapticFeedbackConstants.VIRTUAL_KEY)
            toast(if (prefs.levelVideo) "Level horizon on · the video stays straight even if you tilt (slightly zoomed in)" else "Level horizon off")
            refreshUi()
        }
        intentPortrait.setOnClickListener { setIntent("PORTRAIT") }
        intentNight.setOnClickListener { setIntent("NIGHT") }
        intentSilky.setOnClickListener { setIntent("SILKY") }
        intentFreeze.setOnClickListener { setIntent("FREEZE") }
        // The status pill doubles as a tappable tip ("Dark scene · tap for Night").
        groupStatus.setOnClickListener {
            if (it.tag == TIP_NIGHT) {
                it.tag = null
                setIntent("NIGHT")
            }
        }
        blurButtons.forEachIndexed { i, v ->
            v.setOnClickListener {
                if (prefs.intent == "SILKY") prefs.silkyStrength = i else prefs.portraitStrength = i
                haptic(v, HapticFeedbackConstants.VIRTUAL_KEY)
                refreshUi()
            }
        }

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

        zoomDial.onChange = { z ->
            zoomAnimator?.cancel()
            setZoom(z)
            mainHandler.removeCallbacks(hideZoomBar)
            mainHandler.postDelayed(hideZoomBar, ZOOM_BAR_HIDE_MS)
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
        // Hold the shutter in photo mode (a deliberate 0.8 s, so a slow tap stays a photo) to record
        // a quick video; let go to stop.
        shutter.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mainHandler.removeCallbacks(holdForVideo)
                    if (!videoMode && prefs.holdForVideo) mainHandler.postDelayed(holdForVideo, QUICK_VIDEO_HOLD_MS)
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(holdForVideo)
                    if (quickVideo) {
                        v.isPressed = false
                        endQuickVideo()
                        true // not a photo tap
                    } else {
                        false
                    }
                }
                else -> quickVideo
            }
        }
        thumbnail.setOnClickListener { openLastPhoto() }
        btnSwitch.setOnClickListener {
            if (cameraIds.size < 2) return@setOnClickListener
            cameraIndex = (cameraIndex + 1) % cameraIds.size
            lensCameraId = null
            lensBase = 1f
            settings = settings.copy(manualFocus = false, raw = false, zoom = 1f)
            rawBlocked = false
            lockBadge.visibility = View.GONE
            // Spin half a turn and end where it started (a button left upside down mis-hits taps).
            val rest = btnSwitch.rotation
            btnSwitch.rotation = rest - 180f
            btnSwitch.animate().rotation(rest).setDuration(250).start()
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
            freezeExposureOn = false
            selected = Param.EV
        } else if (freezeExposureOn) {
            settings = settings.copy(manualExposure = false, isoAuto = false)
            freezeExposureOn = false
        }
        syncFreeze()
        syncVideo()
        haptic(btnMode, HapticFeedbackConstants.VIRTUAL_KEY)
        pushSettings()
    }

    private fun setIntent(intent: String) {
        if (intent == prefs.intent) return
        prefs.intent = intent
        cancelGroup()
        overlay.setFaces(emptyList())
        groupStatus.visibility = View.GONE
        if (!superZoomAllowed() && extraCrop > 1f) {
            extraCrop = 1f
            applyPreviewCrop()
        }
        updateZoomSlider()
        haptic(intentRowView(intent), HapticFeedbackConstants.VIRTUAL_KEY)
        if (intent == "PORTRAIT") toast("Blurs the background · step back and use 2× for natural-looking faces")
        if (intent == "SILKY") toast("Turns moving water, clouds and lights into silky streaks · hold still or rest the phone")
        syncFreeze()
        syncVideo()
        refreshUi()
    }

    private fun intentRowView(intent: String) = when (intent) {
        "GROUP" -> intentGroup
        "PORTRAIT" -> intentPortrait
        "NIGHT" -> intentNight
        "SILKY" -> intentSilky
        "FREEZE" -> intentFreeze
        "CLEAN" -> intentClean
        "TRAILS" -> intentTrails
        "CLOSEUP" -> intentCloseup
        else -> intentAuto
    }

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
        // The mode row holds AUTO | MANUAL (Pro only) and the PHOTO | VIDEO switch (always).
        show(modeRow, true)
        show(modeAuto, pro)
        show(modePro, pro)
        (captureSwitch.layoutParams as LinearLayout.LayoutParams).marginStart = if (pro) (12 * resources.displayMetrics.density).toInt() else 0
        show(infoRow, pro)
        show(btnRaw, pro)
        show(btnZebra, pro)
        // Top-bar toggle: shows where it takes you.
        btnMode.text = if (pro) "SIMPLE" else "PRO"

        val intent = prefs.intent
        for ((v, on) in listOf(
            intentAuto to (intent == "AUTO"), intentPortrait to (intent == "PORTRAIT"),
            intentNight to (intent == "NIGHT"), intentSilky to (intent == "SILKY"),
            intentFreeze to (intent == "FREEZE"), intentGroup to (intent == "GROUP"),
            intentClean to (intent == "CLEAN"), intentTrails to (intent == "TRAILS"),
            intentCloseup to (intent == "CLOSEUP"),
        )) {
            v.setBackgroundResource(if (on) R.drawable.pill_selected_bg else R.drawable.pill_bg)
            v.setTextColor(getColor(if (on) R.color.black else R.color.white))
        }
        // Keep the selected intent in view (the row scrolls on narrow screens).
        val selectedPill = intentRowView(intent)
        intentScroll.post { (intentScroll as android.widget.HorizontalScrollView).smoothScrollTo(maxOf(0, selectedPill.left - 60), 0) }
        // Portrait shows its blur selector in place of the hint line, keeping the panel short.
        val options = intent == "PORTRAIT" || intent == "SILKY"
        show(portraitRow, !pro && options)
        show(intentHint, !pro && !options)
        val labels = if (intent == "SILKY") listOf("Softer", "Medium", "Dreamy") else listOf("Soft blur", "Medium", "Strong")
        val strength = if (intent == "SILKY") prefs.silkyStrength else prefs.portraitStrength
        blurButtons.forEachIndexed { i, v -> v.text = labels[i] }
        blurButtons.forEachIndexed { i, v ->
            val on = i == strength
            v.setBackgroundResource(if (on) R.drawable.segment_selected_bg else 0)
            v.setTextColor(getColor(if (on) R.color.white else R.color.dim))
        }
        intentHint.text = when (intent) {
            "GROUP" -> "Waits until everyone's eyes are open, then keeps the best of $GROUP_BURST"
            "PORTRAIT" -> ""
            "NIGHT" -> "Merges several shots for brighter, cleaner low-light photos · hold still about 1 s"
            "FREEZE" -> "Sharp action: kids, pets, sports · catches the moment even if you press a little late"
            "CLEAN" -> "Removes people and cars passing through · hold still about 3 s"
            "CLOSEUP" -> "Sharp from front to back: flowers, food, small things · tap your subject, hold still about 1 s"
            "TRAILS" -> "Moving lights draw lines: cars at night, sparklers, a torch · rest the phone, about 4 s"
            else -> "Finds faces and sets everything for you"
        }
        // Video: the photo modes make way for a video tip; the looks strip replaces the modes.
        captureSelector(videoMode)
        val slowMo = videoMode && prefs.videoKind == OutputPrefs.VIDEO_SLOWMO
        if (videoMode) {
            show(intentScroll, false)
            show(portraitRow, false)
            show(intentHint, !pro)
            intentHint.text = when (prefs.videoKind) {
                OutputPrefs.VIDEO_TIMELAPSE -> "Time-lapse: speeds things up ${CameraController.TIMELAPSE_FACTOR}× · clouds, traffic, sunsets · rest the phone"
                OutputPrefs.VIDEO_SLOWMO -> "Slow motion (${caps?.slowMotion?.second ?: 120} fps) · needs good light · no looks or sound"
                else -> "Tap the red button to start and stop · " + videoLabel() + (if (prefs.stabilize) ", stabilized" else "")
            } + if (prefs.levelVideo && !slowMo) " · level" else ""
        }
        show(btnLook, !slowMo)
        updateVideoKinds()
        setActive(btnLook, looksOpen || !Looks.resolve(prefs).isNatural)
        show(lookScroll, looksOpen && !slowMo && !recording)
        show(lookTools, looksOpen && !slowMo && !recording && prefs.look != Looks.NATURAL)
        show(lookEdit, prefs.look == Looks.MINE)
        lookStrength.progress = prefs.lookStrength
        if (looksOpen && !slowMo) {
            show(videoKinds, false)
            show(intentScroll, false)
            show(portraitRow, false)
            show(intentHint, false)
        }
        shutter.video = videoMode
        shutter.recording = recording
        if (recording) {
            show(intentScroll, false)
            show(intentHint, false)
            show(portraitRow, false)
        }
        // While recording: nothing that would stop or reopen the camera.
        for (v in listOf(thumbnail, btnSwitch, btnMode, btnSettings, btnTimer, captureSwitch)) {
            v.visibility = if (recording) View.INVISIBLE else View.VISIBLE
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

    /**
     * Sets the zoom of the open camera. Past the camera's own maximum (where allowed) the rest is
     * "super zoom": the preview is enlarged and photos are rebuilt from a burst.
     */
    private fun setZoom(z: Float) {
        val c = caps ?: return
        val target = z.coerceIn(c.zoomRange.lower, maxCameraZoom())
        if (abs(target - cameraZoom()) < 0.001f) return
        val hardware = target.coerceAtMost(c.zoomRange.upper)
        extraCrop = (target / hardware).coerceAtLeast(1f)
        if (abs(hardware - settings.zoom) > 0.0005f) {
            settings = settings.copy(zoom = hardware)
            camera.update(settings)
        }
        // After the new zoom is in place (a quick jump, e.g. tapping 5×, may be a single step).
        updateSteady()
        applyPreviewCrop()
        // Face boxes don't match an enlarged or shifted preview.
        if (extraCrop > 1.001f || steady.active) overlay.setFaces(emptyList())
        updateLensRow()
        updateReadout()
        updateZoomSlider()
        showZoomIndicator()
    }

    /** The open camera's total zoom including super zoom. */
    private fun cameraZoom() = settings.zoom * extraCrop

    /** Zoom as the user sees it (relative to the main back camera). */
    private fun displayZoom() = lensBase * cameraZoom()

    /** Super zoom is offered in Auto and Pro (the other intents already process the photo). */
    private fun superZoomAllowed() = !videoMode && (!simple || prefs.intent == "AUTO")

    private fun maxCameraZoom(): Float {
        val c = caps ?: return 1f
        if (!superZoomAllowed()) return c.zoomRange.upper
        return (c.zoomRange.upper * SUPER_ZOOM_FACTOR).coerceAtMost(MAX_DISPLAY_ZOOM / lensBase)
    }

    /** Enlarges the preview for super zoom (the camera itself is already at its maximum). */
    private fun applyPreviewCrop() {
        val w = textureView.width.toFloat()
        val h = textureView.height.toFloat()
        val s = previewScale()
        val shifted = steady.active && (steadyDx != 0f || steadyDy != 0f)
        if (levelActive && w > 0f) {
            // Show what the levelled video records: rotated back by the tilt, slightly enlarged.
            textureView.setTransform(Matrix().apply {
                setScale(levelScale, levelScale, w / 2, h / 2)
                postRotate(Math.toDegrees(levelAngle.toDouble()).toFloat(), w / 2, h / 2)
            })
        } else if ((s <= 1.001f && !shifted) || w == 0f) {
            textureView.setTransform(null)
        } else {
            textureView.setTransform(Matrix().apply {
                setScale(s, s, w / 2, h / 2)
                if (steady.active) postTranslate(steadyDx, steadyDy)
            })
        }
    }

    /** Preview enlargement: super zoom, plus spare picture for steadying at high zoom. */
    private fun previewScale() = extraCrop * if (steady.active) SteadyZoom.MARGIN else 1f

    /** At high zoom (back camera, photos), steady the viewfinder against hand tremor. */
    private fun updateSteady() {
        val c = caps
        val on = c != null && !c.isFront && !videoMode &&
            prefs.steadyZoom && steady.available && displayZoom() >= STEADY_FROM && textureView.height > 0
        if (on) {
            val longSide = maxOf(textureView.width, textureView.height).toFloat()
            // Angle across the sensor's long side (= the preview's long side); a typical phone
            // lens (~70°) if the camera doesn't report its focal length and sensor size.
            val focal = c!!.focalLength
            val across = if (focal != null && focal > 0f && c.sensorWidthMm > 0f) c.sensorWidthMm / focal else 1.2f
            steady.radPerPx = across / (cameraZoom() * SteadyZoom.MARGIN) / longSide
            steady.maxShiftX = (SteadyZoom.MARGIN - 1f) / 2f * textureView.width
            steady.maxShiftY = (SteadyZoom.MARGIN - 1f) / 2f * textureView.height
        }
        if (steady.active != on) {
            steady.active = on
            if (!on) {
                steadyDx = 0f
                steadyDy = 0f
            }
            applyPreviewCrop()
        }
    }

    /** Screen tap → 0..1 position in the camera's (un-super-zoomed) frame. */
    private fun unzoomX(x: Float) = 0.5f + ((x - steadyShiftX()) / textureView.width - 0.5f) / previewScale()
    private fun unzoomY(y: Float) = 0.5f + ((y - steadyShiftY()) / textureView.height - 0.5f) / previewScale()
    private fun steadyShiftX() = if (steady.active) steadyDx else 0f
    private fun steadyShiftY() = if (steady.active) steadyDy else 0f

    // ---- zoom slider (log scale, from widest to maximum incl. super zoom)

    private val hideZoomBar = Runnable { zoomBar.visibility = View.GONE }

    private fun showZoomBar() {
        if (caps == null) return
        zoomBar.visibility = View.VISIBLE
        updateZoomSlider()
        positionLensRow()
        mainHandler.removeCallbacks(hideZoomBar)
        mainHandler.postDelayed(hideZoomBar, ZOOM_BAR_HIDE_MS)
    }

    private fun toggleZoomBar() {
        if (zoomBar.visibility == View.VISIBLE) {
            mainHandler.removeCallbacks(hideZoomBar)
            zoomBar.visibility = View.GONE
        } else {
            showZoomBar()
        }
    }

    private fun updateZoomSlider() {
        val c = caps ?: return
        zoomDial.lo = c.zoomRange.lower
        zoomDial.hi = maxCameraZoom()
        zoomDial.hardwareMax = c.zoomRange.upper
        zoomDial.base = lensBase
        zoomDial.labels = stops.filter { it.cameraId == c.id }.map { it.zoom }
        zoomDial.setValue(cameraZoom())
    }

    private fun zoomIndicatorText(): String = when {
        extraCrop > 1.001f && aiFor(extraCrop) -> "${Stops.zoomLabel(displayZoom())} ✦\nSuper zoom · AI"
        extraCrop > 1.001f -> "${Stops.zoomLabel(displayZoom())} ✦\nSuper zoom"
        else -> Stops.zoomLabel(displayZoom())
    }

    /**
     * AI detail applies to super zoom by [crop] (how far past the camera's maximum): from halfway
     * into super zoom (2× past it, on the zoom dial's log scale), or all of it, per Settings.
     */
    private fun aiFor(crop: Float): Boolean = when (prefs.aiDetail) {
        Prefs.AI_HALFWAY -> crop >= AI_HALFWAY_CROP
        Prefs.AI_ALL_SUPER -> crop > 1.01f
        else -> false
    }

    /** Loaded on first use, on the background thread (about 0.1 s), and kept. */
    private var ai: AiUpscaler? = null
    private fun aiUpscaler(): AiUpscaler? = ai ?: try {
        AiUpscaler(this).also { ai = it }
    } catch (e: Throwable) {
        Log.e(TAG, "AI model unavailable", e)
        null
    }

    private val hideZoomIndicator = Runnable { zoomIndicator.animate().alpha(0f).setDuration(200).withEndAction { zoomIndicator.visibility = View.GONE }.start() }

    private fun showZoomIndicator() {
        zoomIndicator.text = zoomIndicatorText()
        zoomIndicator.setTextColor(getColor(if (extraCrop > 1.001f) R.color.accent else R.color.white))
        zoomIndicator.animate().cancel()
        zoomIndicator.alpha = 1f
        zoomIndicator.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideZoomIndicator)
        mainHandler.postDelayed(hideZoomIndicator, 900)
    }

    /** Zoom button tapped: zoom on this camera, or open the camera that has that lens. */
    private fun selectStop(stop: LensStop) {
        val current = caps?.id
        if (stop == activeStop()) {
            // Tapping the highlighted button opens the zoom slider.
            toggleZoomBar()
            return
        }
        if (stop.cameraId == current) {
            animateZoom(stop.cameraZoom)
            return
        }
        extraCrop = 1f
        applyPreviewCrop()
        val main = cameraIds.getOrNull(0) ?: return
        lensCameraId = if (stop.cameraId == main) null else stop.cameraId
        lensBase = if (stop.cameraId == main) 1f else stop.zoom / stop.cameraZoom
        settings = settings.copy(zoom = stop.cameraZoom, manualFocus = false)
        zoomIndicator.text = Stops.zoomLabel(stop.zoom)
        showZoomIndicator()
        startCamera()
    }

    private fun animateZoom(target: Float) {
        zoomAnimator?.cancel()
        zoomAnimator = ValueAnimator.ofFloat(cameraZoom(), target).apply {
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
        if (videoMode) {
            if (caps != null && openedForVideo) toggleRecording()
            return
        }
        if (countdownRemaining > 0) {
            cancelCountdown(); return
        }
        if (groupWaiting) {
            cancelGroup(); return
        }
        if (capturing || groupProcessing || portraitProcessing || nightProcessing || zoomProcessing || freezeProcessing || cleanProcessing || trailsProcessing || closeupProcessing || caps == null) return
        if (extraCrop > 1.01f && pendingEnhance >= MAX_PENDING_ENHANCE) {
            toast("Still enhancing the last zoom photos…")
            return
        }
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
        if (prefs.steadyShot && !freezeMode && shakeRatio() < 1f) {
            // Hands are shaking: wait a moment for a steadier instant instead of firing blurry.
            steadyWaitStart = SystemClock.uptimeMillis()
            showGroupStatus("Hold still…")
            mainHandler.post(steadyTick)
        } else {
            fire()
        }
    }

    private fun fire() {
        pressRatio = shakeRatio()
        if (!groupProcessing) groupStatus.visibility = View.GONE
        if (portraitMode) {
            // Keep the photo in memory: it gets its background blurred before saving.
            portraitProcessing = true
            camera.takeBurst(deviceOrientation, 1, toMemory = true)
        } else if (nightMode) {
            startNight()
        } else if (silkyMode) {
            startSilky()
        } else if (cleanMode) {
            startClean()
        } else if (trailsMode) {
            startTrails()
        } else if (closeupMode && caps?.canManualFocus == true) {
            startCloseup()
        } else if (freezeMode) {
            // Never miss the moment: the sharpest frame from around the press.
            freezeProcessing = true
            camera.takeMoment(deviceOrientation)
        } else if (hdrStops > 0f && hdrApplies()) {
            // Backlit / high-contrast scene: a darker burst, merged and brightened where needed.
            startHdr()
        } else if (lowLightApplies()) {
            // Dim room: a few shots merged for less grain, at the normal brightness.
            startLowLight()
        } else if (extraCrop > 1.01f || zoomEnhanceApplies()) {
            // Super zoom (past the camera's maximum) or zoom enhance (zoomed within it): a short
            // burst, rebuilt into one detailed zoomed photo in the background.
            zoomProcessing = true
            zoomEnhancing = extraCrop <= 1.01f
            showGroupStatus(if (zoomEnhancing) "Hold still… zoom enhance" else "Hold still… super zoom")
            val c = caps
            val isoR = c?.isoRange
            val expR = c?.exposureRange
            // A shutter fast enough to freeze hand shake at this zoom; the merge removes the extra noise.
            val exposure = if (c != null && c.manualSensor && isoR != null && expR != null && liveIso != null && liveExposure != null) {
                SuperZoomProcessor.plan(liveIso!!, liveExposure!!, safeShutterNs() ?: liveExposure!!, isoR, expR)
            } else null
            exposure?.let { Log.i(TAG, "super zoom plan: ISO ${it.first} ${Stops.shutterLabel(it.second)}") }
            if (zoomEnhancing && prefs.rawZoom && camera.rawBurstAvailable()) {
                // Best: merge the sensor's RAW frames (JPEGs come along as fallback and for the look).
                camera.takeRawZoomBurst(deviceOrientation, zoomBurstFrames(), exposure)
            } else {
                // Full-resolution JPEG shots (video-size frames would be faster but lose detail).
                camera.takeBurst(deviceOrientation, zoomBurstFrames(), toMemory = true, exposure = exposure, minimalProcessing = true)
            }
        } else {
            // A normal photo: never slower than the hands can hold right now.
            val plan = steadyPlan()
            if (plan?.burst != true) armSteadiestMoment()
            when {
                plan == null -> camera.takePicture(deviceOrientation)
                !plan.burst -> {
                    Log.i(TAG, "steady shutter: ISO ${plan.iso} ${Stops.shutterLabel(plan.exposureNs)} (auto wanted ${liveExposure?.let { Stops.shutterLabel(it) }})")
                    camera.takeBurst(deviceOrientation, 1, toMemory = false, exposure = plan.iso to plan.exposureNs)
                }
                else -> startSteadyBurst(plan)
            }
        }
    }

    /**
     * Faster shutter (higher ISO) when auto exposure's would blur with these hands, or null.
     * Only with camera auto exposure and no flash (the flash needs the camera's own metering).
     */
    private fun steadyPlan(): Steady.Plan? {
        val c = caps ?: return null
        if (!prefs.steadyShutter || !c.manualSensor || !settings.cameraAe || settings.flash != FlashMode.OFF) return null
        val isoR = c.isoRange ?: return null
        val expR = c.exposureRange ?: return null
        val iso = liveIso ?: return null
        val exp = liveExposure ?: return null
        val safe = if (prefs.forceShaky) FORCED_SHAKY_SAFE_NS else safeShutterNs() ?: return null
        return Steady.plan(iso, exp, safe, isoR, expR)
    }

    /** Shaky hands and a much faster shutter: a few such shots merged so the extra noise goes away. */
    private fun startSteadyBurst(plan: Steady.Plan) {
        nightProcessing = true
        mergeSilky = false
        mergeHdrStops = 0f
        mergeIndoor = true
        mergeSteady = true
        nightIso = plan.iso
        Log.i(TAG, "steady burst: $STEADY_FRAMES x ISO ${plan.iso} ${Stops.shutterLabel(plan.exposureNs)} (auto wanted ${liveExposure?.let { Stops.shutterLabel(it) }})")
        showGroupStatus("Shaky hands · taking a few fast shots")
        camera.takeBurst(deviceOrientation, STEADY_FRAMES, toMemory = true, exposure = plan.iso to plan.exposureNs)
    }

    // ------------------------------------------------------------------ the subject (for merges)

    private var lastTap: android.graphics.PointF? = null
    private var lastTapAt = 0L

    private fun rememberTap(nx: Float, ny: Float) {
        lastTap = android.graphics.PointF(nx, ny)
        lastTapAt = SystemClock.uptimeMillis()
    }

    /**
     * What the photo is about, for aligning and picking frames: a point tapped in the last
     * 10 s, else the biggest face, else null (= the centre / most detailed part).
     * In 0..1 coordinates of the camera's sensor.
     */
    private fun subjectSensor(): android.graphics.RectF? {
        val c = caps ?: return null
        val view = lastTap?.takeIf { SystemClock.uptimeMillis() - lastTapAt < TAP_SUBJECT_MS }?.let {
            android.graphics.RectF(it.x - 0.08f, it.y - 0.06f, it.x + 0.08f, it.y + 0.06f)
        } ?: lastFaces.maxByOrNull { it.width() * it.height() }?.let { RectF(it) }
        return view?.let { viewToSensor(it, c) }
    }

    /** Portrait view 0..1 → sensor 0..1 (as tap-to-focus maps points). */
    private fun viewToSensor(r: RectF, c: CameraCaps): RectF {
        fun map(px: Float, py: Float): Pair<Float, Float> {
            var x = px.coerceIn(0f, 1f)
            var y = py.coerceIn(0f, 1f)
            if (c.isFront) x = 1f - x
            repeat((c.sensorOrientation % 360) / 90) {
                val t = x
                x = y
                y = 1f - t
            }
            return x to y
        }
        val (x1, y1) = map(r.left, r.top)
        val (x2, y2) = map(r.right, r.bottom)
        return RectF(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
    }

    /** A 0..1 rectangle of the sensor picture → of the picture turned [degrees] clockwise. */
    private fun rotateNorm(r: RectF, degrees: Int): RectF {
        fun map(x: Float, y: Float): Pair<Float, Float> = when (((degrees % 360) + 360) % 360) {
            90 -> (1f - y) to x
            180 -> (1f - x) to (1f - y)
            270 -> y to (1f - x)
            else -> x to y
        }
        val (x1, y1) = map(r.left, r.top)
        val (x2, y2) = map(r.right, r.bottom)
        return RectF(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
    }

    /**
     * The subject in a JPEG's own pixel layout: sensor orientation if it's turned upright by its
     * EXIF tag, upright if the camera already turned its pixels.
     */
    private fun subjectForJpeg(jpeg: ByteArray?, sensor: RectF?): RectF? {
        if (sensor == null || jpeg == null) return sensor
        val exif = SuperZoomProcessor.exifRotation(jpeg)
        return if (exif == 0 && camera.lastCaptureOrientation != 0) rotateNorm(sensor, camera.lastCaptureOrientation) else sensor
    }

    // ------------------------------------------------------------------ steadiest moment

    /** Keep full-size frames (where the phone can) in photo modes other than Freeze (which has its own buffer). */
    private fun zslWanted() = prefs.steadyMoment && !videoMode && !(simple && prefs.intent == "FREEZE")

    private var momentArmed = false
    private var momentFrames: List<CameraController.MomentFrame>? = null
    private var stillUri: Uri? = null
    private var stillTiming: Pair<Long, Long>? = null
    /** The photo being saved is a plain single shot (deblur may apply). */
    private var plainStill = false
    private var pressRotation = 0

    /** At the press: grab the steadiest kept frames from around now, in case the photo shakes. */
    private fun armSteadiestMoment() {
        momentArmed = false
        momentFrames = null
        stillUri = null
        plainStill = photoIntent("AUTO") && extraCrop <= 1.001f
        pressRotation = deviceOrientation
        if (!photoIntent("AUTO") || !camera.steadiestMomentAvailable || extraCrop > 1.001f) return
        momentArmed = true
        camera.snapshotSteadiest(MOMENT_FRAMES, MOMENT_WINDOW_NS, MOMENT_AFTER_MS, ::frameBlurPx) { frames ->
            momentFrames = frames
            maybeRescue()
        }
    }

    /** Blur (px of a 12 MP photo) from the phone's movement during an exposure, or null if unknown. */
    private fun frameBlurPx(start: Long, exposureNs: Long): Float? {
        val c = caps ?: return null
        if (!camera.timestampsMatchSensors || !shake.available) return null
        val angle = shake.angleDuring(start, exposureNs) ?: return null
        return angle / ShakeMeter.radPerPx(c.focalLength ?: 0f, c.sensorWidthMm, cameraZoom()) / if (c.ois) 4f else 1f
    }

    /**
     * Once both the photo and the kept frames are in: if the photo shook and the kept frames
     * were much steadier, replace it with those frames merged (subject-locked); else keep it.
     */
    private fun maybeRescue() {
        val uri = stillUri ?: return
        val frames = momentFrames ?: return
        stillUri = null
        momentFrames = null
        val (start, exposure) = stillTiming ?: (0L to 0L)
        val stillBlur = if (start > 0) frameBlurPx(start, exposure) else null
        val best = frames.firstOrNull()?.blurPx
        val rescue = frames.size >= 2 && (prefs.forceMomentRescue ||
            (stillBlur != null && best != null && stillBlur > RESCUE_ABOVE_PX && best < stillBlur * 0.5f))
        Log.i(TAG, "steadiest moment: photo blur ${stillBlur?.let { "%.1f".format(Locale.US, it) }} px, " +
            "kept frames ${frames.map { it.blurPx?.let { b -> "%.1f".format(Locale.US, b) } }} -> ${if (rescue) "merge kept frames" else "keep photo"}")
        val look = Looks.resolve(prefs)
        if (!rescue) {
            deblurOrLook(uri, stillTiming, look)
            return
        }
        val roi = subjectSensor()
        val rotation = camera.lastCaptureOrientation
        val quality = prefs.jpegQuality
        pendingEnhance++
        thumbnail.alpha = 0.5f
        thumbProgress.visibility = View.VISIBLE
        enhancer.execute {
            var ok = false
            try {
                val original = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("can't read")
                val name = contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "photo.jpg"
                val iterations = if (Runtime.getRuntime().availableProcessors() >= 8) Deconvolve.ITERATIONS else Deconvolve.ITERATIONS / 2
                val merged = TileMerge.merge(frames.map { TileMerge.YuvSource(it.frame) }, roi, MOMENT_FRAMES, iterations)
                var bmp = merged.bitmap
                if (rotation != 0) {
                    bmp = android.graphics.Bitmap.createBitmap(merged.bitmap, 0, 0, merged.bitmap.width, merged.bitmap.height,
                        Matrix().apply { postRotate(rotation.toFloat()) }, true)
                    merged.bitmap.recycle()
                }
                if (!look.isNatural) Looks.apply(bmp, look, seed = name.hashCode().toLong())
                val out = java.io.ByteArrayOutputStream(original.size)
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), out)
                bmp.recycle()
                if (OriginalStore.get(this, name) == null) OriginalStore.save(this, name, original)
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(out.toByteArray()) }
                MediaSaver.copyExif(this, uri, original)
                MediaSaver.setDescription(this, uri, "Steadiest moment: ${merged.used} steady frames merged" +
                    if (!look.isNatural) " · Look: ${look.name}" else "")
                ok = true
            } catch (e: Throwable) {
                Log.e(TAG, "steadiest moment failed (keeping the photo)", e)
            }
            runOnUiThread {
                enhanceDone(uri, "image/jpeg", if (ok) "Steadied · used the steadiest moment around your press" else "")
                if (!ok && !look.isNatural) applyLookToSaved(uri, look)
            }
        }
    }

    // ------------------------------------------------------------------ undoing shake blur

    /**
     * How the phone turned during an exposure that the photo shows (radians about the device's
     * x/y axes, cumulative): the gyroscope, minus what optical stabilisation took out (learnt
     * from the lens-shift reports). Null if unknown.
     */
    private fun shakePath(start: Long, exposureNs: Long): FloatArray? {
        val c = caps ?: return null
        if (!camera.timestampsMatchSensors || !shake.available) return null
        val gyro = shake.pathDuring(start, exposureNs, PATH_STEPS) ?: return null
        if (!c.ois) return gyro
        if (!camera.oisDataAvailable) return null
        val (ots, ox, oy) = camera.oisSamples()
        val calStart = start - OIS_CAL_NS
        val calSteps = (OIS_CAL_NS / OIS_CAL_STEP_NS).toInt()
        val calGyro = shake.pathDuring(calStart, OIS_CAL_NS, calSteps) ?: return null
        fun oisPath(t0: Long, dur: Long, steps: Int): FloatArray? {
            val out = FloatArray(2 * (steps + 1))
            for (k in 0..steps) {
                val (x, y) = MotionDeblur.sampleAt(ots, ox, oy, t0 + dur * k / steps) ?: return null
                out[2 * k] = x
                out[2 * k + 1] = y
            }
            return out
        }
        val calOis = oisPath(calStart, OIS_CAL_NS, calSteps) ?: return null
        val ois = oisPath(start, exposureNs, PATH_STEPS) ?: return null
        return MotionDeblur.oisResidual(calGyro, calOis, gyro, ois)
    }

    /** A single photo just saved: undo its shake blur if the gyroscope says it shook; then the look. */
    private fun deblurOrLook(uri: Uri, timing: Pair<Long, Long>?, look: Looks.Look) {
        val c = caps
        val forced = prefs.forceShakePx
        // Rows are read out one after another: judge the shake at the middle row.
        val skew = camera.lastStillSkewNs
        val turn = if (!prefs.motionDeblur || c == null) null
            else if (forced > 0f) null
            else timing?.takeIf { it.first > 0 && it.second > 0 }?.let { (start, e) -> shakePath(start + skew / 2, e) }
        // Pixels per radian across a 4000 px wide photo.
        val pxPerRad4000 = if (c == null) 0f else 1f / ShakeMeter.radPerPx(c.focalLength ?: 0f, c.sensorWidthMm, cameraZoom())
        val path4000 = when {
            forced > 0f && prefs.motionDeblur -> MotionDeblur.line(forced, 0.5f)
            turn != null -> MotionDeblur.pathPx(turn, pxPerRad4000, pressRotation)
            else -> null
        }
        val len = path4000?.let { MotionDeblur.length(it) } ?: 0f
        Log.i(TAG, "shake blur: ${"%.1f".format(Locale.US, len)} px (4000 px wide)${if (c?.ois == true) " after OIS" else ""}")
        if (path4000 == null || len < DEBLUR_ABOVE_PX || len > MotionDeblur.MAX_BLUR_PX) {
            if (!look.isNatural) applyLookToSaved(uri, look)
            return
        }
        val roi = subjectSensor()
        val quality = prefs.jpegQuality
        pendingEnhance++
        thumbnail.alpha = 0.5f
        thumbProgress.visibility = View.VISIBLE
        enhancer.execute {
            var done = false
            try {
                val t0 = SystemClock.uptimeMillis()
                val original = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("can't read")
                val name = contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "photo.jpg"
                val bmp = PortraitProcessor.decode(original, Budget.sideFor(original, 14))
                val path = MotionDeblur.scaled(path4000, maxOf(bmp.width, bmp.height) / 4000f)
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                var frame = SuperRes.Frame(px, bmp.width, bmp.height)
                if (forced > 0f) {
                    // Test hook: shake the photo with a known shake first, then undo it.
                    frame = MotionDeblur.blur(frame, path)
                    bmp.setPixels(frame.px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                }
                val subject = roi?.let { rotateNorm(it, camera.lastCaptureOrientation) }
                val cx = ((subject?.centerX() ?: 0.5f) * bmp.width).toInt()
                val cy = ((subject?.centerY() ?: 0.5f) * bmp.height).toInt()
                val choice = MotionDeblur.choose(frame, MotionDeblur.variants(path), cx, cy)
                if (choice != null) {
                    MotionDeblur.deblur(frame, choice.path) { x, y, w, h, p -> synchronized(bmp) { bmp.setPixels(p, 0, w, x, y, w, h) } }
                    if (!look.isNatural) Looks.apply(bmp, look, seed = name.hashCode().toLong())
                    val out = java.io.ByteArrayOutputStream(original.size)
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), out)
                    if (OriginalStore.get(this, name) == null) OriginalStore.save(this, name, original)
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(out.toByteArray()) }
                    MediaSaver.copyExif(this, uri, original)
                    MediaSaver.setDescription(this, uri, "Shake undone: ${"%.0f".format(Locale.US, MotionDeblur.length(path))} px blur reversed with the gyroscope" +
                        if (!look.isNatural) " · Look: ${look.name}" else "")
                    done = true
                    Log.i(TAG, "deblurred ${bmp.width}x${bmp.height} (variant ${choice.variant}) in ${SystemClock.uptimeMillis() - t0} ms")
                } else Log.i(TAG, "deblur: no kernel fits, photo kept")
                bmp.recycle()
            } catch (e: Throwable) {
                Log.e(TAG, "deblur failed (keeping the photo)", e)
            }
            runOnUiThread {
                enhanceDone(uri, "image/jpeg", if (done) "Shake blur undone" else "")
                if (!done && !look.isNatural) applyLookToSaved(uri, look)
            }
        }
    }

    /** Frames to keep from a burst: drop those the phone moved during (gyroscope), at least [minKeep]. */
    private fun <T> keepSteady(frames: List<T>, timing: List<Pair<Long, Long>>, minKeep: Int): List<T> {
        if (timing.size != frames.size) return frames
        return keepSteadyIndices(timing, minKeep).map { frames[it] }
    }

    private fun keepSteadyIndices(timing: List<Pair<Long, Long>>, minKeep: Int): List<Int> {
        val c = caps
        if (c == null || !camera.timestampsMatchSensors || !shake.available) return timing.indices.toList()
        val radPerPx = ShakeMeter.radPerPx(c.focalLength ?: 0f, c.sensorWidthMm, cameraZoom())
        val ois = if (c.ois) 4f else 1f
        val blur = timing.map { (start, exposure) ->
            shake.angleDuring(start, exposure)?.let { it / radPerPx / ois }
        }
        val keep = Steady.keep(blur, minKeep)
        Log.i(TAG, "gyro blur per frame (px): ${blur.map { it?.let { b -> "%.1f".format(Locale.US, b) } ?: "?" }} -> keeping ${keep.size} of ${timing.size}")
        return keep
    }

    private val steadyTick = object : Runnable {
        override fun run() {
            if (!capturing) return
            if (shakeRatio() >= 1f || SystemClock.uptimeMillis() - steadyWaitStart > STEADY_WAIT_MS) fire()
            else mainHandler.postDelayed(this, 40)
        }
    }

    /** Exposure the camera is using / will use right now. */
    private fun currentExposureNs(): Long? =
        if (settings.manualExposure && !settings.shutterAuto) settings.exposureNs else liveExposure ?: settings.exposureNs.takeIf { settings.manualExposure }

    /** Slowest sharp shutter for the current hand shake, or null if unknown. */
    private fun safeShutterNs(): Long? {
        val c = caps ?: return null
        if (!shake.available) return null
        return ShakeMeter.safeShutterNs(shake.omega, c.focalLength ?: return null, c.sensorWidthMm, cameraZoom(), c.ois)
    }

    /** > 1 means the current shutter is fast enough for how much the hands are moving. */
    private fun shakeRatio(): Float {
        val safe = safeShutterNs() ?: return Float.MAX_VALUE
        val exp = currentExposureNs() ?: return Float.MAX_VALUE
        return safe.toFloat() / exp.coerceAtLeast(1L)
    }

    private fun updateShakeMeter() {
        val safe = safeShutterNs()
        if (safe == null) {
            shakeView.visibility = View.GONE
            return
        }
        shakeView.visibility = View.VISIBLE
        val r = shakeRatio()
        val sharpTo = if (safe >= 1_000_000_000L) "sharp to 1s+" else "sharp to ${Stops.shutterLabel(safe)}"
        when {
            shake.omega < ShakeMeter.TRIPOD_OMEGA -> shakeView.show(5, ShakeView.GREEN, "Rock steady", "any shutter speed")
            r >= 2f -> shakeView.show(5, ShakeView.GREEN, "Steady", sharpTo)
            r >= 1f -> shakeView.show(4, ShakeView.GREEN, "Steady", sharpTo)
            r >= 0.5f -> shakeView.show(3, ShakeView.YELLOW, "A bit shaky", sharpTo)
            r >= 0.25f -> shakeView.show(2, ShakeView.ORANGE, "Shaky", "brace your elbows")
            else -> shakeView.show(1, ShakeView.RED, "Too shaky", "lean on something")
        }
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
        // The review screen (before/after, share, delete); it also finds the latest photo itself.
        startActivity(Intent(this, ReviewActivity::class.java).apply { lastPhoto?.let { data = it } })
    }

    // ------------------------------------------------------------------ CameraController.Listener

    override fun onCameraOpened(caps: CameraCaps, previewSize: Size) {
        Log.i(TAG, "camera opened id=${caps.id} front=${caps.isFront} faces=${caps.faceDetectMode} facePriority=${caps.facePriorityScene} zoom=${caps.zoomRange} stops=${caps.lensStops}")
        this.caps = caps
        if (caps.id == cameraIds.getOrNull(0)) {
            mainBackCaps = caps
            lensBase = 1f
        }
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
            manualExposure = settings.manualExposure && caps.manualSensor && (prefs.uiPro || freezeExposureOn),
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
        if (settings.zoom < caps.zoomRange.upper - 0.001f) extraCrop = 1f
        syncFreeze()
        buildLensRow()
        applyPreviewCrop()
        updateZoomSlider()
        applyLiveLook()
        updateSteady()
        refreshUi()
        // Quick video: the camera has reopened for video while the shutter is held.
        if (quickVideo && openedForVideo && !recording) {
            if (quickVideoReleased) finishQuickVideo() else toggleRecording()
        }
    }

    override fun onShutter() {
        if (prefs.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
        shutterFlash.alpha = 1f
        shutterFlash.animate().alpha(0f).setDuration(180).start()
    }

    override fun onCaptureFinished(success: Boolean) {
        if ((groupProcessing || portraitProcessing || nightProcessing || zoomProcessing || freezeProcessing || cleanProcessing || trailsProcessing || closeupProcessing) && success) {
            // Wait for the photo(s) to be processed and saved.
            if (pressRatio < BLURRY_RATIO) toast("This one may be blurry: your hands moved. Brace your elbows and try again.")
            pressRatio = Float.MAX_VALUE
            return
        }
        if (!success) {
            groupProcessing = false
            portraitProcessing = false
            nightProcessing = false
            zoomProcessing = false
            freezeProcessing = false
            cleanProcessing = false
            trailsProcessing = false
            closeupProcessing = false
            stopTrailsLive()
            groupStatus.visibility = View.GONE
        }
        if (success && pressRatio < BLURRY_RATIO) {
            toast("This one may be blurry: your hands moved. Brace your elbows and try again.")
        }
        pressRatio = Float.MAX_VALUE
        capturing = false
        shutter.busy = false
        if (!success) toast("Capture failed")
    }

    override fun onPhotoSaved(uri: Uri, mimeType: String) {
        if (mimeType == "image/x-adobe-dng" && lastPhoto != null) return
        lastPhoto = uri
        if (mimeType == "image/jpeg" && momentArmed) {
            // The steadiest-moment check decides first (and applies the look itself).
            momentArmed = false
            plainStill = false
            stillUri = uri
            stillTiming = camera.lastStillTiming
            maybeRescue()
            loadThumbnail(uri)
            return
        }
        val look = Looks.resolve(prefs)
        if (mimeType == "image/jpeg" && plainStill) {
            plainStill = false
            deblurOrLook(uri, camera.lastStillTiming, look)
        } else if (mimeType == "image/jpeg" && !look.isNatural) applyLookToSaved(uri, look)
        plainStill = false
        loadThumbnail(uri)
    }

    private fun loadThumbnail(uri: Uri) {
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
        // In Group mode the smarter ML Kit boxes (green = ready) are drawn instead; with super
        // zoom the camera's face positions no longer match the enlarged preview.
        if (!groupMode && extraCrop <= 1.001f && !steady.active) overlay.setFaces(faces)
        if (faces.isNotEmpty()) lastFaceSeen = SystemClock.uptimeMillis()
        lastFaces = faces
    }

    // ------------------------------------------------------------------ zoom by sliding on the lens buttons

    /** Slide sideways on any lens button to zoom smoothly (the dial shows); a tap still picks the lens. */
    private val lensScrub = View.OnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scrubDownX = e.rawX
                scrubbing = false
                false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - scrubDownX
                if (!scrubbing && abs(dx) > android.view.ViewConfiguration.get(this).scaledTouchSlop && caps != null) {
                    scrubbing = true
                    v.isPressed = false
                    v.cancelLongPress()
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    zoomAnimator?.cancel()
                    showZoomBar()
                    zoomDial.beginDrag()
                }
                if (scrubbing) zoomDial.dragBy(dx)
                scrubbing
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = scrubbing
                scrubbing = false
                if (was) {
                    mainHandler.removeCallbacks(hideZoomBar)
                    mainHandler.postDelayed(hideZoomBar, ZOOM_BAR_HIDE_MS)
                }
                was
            }
            else -> scrubbing
        }
    }

    // ------------------------------------------------------------------ photo / video switch

    private fun captureSelector(video: Boolean) {
        for ((v, on) in listOf(switchPhoto to !video, switchVideo to video)) {
            v.setBackgroundResource(if (on) R.drawable.segment_selected_bg else 0)
            v.setTextColor(getColor(if (on) R.color.white else R.color.dim))
        }
    }

    private fun setVideo(video: Boolean) {
        if (video == prefs.video || recording) return
        prefs.video = video
        cancelGroup()
        groupStatus.visibility = View.GONE
        overlay.setFaces(emptyList())
        if (!superZoomAllowed() && extraCrop > 1f) {
            extraCrop = 1f
            applyPreviewCrop()
        }
        updateZoomSlider()
        haptic(if (video) switchVideo else switchPhoto, HapticFeedbackConstants.VIRTUAL_KEY)
        applyLiveLook()
        updateSteady()
        syncFreeze()
        syncVideo()
        refreshUi()
    }

    // ------------------------------------------------------------------ video kinds and level horizon

    private fun updateVideoKinds() {
        val show = videoMode && !recording && !looksOpen
        videoKinds.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val slowAvailable = caps?.slowMotion != null || (mainBackCaps?.slowMotion != null && caps?.isFront != true)
        kindSlowmo.visibility = if (slowAvailable || prefs.videoKind == OutputPrefs.VIDEO_SLOWMO) View.VISIBLE else View.GONE
        for ((v, on) in listOf(
            kindNormal to (prefs.videoKind == OutputPrefs.VIDEO_NORMAL),
            kindTimelapse to (prefs.videoKind == OutputPrefs.VIDEO_TIMELAPSE),
            kindSlowmo to (prefs.videoKind == OutputPrefs.VIDEO_SLOWMO),
            kindLevel to (prefs.levelVideo && prefs.videoKind != OutputPrefs.VIDEO_SLOWMO),
        )) {
            v.setBackgroundResource(if (on) R.drawable.pill_selected_bg else R.drawable.pill_bg)
            v.setTextColor(getColor(if (on) R.color.black else R.color.white))
        }
        kindLevel.visibility = if (prefs.videoKind == OutputPrefs.VIDEO_SLOWMO) View.GONE else View.VISIBLE
    }

    private fun setVideoKind(kind: Int) {
        if (kind == prefs.videoKind || recording) return
        if (kind == OutputPrefs.VIDEO_SLOWMO && caps?.slowMotion == null) {
            toast("Slow motion isn't available on this camera")
            return
        }
        prefs.videoKind = kind
        haptic(videoKinds, HapticFeedbackConstants.VIRTUAL_KEY)
        // Each kind sets the camera up differently: reopen it.
        if (openedForVideo && resumed && hasCameraPermission()) {
            caps = null
            startCamera()
        }
        refreshUi()
    }

    /** Recording started: keep the horizon level if asked (and possible). */
    private fun startLevel() {
        val size = camera.videoSize
        val c = caps
        levelActive = prefs.levelVideo && size != null && c != null && !c.isFront && camera.videoEffectsAvailable
        if (!levelActive) return
        levelStartedAt = ((deviceOrientation + 45) / 90 * 90) % 360
        levelScale = VideoEffects.coverScale(size!!.width, size.height, MAX_LEVEL_RAD)
        levelAngle = 0f
        updateLevel()
    }

    private fun updateLevel() {
        val target = VideoEffects.levelFor(((phoneRoll.toInt() % 360) + 360) % 360, levelStartedAt, MAX_LEVEL_RAD)
        levelAngle += (target - levelAngle) * 0.5f
        camera.setVideoLevel(levelAngle, levelScale)
        applyPreviewCrop()
    }

    private fun stopLevel() {
        if (!levelActive) return
        levelActive = false
        levelAngle = 0f
        camera.setVideoLevel(0f, 1f)
        applyPreviewCrop()
    }

    /** Shutter held in photo mode: switch to video, record while held, come back to photos. */
    private val holdForVideo = Runnable {
        if (videoMode || recording || capturing || caps == null || groupWaiting || countdownRemaining > 0 || !shutter.isPressed) return@Runnable
        startQuickVideo()
    }

    private fun startQuickVideo() {
        quickVideo = true
        quickVideoReleased = false
        haptic(shutter, HapticFeedbackConstants.LONG_PRESS)
        showGroupStatus("Recording while you hold…")
        setVideo(true) // reopens the camera; recording starts in onCameraOpened
    }

    private fun endQuickVideo() {
        quickVideoReleased = true
        when {
            recording || videoStarting -> camera.stopRecording() // also cancels a start still waiting for the camera
            else -> finishQuickVideo() // released before the camera even switched
        }
    }

    private fun finishQuickVideo() {
        quickVideo = false
        quickVideoReleased = false
        groupStatus.visibility = View.GONE
        setVideo(false)
    }

    // ------------------------------------------------------------------ looks (filters)

    private fun toggleLooks() {
        looksOpen = !looksOpen
        haptic(btnLook, HapticFeedbackConstants.VIRTUAL_KEY)
        if (looksOpen) buildLookRow()
        refreshUi()
    }

    /** The looks strip: each look previewed on the current view. */
    private fun buildLookRow() {
        lookRow.removeAllViews()
        val d = resources.displayMetrics.density
        val tw = (64 * d).toInt()
        val th = if (textureView.width > 0) tw * textureView.height / textureView.width else tw * 4 / 3
        val frame = try {
            textureView.getBitmap(tw, th.coerceAtLeast(1))
        } catch (e: Exception) {
            null
        }
        val current = prefs.look
        for (look in Looks.all + Looks.custom(prefs.customLook())) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding((5 * d).toInt(), (4 * d).toInt(), (5 * d).toInt(), (2 * d).toInt())
                contentDescription = "Look ${look.name}"
                // Tapping My look again opens its editor.
                setOnClickListener { if (look.id == Looks.MINE && prefs.look == Looks.MINE) editMyLook() else selectLook(look.id) }
            }
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.thumb_bg)
                clipToOutline = true
                frame?.let { setImageBitmap(Looks.preview(it, look)) }
                if (look.id == current) {
                    foreground = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 12 * d
                        setStroke((2.5f * d).toInt(), getColor(R.color.accent))
                    }
                }
            }
            cell.addView(img, LinearLayout.LayoutParams(tw, (tw * 1.15f).toInt()))
            cell.addView(TextView(this).apply {
                text = look.name
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(getColor(if (look.id == current) R.color.accent else R.color.white))
                setPadding(0, (3 * d).toInt(), 0, 0)
            })
            lookRow.addView(cell)
        }
        frame?.recycle()
    }

    private fun selectLook(id: String) {
        if (id != prefs.look) prefs.lookStrength = 100
        prefs.look = id
        haptic(lookRow, HapticFeedbackConstants.VIRTUAL_KEY)
        applyLiveLook()
        buildLookRow()
        zoomIndicator.text = Looks.resolve(prefs).name
        zoomIndicator.setTextColor(getColor(R.color.white))
        zoomIndicator.animate().cancel()
        zoomIndicator.alpha = 1f
        zoomIndicator.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideZoomIndicator)
        mainHandler.postDelayed(hideZoomIndicator, 900)
        refreshUi()
    }

    /** My look: sliders for warmth, fade, contrast, colour, grain and vignette, applied live. */
    private fun editMyLook() {
        if (prefs.look != Looks.MINE) selectLook(Looks.MINE)
        val d = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), (8 * d).toInt())
        }
        val sliders = listOf(
            Triple("Warmth", "myWarmth", 20) to (-100..100),
            Triple("Fade", "myFade", 30) to (0..100),
            Triple("Contrast", "myContrast", 0) to (-100..100),
            Triple("Colour", "mySaturation", -10) to (-100..100),
            Triple("Grain", "myGrain", 30) to (0..100),
            Triple("Vignette", "myVignette", 30) to (0..100),
        )
        for ((spec, range) in sliders) {
            val (label, key, default) = spec
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(getColor(R.color.white))
            }, LinearLayout.LayoutParams((84 * d).toInt(), -2))
            row.addView(SeekBar(this).apply {
                contentDescription = "My look $label"
                max = range.last - range.first
                progress = prefs.customValue(key, default) - range.first
                progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
                thumbTintList = progressTintList
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        prefs.setCustomValue(key, p + range.first)
                        applyLiveLook()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                })
            }, LinearLayout.LayoutParams(0, (40 * d).toInt(), 1f))
            panel.addView(row)
        }
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setTitle("My look")
            .setView(panel)
            .setPositiveButton("Done", null)
            .create()
        dialog.setOnDismissListener {
            buildLookRow()
            refreshUi()
        }
        dialog.show()
        // Keep the viewfinder visible: no dimming, panel at the bottom.
        dialog.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.BOTTOM)
        }
    }

    /** Shows the look live: colours as a filter on the preview layer, vignette/date on the overlay. */
    private fun applyLiveLook() {
        val look = Looks.resolve(prefs)
        // Video records the look only through the effects stage (not in slow motion).
        val unsupported = videoMode && openedForVideo && caps != null && !camera.videoEffectsAvailable
        camera.setVideoLook(if (unsupported) null else look)
        if (unsupported || look.isNatural) {
            textureView.setLayerPaint(null)
            overlay.look = null
        } else {
            textureView.setLayerPaint(android.graphics.Paint().apply { colorFilter = look.colorFilter() })
            overlay.look = look
        }
    }

    /** Applies the chosen look to a just-saved photo (the unfiltered shot is kept for before/after). */
    private fun applyLookToSaved(uri: Uri, look: Looks.Look) {
        pendingLooks++
        thumbnail.alpha = 0.5f
        thumbProgress.visibility = View.VISIBLE
        val quality = prefs.jpegQuality
        lookExecutor.execute {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("can't read $uri")
                val name = contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "photo.jpg"
                val bmp = PortraitProcessor.decode(bytes, Budget.sideFor(bytes, 10))
                Looks.apply(bmp, look, seed = name.hashCode().toLong())
                val out = java.io.ByteArrayOutputStream(bytes.size)
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), out)
                bmp.recycle()
                // Enhanced photos already have their original kept; plain photos keep this one.
                if (OriginalStore.get(this, name) == null) OriginalStore.save(this, name, bytes)
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(out.toByteArray()) }
                MediaSaver.copyExif(this, uri, bytes)
                MediaSaver.setDescription(this, uri, "Look: ${look.name}")
                Log.i(TAG, "look ${look.id} applied to $name")
            } catch (e: Throwable) {
                Log.e(TAG, "look failed", e)
            }
            runOnUiThread {
                pendingLooks--
                if (pendingLooks <= 0) {
                    pendingLooks = 0
                    if (pendingEnhance == 0) {
                        thumbnail.alpha = 1f
                        thumbProgress.visibility = View.GONE
                    }
                }
                if (lastPhoto == uri) loadThumbnail(uri)
            }
        }
    }

    // ------------------------------------------------------------------ video

    /** Reopens the camera when switching between photos and video (they use different setups). */
    private fun syncVideo() {
        if ((videoMode == openedForVideo && zslWanted() == openedZsl) || !resumed || !hasCameraPermission()) return
        if (recording) camera.stopRecording()
        caps = null
        readout.text = ""
        startCamera()
    }

    /** "4K", "1080p", "720p": what this camera actually records (the setting is a maximum). */
    private fun videoLabel(): String {
        val size = camera.videoSize ?: return if (prefs.video4k) "up to 4K" else "1080p"
        val lines = minOf(size.width, size.height)
        return if (lines >= 2160) "4K" else "${lines}p"
    }

    private fun hasAudioPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun toggleRecording() {
        if (videoStarting) return
        haptic(shutter, HapticFeedbackConstants.VIRTUAL_KEY)
        if (recording) {
            shutter.busy = true
            camera.stopRecording()
            return
        }
        videoStarting = true
        shutter.busy = true
        if (!hasAudioPermission() && !prefs.audioAsked) {
            // Ask once for the microphone; if declined, videos are recorded without sound.
            prefs.audioAsked = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
            return
        }
        camera.startRecording(deviceOrientation, hasAudioPermission())
    }

    override fun onSlowMotionFailed(reason: String) {
        prefs.slowMotionProblem = reason
        if (prefs.videoKind == OutputPrefs.VIDEO_SLOWMO) prefs.videoKind = OutputPrefs.VIDEO_NORMAL
        toast("Slow motion couldn't start on this phone · switched to normal video. Settings → Send diagnostics helps us fix it.")
        if (resumed && hasCameraPermission() && videoMode) {
            caps = null
            startCamera()
        }
        refreshUi()
    }

    override fun onRecordingStarted(withSound: Boolean) {
        videoStarting = false
        if (quickVideo && quickVideoReleased) {
            camera.stopRecording() // let go while it was starting
        }
        recording = true
        recordingStartedAt = SystemClock.uptimeMillis()
        shutter.busy = false
        recTime.visibility = View.VISIBLE
        mainHandler.post(recTick)
        startLevel()
        if (!withSound) toast("Recording without sound (microphone not allowed)")
        refreshUi()
    }

    override fun onRecordingStopped(uri: Uri?) {
        videoStarting = false
        val length = SystemClock.uptimeMillis() - recordingStartedAt
        recording = false
        stopLevel()
        mainHandler.removeCallbacks(recTick)
        recTime.visibility = View.GONE
        shutter.busy = false
        if (uri != null) {
            onPhotoSaved(uri, "video/mp4")
            toast("Video saved · ${VideoRecorder.durationLabel(length)}")
        } else {
            toast("Video not saved (too short?)")
        }
        refreshUi()
        if (quickVideo) finishQuickVideo()
    }

    private val recTick = object : Runnable {
        override fun run() {
            if (!recording) return
            val elapsed = SystemClock.uptimeMillis() - recordingStartedAt
            recTime.text = "● " + VideoRecorder.durationLabel(elapsed) +
                if (openedKind == OutputPrefs.VIDEO_TIMELAPSE && camera.videoEffectsAvailable)
                    " → " + VideoRecorder.durationLabel(elapsed / CameraController.TIMELAPSE_FACTOR) else ""
            mainHandler.postDelayed(this, 500)
        }
    }

    // ------------------------------------------------------------------ auto HDR

    /** Auto HDR applies to Auto in Simple mode (the other intents process photos their own way). */
    private fun hdrApplies() = photoIntent("AUTO") && prefs.autoHdr && extraCrop <= 1.001f &&
        caps?.manualSensor == true

    /** Each analysis tick: does the scene need HDR? Needs a few agreeing ticks to switch (no flicker). */
    private fun updateHdr() {
        if (!hdrApplies()) {
            if (hdrStops != 0f) setHdr(0f)
            return
        }
        val face = lastFaces.maxByOrNull { it.width() * it.height() }
        val faceRatio = face?.let { f ->
            val whole = analyzer.meanLuma8
            if (whole > 1f) analyzer.meanLuma(f) / whole else null
        }
        val wanted = if (prefs.forceHdr) 1.5f
        else Hdr.stopsDarker(Hdr.Scene(analyzer.clippedFraction, analyzer.darkFraction, faceRatio))
        if (wanted > 0f) {
            hdrOffTicks = 0
            if (++hdrOnTicks >= HDR_ON_TICKS || hdrStops > 0f) setHdr(wanted)
        } else {
            hdrOnTicks = 0
            if (hdrStops > 0f && ++hdrOffTicks >= HDR_OFF_TICKS) setHdr(0f)
        }
    }

    private fun setHdr(stops: Float) {
        if (stops == hdrStops) return
        hdrStops = stops
        hdrBadge.visibility = if (stops > 0f) View.VISIBLE else View.GONE
    }

    private fun startHdr() {
        val c = caps ?: return
        val isoR = c.isoRange ?: return
        val expR = c.exposureRange ?: return
        nightProcessing = true
        mergeSilky = false
        mergeIndoor = false
        mergeHdrStops = hdrStops
        val (iso, t) = Hdr.plan(
            liveIso ?: 100, liveExposure ?: 10_000_000L, safeShutterNs() ?: 33_000_000L, hdrStops, isoR, expR,
        )
        nightIso = iso
        Log.i(TAG, "hdr plan: $HDR_FRAMES x ISO $iso ${Stops.shutterLabel(t)} (${hdrStops} stops darker)")
        showGroupStatus("Hold still… HDR")
        camera.takeBurst(deviceOrientation, HDR_FRAMES, toMemory = true, exposure = iso to t)
    }

    override fun onBurstCaptured(frames: List<ByteArray>, mimeType: String) {
        if (portraitProcessing) {
            processPortrait(frames.first(), mimeType)
            return
        }
        if (zoomProcessing) {
            processSuperZoom(frames, mimeType)
            return
        }
        if (nightProcessing) {
            processNight(keepSteady(frames, camera.lastBurstTiming, minKeep = maxOf(2, frames.size / 2)), mimeType)
            return
        }
        if (cleanProcessing) {
            processClean(frames, mimeType)
            return
        }
        if (trailsProcessing) {
            processTrails(frames, mimeType)
            return
        }
        if (closeupProcessing) {
            processCloseup(frames, mimeType)
            return
        }
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

    // ------------------------------------------------------------------ super zoom

    /** Frames are in memory: free the shutter at once and enhance in the background. */
    private fun releaseShutterForBackground() {
        zoomProcessing = false
        capturing = false
        shutter.busy = false
        pendingEnhance++
        thumbnail.alpha = 0.5f
        thumbProgress.visibility = View.VISIBLE
        showGroupStatus("Enhancing zoom photo… you can keep shooting")
    }

    private fun enhanceDone(uri: Uri?, mime: String, message: String) {
        pendingEnhance--
        if (pendingEnhance <= 0) {
            pendingEnhance = 0
            thumbnail.alpha = 1f
            if (pendingLooks == 0) thumbProgress.visibility = View.GONE
            if (groupStatus.text.startsWith("Enhancing")) groupStatus.visibility = View.GONE
        }
        if (uri != null) onPhotoSaved(uri, mime) else toast(getString(R.string.save_failed))
        if (message.isNotEmpty()) toast(message)
    }

    /** Super zoom from full-resolution JPEGs (phones without the fast stream). */
    private fun processSuperZoom(frames: List<ByteArray>, mimeType: String) {
        val timingForZoom = camera.lastBurstTiming
        val roiSensor = subjectSensor()
        val crop = extraCrop
        val quality = prefs.jpegQuality
        val enhance = zoomEnhancing
        zoomEnhancing = false
        releaseShutterForBackground()
        enhancer.execute {
            val result = try {
                // The first frame can be stale; then drop the frames the phone moved during.
                val timing = timingForZoom
                val skip = if (frames.size > 3) 1 else 0
                val burst = keepSteady(frames.drop(skip), timing.drop(skip), minKeep = 3)
                when {
                    enhance -> superZoom.enhance(burst, quality, roi = subjectForJpeg(burst.firstOrNull(), roiSensor))
                    aiFor(crop) -> superZoom.process(burst, crop, quality, ai = aiUpscaler())
                    else -> superZoom.process(burst, crop, quality)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "super zoom failed", e)
                null
            }
            val uri = if (result != null) {
                val name = "${MediaSaver.newBaseName()}_ZOOM.jpg"
                result.before?.let { OriginalStore.save(this, name, it) }
                MediaSaver.save(
                    this, name, "image/jpeg",
                    writer = { it.write(result.jpeg) },
                    afterWrite = { u -> MediaSaver.copyExif(this, u, frames.first()) },
                )
            } else {
                // Fall back to the camera's own photo (at the camera's maximum zoom).
                val ext = if (mimeType == "image/heic") "heic" else "jpg"
                MediaSaver.save(this, "${MediaSaver.newBaseName()}.$ext", mimeType, writer = { it.write(frames.first()) })
            }
            runOnUiThread {
                enhanceDone(
                    uri, if (result != null) "image/jpeg" else mimeType,
                    when {
                        result == null -> "Saved at the camera's maximum zoom"
                        enhance -> "Zoom enhance · ${result.merged} shots merged for detail"
                        aiFor(crop) -> "Super zoom · AI detail · ${result.merged} shots merged"
                        else -> "Super zoom · sharpest ${result.merged} of ${frames.size} shots merged"
                    },
                )
            }
        }
    }

    private val rawZoom = RawZoomProcessor()

    override fun onRawBurst(capture: RawBurstCapture?, jpegs: List<ByteArray>) {
        if (!zoomProcessing) return
        if (capture == null) {
            // RAW didn't come through: the JPEG merge.
            processSuperZoom(jpegs, "image/jpeg")
            return
        }
        val quality = prefs.jpegQuality
        zoomEnhancing = false
        releaseShutterForBackground()
        val roiSensor = subjectSensor()
        // Drop the RAW frames the phone moved during (by the gyroscope).
        val steady = keepSteadyIndices(capture.timing, minKeep = 3)
        val raw = if (steady.size < capture.frames.size) capture.only(steady) else capture
        enhancer.execute {
            var how = "RAW"
            val result = try {
                rawZoom.process(raw, jpegs, capture.rotation, quality, roi = roiSensor)
            } catch (e: Throwable) {
                Log.e(TAG, "raw zoom failed, using the JPEG merge", e)
                how = "JPEG"
                try {
                    superZoom.enhance(if (jpegs.size > 3) jpegs.drop(1) else jpegs, quality, roi = subjectForJpeg(jpegs.firstOrNull(), roiSensor))
                } catch (e2: Throwable) {
                    Log.e(TAG, "zoom enhance failed", e2)
                    null
                }
            }
            val uri = if (result != null) {
                val name = "${MediaSaver.newBaseName()}_ZOOM.jpg"
                result.before?.let { OriginalStore.save(this, name, it) }
                MediaSaver.save(this, name, "image/jpeg", writer = { it.write(result.jpeg) },
                    afterWrite = { u -> MediaSaver.copyExif(this, u, jpegs.first()) })
            } else {
                MediaSaver.save(this, "${MediaSaver.newBaseName()}.jpg", "image/jpeg", writer = { it.write(jpegs.first()) })
            }
            runOnUiThread {
                enhanceDone(uri, "image/jpeg",
                    if (result != null) "Zoom enhance · ${result.merged} $how shots merged for detail" else "Saved a single shot")
            }
        }
    }

    /** Freeze: the moment's frame (raw YUV) → upright, lightly sharpened JPEG, in the background. */
    override fun onMoment(frame: MomentBuffer.Frame, rotationDegrees: Int) {
        if (!freezeProcessing) return
        freezeProcessing = false
        capturing = false
        shutter.busy = false
        val quality = prefs.jpegQuality
        val loc = lastLocation
        enhancer.execute {
            val jpeg = try {
                val px = MomentBuffer.toArgb(frame)
                SuperRes.sharpen(px, amount = 0.5f)
                var bmp = Bitmap.createBitmap(px.px, px.w, px.h, Bitmap.Config.ARGB_8888)
                if (rotationDegrees != 0) {
                    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true)
                    bmp.recycle()
                    bmp = rotated
                }
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), out)
                bmp.recycle()
                out.toByteArray()
            } catch (e: Throwable) {
                Log.e(TAG, "freeze frame failed", e)
                null
            }
            val uri = jpeg?.let { bytes ->
                MediaSaver.save(
                    this, "${MediaSaver.newBaseName()}_ACTION.jpg", "image/jpeg",
                    writer = { it.write(bytes) },
                    afterWrite = { u -> MediaSaver.writeBasicExif(this, u, loc) },
                )
            }
            runOnUiThread {
                if (uri != null) onPhotoSaved(uri, "image/jpeg") else toast(getString(R.string.save_failed))
            }
        }
    }

    // ------------------------------------------------------------------ freeze exposure

    /** Freeze: fast shutter (ISO automatic) and the moment buffer, while the Freeze intent is on. */
    private fun syncFreeze() {
        val c = caps ?: return
        val want = freezeMode
        camera.setMomentBuffer(want)
        if (want && c.manualSensor && !freezeExposureOn) {
            val isoR = c.isoRange
            val expR = c.exposureRange
            if (isoR != null && expR != null) {
                val (iso, t) = MomentBuffer.freezePlan(
                    liveIso ?: 400, liveExposure ?: 20_000_000L, FREEZE_SHUTTER_NS, FREEZE_LIMIT_NS, isoR, expR,
                )
                settings = settings.copy(manualExposure = true, isoAuto = true, shutterAuto = false, iso = iso, exposureNs = t)
                freezeExposureOn = true
                Log.i(TAG, "freeze: ISO $iso ${Stops.shutterLabel(t)}")
                pushSettings()
            }
        } else if (!want && freezeExposureOn) {
            settings = settings.copy(manualExposure = false, isoAuto = false)
            freezeExposureOn = false
            pushSettings()
        }
    }

    /** Super zoom from a fast burst: frames are already centre crops in pixels. */
    override fun onFastBurst(frames: List<SuperRes.Frame>, rotationDegrees: Int) {
        if (!zoomProcessing) return
        if (frames.isEmpty()) {
            zoomProcessing = false
            capturing = false
            shutter.busy = false
            groupStatus.visibility = View.GONE
            toast("Capture failed")
            return
        }
        val quality = prefs.jpegQuality
        val loc = lastLocation
        releaseShutterForBackground()
        enhancer.execute {
            val t0 = SystemClock.uptimeMillis()
            val result = try {
                // The first frame of a burst can predate the burst's settings: skip it.
                superZoom.processFrames(if (frames.size > 3) frames.drop(1) else frames, rotationDegrees, quality)
            } catch (e: Throwable) {
                Log.e(TAG, "super zoom failed", e)
                null
            }
            Log.i(TAG, "super zoom processed in ${SystemClock.uptimeMillis() - t0} ms")
            val uri = result?.let { r ->
                val name = "${MediaSaver.newBaseName()}_ZOOM.jpg"
                r.before?.let { OriginalStore.save(this, name, it) }
                MediaSaver.save(
                    this, name, "image/jpeg",
                    writer = { it.write(r.jpeg) },
                    afterWrite = { u -> MediaSaver.writeBasicExif(this, u, loc) },
                )
            }
            runOnUiThread {
                enhanceDone(uri, "image/jpeg", if (result != null) "Super zoom · sharpest ${result.merged} of ${frames.size} shots merged" else "Super zoom failed")
            }
        }
    }

    // ------------------------------------------------------------------ night

    private fun startSilky() {
        val c = caps ?: return
        nightProcessing = true
        mergeSilky = true
        mergeIndoor = false
        mergeHdrStops = 0f
        val frames = SILKY_FRAMES[prefs.silkyStrength.coerceIn(0, 2)]
        val isoRange = c.isoRange
        val expRange = c.exposureRange
        if (c.manualSensor && isoRange != null && expRange != null) {
            val (iso, t) = NightProcessor.silkyPlan(liveIso ?: 100, liveExposure ?: 10_000_000L, isoRange, expRange)
            nightIso = iso
            Log.i(TAG, "silky plan: $frames x ISO $iso ${Stops.shutterLabel(t)}")
            camera.takeBurst(deviceOrientation, frames, toMemory = true, exposure = iso to t)
        } else {
            nightIso = liveIso ?: 100
            camera.takeBurst(deviceOrientation, frames, toMemory = true)
        }
        showGroupStatus("Hold still… capturing $frames shots")
    }

    // ------------------------------------------------------------------ close-up (focus stacking)

    private fun startCloseup() {
        val c = caps ?: return
        closeupProcessing = true
        val focus = FocusStackProcessor.plan(liveFocus ?: (c.minFocusDistance / 2), c.minFocusDistance, CLOSEUP_FRAMES)
        Log.i(TAG, "close-up plan: focus ${focus.joinToString { "%.2f".format(Locale.US, it) }} dpt")
        showGroupStatus("Hold still… focusing near to far")
        camera.takeBurst(deviceOrientation, CLOSEUP_FRAMES, toMemory = true, focusDistances = focus)
    }

    private fun processCloseup(frames: List<ByteArray>, mimeType: String) {
        showGroupStatus("Making everything sharp…")
        val quality = prefs.jpegQuality
        Thread {
            val result = try {
                if (frames.size >= 2) focusStack.process(frames, quality) else null
            } catch (e: Throwable) {
                Log.e(TAG, "close-up failed", e)
                null
            }
            val source = frames[result?.reference ?: (frames.size / 2)]
            val bytes = result?.jpeg ?: source
            val mime = if (result != null) "image/jpeg" else mimeType
            val ext = if (mime == "image/heic") "heic" else "jpg"
            val name = "${MediaSaver.newBaseName()}${if (result != null) "_CLOSEUP" else ""}.$ext"
            if (result != null) OriginalStore.save(this, name, source)
            val uri = MediaSaver.save(
                this, name, mime,
                writer = { it.write(bytes) },
                afterWrite = if (result != null) { u -> MediaSaver.copyExif(this, u, source) } else null,
            )
            runOnUiThread {
                closeupProcessing = false
                capturing = false
                shutter.busy = false
                groupStatus.visibility = View.GONE
                if (uri != null) onPhotoSaved(uri, mime) else toast(getString(R.string.save_failed))
                toast(if (result != null) "Close-up · ${result.used} focus steps combined" else "Saved a single shot")
            }
        }.start()
    }

    // ------------------------------------------------------------------ light trails

    private val trails = TrailsProcessor()

    private fun startTrails() {
        trailsProcessing = true
        val c = caps
        val isoR = c?.isoRange
        val expR = c?.exposureRange
        val exposure = if (c != null && c.manualSensor && isoR != null && expR != null) {
            TrailsProcessor.plan(liveIso ?: 400, liveExposure ?: 33_000_000L, isoR, expR, TRAILS_FRAME_NS)
        } else null
        val seconds = TRAILS_FRAMES * (exposure?.second ?: 33_000_000L) / 1e9
        Log.i(TAG, "trails plan: $TRAILS_FRAMES x ${exposure?.let { "ISO ${it.first} ${Stops.shutterLabel(it.second)}" } ?: "auto"}")
        showGroupStatus("Hold still… drawing light trails (${"%.0f".format(Locale.US, maxOf(1.0, seconds))} s)")
        trailsLive = null
        trailsPreview.setImageDrawable(null)
        trailsPreview.visibility = View.VISIBLE
        mainHandler.post(trailsTick)
        camera.takeBurst(deviceOrientation, TRAILS_FRAMES, toMemory = true, exposure = exposure)
    }

    /** Live preview: the viewfinder frames lighten-stacked as they come, so the lines grow on screen. */
    private val trailsTick = object : Runnable {
        override fun run() {
            if (!trailsProcessing || textureView.width == 0) return
            val w = TRAILS_LIVE_WIDTH
            val h = w * textureView.height / textureView.width
            val frame = try {
                textureView.getBitmap(w, h)
            } catch (e: Exception) {
                null
            }
            if (frame != null) {
                val px = IntArray(w * h)
                frame.getPixels(px, 0, w, 0, 0, w, h)
                frame.recycle()
                val acc = trailsLive?.takeIf { trailsLiveW == w && trailsLiveH == h }
                if (acc == null) {
                    trailsLive = px
                    trailsLiveW = w
                    trailsLiveH = h
                } else {
                    TrailsProcessor.lighten(acc, px, w, h, 0, 0)
                }
                trailsPreview.setImageBitmap(android.graphics.Bitmap.createBitmap(trailsLive!!, w, h, android.graphics.Bitmap.Config.ARGB_8888))
            }
            mainHandler.postDelayed(this, TRAILS_LIVE_MS)
        }
    }

    private fun stopTrailsLive() {
        mainHandler.removeCallbacks(trailsTick)
        trailsLive = null
        trailsPreview.visibility = View.GONE
        trailsPreview.setImageDrawable(null)
    }

    private fun processTrails(frames: List<ByteArray>, mimeType: String) {
        mainHandler.removeCallbacks(trailsTick) // keep showing the last live result while the photo is made
        showGroupStatus("Stacking light trails…")
        val quality = prefs.jpegQuality
        Thread {
            val result = try {
                if (frames.size >= 2) trails.process(frames, quality) else null
            } catch (e: Throwable) {
                Log.e(TAG, "trails failed", e)
                null
            }
            val source = frames.first()
            val bytes = result?.jpeg ?: source
            val mime = if (result != null) "image/jpeg" else mimeType
            val ext = if (mime == "image/heic") "heic" else "jpg"
            val name = "${MediaSaver.newBaseName()}${if (result != null) "_TRAILS" else ""}.$ext"
            if (result != null) OriginalStore.save(this, name, source)
            val uri = MediaSaver.save(
                this, name, mime,
                writer = { it.write(bytes) },
                afterWrite = if (result != null) { u -> MediaSaver.copyExif(this, u, source) } else null,
            )
            runOnUiThread {
                trailsProcessing = false
                stopTrailsLive()
                capturing = false
                shutter.busy = false
                groupStatus.visibility = View.GONE
                if (uri != null) onPhotoSaved(uri, mime) else toast(getString(R.string.save_failed))
                toast(if (result != null) "Light trails · ${result.used} shots stacked" else "Saved a single shot")
            }
        }.start()
    }

    // ------------------------------------------------------------------ clean (remove passers-by)

    private fun startClean() {
        cleanProcessing = true
        showGroupStatus("Hold still… removing passers-by (3 s)")
        camera.takeBurst(deviceOrientation, CLEAN_FRAMES, toMemory = true, intervalMs = CLEAN_INTERVAL_MS)
    }

    private fun processClean(frames: List<ByteArray>, mimeType: String) {
        showGroupStatus("Removing passers-by…")
        val quality = prefs.jpegQuality
        Thread {
            val result = try {
                if (frames.size >= 3) clean.process(frames, quality) else null
            } catch (e: Throwable) {
                Log.e(TAG, "clean failed", e)
                null
            }
            val source = frames[result?.reference ?: (frames.size / 2)]
            val bytes = result?.jpeg ?: source
            val mime = if (result != null) "image/jpeg" else mimeType
            val ext = if (mime == "image/heic") "heic" else "jpg"
            val name = "${MediaSaver.newBaseName()}${if (result != null) "_CLEAN" else ""}.$ext"
            if (result != null) OriginalStore.save(this, name, source)
            val uri = MediaSaver.save(
                this, name, mime,
                writer = { it.write(bytes) },
                afterWrite = if (result != null) { u -> MediaSaver.copyExif(this, u, source) } else null,
            )
            runOnUiThread {
                cleanProcessing = false
                capturing = false
                shutter.busy = false
                groupStatus.visibility = View.GONE
                if (uri != null) onPhotoSaved(uri, mime) else toast(getString(R.string.save_failed))
                toast(if (result != null) "Clean photo · passers-by removed (${result.used} shots)" else "Saved a single shot")
            }
        }.start()
    }

    /** Shots in a zoom burst: fewer on slower phones, so holding still and merging stay short. */
    private fun zoomBurstFrames(): Int {
        val fast = Runtime.getRuntime().availableProcessors() >= 8 && Runtime.getRuntime().maxMemory() >= 400_000_000L
        return if (fast) SUPER_ZOOM_FRAMES else SUPER_ZOOM_FRAMES_SLOW
    }

    /** Zoomed in (1.5× or more) within the camera's range: merge a burst for detail, like Super Res Zoom. */
    private fun zoomEnhanceApplies(): Boolean {
        val c = caps ?: return false
        // Needs a camera with full manual control (fast shutter, own processing off) to gain anything.
        return prefs.zoomEnhance && !c.isFront && c.manualSensor && displayZoom() >= ZOOM_ENHANCE_FROM && !settings.raw &&
            (photoIntent("AUTO") || (prefs.uiPro && settings.cameraAe))
    }

    /** Auto in a dim room (not dark enough for Night): merge a few shots quietly. */
    private fun lowLightApplies() = photoIntent("AUTO") && prefs.lowLightMerge && extraCrop <= 1.001f && !settings.raw &&
        !settings.manualExposure && ((liveIso ?: 0) >= LOW_LIGHT_ISO || prefs.forceLowLight)

    private fun startLowLight() {
        nightProcessing = true
        mergeSilky = false
        mergeHdrStops = 0f
        mergeIndoor = true
        nightIso = liveIso ?: LOW_LIGHT_ISO
        showGroupStatus("Hold still…")
        // With shaky hands, each shot is also faster (the merge removes the extra noise).
        val plan = steadyPlan()
        plan?.let { nightIso = it.iso }
        camera.takeBurst(deviceOrientation, LOW_LIGHT_FRAMES, toMemory = true, exposure = plan?.let { it.iso to it.exposureNs })
    }

    private fun startNight() {
        val c = caps ?: return
        nightProcessing = true
        mergeSilky = false
        mergeIndoor = false
        mergeHdrStops = 0f
        val isoRange = c.isoRange
        val expRange = c.exposureRange
        if (c.manualSensor && isoRange != null && expRange != null) {
            val (iso, t, frames) = NightProcessor.plan(
                aeIso = liveIso ?: 400,
                aeExposureNs = liveExposure ?: 33_000_000L,
                safeShutterNs = safeShutterNs() ?: 66_000_000L,
                isoRange = isoRange,
                exposureRange = expRange,
            )
            nightIso = iso
            Log.i(TAG, "night plan: $frames x ISO $iso ${Stops.shutterLabel(t)}")
            showGroupStatus("Hold still… ${"%.1f".format(Locale.US, frames * t / 1e9 + 0.3)} s")
            camera.takeBurst(deviceOrientation, frames, toMemory = true, exposure = iso to t)
        } else {
            // No manual exposure on this camera: merge a normal burst for less noise.
            nightIso = liveIso ?: 800
            showGroupStatus("Hold still…")
            camera.takeBurst(deviceOrientation, NIGHT_FALLBACK_FRAMES, toMemory = true)
        }
    }

    private fun processNight(frames: List<ByteArray>, mimeType: String) {
        val silky = mergeSilky
        val hdr = mergeHdrStops
        val indoor = mergeIndoor
        val steadyMerge = mergeSteady
        mergeIndoor = false
        mergeSteady = false
        showGroupStatus(
            when {
                hdr > 0f -> "Balancing light…"
                silky -> "Blending ${frames.size} shots…"
                else -> "Merging ${frames.size} shots…"
            },
        )
        val iso = nightIso
        val quality = prefs.jpegQuality
        val roiUpright = subjectSensor()?.let { rotateNorm(it, camera.lastCaptureOrientation) }
        Thread {
            val result = try {
                night.process(frames, iso, quality, silky, hdrStops = hdr, lift = !indoor, roi = roiUpright)
            } catch (e: Throwable) {
                Log.e(TAG, "night merge failed", e)
                null
            }
            val bytes = result?.jpeg ?: frames.first()
            val mime = if (result != null) "image/jpeg" else mimeType
            val ext = if (mime == "image/heic") "heic" else "jpg"
            val source = frames[result?.reference ?: 0]
            val suffix = when {
                result == null -> ""
                hdr > 0f -> "_HDR"
                steadyMerge -> "_STEADY"
                indoor -> "_LOWLIGHT"
                silky -> "_SILKY"
                else -> "_NIGHT"
            }
            val name = "${MediaSaver.newBaseName()}$suffix.$ext"
            // HDR's "before" is what a normal photo would have looked like (bright parts blown out).
            if (result != null) OriginalStore.save(this, name, result.before ?: source)
            val uri = MediaSaver.save(
                this, name, mime,
                writer = { it.write(bytes) },
                afterWrite = if (result != null) { u -> MediaSaver.copyExif(this, u, source) } else null,
            )
            runOnUiThread {
                nightProcessing = false
                capturing = false
                shutter.busy = false
                groupStatus.visibility = View.GONE
                if (uri != null) onPhotoSaved(uri, mime) else toast(getString(R.string.save_failed))
                toast(
                    when {
                        result == null -> "Saved a single shot"
                        hdr > 0f -> "HDR photo · bright and dark areas balanced"
                        steadyMerge -> "Steady · ${result.merged} fast shots merged so shaking doesn't blur it"
                        indoor -> "Low light · merged ${result.merged} shots for less grain"
                        silky -> "Silky photo · blended ${result.merged} shots"
                        else -> "Night photo · merged ${result.merged} shots"
                    },
                )
            }
        }.start()
    }

    /** In Auto, suggest Night when the scene is dark. */
    private fun updateNightTip() {
        if (!photoIntent("AUTO") || capturing) return
        val dark = (liveIso ?: 0) >= DARK_ISO || (liveExposure ?: 0L) >= DARK_EXPOSURE_NS
        if (dark) {
            showGroupStatus("Dark scene · tap for Night")
            groupStatus.tag = TIP_NIGHT
        } else if (groupStatus.tag == TIP_NIGHT) {
            groupStatus.tag = null
            groupStatus.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------ portrait

    private fun processPortrait(jpeg: ByteArray, mimeType: String) {
        showGroupStatus("Creating portrait…")
        val strength = prefs.portraitStrength
        val quality = prefs.jpegQuality
        Thread {
            val result = try {
                // HEIF captures can't be decoded+re-encoded the same way; treat them like JPEG input.
                portrait.process(jpeg, strength, quality)
            } catch (e: Throwable) {
                Log.e(TAG, "portrait failed", e)
                PortraitProcessor.Result(null, false)
            }
            val bytes = result.jpeg ?: jpeg
            val mime = if (result.jpeg != null) "image/jpeg" else mimeType
            val ext = if (mime == "image/heic") "heic" else "jpg"
            val processed = result.jpeg != null
            val name = "${MediaSaver.newBaseName()}${if (processed) "_PORTRAIT" else ""}.$ext"
            if (processed) OriginalStore.save(this, name, jpeg)
            val uri = MediaSaver.save(
                this, name, mime,
                writer = { it.write(bytes) },
                afterWrite = if (processed) { u -> MediaSaver.copyExif(this, u, jpeg) } else null,
            )
            runOnUiThread {
                portraitProcessing = false
                capturing = false
                shutter.busy = false
                groupStatus.visibility = View.GONE
                if (uri != null) onPhotoSaved(uri, mime) else toast(getString(R.string.save_failed))
                toast(if (processed) "Portrait saved" else "Nothing stood out from the background · saved a normal photo")
            }
        }.start()
    }

    /** In Portrait, nudge the user toward a person when none is in view. */
    private fun updatePortraitHint() {
        if (!portraitMode || capturing || portraitProcessing) return
        if (SystemClock.uptimeMillis() - lastFaceSeen > 1200) showGroupStatus("Point at a person, pet or object · stand close")
        else groupStatus.visibility = View.GONE
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
        groupStatus.tag = null
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
        phoneRoll = roll
        if (levelActive) updateLevel()
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
            updateShakeMeter()
            if (groupMode) analyzeGroup()
            updatePortraitHint()
            updateNightTip()
            updateHdr()
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
            val iso = (settings.iso * factor).roundToInt()
            val er = c.exposureRange
            when {
                // Freeze in bright light: ISO is already at its lowest, so shorten the shutter.
                freezeExposureOn && iso < r.lower && er != null ->
                    settings.copy(iso = r.lower, exposureNs = (settings.exposureNs * factor).toLong().coerceIn(er.lower, FREEZE_SHUTTER_NS))
                // Freeze in dim light: ISO maxed out, lengthen the shutter a little (never past the limit).
                freezeExposureOn && iso > r.upper && er != null ->
                    settings.copy(iso = r.upper, exposureNs = (settings.exposureNs * factor).toLong().coerceIn(er.lower, FREEZE_LIMIT_NS))
                else -> settings.copy(iso = iso.coerceIn(r.lower, r.upper))
            }
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

    /** Floats the lens buttons just inside the bottom edge of the picture (but above the controls). */
    private fun positionLensRow() {
        lensRow.post {
            val gap = (10 * resources.displayMetrics.density).toInt()
            val pictureBottom = textureView.bottom
            val panelTop = bottomPanel.top - previewContainer.top
            val bottomEdge = if (panelTop in 1 until pictureBottom) panelTop else pictureBottom
            val lp = lensRow.layoutParams as FrameLayout.LayoutParams
            val top = (bottomEdge - lensRow.height - gap).coerceAtLeast(0)
            if (lp.topMargin != top) {
                lp.topMargin = top
                lensRow.layoutParams = lp
            }
            val zp = zoomBar.layoutParams as FrameLayout.LayoutParams
            val zTop = (top - zoomBar.height - gap / 2).coerceAtLeast(0)
            if (zp.topMargin != zTop) {
                zp.topMargin = zTop
                zoomBar.layoutParams = zp
            }
        }
    }

    private fun buildLensRow() {
        lensRow.removeAllViews()
        val c = caps ?: return
        val main = mainBackCaps
        stops = if (!c.isFront && main != null) {
            LensPlanner.plan(main.id, main.lensStops, main.zoomRange, main.multiCamera, extraLenses)
        } else {
            c.lensStops.map { LensStop(it, c.id, it) }
        }
        Log.i(TAG, "zoom buttons: ${stops.joinToString { "${Stops.zoomLabel(it.zoom)}@${it.cameraId}" }}")
        val size = (38 * resources.displayMetrics.density).toInt()
        val margin = (3 * resources.displayMetrics.density).toInt()
        for (stop in stops) {
            val tv = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                setBackgroundResource(R.drawable.circle_bg)
                tag = stop
                contentDescription = "Zoom ${Stops.zoomLabel(stop.zoom)}"
                rotation = uiRotation.toFloat()
                setOnClickListener {
                    haptic(this, HapticFeedbackConstants.VIRTUAL_KEY)
                    selectStop(stop)
                }
                setOnTouchListener(lensScrub)
            }
            lensRow.addView(tv, LinearLayout.LayoutParams(size, size).apply { setMargins(margin, 0, margin, 0) })
        }
        lensRow.visibility = if (stops.size > 1 || c.zoomRange.upper > 1.01f) View.VISIBLE else View.GONE
        updateLensRow()
        positionLensRow()
    }

    /** The highlighted button: the largest stop on the open camera at or below the current zoom. */
    private fun activeStop(): LensStop? {
        val id = caps?.id ?: return null
        val mine = stops.filter { it.cameraId == id }
        return mine.lastOrNull { it.zoom <= displayZoom() + 0.01f } ?: mine.firstOrNull()
    }

    private fun updateLensRow() {
        if (caps == null || stops.isEmpty()) return
        val z = displayZoom()
        val active = activeStop()
        for (i in 0 until lensRow.childCount) {
            val tv = lensRow.getChildAt(i) as TextView
            val stop = tv.tag as LensStop
            if (stop == active) {
                tv.text = Stops.zoomLabel(z)
                tv.setTextColor(getColor(R.color.accent))
            } else {
                tv.text = Stops.zoomLabel(stop.zoom).removeSuffix("×")
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
        shutter.pro = pro && prefs.uiPro
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
        if (c != null) parts += Stops.zoomLabel(displayZoom())
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
        private const val REQUEST_AUDIO = 3
        private const val CLEAN_FRAMES = 7
        /** Steadiest moment: frames merged, how far back from the press they may be, wait after the press. */
        private const val MOMENT_FRAMES = 3
        private const val MOMENT_WINDOW_NS = 600_000_000L
        private const val MOMENT_AFTER_MS = 150L
        /** Replace the photo only if it shook more than this (px) and the kept frames were twice as steady. */
        private const val RESCUE_ABOVE_PX = 2f
        /** Shake blur (px of a 4000 px wide photo) worth undoing. */
        private const val DEBLUR_ABOVE_PX = 2.5f
        private const val PATH_STEPS = 24
        /** Lens shift is learnt against the gyroscope over this long before the photo. */
        private const val OIS_CAL_NS = 1_500_000_000L
        private const val OIS_CAL_STEP_NS = 20_000_000L
        /** A tap marks the subject for this long. */
        private const val TAP_SUBJECT_MS = 10_000L
        private const val STEADY_FRAMES = 4
        /** Test hook: pretend the hands only hold 1/500 s. */
        private const val FORCED_SHAKY_SAFE_NS = 2_000_000L
        /** "Halfway into super zoom": super zoom reaches 4× past the camera's maximum; halfway on a log scale is 2×. */
        private const val AI_HALFWAY_CROP = 2f
        /** Zoom enhance from this zoom (relative to the main lens) up. */
        private const val ZOOM_ENHANCE_FROM = 1.5f
        private const val TRAILS_FRAMES = 12
        private const val CLOSEUP_FRAMES = 5
        /** Each trails frame up to 1/3 s, back to back: lines without gaps. */
        private const val TRAILS_FRAME_NS = 333_000_000L
        private const val TRAILS_LIVE_WIDTH = 270
        private const val TRAILS_LIVE_MS = 80L
        /** Auto merges a few shots from this ISO up (dim rooms); Night is suggested from DARK_ISO. */
        private const val LOW_LIGHT_ISO = 640
        private const val LOW_LIGHT_FRAMES = 4
        /** Level horizon: tilts up to this much (radians, ~8°) are straightened. */
        private const val MAX_LEVEL_RAD = 0.14f
        /** Steady the viewfinder from this zoom up. */
        private const val STEADY_FROM = 4f
        private const val CLEAN_INTERVAL_MS = 450L
        private const val QUICK_VIDEO_HOLD_MS = 800L
        private const val GROUP_BURST = 3
        private const val GROUP_ANALYSIS_WIDTH = 480
        private const val READY_FRAMES = 2
        private const val GROUP_TIMEOUT_MS = 6000L
        private const val NO_FACE_GIVE_UP_MS = 1500L
        private const val STEADY_WAIT_MS = 800L
        private const val SUPER_ZOOM_FACTOR = 4f
        private const val MAX_DISPLAY_ZOOM = 100f
        /** Shots taken for super zoom; the sharpest [SuperZoomProcessor.KEEP_FRAMES] are merged. */
        private const val SUPER_ZOOM_FRAMES = 11 // the first is skipped, the sharpest 6 of the other 10 merged
        private const val SUPER_ZOOM_FRAMES_SLOW = 6 // slower phones: the first skipped, up to 5 merged
        private const val ZOOM_BAR_HIDE_MS = 5000L
        private const val MAX_PENDING_ENHANCE = 2
        private const val HDR_FRAMES = 6
        private const val HDR_ON_TICKS = 3 // ~0.45 s of a backlit scene before HDR switches on
        private const val HDR_OFF_TICKS = 8
        /** Freeze: shutter at most 1/500 s; in dim light it may lengthen to 1/250 s, never more. */
        private const val FREEZE_SHUTTER_NS = 2_000_000L
        private const val FREEZE_LIMIT_NS = 4_000_000L
        private const val NIGHT_FALLBACK_FRAMES = 6
        private val SILKY_FRAMES = intArrayOf(8, 12, 16)
        private const val DARK_ISO = 1600
        private const val DARK_EXPOSURE_NS = 50_000_000L
        private const val TIP_NIGHT = "tip_night"
        private const val BLURRY_RATIO = 0.5f
        private const val FOCUS_STEPS = 100
        private const val ANALYSIS_INTERVAL_MS = 150L
        private const val ANALYSIS_WIDTH = 270
        private const val MAX_AUTO_SHUTTER_NS = 500_000_000L // 1/2 s
    }
}
