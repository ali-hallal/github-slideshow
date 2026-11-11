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
    private lateinit var flash: View

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
        flash = findViewById(R.id.flash)

        engine = CameraEngine(this, this)
        photos = PhotoMaker(this)

        viewfinder.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        setUpTouch()
        shutter.setOnClickListener { takePhoto() }
        switchButton.setOnClickListener {
            if (cameras.size < 2 || taking) return@setOnClickListener
            cameraIndex = (cameraIndex + 1) % cameras.size
            zoom = 1f
            torch = false
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
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
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
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera()
        else say(getString(R.string.needs_camera), stay = true)
    }

    override fun onPause() {
        resumed = false
        ready = false
        orientation.disable()
        engine.close()
        taking = false
        shutter.busy = false
        info = null
        super.onPause()
    }

    override fun onDestroy() {
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
        engine.open(camera.id, viewfinder.surfaceTexture ?: return)
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
                if (viewfinder.width == 0) return false
                val (sx, sy) = Geometry.viewToSensor(e.x / viewfinder.width, e.y / viewfinder.height, cam.sensorOrientation, cam.front)
                marks.focusAt(e.x, e.y)
                engine.focusAt(sx, sy)
                return true
            }

            /** Drag up or down: brighter or darker. */
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val cam = info ?: return false
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
        val z = value.coerceIn(cam.minZoom, cam.maxZoom)
        if (abs(z - zoom) < 0.001f) return
        zoom = z
        engine.setZoom(z)
        showZoom()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.repeatCount == 0) takePhoto()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ photos

    private fun takePhoto() {
        val cam = info ?: return
        if (taking) return
        taking = true
        shutter.busy = true
        shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val size = photoSize
        // In dim light: faster, noisier shots, and more of them (sharper, and the merge cleans them).
        val iso = liveIso
        val exposure = liveExposure
        val isoRange = cam.isoRange
        val exposureRange = cam.exposureRange
        val plan = if (iso != null && exposure != null && isoRange != null && exposureRange != null && !torch) {
            Exposure.plan(iso, exposure, isoRange.lower, isoRange.upper, exposureRange.lower, exposureRange.upper)
        } else null
        val wanted = plan?.frames ?: Geometry.burstFrames(iso)
        val count = Geometry.framesThatFit(wanted, size.width.toLong() * size.height, Runtime.getRuntime().maxMemory())
        if (count > 3) say(getString(R.string.hold_still))
        engine.takeBurst(count, Geometry.photoRotation(cam.sensorOrientation, deviceDegrees, cam.front), plan?.let { it.iso to it.exposureNs })
    }

    private var photoSize = Size(0, 0)

    override fun onOpened(info: CameraInfo, previewSize: Size, photoSize: Size) {
        this.info = info
        this.photoSize = photoSize
        ev = 0
        zoom = zoom.coerceIn(info.minZoom, info.maxZoom)
        // Shown upright: the sensor's long side runs down the screen.
        viewfinder.setShape(previewSize.height, previewSize.width)
        buildZoomRow(info)
        showTorch()
        switchButton.visibility = if (cameras.size > 1) View.VISIBLE else View.INVISIBLE
        status.visibility = View.INVISIBLE
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
        say(if (frames.size > 1) getString(R.string.merging, frames.size) else getString(R.string.saving), stay = true)
        photos.make(frames, shot) { photo ->
            making--
            if (making == 0) thumbnail.alpha = 1f
            if (photo == null) {
                say(getString(R.string.not_saved))
                return@make
            }
            lastPhoto = photo.uri
            lastMerged = photo.merged
            photoCount++
            say(if (photo.merged > 1) getString(R.string.merged, photo.merged) else getString(R.string.saved))
            showThumbnail(photo.uri)
        }
    }

    override fun onCaptureFailed(reason: String) {
        taking = false
        shutter.busy = false
        say(reason)
    }

    override fun onError(message: String) {
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
        zoomRow.visibility = if (stops.size > 1) View.VISIBLE else View.GONE
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
        torchButton.text = getString(if (torch && has) R.string.light_on else R.string.light_off)
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
    }
}
