package com.procam.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.location.Location
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Camera2 engine. All camera state lives on a private background thread; public methods
 * post work onto it and listener callbacks are delivered on the main thread.
 */
class CameraController(context: Context, private val listener: Listener) {

    interface Listener {
        fun onCameraOpened(caps: CameraCaps, previewSize: Size)
        fun onShutter()
        fun onCaptureFinished(success: Boolean)
        fun onPhotoSaved(uri: Uri, mimeType: String)
        fun onLiveStats(iso: Int?, exposureNs: Long?, focusDiopters: Float?, afState: Int?)
        fun onRawUnavailable()
        /** Faces in preview coordinates (0..1 of the preview view), updated a few times a second. */
        fun onFaces(faces: List<RectF>)
        /** A burst taken with [takeBurst]: encoded photos, not yet saved. */
        fun onBurstCaptured(frames: List<ByteArray>, mimeType: String)
        fun onCameraError(message: String)
    }

    private enum class State { PREVIEW, WAIT_PRECAPTURE, WAIT_NON_PRECAPTURE, CAPTURING }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(CameraManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val saveExecutor = Executors.newSingleThreadExecutor()
    private val openLock = Semaphore(1)

    private var thread: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    private val sessionExecutor = Executor { r -> handler?.post(r) }

    // ---- camera-thread state ----
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var characteristics: CameraCharacteristics? = null
    private var caps: CameraCaps? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var rawInSession = false
    private var opening = false
    private var settings = ShotSettings()
    private var output = OutputPrefs()
    private var photoFormat = ImageFormat.JPEG
    private var aeLocked = false
    @Volatile private var location: Location? = null
    private var meteringRect: MeteringRectangle? = null
    /** Metering on the main face, used when the camera has no built-in face priority. */
    private var faceRect: MeteringRectangle? = null
    private var faceRectAt = 0L
    private var hadFaces = false
    private var stillCount = 1
    private var stillToMemory = false
    private var pendingStills = 0
    private var burstExpected = 0
    private val burstFrames = mutableListOf<ByteArray>()
    private var state = State.PREVIEW
    private var precaptureFrames = 0
    private var usedPrecapture = false
    private var captureOrientation = 0
    private var lastStatsAt = 0L
    private val rawImages = LinkedHashMap<Long, Image>()
    private val rawResults = LinkedHashMap<Long, TotalCaptureResult>()
    private val shotNames = LinkedHashMap<Long, String>()

    // ------------------------------------------------------------------ public API

    /** Camera ids usable as a normal camera, back cameras first. */
    fun listCameras(): List<String> = try {
        manager.cameraIdList.filter { id ->
            val c = manager.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) == true
        }.sortedBy { id ->
            if (manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
            ) 0 else 1
        }
    } catch (e: Exception) {
        Log.e(TAG, "listCameras", e)
        emptyList()
    }

    /** One back and one front camera (lenses are reached via zoom); falls back to all cameras. */
    fun primaryCameras(): List<String> {
        val all = listCameras()
        fun facing(id: String) = try {
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
        } catch (e: Exception) {
            null
        }
        val back = all.firstOrNull { facing(it) == CameraCharacteristics.LENS_FACING_BACK }
        val front = all.firstOrNull { facing(it) == CameraCharacteristics.LENS_FACING_FRONT }
        val primary = listOfNotNull(back, front)
        return primary.ifEmpty { all }
    }

    fun open(cameraId: String, texture: SurfaceTexture, initial: ShotSettings, outputPrefs: OutputPrefs) {
        Log.i(TAG, "open camera $cameraId")
        close()
        val t = HandlerThread("CameraThread").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        h.post {
            settings = initial
            output = outputPrefs
            openInternal(cameraId, texture)
        }
    }

