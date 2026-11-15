package com.anycam.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.anycam.app.R
import com.anycam.app.camera.CameraEngine
import com.anycam.app.camera.CameraInfo
import com.anycam.app.capture.PhotoMaker
import com.anycam.app.core.Exposure
import com.anycam.app.core.Geometry
import com.anycam.app.core.RawFrame
import com.anycam.app.core.RawInfo
import com.anycam.app.core.Yuv
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The camera screen. It only wires things together: touches go to the [CameraEngine], bursts go
 * to the [PhotoMaker], and what comes back is shown.
 */
class MainActivity : Activity(), CameraEngine.Listener {

    private lateinit var viewfinder: ViewfinderView
    private lateinit var marks: MarksView
    private lateinit var shutter: ShutterView
    private lateinit var thumbnail: ImageView
    private lateinit var switchButton: View
    private lateinit var torchButton: TextView
    private lateinit var readout: TextView
    private lateinit var status: TextView
    private lateinit var zoomRow: LinearLayout
    private lateinit var modeRow: LinearLayout
    private lateinit var flash: View
    private lateinit var timerButton: TextView
    private lateinit var measure: MeasureView
    private lateinit var measureBar: LinearLayout
    /** Measure mode, and what is being measured by aiming. */
    private var measureMode = false
    private var cameraHeightCm = 140
    private var baseDistance: Double? = null
    private var startPoint: DoubleArray? = null
    private var aimDown = Double.NaN
    private var aimHeading = 0.0
    private lateinit var sensors: android.hardware.SensorManager
    private lateinit var track: com.anycam.app.camera.TrackRecorder
    private var trackFrame: android.graphics.Bitmap? = null
    private var previewSize = Size(0, 0)
    private lateinit var gridButton: TextView
    private lateinit var working: View

    private lateinit var engine: CameraEngine
    private lateinit var photos: PhotoMaker

    private var cameras: List<CameraInfo> = emptyList()
    private var cameraIndex = 0
    private var info: CameraInfo? = null
    private var zoom = 1f
    private var ev = 0
    private var torch = false
    private var liveIso: Int? = null
    private var liveExposure: Long? = null
    private var deviceDegrees = 0
    private var resumed = false
    private var taking = false
    private var making = 0
    private var lastPhoto: Uri? = null
    /** Sensor frames turned out not to be usable on this phone (found out by checking a photo). */
    private var sensorOff = false
    /** The phone maker's processing in use (one of the camera's extensions), or null for AnyCam's own merge. */
    private var extension: Int? = null
    /** Video mode (instead of photos). */
    private var videoMode = false
    private var recordingSince = 0L
    private var lastIsVideo = false
    /** The self-timer: 0 (off), 3 or 10 seconds; and the count in progress, if any. */
    private var timerSeconds = 0
    private var countdown = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewfinder = findViewById(R.id.viewfinder)
        marks = findViewById(R.id.marks)
        shutter = findViewById(R.id.shutter)
        thumbnail = findViewById(R.id.thumbnail)
        switchButton = findViewById(R.id.switchCamera)
        torchButton = findViewById(R.id.torch)
        readout = findViewById(R.id.readout)
        status = findViewById(R.id.status)
        zoomRow = findViewById(R.id.zoomRow)
        modeRow = findViewById(R.id.modeRow)
        flash = findViewById(R.id.flash)
        timerButton = findViewById(R.id.timer)
        gridButton = findViewById(R.id.grid)
        measure = findViewById(R.id.measure)
        working = findViewById(R.id.working)
        measureBar = findViewById(R.id.measureBar)
        sensors = getSystemService(android.hardware.SensorManager::class.java)
        try { cameraHeightCm = getPreferences(MODE_PRIVATE).getInt("cameraHeight", 140) } catch (_: Exception) {}
        measure.onChange = { showPhotoMeasures() }
        measure.onTap = { x, y -> findCard(x, y) }
        track = com.anycam.app.camera.TrackRecorder(this)
        try {
            val prefs = getPreferences(MODE_PRIVATE)
            timerSeconds = prefs.getInt("timer", 0)
            marks.grid = prefs.getBoolean("grid", false)
        } catch (_: Exception) {}
        showTimerAndGrid()
        timerButton.setOnClickListener {
            timerSeconds = when (timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }
            getPreferences(MODE_PRIVATE).edit().putInt("timer", timerSeconds).apply()
            showTimerAndGrid()
        }
        gridButton.setOnClickListener {
            marks.grid = !marks.grid
            getPreferences(MODE_PRIVATE).edit().putBoolean("grid", marks.grid).apply()
            showTimerAndGrid()
        }

