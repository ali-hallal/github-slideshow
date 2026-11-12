package com.anycam.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import com.anycam.app.core.RawFrame
import com.anycam.app.core.RawInfo
import com.anycam.app.core.Yuv
import kotlin.math.abs

/**
 * The camera itself: viewfinder, focus, zoom, and bursts of full-size frames.
 *
 * Uses only what Android requires every camera to provide (a viewfinder stream plus full-size
 * YUV frames, auto exposure and focus), so it behaves the same on any phone. Everything runs on
 * one camera thread; the [Listener] is called on the main thread.
 */
class CameraEngine(context: Context, private val listener: Listener) {

    interface Listener {
        fun onOpened(info: CameraInfo, previewSize: Size, photoSize: Size)
        /** The first shot of a burst is being exposed. */
        fun onShutter()
        /** A burst is in: [frames] in the order taken. */
        fun onBurst(frames: List<Yuv>, shot: Shot)
        fun onCaptureFailed(reason: String)
        fun onError(message: String)
        /** What auto exposure is using right now (null where the phone doesn't report it). */
        fun onLive(iso: Int?, exposureNs: Long?)
        /** Touch focus finished: sharp or not. */
        fun onFocused(sharp: Boolean)
        /**
         * A burst of sensor (RAW) frames is in, with what the camera says about them and, if it
         * came, one ordinary frame of the same scene ([guide]) to take the look from.
         */
        fun onRawBurst(frames: List<RawFrame>, guide: Yuv?, info: RawInfo, shot: Shot)
        /** A photo made by the phone maker's own processing (an extension), ready to save. */
        fun onJpeg(jpeg: ByteArray, shot: Shot)
    }

    /** How a burst was taken. */
    class Shot(val rotation: Int, val iso: Int?, val exposureNs: Long?, val takenAt: Long)

    private val manager = context.getSystemService(CameraManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("anycam-camera").apply { start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    /** Set instead of [session] while the phone maker's own processing is in use. */
    private var extensionSession: CameraExtensionSession? = null
    private var extensionZoom = false
    private var reader: ImageReader? = null
    /** Sensor (RAW) frames, on cameras that give them. */
    private var rawReader: ImageReader? = null
    private var characteristics: CameraCharacteristics? = null
    private var shadingMap = false
    /** Sensor frames can be taken right now. */
    @Volatile var rawWorks = false
        private set
    /** The size of sensor frames (0 if none). */
    @Volatile var rawPixels = 0L
        private set
    private var previewSurface: Surface? = null
    private var info: CameraInfo? = null
    /** Bumped on every open/close: callbacks from an earlier camera are ignored. */
    private var generation = 0

    private var zoom = 1f
    private var ev = 0
    private var torch = false
    private var focusPoint: Pair<Float, Float>? = null
    private var focusing = false

    private var liveIso: Int? = null
    private var liveExposure: Long? = null

    // The burst in flight.
    private var burstWanted = 0
    private var burstFailed = 0
    private var burstRotation = 0
    private var burstStarted = false
    private var burstIso: Int? = null
    private var burstExposure: Long? = null
    private val burstFrames = ArrayList<Yuv>()
    private var burstRaw = false
    private val burstRawFrames = ArrayList<RawFrame>()
    private var burstGuide: Yuv? = null
    private var burstResult: TotalCaptureResult? = null

    /** The cameras to offer: the first back and the first front camera the phone lists. */
    fun cameras(): List<CameraInfo> = try {
        val all = manager.cameraIdList.mapNotNull { id ->
            try {
                val c = manager.getCameraCharacteristics(id)
                val usable = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) == true
                if (usable) CameraInfo.from(id, c, extensionsOf(id)) else null
            } catch (e: Exception) {
                null
            }
        }
        listOfNotNull(all.firstOrNull { !it.front }, all.firstOrNull { it.front }).ifEmpty { all.take(1) }
    } catch (e: Exception) {
        Log.e(TAG, "listing cameras", e)
        emptyList()
    }

    /**
     * The maker's processing modes this camera offers other apps, of those that deliver a
     * finished photo and a viewfinder. Asked of the phone; nothing is assumed about any brand.
     */
    private fun extensionsOf(id: String): List<Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        return try {
            val ext = manager.getCameraExtensionCharacteristics(id)
            ext.supportedExtensions.filter { e ->
                try {
                    ext.getExtensionSupportedSizes(e, ImageFormat.JPEG).isNotEmpty() &&
                        ext.getExtensionSupportedSizes(e, SurfaceTexture::class.java).isNotEmpty()
                } catch (e: Exception) {
                    false
                }
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /** [extension]: one of the camera's [CameraInfo.extensions] to use the maker's processing, or null for AnyCam's own. */
    @SuppressLint("MissingPermission")
    fun open(id: String, texture: SurfaceTexture, extension: Int? = null) = handler.post {
        closeNow()
        val mine = ++generation
        if (extension != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            openExtension(id, texture, extension, mine)
            return@post
        }
        try {
            val c = manager.getCameraCharacteristics(id)
            val cam = CameraInfo.from(id, c, extensionsOf(id))
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: throw IllegalStateException("no stream sizes")
            val photo = pickPhotoSize(map.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray())
                ?: throw IllegalStateException("no photo sizes")
            val preview = pickPreviewSize(map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray(), photo)
                ?: throw IllegalStateException("no viewfinder sizes")
            texture.setDefaultBufferSize(preview.width, preview.height)
            val surface = Surface(texture)
            val frames = ImageReader.newInstance(photo.width, photo.height, ImageFormat.YUV_420_888, MAX_BURST + 2)
            frames.setOnImageAvailableListener({ onImage(it) }, handler)
            // Sensor frames, where the camera gives them in a layout this app develops.
            val rawSize = if (c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true &&
                (c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: -1) in 0..3
            ) {
                map.getOutputSizes(ImageFormat.RAW_SENSOR)?.filter { it.width.toLong() * it.height <= MAX_PHOTO_PIXELS }
                    ?.maxByOrNull { it.width.toLong() * it.height }
            } else null
            val raw = rawSize?.let { size ->
                ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, MAX_BURST + 2).apply {
                    setOnImageAvailableListener({ onRawImage(it) }, handler)
                }
            }
            shadingMap = c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
                ?.contains(CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON) == true
            characteristics = c
            info = cam
            previewSurface = surface
            reader = frames
            rawReader = raw
            zoom = zoom.coerceIn(cam.minZoom, cam.maxZoom)
            ev = 0
            focusPoint = null
            Log.i(TAG, "opening camera $id: photo $photo, sensor frames $rawSize, viewfinder $preview, zoom ${cam.minZoom}..${cam.maxZoom}")
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (mine != generation) { d.close(); return }
                    device = d
                    startSession(d, surface, frames, cam, preview, photo, mine, withRaw = raw != null)
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    if (mine == generation) device = null
                }

                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    if (mine == generation) {
                        device = null
                        fail("The camera stopped (error $error)")
                    }
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "open", e)
            fail("Couldn't open the camera: ${e.message}")
        }
    }