    fun close() {
        val t = thread ?: return
        val h = handler
        val locked = try {
            openLock.tryAcquire(2500, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        h?.post { closeInternal() }
        t.quitSafely()
        try {
            t.join(3000)
        } catch (_: InterruptedException) {
        }
        thread = null
        handler = null
        if (locked) openLock.release()
    }

    fun update(newSettings: ShotSettings) {
        handler?.post {
            settings = newSettings
            if (state == State.PREVIEW) updateRepeating()
        }
    }

    /** Tap-to-focus/meter. [nx],[ny] are 0..1 in preview (portrait view) coordinates. */
    fun focusAt(nx: Float, ny: Float) {
        handler?.post { focusAtInternal(nx, ny) }
    }

    /** Focus + meter on a point, then lock exposure and focus there (AE/AF lock). */
    fun lockAt(nx: Float, ny: Float) {
        val h = handler ?: return
        h.post { focusAtInternal(nx, ny) }
        h.postDelayed({
            if (meteringRect != null) {
                aeLocked = true
                if (state == State.PREVIEW) updateRepeating()
            }
        }, AE_LOCK_DELAY_MS)
    }

    /** GPS position written into photos, or null to not tag them. */
    fun setLocation(loc: Location?) {
        location = loc
    }

    /** Back to continuous AF / center-weighted metering. */
    fun resetFocus() {
        handler?.post {
            meteringRect = null
            aeLocked = false
            sendAfTrigger(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            updateRepeating()
        }
    }

    /** [deviceOrientation] is the raw value from an [OrientationEventListener]. */
    fun takePicture(deviceOrientation: Int) = takeBurst(deviceOrientation, 1)

    /**
     * Takes [count] photos back to back. With count > 1 they are NOT saved: they are handed to
     * [Listener.onBurstCaptured] so the caller can keep the best one.
     */
    fun takeBurst(deviceOrientation: Int, count: Int, toMemory: Boolean = count > 1) {
        val h = handler
        if (h == null) {
            main.post { listener.onCaptureFinished(false) }
            return
        }
        h.post {
            stillCount = count.coerceIn(1, MAX_BURST)
            stillToMemory = toMemory || stillCount > 1
            takePictureInternal(deviceOrientation)
        }
    }

    // ------------------------------------------------------------------ open / close

    @SuppressLint("MissingPermission")
    private fun openInternal(cameraId: String, texture: SurfaceTexture) {
        try {
            val c = manager.getCameraCharacteristics(cameraId)
            characteristics = c
            val cp = CameraCaps.from(cameraId, c, manager)
            caps = cp
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IllegalStateException("No stream configuration map")

            val heicSizes = if (output.heif) map.getOutputSizes(ImageFormat.HEIC) else null
            photoFormat = if (!heicSizes.isNullOrEmpty()) ImageFormat.HEIC else ImageFormat.JPEG
            if (output.heif && photoFormat != ImageFormat.HEIC) postError("HEIF isn't supported on this camera, using JPEG")
            val jpegSize = pickPhotoSize(map.getOutputSizes(photoFormat))
            val previewSize = pickPreviewSize(map.getOutputSizes(SurfaceTexture::class.java), jpegSize)

            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurface = Surface(texture)

            jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, photoFormat, MAX_BURST + 1).apply {
                setOnImageAvailableListener({ onJpegAvailable(it) }, handler)
            }
            if (cp.raw) {
                map.getOutputSizes(ImageFormat.RAW_SENSOR)
                    ?.maxByOrNull { it.width.toLong() * it.height }
                    ?.let { size ->
                        rawReader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 3).apply {
                            setOnImageAvailableListener({ onRawAvailable(it) }, handler)
                        }
                    }
            }

            main.post { listener.onCameraOpened(cp, previewSize) }

            if (!openLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("Timed out waiting to open camera")
            }
            opening = true
            try {
                manager.openCamera(cameraId, deviceCallback, handler)
            } catch (e: Exception) {
                releaseOpenLock()
                throw e
            }
        } catch (e: Exception) {
            Log.e(TAG, "open failed", e)
            postError("Could not open camera: ${e.message}")
        }
    }

    private fun closeInternal() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        device?.close()
        device = null
        jpegReader?.close()
        jpegReader = null
        rawImages.values.forEach { it.close() }
        rawImages.clear()
        rawResults.clear()
        shotNames.clear()
        rawReader?.close()
        rawReader = null
        previewSurface?.release()
        previewSurface = null
        state = State.PREVIEW
        meteringRect = null
        faceRect = null
        aeLocked = false
        burstExpected = 0
        burstFrames.clear()
    }