        engine = CameraEngine(this, this)
        photos = PhotoMaker(this)

        viewfinder.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                if (!track.running) return
                val bmp = trackFrame ?: return
                viewfinder.getBitmap(bmp)
                track.frame(bmp, surface.timestamp)
                if (track.frameCount % 15 == 0) say(getString(R.string.measure_track_on, track.frameCount), stay = true)
            }
        }
        setUpTouch()
        shutter.setOnClickListener { if (measureMode) freezeForMeasuring() else press() }
        switchButton.setOnClickListener {
            if (cameras.size < 2 || taking || engine.recording) return@setOnClickListener
            cameraIndex = (cameraIndex + 1) % cameras.size
            zoom = 1f
            torch = false
            extension = null
            it.animate().rotationBy(180f).setDuration(250).start()
            openCamera()
        }
        torchButton.setOnClickListener {
            if (info?.hasFlash != true) return@setOnClickListener
            torch = !torch
            engine.setTorch(torch)
            showTorch()
        }
        thumbnail.setOnClickListener {
            val uri = lastPhoto ?: return@setOnClickListener
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (lastIsVideo) "video/mp4" else "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (e: Exception) {
                say("No app to show photos")
            }
        }
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees != ORIENTATION_UNKNOWN) deviceDegrees = ((degrees + 45) / 90 * 90) % 360
            }
        }
    }

    private lateinit var orientation: OrientationEventListener

    override fun onResume() {
        super.onResume()
        resumed = true
        orientation.enable()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!asked) {
                asked = true
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            } else {
                say(getString(R.string.needs_camera), stay = true)
            }
            return
        }
        openCamera()
    }

    private var asked = false

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == AUDIO_REQUEST) return // video records without sound if it wasn't allowed
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera()
        else say(getString(R.string.needs_camera), stay = true)
    }

    override fun onPause() {
        resumed = false
        cancelCountdown()
        stopAiming()
        stopTrack()
        ready = false
        orientation.disable()
        engine.close()
        taking = false
        shutter.busy = false
        info = null
        super.onPause()
    }

    override fun onDestroy() {
        track.release()
        engine.release()
        super.onDestroy()
    }

    private fun openCamera() {
        if (!resumed || !viewfinder.isAvailable) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (cameras.isEmpty()) cameras = engine.cameras()
        val camera = cameras.getOrNull(cameraIndex.coerceIn(0, (cameras.size - 1).coerceAtLeast(0)))
        if (camera == null) {
            say(getString(R.string.no_camera), stay = true)
            return
        }
        ready = false
        if (extension != null && extension !in camera.extensions) extension = null
        engine.open(camera.id, viewfinder.surfaceTexture ?: return, if (videoMode || measureMode) null else extension, forVideo = videoMode)
    }

    // ------------------------------------------------------------------ touch

    private var dragFromEv: Int? = null
    private var dragFromY = 0f

    private fun setUpTouch() {
        val density = resources.displayMetrics.density
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            /** Tap: focus and set the brightness there. */
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val cam = info ?: return false
                if (extension != null) return false
                if (viewfinder.width == 0) return false
                val (sx, sy) = Geometry.viewToSensor(e.x / viewfinder.width, e.y / viewfinder.height, cam.sensorOrientation, cam.front)
                marks.focusAt(e.x, e.y)
                engine.focusAt(sx, sy)
                return true
            }

            /** Drag up or down: brighter or darker. */
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val cam = info ?: return false
                if (extension != null) return false
                val start = e1 ?: return false
                val moved = e2.y - start.y
                if (dragFromEv == null) {
                    if (abs(moved) < 24 * density || abs(moved) < 2 * abs(e2.x - start.x) || cam.evRange.upper <= cam.evRange.lower) return false
                    dragFromEv = ev
                    dragFromY = e2.y
                }
                val stops = -(e2.y - dragFromY) / (70 * density)
                val target = (dragFromEv!! + (stops / cam.evStep.toFloat()).roundToInt()).coerceIn(cam.evRange.lower, cam.evRange.upper)
                if (target != ev) {
                    ev = target
                    engine.setBrightness(ev)
                    val value = ev * cam.evStep.toFloat()
                    say(if (ev == 0) getString(R.string.brightness_auto) else getString(R.string.brightness, String.format(Locale.US, "%+.1f", value)))
                }
                return true
            }
        })
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoom(zoom * detector.scaleFactor)
                return true
            }
        })
        viewfinder.setOnTouchListener { v, event ->
            pinch.onTouchEvent(event)
            if (!pinch.isInProgress && event.pointerCount == 1) taps.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) dragFromEv = null
            if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    private fun setZoom(value: Float) {
        val cam = info ?: return
        if (!engine.zoomWorks) return
        val z = value.coerceIn(cam.minZoom, cam.maxZoom)
        if (abs(z - zoom) < 0.001f) return
        zoom = z
        engine.setZoom(z)
        showZoom()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.repeatCount == 0) { if (measureMode) freezeForMeasuring() else press() }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ measuring

    private fun enterMeasuring() {
        measureMode = true
        measure.unfreeze()
        startAiming()
        showMeasureBar()
    }

    private fun leaveMeasuring() {
        if (!measureMode) return
        stopTrack()
        measureMode = false
        stopAiming()
        measure.unfreeze()
        measure.aiming = false
        (measureBar.parent as View).visibility = View.GONE
        status.visibility = View.INVISIBLE
    }

    private val tilt = object : android.hardware.SensorEventListener {
        private val r = FloatArray(9)
        private var lastShown = 0L
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            if (e.sensor.type == android.hardware.Sensor.TYPE_ACCELEROMETER) {
                // Tilt only (no heading): from which way gravity pulls.
                val (gx, gy, gz) = Triple(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
                val g = kotlin.math.sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1e-6)
                aimDown = kotlin.math.asin((gz / g).coerceIn(-1.0, 1.0))
                aimHeading = 0.0
            } else {
                android.hardware.SensorManager.getRotationMatrixFromVector(r, e.values)
                val a = com.anycam.app.core.Measure.Aim.angles(r)
                aimDown = a[0]
                aimHeading = a[1]
            }
            val now = System.currentTimeMillis()
            if (now - lastShown > 150) {
                lastShown = now
                showAim()
            }
        }

        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
    }

    private fun startAiming() {
        val sensor = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            say(getString(R.string.measure_no_sensor), stay = true)
            return
        }
        measure.aiming = true
        sensors.registerListener(tilt, sensor, android.hardware.SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopAiming() {
        sensors.unregisterListener(tilt)
    }

    private fun showAim() {
        if (!measureMode || measure.photo != null || aimDown.isNaN()) return
        val h = cameraHeightCm / 100.0
        val base = baseDistance
        val start = startPoint
        val text = when {
            base != null -> getString(R.string.measure_height_at, com.anycam.app.core.Measure.label(com.anycam.app.core.Measure.Aim.heightAt(h, base, aimDown) * 1000), com.anycam.app.core.Measure.label(base * 1000))
            start != null -> com.anycam.app.core.Measure.Aim.floorPoint(h, aimDown, aimHeading)?.let {
                getString(R.string.measure_from_start, com.anycam.app.core.Measure.label(com.anycam.app.core.Measure.Aim.length(start, it) * 1000))
            } ?: getString(R.string.measure_aim)
            else -> com.anycam.app.core.Measure.Aim.distance(h, aimDown)?.let { getString(R.string.measure_floor, com.anycam.app.core.Measure.label(it * 1000)) }
                ?: getString(R.string.measure_aim)
        }
        say(text, stay = true)
    }

    private fun barButton(text: String, lit: Boolean = false, action: () -> Unit): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(getColor(if (lit) R.color.accent else R.color.white))
            setBackgroundResource(R.drawable.chip_bg)
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                action()
            }
        }
    }

    private fun addToBar(v: View) {
        val density = resources.displayMetrics.density
        measureBar.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (34 * density).toInt()).apply {
            setMargins((3 * density).toInt(), 0, (3 * density).toInt(), 0)
        })
    }

    /** The tools for what is being measured: by aiming (live), or on a frozen photo. */
    private fun showMeasureBar() {
        measureBar.removeAllViews()
        (measureBar.parent as View).visibility = if (measureMode) View.VISIBLE else View.GONE
        if (!measureMode) return
        if (measure.photo == null) {
            addToBar(barButton(getString(R.string.measure_height, com.anycam.app.core.Measure.label(cameraHeightCm * 10.0))) { askCameraHeight() })
            addToBar(barButton(getString(R.string.measure_base), lit = baseDistance != null) {
                val d = com.anycam.app.core.Measure.Aim.distance(cameraHeightCm / 100.0, aimDown)
                baseDistance = if (baseDistance == null) d else null
                startPoint = null
                showMeasureBar()
                showAim()
            })
            addToBar(barButton(getString(R.string.measure_start), lit = startPoint != null) {
                startPoint = if (startPoint == null) com.anycam.app.core.Measure.Aim.floorPoint(cameraHeightCm / 100.0, aimDown, aimHeading) else null
                baseDistance = null
                showMeasureBar()
                showAim()
            })
            // The 3D test only while it records (it is started by holding the Measure button).
            if (track.running) addToBar(barButton(getString(R.string.measure_track_stop), lit = true) {
                stopTrack()
                showMeasureBar()
            })
        } else {
            addToBar(barButton(measure.reference.label, lit = true) {
                val all = com.anycam.app.core.Measure.Reference.values()
                measure.reference = all[(measure.reference.ordinal + 1) % all.size]
                showMeasureBar()
                showPhotoMeasures()
            })
            addToBar(barButton(getString(R.string.measure_add)) { measure.addLine() })
            addToBar(barButton(getString(R.string.measure_save)) { saveMeasurement() })
            addToBar(barButton(getString(R.string.measure_retake)) {
                measure.unfreeze()
                measure.aiming = true
                startAiming()
                showMeasureBar()
            })
        }
    }

    /** The 3D test: viewfinder frames and motion sensors recorded for checking motion tracking off the phone. */
    private fun startTrack() {
        if (track.running || viewfinder.width == 0) return
        try {
            trackFrame = android.graphics.Bitmap.createBitmap(480, 480 * viewfinder.height / viewfinder.width, android.graphics.Bitmap.Config.ARGB_8888)
            track.start(engine.cameraCharacteristics, previewSize, viewfinder.width, viewfinder.height, 480, 480 * viewfinder.height / viewfinder.width)
            engine.frameLog = { t, f, e, k, d -> track.frameInfo(t, f, e, k, d) }
            engine.holdFocus(true)
            stopAiming()
        } catch (e: Exception) {
            say(e.message ?: getString(R.string.not_saved))
        }
    }

    private fun stopTrack() {
        if (!track.running) return
        engine.frameLog = null
        engine.holdFocus(false)
        track.stop { uri, seconds ->
            runOnUiThread {
                val frames = track.frameCount
                if (uri == null) say(getString(R.string.not_saved)) else { trackCount++; say(getString(R.string.measure_track_saved, frames, seconds)) }
                if (measureMode && measure.photo == null && resumed) startAiming()
            }
        }
    }

    private fun askCameraHeight() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(cameraHeightCm.toString())
            selectAll()
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.measure_height_title)
            .setMessage(R.string.measure_height_hint)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().toIntOrNull()?.takeIf { it in 20..300 }?.let {
                    cameraHeightCm = it
                    getPreferences(MODE_PRIVATE).edit().putInt("cameraHeight", it).apply()
                    baseDistance = null
                    startPoint = null
                    showMeasureBar()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The viewfinder's picture, held still to measure on. */
    private fun freezeForMeasuring() {
        if (!measureMode || measure.photo != null) return
        val bmp = viewfinder.bitmap ?: return
        shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onShutter()
        stopAiming()
        measure.freeze(bmp)
        showMeasureBar()
        showPhotoMeasures()
    }

    private fun showPhotoMeasures() {
        if (measure.photo == null) return
        val name = measure.reference.label.lowercase(Locale.getDefault())
        if (!measure.found) say(getString(R.string.measure_tap, name), stay = true)
        else status.visibility = View.INVISIBLE
    }

    /** A tap on the frozen photo: find the card (or sheet) there, by itself. */
    private fun findCard(x: Float, y: Float) {
        val bmp = measure.photo ?: return
        Thread {
            val corners = try {
                val w = bmp.width
                val h = bmp.height
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                val sx = w / measure.width.toFloat()
                val sy = h / measure.height.toFloat()
                com.anycam.app.core.CardFinder.find(px, w, h, x * sx, y * sy)?.let { c -> FloatArray(8) { c[it] / if (it % 2 == 0) sx else sy } }
            } catch (e: Throwable) {
                null
            }
            runOnUiThread {
                if (measure.photo !== bmp) return@runOnUiThread
                if (corners != null) {
                    measure.setCorners(corners)
                    status.visibility = View.INVISIBLE
                } else {
                    measure.placeCardAt(x, y)
                    say(getString(R.string.measure_not_found))
                }
            }
        }.start()
    }

    private fun saveMeasurement() {
        val bmp = measure.render() ?: return
        Thread {
            val uri = try {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "ANYCAM_MEASURE_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date()) + ".jpg")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AnyCam")
                }
                contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.also { u ->
                    contentResolver.openOutputStream(u)?.use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (uri == null) say(getString(R.string.not_saved)) else {
                    lastPhoto = uri
                    lastIsVideo = false
                    measureCount++
                    say(getString(R.string.measure_saved))
                    showThumbnail(uri)
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ photos

    /** The shutter: record or stop in video mode; a photo otherwise (after the self-timer, if set). */
    private fun press() {
        if (info == null) return
        if (videoMode) {
            shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (engine.recording) engine.stopRecording()
            else {
                val cam = info ?: return
                val sound = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (!sound) say(getString(R.string.no_sound))
                engine.startRecording(Geometry.photoRotation(cam.sensorOrientation, deviceDegrees, cam.front), sound)
            }
            return
        }
        if (countdown > 0) {
            // A second press stops the count.
            cancelCountdown()
            say(getString(R.string.timer_off))
            return
        }
        if (timerSeconds > 0 && !taking) {
            countdown = timerSeconds
            tick.run()
            return
        }
        takePhoto()
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (countdown <= 0) return
            say(getString(R.string.counting, countdown), stay = true)
            countdown--
            if (countdown == 0) shutter.postDelayed({ if (resumed) { status.visibility = View.INVISIBLE; takePhoto() } }, 1000)
            else shutter.postDelayed(this, 1000)
        }
    }

    private fun cancelCountdown() {
        countdown = 0
        shutter.removeCallbacks(tick)
    }

    private fun showTimerAndGrid() {
        timerButton.text = if (timerSeconds == 0) "" else timerSeconds.toString()
        timerButton.contentDescription = if (timerSeconds == 0) getString(R.string.timer_off) else getString(R.string.timer_on, timerSeconds)
        val timerColor = getColor(if (timerSeconds > 0) R.color.accent else R.color.white)
        timerButton.setTextColor(timerColor)
        timerButton.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(timerColor)
        gridButton.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(getColor(if (marks.grid) R.color.accent else R.color.white))
        val photo = !videoMode
        timerButton.visibility = if (photo) View.VISIBLE else View.GONE
    }

    private val elapsed: Runnable = object : Runnable {
        override fun run() {
            if (!engine.recording) return
            say(getString(R.string.recording, clock(((System.currentTimeMillis() - recordingSince) / 1000).toInt())), stay = true)
            status.postDelayed(this, 500)
        }
    }

    private fun clock(seconds: Int) = String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)

    override fun onRecording(on: Boolean) {
        shutter.recording = on
        switchButton.alpha = if (on) 0.35f else 1f
        modeRow.alpha = if (on) 0.35f else 1f
        if (on) {
            recordingSince = System.currentTimeMillis()
            elapsed.run()
        } else status.removeCallbacks(elapsed)
    }

    override fun onVideo(uri: Uri?, seconds: Int) {
        if (uri == null) {
            say(getString(R.string.video_not_saved))
            return
        }
        lastPhoto = uri
        lastIsVideo = true
        videoCount++
        status.removeCallbacks(elapsed)
        status.visibility = View.INVISIBLE
        showThumbnail(uri)
    }

    private fun takePhoto() {
        val cam = info ?: return
        if (taking) return
        taking = true
        shutter.busy = true
        shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (extension != null) {
            // The phone's own processing takes and merges its own shots.
            say(getString(R.string.phone_working), stay = true)
            engine.takeBurst(1, Geometry.photoRotation(cam.sensorOrientation, deviceDegrees, cam.front))
            return
        }
        val size = photoSize
        // In dim light: faster, noisier shots, and more of them (sharper, and the merge cleans them).
        val iso = liveIso
        val exposure = liveExposure
        val isoRange = cam.isoRange
        val exposureRange = cam.exposureRange
        val plan = if (iso != null && exposure != null && isoRange != null && exposureRange != null && !torch) {
            Exposure.night(iso, exposure, isoRange.lower, isoRange.upper, exposureRange.upper, cam.stabilised)
                ?: Exposure.plan(iso, exposure, isoRange.lower, isoRange.upper, exposureRange.lower, exposureRange.upper, cam.stabilised)
        } else null
        // Sensor frames where the camera gives them, zoomed too (the view is cut out of them:
        // a cleaner, more detailed zoom than the phone's from one processed frame).
        val raw = engine.rawWorks && !sensorOff && Geometry.sensorZoomWorks(zoom, engine.rawWidth)
        val zoomedIn = raw && zoom > 1.01f
        val wanted = (plan?.frames ?: Geometry.burstFrames(iso)).let { if (zoomedIn) Geometry.zoomedFrames(it, zoom) else it }
        // Kept frames are the zoomed part only; the merge's work is on that part too.
        val kept = if (zoomedIn) (engine.rawPixels / (zoom * zoom)).toLong() else engine.rawPixels
        val count = if (raw) Geometry.framesThatFit(wanted, kept, Runtime.getRuntime().maxMemory(), bytesPerPixel = 2f, workBytesPerPixel = 7f)
        else Geometry.framesThatFit(wanted, size.width.toLong() * size.height, Runtime.getRuntime().maxMemory())
        if (count > 3) say(getString(R.string.hold_still))
        engine.takeBurst(count, Geometry.photoRotation(cam.sensorOrientation, deviceDegrees, cam.front), plan?.let { it.iso to it.exposureNs }, raw)
    }

    private var photoSize = Size(0, 0)

    override fun onOpened(info: CameraInfo, previewSize: Size, photoSize: Size) {
        this.info = info
        this.photoSize = photoSize
        this.previewSize = previewSize
        ev = 0
        zoom = zoom.coerceIn(info.minZoom, info.maxZoom)
        // Shown upright: the sensor's long side runs down the screen.
        viewfinder.setShape(previewSize.height, previewSize.width)
        buildZoomRow(info)
        buildModeRow(info)
        shutter.video = videoMode
        showMeasureBar()
        shutter.recording = false
        showTimerAndGrid()
        showTorch()
        switchButton.visibility = if (cameras.size > 1) View.VISIBLE else View.INVISIBLE
        if (!videoMode && !measureMode) status.visibility = View.INVISIBLE
        ready = true
    }

    override fun onShutter() {
        flash.alpha = 0.7f
        flash.animate().alpha(0f).setDuration(160).start()
    }

    override fun onBurst(frames: List<Yuv>, shot: CameraEngine.Shot) {
        // The camera is free again at once; the photo is finished in the background.
        taking = false
        shutter.busy = false
        making++
        thumbnail.alpha = 0.5f
        working.visibility = View.VISIBLE
        showShot(frames.firstOrNull(), shot.rotation)
        photos.make(frames, shot) { photo ->
            making--
            if (making == 0) { thumbnail.alpha = 1f; working.visibility = View.INVISIBLE }
            if (photo == null) {
                say(getString(R.string.not_saved))
                return@make
            }
            lastPhoto = photo.uri
            lastIsVideo = false
            lastMerged = photo.merged
            photoCount++
            showThumbnail(photo.uri)
        }
    }

    override fun onRawBurst(frames: List<RawFrame>, guide: Yuv?, info: RawInfo, shot: CameraEngine.Shot) {
        taking = false
        shutter.busy = false
        making++
        thumbnail.alpha = 0.5f
        working.visibility = View.VISIBLE
        showShot(guide, shot.rotation)
        photos.makeRaw(frames, guide, info, shot) { photo ->
            making--
            if (making == 0) { thumbnail.alpha = 1f; working.visibility = View.INVISIBLE }
            if (photo == null) {
                say(getString(R.string.not_saved))
                return@makeRaw
            }
            lastPhoto = photo.uri
            lastIsVideo = false
            lastMerged = photo.merged
            lastFromSensor = photo.fromSensor
            // This phone's sensor frames didn't develop into a trustworthy photo: ordinary frames from now on.
            if (photo.sensorFailed) sensorOff = true
            photoCount++

            showThumbnail(photo.uri)
        }
    }

    override fun onJpeg(jpeg: ByteArray, shot: CameraEngine.Shot) {
        taking = false
        shutter.busy = false
        making++
        thumbnail.alpha = 0.5f
        working.visibility = View.VISIBLE
        photos.keep(jpeg, shot) { photo ->
            making--
            if (making == 0) { thumbnail.alpha = 1f; working.visibility = View.INVISIBLE }
            if (photo == null) {
                say(getString(R.string.not_saved))
                return@keep
            }
            lastPhoto = photo.uri
            lastIsVideo = false
            lastMerged = 1
            photoCount++
            showThumbnail(photo.uri)
        }
    }

    override fun onCaptureFailed(reason: String) {
        taking = false
        shutter.busy = false
        say(reason)
    }

    override fun onError(message: String) {
        if (extension != null) {
            // The phone's own processing wouldn't start: back to AnyCam's.
            extension = null
            say(message)
            openCamera()
            return
        }
        say(message, stay = true)
    }

    override fun onLive(iso: Int?, exposureNs: Long?) {
        liveIso = iso
        liveExposure = exposureNs
        readout.text = listOfNotNull(
            iso?.let { "ISO $it" },
            exposureNs?.takeIf { it > 0 }?.let { ns -> if (ns >= 500_000_000L) String.format(Locale.US, "%.1f s", ns / 1e9) else "1/${(1e9 / ns).roundToInt()}" },
        ).joinToString("  ·  ")
    }

    override fun onFocused(sharp: Boolean) = marks.focused(sharp)

    /**
     * The shot on the thumbnail the moment it is taken, as the phone's own camera does; the
     * finished photo replaces it when it is ready (seconds later, for a merged one).
     */
    private fun showShot(frame: Yuv?, rotation: Int) {
        frame ?: return
        Thread {
            val bmp = try {
                Thumb.of(frame, rotation, 160)
            } catch (e: Throwable) {
                null
            }
            runOnUiThread { if (bmp != null && making > 0) thumbnail.setImageBitmap(bmp) }
        }.start()
    }

    private fun showThumbnail(uri: Uri) {
        Thread {
            val bmp = try {
                contentResolver.loadThumbnail(uri, Size(200, 200), null)
            } catch (e: Exception) {
                null
            }
            runOnUiThread { if (bmp != null) thumbnail.setImageBitmap(bmp) }
        }.start()
    }

    // ------------------------------------------------------------------ small things on screen

    private fun buildZoomRow(cam: CameraInfo) {
        zoomRow.removeAllViews()
        val stops = Geometry.zoomStops(cam.minZoom, cam.maxZoom)
        zoomRow.visibility = if (stops.size > 1 && (extension == null || engine.zoomWorks)) View.VISIBLE else View.GONE
        val size = (40 * resources.displayMetrics.density).toInt()
        val gap = (4 * resources.displayMetrics.density).toInt()
        for (stop in stops) {
            val chip = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                tag = stop
                contentDescription = getString(R.string.zoom_to, label(stop))
                setBackgroundResource(R.drawable.chip_bg)
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    setZoom(stop)
                }
            }
            zoomRow.addView(chip, LinearLayout.LayoutParams(size, size).apply { setMargins(gap, 0, gap, 0) })
        }
        showZoom()
    }

    /** AnyCam's own way first, then each kind of processing the phone maker offers on this camera. */
    private fun buildModeRow(cam: CameraInfo) {
        modeRow.removeAllViews()
        modeRow.visibility = View.VISIBLE
        val density = resources.displayMetrics.density
        for (mode in listOf<Int?>(null, VIDEO, MEASURE) + cam.extensions.sortedBy { MODE_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }) {
            val on = when {
                videoMode -> mode == VIDEO
                measureMode -> mode == MEASURE
                else -> mode == extension
            }
            val chip = TextView(this).apply {
                text = modeName(mode)
                textSize = 14f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(getColor(if (on) R.color.accent else R.color.white))
                if (on) setBackgroundResource(R.drawable.chip_bg)
                setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
                // Holding Measure (in Measure mode) starts or stops the 3D test recording.
                if (mode == MEASURE) setOnLongClickListener {
                    if (!measureMode || measure.photo != null) return@setOnLongClickListener false
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    if (track.running) stopTrack() else startTrack()
                    showMeasureBar()
                    true
                }
                setOnClickListener {
                    if (taking || engine.recording || on) return@setOnClickListener
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    cancelCountdown()
                    leaveMeasuring()
                    if (mode == MEASURE) {
                        videoMode = false
                        extension = null
                        enterMeasuring()
                    } else if (mode == VIDEO) {
                        videoMode = true
                        extension = null
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED && !askedAudio) {
                            askedAudio = true
                            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_REQUEST)
                        }
                    } else {
                        videoMode = false
                        extension = mode
                    }
                    openCamera()
                }
            }
            modeRow.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (36 * density).toInt()))
        }
    }

    private var askedAudio = false

    private fun modeName(mode: Int?): String = when (mode) {
        null -> getString(R.string.mode_photo)
        VIDEO -> getString(R.string.mode_video)
        MEASURE -> getString(R.string.mode_measure)
        EXT_NIGHT -> getString(R.string.mode_night)
        EXT_HDR -> getString(R.string.mode_hdr)
        EXT_BOKEH -> getString(R.string.mode_portrait)
        EXT_AUTO -> getString(R.string.mode_auto)
        EXT_BEAUTY -> getString(R.string.mode_beauty)
        else -> getString(R.string.mode_other, mode)
    }

    /** The button of the widest step at or below the current zoom is lit and shows the exact zoom. */
    private fun showZoom() {
        var lit: TextView? = null
        for (i in 0 until zoomRow.childCount) {
            val chip = zoomRow.getChildAt(i) as TextView
            val stop = chip.tag as Float
            chip.text = label(stop).removeSuffix("×")
            chip.setTextColor(getColor(R.color.white))
            if (stop <= zoom + 0.01f) lit = chip
        }
        lit?.let {
            it.text = label(zoom)
            it.setTextColor(getColor(R.color.accent))
        }
    }

    private fun label(z: Float) =
        if (abs(z - z.roundToInt()) < 0.05f) "${z.roundToInt()}×" else String.format(Locale.US, "%.1f×", z)

    private fun showTorch() {
        val has = info?.hasFlash == true
        torchButton.alpha = if (has) 1f else 0.35f
        val color = getColor(if (torch && has) R.color.accent else R.color.white)
        torchButton.setTextColor(color)
        torchButton.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(color)
        torchButton.contentDescription = getString(if (torch && has) R.string.light_on else R.string.light_off)
    }

    private val hideStatus = Runnable { status.animate().alpha(0f).setDuration(250).withEndAction { status.visibility = View.INVISIBLE }.start() }

    /** A line of text over the viewfinder; gone after a moment unless it has to [stay]. */
    private fun say(text: String, stay: Boolean = false) {
        status.removeCallbacks(hideStatus)
        status.animate().cancel()
        status.text = text
        status.alpha = 1f
        status.visibility = View.VISIBLE
        if (!stay) status.postDelayed(hideStatus, 2200)
    }

    companion object {
        /** For tests: the camera is open and showing. */
        @Volatile var ready = false
        @Volatile var photoCount = 0
        @Volatile var lastMerged = 0
        @Volatile var lastFromSensor = false
        @Volatile var videoCount = 0
        @Volatile var measureCount = 0
        @Volatile var trackCount = 0
        /** Video in the row of modes (not one of Android's extension numbers, which start at 0). */
        private const val VIDEO = -1
        private const val MEASURE = -2
        private const val AUDIO_REQUEST = 2

        // Android's names for the kinds of processing a phone maker can offer other apps.
        private const val EXT_AUTO = 0
        private const val EXT_BEAUTY = 1
        private const val EXT_BOKEH = 2
        private const val EXT_HDR = 3
        private const val EXT_NIGHT = 4
        private val MODE_ORDER = listOf(EXT_NIGHT, EXT_HDR, EXT_BOKEH, EXT_AUTO, EXT_BEAUTY)
    }
}