    private fun startSession(d: CameraDevice, surface: Surface, frames: ImageReader, cam: CameraInfo, preview: Size, photo: Size, mine: Int, withRaw: Boolean) {
        val raw = rawReader
        // A phone that can't run all three streams together goes on without sensor frames.
        fun withoutRaw(why: String): Boolean {
            if (!withRaw || mine != generation) return false
            Log.w(TAG, "no sensor frames on this camera ($why)")
            try { raw?.close() } catch (_: Exception) {}
            rawReader = null
            startSession(d, surface, frames, cam, preview, photo, mine, withRaw = false)
            return true
        }
        try {
            val outputs = mutableListOf(OutputConfiguration(surface), OutputConfiguration(frames.surface))
            if (withRaw && raw != null) outputs += OutputConfiguration(raw.surface)
            d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, { handler.post(it) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (mine != generation) return
                        session = s
                        rawWorks = withRaw && raw != null
                        rawPixels = if (rawWorks && raw != null) raw.width.toLong() * raw.height else 0L
                        startPreview()
                        main.post { listener.onOpened(cam, preview, photo) }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (mine == generation && !withoutRaw("not configurable")) fail("The camera couldn't start")
                    }
                }))
        } catch (e: Exception) {
            Log.e(TAG, "session", e)
            if (!withoutRaw(e.message ?: "error")) fail("The camera couldn't start: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    @android.annotation.TargetApi(Build.VERSION_CODES.S)
    private fun openExtension(id: String, texture: SurfaceTexture, extension: Int, mine: Int) {
        try {
            val c = manager.getCameraCharacteristics(id)
            val cam = CameraInfo.from(id, c, extensionsOf(id))
            val ext = manager.getCameraExtensionCharacteristics(id)
            val photo = ext.getExtensionSupportedSizes(extension, ImageFormat.JPEG).maxByOrNull { it.width.toLong() * it.height }
                ?: throw IllegalStateException("no photo sizes")
            val preview = pickPreviewSize(ext.getExtensionSupportedSizes(extension, SurfaceTexture::class.java).toTypedArray(), photo)
                ?: throw IllegalStateException("no viewfinder sizes")
            extensionZoom = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && cam.zoomRatio != null &&
                ext.getAvailableCaptureRequestKeys(extension).contains(CaptureRequest.CONTROL_ZOOM_RATIO)
            texture.setDefaultBufferSize(preview.width, preview.height)
            val surface = Surface(texture)
            val photos = ImageReader.newInstance(photo.width, photo.height, ImageFormat.JPEG, 2)
            photos.setOnImageAvailableListener({ onExtensionPhoto(it) }, handler)
            info = cam
            previewSurface = surface
            reader = photos
            if (!extensionZoom) zoom = 1f
            ev = 0
            focusPoint = null
            Log.i(TAG, "opening camera $id with the maker's processing $extension: photo $photo, viewfinder $preview, zoom $extensionZoom")
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (mine != generation) { d.close(); return }
                    device = d
                    try {
                        val outputs = listOf(OutputConfiguration(surface), OutputConfiguration(photos.surface))
                        d.createExtensionSession(ExtensionSessionConfiguration(extension, outputs, { handler.post(it) },
                            object : CameraExtensionSession.StateCallback() {
                                override fun onConfigured(s: CameraExtensionSession) {
                                    if (mine != generation) return
                                    extensionSession = s
                                    startPreview()
                                    main.post { listener.onOpened(cam, preview, photo) }
                                }

                                override fun onConfigureFailed(s: CameraExtensionSession) {
                                    if (mine == generation) fail(EXTENSION_FAILED)
                                }
                            }))
                    } catch (e: Exception) {
                        Log.e(TAG, "extension session", e)
                        fail(EXTENSION_FAILED)
                    }
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    if (mine == generation) device = null
                }

                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    if (mine == generation) {
                        device = null
                        fail(EXTENSION_FAILED)
                    }
                }
            }, handler)
        } catch (e: Throwable) {
            Log.e(TAG, "open extension", e)
            fail(EXTENSION_FAILED)
        }
    }

    /** Whether zoom can be used right now (the maker's processing doesn't always allow it). */
    val zoomWorks get() = extensionSession == null || extensionZoom

    fun close() = handler.post { closeNow() }

    /** Close for good (the screen is going away). */
    fun release() {
        handler.post {
            closeNow()
            thread.quitSafely()
        }
    }

    private fun closeNow() {
        generation++
        handler.removeCallbacks(backToAuto)
        handler.removeCallbacks(burstTimeout)
        rawWorks = false
        try { rawReader?.close() } catch (_: Exception) {}
        rawReader = null
        if (burstWanted > 0) {
            burstWanted = 0
            burstFrames.clear()
            burstRawFrames.clear()
            burstGuide = null
            main.post { listener.onCaptureFailed("The camera closed") }
        }
        try { session?.close() } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) try { extensionSession?.close() } catch (_: Exception) {}
        extensionSession = null
        try { device?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { previewSurface?.release() } catch (_: Exception) {}
        session = null
        device = null
        reader = null
        previewSurface = null
        focusing = false
    }

    // ------------------------------------------------------------------ viewfinder

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            if (iso != liveIso || exposure != liveExposure) {
                liveIso = iso
                liveExposure = exposure
                main.post { listener.onLive(iso, exposure) }
            }
            if (focusing) {
                when (result.get(CaptureResult.CONTROL_AF_STATE)) {
                    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> focusDone(true)
                    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> focusDone(false)
                }
            }
        }
    }

    private fun focusDone(sharp: Boolean) {
        focusing = false
        main.post { listener.onFocused(sharp) }
    }

    private fun startPreview() {
        val d = device ?: return
        val surface = previewSurface ?: return
        val es = extensionSession
        if (es != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(surface)
                if (extensionZoom && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                es.setRepeatingRequest(b.build(), { handler.post(it) }, object : CameraExtensionSession.ExtensionCaptureCallback() {})
            } catch (e: Exception) {
                Log.e(TAG, "extension preview", e)
            }
            return
        }
        val s = session ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(surface)
            applyControls(b)
            s.setRepeatingRequest(b.build(), previewCallback, handler)
        } catch (e: Exception) {
            Log.e(TAG, "preview", e)
        }
    }

    /** The settings shared by the viewfinder and the shots, so a photo looks like what was framed. */
    private fun applyControls(b: CaptureRequest.Builder) {
        val cam = info ?: return
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev.coerceIn(cam.evRange.lower, cam.evRange.upper))
        if (cam.stabilised) b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        b.set(CaptureRequest.FLASH_MODE, if (torch && cam.hasFlash) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
        val point = focusPoint
        if (point != null && cam.touchFocus) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            val area = arrayOf(region(point.first, point.second, cam))
            if (cam.focusRegions) b.set(CaptureRequest.CONTROL_AF_REGIONS, area)
            if (cam.meterRegions) b.set(CaptureRequest.CONTROL_AE_REGIONS, area)
        } else if (cam.continuousFocus) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && cam.zoomRatio != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom.coerceIn(cam.minZoom, cam.maxZoom))
        } else {
            b.set(CaptureRequest.SCALER_CROP_REGION, cropFor(cam))
        }
    }

    /** The part of the sensor in view at the current zoom (older phones zoom by cropping). */
    private fun cropFor(cam: CameraInfo): Rect {
        val a = cam.activeArray
        if (cam.zoomRatio != null) return a
        val z = zoom.coerceIn(1f, cam.maxCropZoom)
        val w = (a.width() / z).toInt()
        val h = (a.height() / z).toInt()
        val left = a.left + (a.width() - w) / 2
        val top = a.top + (a.height() - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    /** A focus/metering area around a point of the view (0..1 of the sensor frame in view). */
    private fun region(sx: Float, sy: Float, cam: CameraInfo): MeteringRectangle {
        val view = cropFor(cam)
        val half = (minOf(view.width(), view.height()) * FOCUS_AREA / 2).toInt().coerceAtLeast(1)
        val cx = (view.left + sx * view.width()).toInt().coerceIn(view.left + half, view.right - half)
        val cy = (view.top + sy * view.height()).toInt().coerceIn(view.top + half, view.bottom - half)
        return MeteringRectangle(Rect(cx - half, cy - half, cx + half, cy + half), MeteringRectangle.METERING_WEIGHT_MAX)
    }

    fun setZoom(value: Float) = handler.post {
        val cam = info ?: return@post
        val z = value.coerceIn(cam.minZoom, cam.maxZoom)
        if (abs(z - zoom) < 0.001f) return@post
        zoom = z
        startPreview()
    }

    fun setBrightness(evIndex: Int) = handler.post {
        ev = evIndex
        startPreview()
    }

    fun setTorch(on: Boolean) = handler.post {
        torch = on
        startPreview()
    }

    /** Focus and meter on a point ([sx], [sy]: 0..1 of the sensor's frame); back to automatic after a while. */
    fun focusAt(sx: Float, sy: Float) = handler.post {
        if (extensionSession != null) return@post // the maker's processing focuses by itself
        val cam = info ?: return@post
        val d = device ?: return@post
        val s = session ?: return@post
        val surface = previewSurface ?: return@post
        if (!cam.touchFocus) {
            main.post { listener.onFocused(true) }
            return@post
        }
        focusPoint = sx to sy
        try {
            startPreview()
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(surface)
            applyControls(b)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), previewCallback, handler)
            focusing = true
        } catch (e: Exception) {
            Log.w(TAG, "focus", e)
        }
        handler.removeCallbacks(backToAuto)
        handler.postDelayed(backToAuto, FOCUS_HOLD_MS)
    }

    private val backToAuto = Runnable {
        if (focusPoint == null) return@Runnable
        focusPoint = null
        focusing = false
        val d = device ?: return@Runnable
        val s = session ?: return@Runnable
        val surface = previewSurface ?: return@Runnable
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(surface)
            applyControls(b)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(b.build(), null, handler)
            startPreview()
        } catch (e: Exception) {
            Log.w(TAG, "focus reset", e)
        }
    }

    // ------------------------------------------------------------------ bursts

    /**
     * Takes [count] full-size shots in a row at one exposure. [rotation]: degrees to turn them
     * upright. [exposure]: ISO and shutter time (ns) to use instead of auto exposure's, on
     * cameras that take them. [raw]: take sensor frames (where [rawWorks]) instead of ordinary ones.
     */
    fun takeBurst(count: Int, rotation: Int, exposure: Pair<Int, Long>? = null, raw: Boolean = false) = handler.post {
        val cam = info
        val d = device
        val s = session
        val frames = reader
        val es = extensionSession
        if (es != null && d != null && frames != null && burstWanted == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            takeExtensionPhoto(d, es, frames, rotation)
            return@post
        }
        if (cam == null || d == null || s == null || frames == null || burstWanted > 0) {
            main.post { listener.onCaptureFailed("The camera isn't ready") }
            return@post
        }
        val n = count.coerceIn(1, MAX_BURST)
        val sensor = if (raw && rawWorks) rawReader else null
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(sensor?.surface ?: frames.surface)
            applyControls(b)
            if (sensor != null && shadingMap) b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            // One exposure and colour for the whole burst, so the shots can be laid on each other.
            if (exposure != null && cam.isoRange != null) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, exposure.first)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure.second)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, exposure.second)
                burstIso = exposure.first
                burstExposure = exposure.second
            } else {
                if (cam.canLockExposure) b.set(CaptureRequest.CONTROL_AE_LOCK, true)
                burstIso = liveIso
                burstExposure = liveExposure
            }
            if (cam.canLockWhiteBalance) b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            val request = b.build()
            // With sensor frames, the first shot also gives an ordinary frame: the phone's own
            // rendering of the scene, which the developed photo takes its look from.
            val first = if (sensor != null) {
                b.addTarget(frames.surface)
                b.build()
            } else request
            burstWanted = n
            burstFailed = 0
            burstStarted = false
            burstRotation = rotation
            burstFrames.clear()
            burstRaw = sensor != null
            burstRawFrames.clear()
            burstGuide = null
            burstResult = null
            val mine = generation
            s.captureBurst(List(n) { if (it == 0) first else request }, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (mine != generation || burstResult != null) return
                    burstResult = result
                    finishBurstIfComplete()
                }

                override fun onCaptureStarted(s: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                    if (mine != generation || burstStarted) return
                    burstStarted = true
                    main.post { listener.onShutter() }
                }

                override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    if (mine != generation) return
                    burstFailed++
                    finishBurstIfComplete()
                }
            }, handler)
            handler.postDelayed(burstTimeout, BURST_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "burst", e)
            burstWanted = 0
            main.post { listener.onCaptureFailed("Couldn't take the photo: ${e.message}") }
        }
    }

    /** One photo by the maker's processing (it takes and merges its own shots; this can last seconds). */
    @android.annotation.TargetApi(Build.VERSION_CODES.S)
    private fun takeExtensionPhoto(d: CameraDevice, es: CameraExtensionSession, photos: ImageReader, rotation: Int) {
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(photos.surface)
            b.set(CaptureRequest.JPEG_ORIENTATION, rotation)
            b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            if (extensionZoom && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
            burstWanted = 1
            burstFailed = 0
            burstStarted = false
            burstRotation = rotation
            burstIso = liveIso
            burstExposure = liveExposure
            val mine = generation
            es.capture(b.build(), { handler.post(it) }, object : CameraExtensionSession.ExtensionCaptureCallback() {
                override fun onCaptureStarted(session: CameraExtensionSession, request: CaptureRequest, timestamp: Long) {
                    if (mine != generation || burstStarted) return
                    burstStarted = true
                    main.post { listener.onShutter() }
                }

                override fun onCaptureFailed(session: CameraExtensionSession, request: CaptureRequest) {
                    if (mine != generation || burstWanted == 0) return
                    burstWanted = 0
                    handler.removeCallbacks(burstTimeout)
                    main.post { listener.onCaptureFailed("The phone's own processing couldn't take this photo") }
                }
            })
            handler.postDelayed(burstTimeout, EXTENSION_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "extension photo", e)
            burstWanted = 0
            main.post { listener.onCaptureFailed("Couldn't take the photo: ${e.message}") }
        }
    }

    private fun onExtensionPhoto(r: ImageReader) {
        val image = try { r.acquireNextImage() } catch (e: Exception) { null } ?: return
        val bytes = try {
            val buffer = image.planes[0].buffer
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (e: Throwable) {
            null
        } finally {
            image.close()
        }
        if (burstWanted == 0) return
        burstWanted = 0
        handler.removeCallbacks(burstTimeout)
        val shot = Shot(burstRotation, burstIso, burstExposure, System.currentTimeMillis())
        if (bytes == null) main.post { listener.onCaptureFailed("The camera didn't deliver the photo") }
        else main.post { listener.onJpeg(bytes, shot) }
    }

    private fun onImage(r: ImageReader) {
        val image = try { r.acquireNextImage() } catch (e: Exception) { null } ?: return
        try {
            if (burstWanted > 0 && burstRaw) burstGuide = image.toYuv()
            else if (burstWanted > 0) burstFrames += image.toYuv()
        } catch (e: Throwable) {
            // Out of memory on a frame: go on with the ones we have.
            Log.w(TAG, "frame dropped", e)
            if (!burstRaw) burstFailed++
        } finally {
            image.close()
        }
        finishBurstIfComplete()
    }

    private fun onRawImage(r: ImageReader) {
        val image = try { r.acquireNextImage() } catch (e: Exception) { null } ?: return
        try {
            if (burstWanted > 0 && burstRaw) burstRawFrames += image.toRawFrame()
        } catch (e: Throwable) {
            Log.w(TAG, "sensor frame dropped", e)
            burstFailed++
        } finally {
            image.close()
        }
        finishBurstIfComplete()
    }

    private fun finishBurstIfComplete() {
        if (burstWanted == 0) return
        if (burstRaw) {
            // All the sensor frames, what the camera says about them, and the ordinary frame.
            if (burstRawFrames.size + burstFailed < burstWanted || burstResult == null || burstGuide == null) return
        } else if (burstFrames.size + burstFailed < burstWanted) return
        deliverBurst()
    }

    /** Frames that never arrive must not leave the shutter stuck: go with what came in. */
    private val burstTimeout = Runnable { if (burstWanted > 0) deliverBurst() }

    private fun deliverBurst() {
        handler.removeCallbacks(burstTimeout)
        val frames = ArrayList(burstFrames)
        burstFrames.clear()
        burstWanted = 0
        val shot = Shot(burstRotation, burstIso, burstExposure, System.currentTimeMillis())
        if (burstRaw) {
            val sensorFrames = ArrayList(burstRawFrames)
            val guide = burstGuide
            val c = characteristics
            burstRawFrames.clear()
            burstGuide = null
            burstRaw = false
            when {
                sensorFrames.isNotEmpty() && c != null -> {
                    val about = rawInfo(c, burstResult)
                    main.post { listener.onRawBurst(sensorFrames, guide, about, shot) }
                }
                // No sensor frames after all: the ordinary frame is still a photo.
                guide != null -> main.post { listener.onBurst(listOf(guide), shot) }
                else -> main.post { listener.onCaptureFailed("The camera didn't deliver the photo") }
            }
            return
        }
        if (frames.isEmpty()) main.post { listener.onCaptureFailed("The camera didn't deliver the photo") }
        else main.post { listener.onBurst(frames, shot) }
    }

    private fun fail(message: String) {
        main.post { listener.onError(message) }
    }

    companion object {
        private const val TAG = "AnyCam"
        /** Most shots in one burst. */
        const val MAX_BURST = 8
        /** Photos up to this many pixels (bursts of larger frames wouldn't fit in memory). */
        private const val MAX_PHOTO_PIXELS = 16_800_000L
        private const val MAX_PREVIEW_SIDE = 1920
        private const val FOCUS_AREA = 0.12f
        private const val FOCUS_HOLD_MS = 4000L
        private const val BURST_TIMEOUT_MS = 8000L
        /** The maker's night processing can expose for many seconds. */
        private const val EXTENSION_TIMEOUT_MS = 40_000L
        /** Reported when the maker's processing won't start: the screen goes back to AnyCam's own. */
        const val EXTENSION_FAILED = "The phone's own processing isn't available right now"

        /** The largest 4:3 photo within [MAX_PHOTO_PIXELS] (4:3 is the whole sensor on nearly every phone). */
        fun pickPhotoSize(sizes: Array<Size>): Size? {
            val fits = sizes.filter { it.width.toLong() * it.height <= MAX_PHOTO_PIXELS }.ifEmpty { sizes.toList() }
            val fourThree = fits.filter { abs(it.width * 3 - it.height * 4) <= it.width / 50 }
            return (fourThree.ifEmpty { fits }).maxByOrNull { it.width.toLong() * it.height }
        }

        /** The largest viewfinder size of the photo's shape, up to [MAX_PREVIEW_SIDE]. */
        fun pickPreviewSize(sizes: Array<Size>, photo: Size): Size? {
            val ok = sizes.filter { it.width <= MAX_PREVIEW_SIDE && it.height <= MAX_PREVIEW_SIDE }.ifEmpty { sizes.toList() }
            val ratio = photo.width.toFloat() / photo.height
            val same = ok.filter { abs(it.width.toFloat() / it.height - ratio) < 0.02f }
            return (same.ifEmpty { ok }).maxByOrNull { it.width.toLong() * it.height }
        }
    }
}