    private fun releaseOpenLock() {
        if (opening) {
            opening = false
            openLock.release()
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            releaseOpenLock()
            device = camera
            createSession(withRaw = rawReader != null)
        }

        override fun onDisconnected(camera: CameraDevice) {
            releaseOpenLock()
            camera.close()
            device = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            releaseOpenLock()
            camera.close()
            device = null
            postError("Camera error ($error)")
        }
    }

    private fun createSession(withRaw: Boolean) {
        val d = device ?: return
        val preview = previewSurface ?: return
        val jpeg = jpegReader ?: return
        val outputs = mutableListOf(OutputConfiguration(preview), OutputConfiguration(jpeg.surface))
        val raw = rawReader
        if (withRaw && raw != null) outputs += OutputConfiguration(raw.surface)

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (device == null) return
                session = s
                rawInSession = withRaw && raw != null
                state = State.PREVIEW
                updateRepeating()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (withRaw) {
                    // Some devices can't do preview + JPEG + RAW together; fall back to JPEG only.
                    main.post { listener.onRawUnavailable() }
                    createSession(withRaw = false)
                } else {
                    postError("Failed to configure the camera")
                }
            }
        }
        try {
            d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, sessionExecutor, callback))
        } catch (e: Exception) {
            Log.e(TAG, "createSession", e)
            if (withRaw) {
                main.post { listener.onRawUnavailable() }
                createSession(withRaw = false)
            } else {
                postError("Failed to start the camera: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------ requests

    private fun updateRepeating() {
        val s = session ?: return
        try {
            val b = previewBuilder() ?: return
            s.setRepeatingRequest(b.build(), previewCallback, handler)
        } catch (e: Exception) {
            Log.w(TAG, "updateRepeating", e)
        }
    }

    private fun previewBuilder(): CaptureRequest.Builder? {
        val d = device ?: return null
        val surface = previewSurface ?: return null
        return d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            applySettings(this, still = false)
        }
    }

    private fun applySettings(b: CaptureRequest.Builder, still: Boolean) {
        val c = caps ?: return
        val s = settings
        val autoFocus = !(s.manualFocus && c.canManualFocus)
        val faces = c.faceDetectMode != CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
        if (faces) b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, c.faceDetectMode)
        // Face priority: the camera focuses, exposes and white-balances for faces it finds.
        // Only when the user hasn't picked a point or taken manual control.
        val facePriority = faces && c.facePriorityScene && s.cameraAe && autoFocus && meteringRect == null
        if (facePriority) {
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
            b.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY)
        } else {
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        }
        // No built-in face priority: meter on the main face ourselves.
        val face = if (!facePriority && meteringRect == null) faceRect else null

        // ---- exposure + flash
        if (!s.cameraAe && c.manualSensor) {
            var exposure = s.exposureNs
            var iso = s.iso
            if (!still && exposure > MAX_PREVIEW_EXPOSURE_NS) {
                // Keep the viewfinder fluid for long exposures: shorten the preview exposure
                // and raise ISO to keep the same brightness.
                val gain = exposure.toDouble() / MAX_PREVIEW_EXPOSURE_NS
                iso = min((iso * gain).toInt(), c.isoRange?.upper ?: iso)
                exposure = MAX_PREVIEW_EXPOSURE_NS
            }
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, exposure)
            val flashMode = when {
                !c.hasFlash -> CameraMetadata.FLASH_MODE_OFF
                s.flash == FlashMode.TORCH -> CameraMetadata.FLASH_MODE_TORCH
                still && s.flash == FlashMode.ON -> CameraMetadata.FLASH_MODE_SINGLE
                else -> CameraMetadata.FLASH_MODE_OFF
            }
            b.set(CaptureRequest.FLASH_MODE, flashMode)
        } else {
            val aeMode = when {
                !c.hasFlash -> CameraMetadata.CONTROL_AE_MODE_ON
                s.flash == FlashMode.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                s.flash == FlashMode.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                else -> CameraMetadata.CONTROL_AE_MODE_ON
            }
            b.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
            b.set(
                CaptureRequest.FLASH_MODE,
                if (c.hasFlash && s.flash == FlashMode.TORCH) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF,
            )
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, s.evIndex.coerceIn(c.evRange.lower, c.evRange.upper))
            val m = meteringRect ?: face
            if (m != null && c.maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(m))
            b.set(CaptureRequest.CONTROL_AE_LOCK, aeLocked)
        }

        // ---- zoom
        val zoom = s.zoom.coerceIn(c.zoomRange.lower, c.zoomRange.upper)
        if (c.zoomRatioApi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else if (zoom > 1.001f) {
            b.set(CaptureRequest.SCALER_CROP_REGION, cropRegion(c, zoom))
        }

        // ---- focus
        val m = meteringRect
        when {
            s.manualFocus && c.canManualFocus -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDiopters.coerceIn(0f, c.minFocusDistance))
            }
            m != null && c.afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) && c.maxAfRegions > 0 -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(m))
            }
            c.afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                if (face != null && c.maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(face))
            }
            c.afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            else -> b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        }

        // ---- optical image stabilisation
        if (c.ois) b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)

        // ---- white balance
        val awb = if (c.awbModes.contains(s.wb.awbMode)) s.wb.awbMode else CameraMetadata.CONTROL_AWB_MODE_AUTO
        b.set(CaptureRequest.CONTROL_AWB_MODE, awb)

        if (still) {
            b.set(CaptureRequest.JPEG_QUALITY, output.jpegQuality.coerceIn(1, 100).toByte())
            b.set(CaptureRequest.JPEG_ORIENTATION, captureOrientation)
            location?.let { b.set(CaptureRequest.JPEG_GPS_LOCATION, it) }
        }
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            when (state) {
                State.WAIT_PRECAPTURE -> {
                    val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                    precaptureFrames++
                    if (ae == null || ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                        ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                    ) {
                        state = State.WAIT_NON_PRECAPTURE
                    } else if (precaptureFrames > PRECAPTURE_TIMEOUT_FRAMES) {
                        captureStill()
                    }
                }
                State.WAIT_NON_PRECAPTURE -> {
                    val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                    precaptureFrames++
                    if (ae == null || ae != CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                        precaptureFrames > PRECAPTURE_TIMEOUT_FRAMES
                    ) {
                        captureStill()
                    }
                }
                else -> Unit
            }

            val now = SystemClock.uptimeMillis()
            if (now - lastStatsAt >= STATS_INTERVAL_MS) {
                lastStatsAt = now
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                val af = result.get(CaptureResult.CONTROL_AF_STATE)
                main.post { listener.onLiveStats(iso, exp, focus, af) }
                handleFaces(result.get(CaptureResult.STATISTICS_FACES), now)
            }
        }
    }

    private fun handleFaces(faces: Array<android.hardware.camera2.params.Face>?, now: Long) {
        val c = caps ?: return
        val list = faces?.filter { it.score >= 30 }.orEmpty()
        if (list.isEmpty() && !hadFaces) return
        hadFaces = list.isNotEmpty()
        val frame = sensorFrame(c)
        val viewRects = list.map { sensorToView(c, frame, it.bounds) }
        main.post { listener.onFaces(viewRects) }

        if (c.facePriorityScene || state != State.PREVIEW) return
        // Fallback face metering: follow the biggest face, but don't thrash the camera.
        val biggest = list.maxByOrNull { it.bounds.width() * it.bounds.height() }
        val old = faceRect
        if (biggest == null) {
            if (old != null && now - faceRectAt > FACE_HOLD_MS) {
                faceRect = null
                updateRepeating()
            }
            return
        }
        val moved = old == null || abs(old.rect.centerX() - biggest.bounds.centerX()) > biggest.bounds.width() / 4 ||
            abs(old.rect.centerY() - biggest.bounds.centerY()) > biggest.bounds.height() / 4
        if (moved && now - faceRectAt > FACE_UPDATE_MS) {
            faceRect = MeteringRectangle(biggest.bounds, MeteringRectangle.METERING_WEIGHT_MAX - 1)
            faceRectAt = now
            updateRepeating()
        } else if (!moved) {
            faceRectAt = now
        }
    }

    /** The coordinate frame that 3A regions and face rectangles use at the current zoom. */
    private fun sensorFrame(c: CameraCaps): Rect {
        val area = c.activeArray
        return if (!c.zoomRatioApi && settings.zoom > 1.001f) cropRegion(c, settings.zoom)
        else Rect(0, 0, area.width(), area.height())
    }

    /** Sensor rectangle → 0..1 rectangle in the (portrait) preview view. Inverse of the tap mapping. */
    private fun sensorToView(c: CameraCaps, frame: Rect, r: Rect): RectF {
        fun map(px: Int, py: Int): Pair<Float, Float> {
            var x = (px - frame.left).toFloat() / frame.width()
            var y = (py - frame.top).toFloat() / frame.height()
            repeat((c.sensorOrientation % 360) / 90) {
                val t = y
                y = x
                x = 1f - t
            }
            if (c.isFront) x = 1f - x
            return x to y
        }
        val (x1, y1) = map(r.left, r.top)
        val (x2, y2) = map(r.right, r.bottom)
        return RectF(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    }

    // ------------------------------------------------------------------ focus

    private fun focusAtInternal(nx: Float, ny: Float) {
        val c = caps ?: return
        if (session == null) return
        // Map portrait view coordinates onto the (landscape) sensor.
        var x = nx.coerceIn(0f, 1f)
        var y = ny.coerceIn(0f, 1f)
        if (c.isFront) x = 1f - x
        repeat(((c.sensorOrientation % 360) / 90)) {
            val t = x
            x = y
            y = 1f - t
        }
        // With CONTROL_ZOOM_RATIO, regions are relative to the zoomed view; with crop zoom they
        // are in full-sensor coordinates, so map into the crop.
        val frame = sensorFrame(c)
        val half = (min(frame.width(), frame.height()) * 0.08f).toInt()
        val cx = frame.left + (x * frame.width()).toInt()
        val cy = frame.top + (y * frame.height()).toInt()
        val rect = Rect(
            max(cx - half, frame.left),
            max(cy - half, frame.top),
            min(cx + half, frame.right - 1),
            min(cy + half, frame.bottom - 1),
        )
        aeLocked = false
        meteringRect = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        updateRepeating()
        if (!(settings.manualFocus && c.canManualFocus)) {
            sendAfTrigger(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            sendAfTrigger(CaptureRequest.CONTROL_AF_TRIGGER_START)
        }
    }

    private fun sendAfTrigger(trigger: Int) {
        val s = session ?: return
        try {
            val b = previewBuilder() ?: return
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
            s.capture(b.build(), previewCallback, handler)
        } catch (e: Exception) {
            Log.w(TAG, "af trigger", e)
        }
    }

    // ------------------------------------------------------------------ capture

    private fun takePictureInternal(deviceOrientation: Int) {
        val c = caps
        val s = session
        if (c == null || s == null || state != State.PREVIEW) {
            main.post { listener.onCaptureFinished(false) }
            return
        }
        captureOrientation = jpegOrientation(c, deviceOrientation)
        val needsPrecapture = settings.cameraAe && c.hasFlash &&
            (settings.flash == FlashMode.AUTO || settings.flash == FlashMode.ON)
        usedPrecapture = needsPrecapture
        if (!needsPrecapture) {
            captureStill()
            return
        }
        try {
            val b = previewBuilder() ?: return
            b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            state = State.WAIT_PRECAPTURE
            precaptureFrames = 0
            s.capture(b.build(), previewCallback, handler)
        } catch (e: Exception) {
            Log.w(TAG, "precapture", e)
            captureStill()
        }
    }

    private fun captureStill() {
        val d = device
        val s = session
        val jpeg = jpegReader
        if (d == null || s == null || jpeg == null) {
            state = State.PREVIEW
            main.post { listener.onCaptureFinished(false) }
            return
        }
        state = State.CAPTURING
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(jpeg.surface)
            val raw = rawReader
            val burst = stillToMemory
            if (!burst && settings.raw && rawInSession && raw != null) {
                b.addTarget(raw.surface)
                b.setTag(RAW_TAG)
            }
            applySettings(b, still = true)
            pendingStills = stillCount
            if (burst) {
                burstFrames.clear()
                burstExpected = stillCount
                if (stillCount == 1) s.capture(b.build(), stillCallback, handler)
                else s.captureBurst(List(stillCount) { b.build() }, stillCallback, handler)
            } else {
                burstExpected = 0
                s.capture(b.build(), stillCallback, handler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureStill", e)
            finishCapture(false)
        }
    }

    private val stillCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(s: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            if (pendingStills == stillCount) main.post { listener.onShutter() }
        }

        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
            if (ts != null && request.tag == RAW_TAG) {
                rawResults[ts] = result
                trimOldest(rawResults)
                tryWriteDng(ts)
            }
            if (--pendingStills <= 0) finishCapture(true)
        }

        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            Log.w(TAG, "capture failed: ${failure.reason}")
            if (burstExpected > 0) {
                burstExpected--
                if (burstExpected > 0 && burstFrames.size >= burstExpected) deliverBurst()
            }
            if (--pendingStills <= 0) finishCapture(burstFrames.isNotEmpty())
        }
    }

    private var burstMime = "image/jpeg"

    private fun deliverBurst() {
        val frames = burstFrames.toList()
        val mime = burstMime
        burstFrames.clear()
        burstExpected = 0
        main.post { listener.onBurstCaptured(frames, mime) }
    }

    private fun finishCapture(success: Boolean) {
        if (usedPrecapture) {
            try {
                val b = previewBuilder()
                if (b != null) {
                    b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL)
                    session?.capture(b.build(), null, handler)
                }
            } catch (_: Exception) {
            }
            usedPrecapture = false
        }
        state = State.PREVIEW
        updateRepeating()
        main.post { listener.onCaptureFinished(success) }
    }

    private fun onJpegAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (e: Exception) {
            null
        } ?: return
        val ts = image.timestamp
        val bytes = try {
            val buffer = image.planes[0].buffer
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } finally {
            image.close()
        }
        val heic = reader.imageFormat == ImageFormat.HEIC
        if (burstExpected > 0) {
            burstMime = if (heic) "image/heic" else "image/jpeg"
            burstFrames += bytes
            if (burstFrames.size >= burstExpected) deliverBurst()
            return
        }
        val name = nameFor(ts)
        val mime = if (heic) "image/heic" else "image/jpeg"
        val mirror = output.mirrorFront && caps?.isFront == true && !heic
        saveExecutor.execute {
            val uri = MediaSaver.save(
                appContext,
                if (heic) "$name.heic" else "$name.jpg",
                mime,
                writer = { it.write(bytes) },
                afterWrite = if (mirror) { u -> MediaSaver.mirrorJpeg(appContext, u) } else null,
            )
            if (uri != null) main.post { listener.onPhotoSaved(uri, mime) } else postError("Could not save photo")
        }
    }

    private fun onRawAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (e: Exception) {
            null
        } ?: return
        rawImages[image.timestamp] = image
        while (rawImages.size > 2) {
            val oldest = rawImages.keys.first()
            rawImages.remove(oldest)?.close()
        }
        tryWriteDng(image.timestamp)
    }

    private fun tryWriteDng(ts: Long) {
        val image = rawImages[ts] ?: return
        val result = rawResults[ts] ?: return
        val chars = characteristics ?: return
        rawImages.remove(ts)
        rawResults.remove(ts)
        val name = nameFor(ts)
        try {
            DngCreator(chars, result).use { dng ->
                dng.setOrientation(exifOrientation(captureOrientation))
                location?.let { dng.setLocation(it) }
                val uri = MediaSaver.save(appContext, "$name.dng", "image/x-adobe-dng", writer = { out ->
                    dng.writeImage(out, image)
                })
                if (uri != null) main.post { listener.onPhotoSaved(uri, "image/x-adobe-dng") } else postError("Could not save RAW")
            }
        } catch (e: Exception) {
            Log.e(TAG, "DNG", e)
            postError("Could not save RAW: ${e.message}")
        } finally {
            image.close()
        }
    }

    /** JPEG and DNG from the same shot share a file name. */
    private fun nameFor(ts: Long): String {
        val name = shotNames.getOrPut(ts) { MediaSaver.newBaseName() }
        trimOldest(shotNames)
        return name
    }

    private fun <V> trimOldest(map: LinkedHashMap<Long, V>) {
        while (map.size > 8) map.remove(map.keys.first())
    }

    // ------------------------------------------------------------------ helpers

    private fun postError(message: String) {
        main.post { listener.onCameraError(message) }
    }

    private fun jpegOrientation(c: CameraCaps, deviceOrientation: Int): Int {
        if (deviceOrientation == OrientationEventListener.ORIENTATION_UNKNOWN) return c.sensorOrientation
        var d = ((deviceOrientation + 45) / 90 * 90) % 360
        if (c.isFront) d = -d
        return (c.sensorOrientation + d + 360) % 360
    }

    private fun exifOrientation(degrees: Int): Int = when (degrees) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private fun pickPhotoSize(sizes: Array<Size>): Size {
        val ratio = if (output.aspect169) 16f / 9f else 4f / 3f
        val matching = sizes.filter { abs(it.width.toFloat() / it.height - ratio) < 0.02f }.ifEmpty { sizes.toList() }
        val byArea = matching.sortedBy { it.width.toLong() * it.height }
        if (byArea.isEmpty()) return Size(1920, 1080)
        if (output.maxResolution) return byArea.last()
        // "Standard" resolution: about 12 MP, the sweet spot for file size vs. detail.
        return byArea.lastOrNull { it.width.toLong() * it.height <= STANDARD_PIXELS } ?: byArea.first()
    }

    private fun cropRegion(c: CameraCaps, zoom: Float): Rect {
        val a = c.activeArray
        val w = (a.width() / zoom).toInt()
        val h = (a.height() / zoom).toInt()
        val left = (a.width() - w) / 2
        val top = (a.height() - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    private fun pickPreviewSize(choices: Array<Size>, photo: Size): Size {
        val target = photo.width.toFloat() / photo.height
        val fitting = choices.filter { it.width <= 1920 && it.height <= 1440 }
        return fitting.filter { abs(it.width.toFloat() / it.height - target) < 0.02f }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: fitting.maxByOrNull { it.width.toLong() * it.height }
            ?: choices.first()
    }

    companion object {
        private const val TAG = "ProCam"
        private const val RAW_TAG = "raw"
        private const val STANDARD_PIXELS = 12_600_000L
        private const val AE_LOCK_DELAY_MS = 700L
        private const val MAX_BURST = 5
        private const val FACE_UPDATE_MS = 500L
        private const val FACE_HOLD_MS = 1500L
        private const val MAX_PREVIEW_EXPOSURE_NS = 66_666_667L // 1/15 s
        private const val PRECAPTURE_TIMEOUT_FRAMES = 30
        private const val STATS_INTERVAL_MS = 200L
    }
}
