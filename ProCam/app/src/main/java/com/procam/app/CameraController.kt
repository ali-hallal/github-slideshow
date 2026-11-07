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
import android.util.Range
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
        /**
         * A fast burst taken with [takeFastBurst]: centre crops already converted to pixels, plus
         * the clockwise rotation that makes them upright.
         */
        fun onFastBurst(frames: List<SuperRes.Frame>, rotationDegrees: Int)
        /** "Never miss the moment": the frame picked from around the shutter press. */
        fun onMoment(frame: MomentBuffer.Frame, rotationDegrees: Int)
        fun onCameraError(message: String)
        /** Video recording started ([withSound] = microphone is recording too). */
        fun onRecordingStarted(withSound: Boolean) {}
        /**
         * RAW zoom burst done: the RAW frames (cropped to the zoomed view) with what's needed to
         * develop them, plus the JPEGs of the same shots (fallback, and the tone to match).
         * [capture] is null if the RAW frames didn't all arrive (then use the JPEGs).
         */
        fun onRawBurst(capture: RawBurstCapture?, jpegs: List<ByteArray>) {}
        /** Slow motion couldn't be set up on this camera ([reason] for diagnostics); video should fall back. */
        fun onSlowMotionFailed(reason: String) {}
        /** Video recording ended; [uri] is the saved video, or null if it couldn't be saved. */
        fun onRecordingStopped(uri: Uri?) {}
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
    private var burstExposure: Pair<Int, Long>? = null
    private var burstMinimal = false
    private var burstIntervalMs = 0L
    private var burstFocus: FloatArray? = null
    // RAW zoom burst
    private var rawBurstMode = false
    private var rawCrop = Rect()
    private var rawFullW = 0
    private var rawFullH = 0
    private val rawBurstFrames = LinkedHashMap<Long, ShortArray>()
    private val rawBurstResults = LinkedHashMap<Long, TotalCaptureResult>()
    private var rawBurstJpegs: List<ByteArray>? = null
    private var rawBurstCount = 0
    @Volatile private var lastPhysicalId: String? = null
    // Fast bursts: a video-size YUV stream (no JPEG encode/decode), centre-cropped on arrival.
    private var yuvReader: ImageReader? = null
    // Video
    private var videoRecorder: VideoRecorder? = null
    private var videoStabMode: Int? = null
    private var videoFps: Range<Int>? = null
    @Volatile private var yuvInSession = false
    private var burstToYuv = false
    private var yuvCrop = 1f
    private var yuvExpected = 0
    private val yuvFrames = mutableListOf<SuperRes.Frame>()
    // Moment buffer (Freeze): the video-size stream runs continuously into a ring of recent frames.
    private val moments = MomentBuffer(MOMENT_FRAMES)
    @Volatile private var momentEnabled = false
    private var momentAfter = 0
    private var momentPressSeq = 0L
    /** Last focus distance the camera reported (diopters), to lock focus for a burst. */
    @Volatile private var lastFocusDistance: Float? = null
    private var pendingStills = 0
    private var burstExpected = 0
    private val burstFrames = mutableListOf<ByteArray>()
    private var state = State.PREVIEW
    private var precaptureFrames = 0
    private var usedPrecapture = false
    private var captureOrientation = 0

    /** JPEG orientation (clockwise degrees from sensor to upright) of the last capture. */
    val lastCaptureOrientation get() = captureOrientation
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

    /** Keep recent frames in memory (Freeze) — only while enabled, it costs power. */
    fun setMomentBuffer(enabled: Boolean) {
        handler?.post {
            if (momentEnabled == enabled) return@post
            momentEnabled = enabled
            moments.clear()
            momentAfter = 0
            if (state == State.PREVIEW) updateRepeating()
        }
    }

    /**
     * Takes the moment: the sharpest frame from just before to just after this call. Falls back
     * to a normal photo when the moment buffer can't run on this camera.
     */
    fun takeMoment(deviceOrientation: Int) {
        val h = handler
        if (h == null) {
            main.post { listener.onCaptureFinished(false) }
            return
        }
        h.post {
            val c = caps
            if (c == null || !momentEnabled || !yuvInSession || moments.size == 0) {
                stillCount = 1
                stillToMemory = false
                burstToYuv = false
                burstExposure = null
                takePictureInternal(deviceOrientation)
                return@post
            }
            captureOrientation = jpegOrientation(c, deviceOrientation)
            momentPressSeq = moments.latestSequence
            momentAfter = MOMENT_AFTER
        }
    }

    // ------------------------------------------------------------------ steadiest moment (ZSL)

    private val zslRing = ArrayDeque<Image>()
    @Volatile private var zslCapable = false

    /** The last few full-size frames are being kept (steadiest-moment capture is possible). */
    val steadiestMomentAvailable get() = zslCapable && yuvInSession

    /** Timing (start, exposure ns) of the last single photo, for judging its shake. */
    @Volatile var lastStillTiming: Pair<Long, Long>? = null
        private set

    /** The camera reports where optical stabilisation moved the lens (per frame). */
    @Volatile var oisDataAvailable = false
        private set
    /** Readout time (ns) of the stream's frames (rolling shutter); 0 if unknown. */
    @Volatile var frameSkewNs = 0L
        private set
    /** Time for the sensor to read out top to bottom (ns) of the last photo; 0 if unknown. */
    @Volatile var lastStillSkewNs = 0L
        private set
    private val oisTs = LongArray(OIS_HISTORY)
    private val oisX = FloatArray(OIS_HISTORY)
    private val oisY = FloatArray(OIS_HISTORY)
    private var oisN = 0
    private var oisHead = 0

    private fun recordOis(result: CaptureResult) {
        if (!oisDataAvailable) return
        val samples = try { result.get(CaptureResult.STATISTICS_OIS_SAMPLES) } catch (e: Exception) { null } ?: return
        synchronized(oisTs) {
            for (o in samples) {
                if (oisN > 0 && o.timestamp <= oisTs[(oisHead - 1 + OIS_HISTORY) % OIS_HISTORY]) continue
                oisTs[oisHead] = o.timestamp
                oisX[oisHead] = o.xshift
                oisY[oisHead] = o.yshift
                oisHead = (oisHead + 1) % OIS_HISTORY
                if (oisN < OIS_HISTORY) oisN++
            }
        }
    }

    /** Recent lens-shift samples (timestamps ns, x, y in pixels), oldest first. */
    fun oisSamples(): Triple<LongArray, FloatArray, FloatArray> = synchronized(oisTs) {
        val start = (oisHead - oisN + OIS_HISTORY) % OIS_HISTORY
        Triple(LongArray(oisN) { oisTs[(start + it) % OIS_HISTORY] },
            FloatArray(oisN) { oisX[(start + it) % OIS_HISTORY] },
            FloatArray(oisN) { oisY[(start + it) % OIS_HISTORY] })
    }

    /** One kept frame, copied out, with its timing and measured shake. */
    class MomentFrame(val frame: MomentBuffer.Frame, val timestamp: Long, val exposureNs: Long, val blurPx: Float?)

    /**
     * After [delayMs] (to include frames just after the press), copies the [count] steadiest kept
     * frames from the last [windowNs] (by [blurOf](start, exposure) → pixels of blur), steadiest
     * first, to [done] on the main thread. Empty if none.
     */
    fun snapshotSteadiest(count: Int, windowNs: Long, delayMs: Long, blurOf: (Long, Long) -> Float?, done: (List<MomentFrame>) -> Unit) {
        val h = handler ?: return main.post { done(emptyList()) }.let { }
        h.postDelayed({
            val newest = zslRing.lastOrNull()?.timestamp ?: 0L
            val candidates = zslRing.filter { newest - it.timestamp <= windowNs }.map { img ->
                val e = frameExposure[img.timestamp] ?: 33_000_000L
                Triple(img, e, blurOf(img.timestamp, e))
            }.sortedBy { it.third ?: Float.MAX_VALUE }.take(count)
            val out = candidates.mapNotNull { (img, e, blur) ->
                try {
                    val f = MomentBuffer.Frame()
                    MomentBuffer.copyInto(img, f)
                    MomentFrame(f, img.timestamp, e, blur)
                } catch (ex: Exception) {
                    Log.w(TAG, "moment copy", ex)
                    null
                }
            }
            Log.i(TAG, "steadiest moment: ${zslRing.size} kept, picked ${out.map { "%.1f".format(it.blurPx ?: -1f) }} px blur")
            main.post { done(out) }
        }, delayMs)
    }

    /** True when fast (video-size) bursts are available in the current session. */
    fun fastBurstAvailable() = yuvInSession

    /**
     * A fast burst for multi-frame merging: [count] frames from the video-size stream, each
     * centre-cropped to 1/[crop] and converted to pixels as it arrives (no JPEG encode/decode).
     * Delivered via [Listener.onFastBurst].
     */
    fun takeFastBurst(deviceOrientation: Int, count: Int, crop: Float, exposure: Pair<Int, Long>?) {
        val h = handler
        if (h == null) {
            main.post { listener.onCaptureFinished(false) }
            return
        }
        h.post {
            stillCount = count.coerceIn(1, MAX_BURST)
            stillToMemory = true
            burstExposure = exposure
            burstMinimal = true
            burstToYuv = true
            yuvCrop = crop.coerceAtLeast(1f)
            takePictureInternal(deviceOrientation)
        }
    }

    /**
     * Other back cameras (separate lenses on phones that don't merge them into one camera), with
     * their zoom relative to [mainId].
     */
    fun extraBackLenses(mainId: String): List<Pair<String, Float>> = try {
        val main = CameraCaps.fieldOfViewScale(manager.getCameraCharacteristics(mainId))
        if (main == null) emptyList() else listCameras()
            .filter { it != mainId }
            .filter { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            .mapNotNull { id ->
                CameraCaps.fieldOfViewScale(manager.getCameraCharacteristics(id))?.let { id to CameraCaps.roundStop(it / main) }
            }
            .filter { kotlin.math.abs(it.second - 1f) > 0.15f }
            .distinctBy { it.second }
    } catch (e: Exception) {
        Log.w(TAG, "extraBackLenses", e)
        emptyList()
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
            // On the main thread: never wait long (an unresponsive app would be closed by Android).
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
    fun takeBurst(
        deviceOrientation: Int,
        count: Int,
        toMemory: Boolean = count > 1,
        /** Per-frame (ISO, exposure ns) for every frame of the burst, e.g. for Night; null = normal. */
        exposure: Pair<Int, Long>? = null,
        /**
         * For multi-frame merging (super zoom): switch off the camera's own noise reduction and
         * sharpening (they erase the fine detail the merge recovers) and lock focus.
         */
        minimalProcessing: Boolean = false,
        /** Spread the frames out in time (Clean mode: let passers-by move), 0 = as fast as possible. */
        intervalMs: Long = 0L,
        /** Close-up focus stacking: one focus distance (diopters) per frame; null = normal focus. */
        focusDistances: FloatArray? = null,
    ) {
        val h = handler
        if (h == null) {
            main.post { listener.onCaptureFinished(false) }
            return
        }
        h.post {
            stillCount = count.coerceIn(1, MAX_BURST)
            stillToMemory = toMemory || stillCount > 1
            burstToYuv = false
            burstExposure = exposure
            burstMinimal = minimalProcessing
            burstIntervalMs = intervalMs
            burstFocus = focusDistances?.takeIf { caps?.canManualFocus == true }
            if (burstFocus != null) stillCount = burstFocus!!.size.coerceAtMost(MAX_BURST)
            takePictureInternal(deviceOrientation)
        }
    }

    /** RAW zoom bursts are possible here (RAW stream in the session, colour filter known). */
    fun rawBurstAvailable() = rawInSession && rawReader != null && (caps?.cfa ?: -1) in 0..3

    /**
     * Zoom burst in RAW + JPEG: each RAW frame is cropped to what the zoom shows (RAW always covers
     * the active lens's whole sensor). Result in [Listener.onRawBurst].
     */
    fun takeRawZoomBurst(deviceOrientation: Int, count: Int, exposure: Pair<Int, Long>?) {
        val h = handler
        if (h == null) {
            main.post { listener.onCaptureFinished(false) }
            return
        }
        h.post {
            val raw = rawReader
            val c = caps
            if (raw == null || c == null) {
                main.post { listener.onCaptureFinished(false) }
                return@post
            }
            // Which lens the RAW comes from, and how much of it the zoom shows.
            val lensScale = lastPhysicalId?.let { c.physicalScale[it] } ?: 1f
            val crop = (settings.zoom / lensScale).coerceAtLeast(1f)
            rawFullW = raw.width
            rawFullH = raw.height
            val cw = ((raw.width / crop).toInt() and 1.inv()).coerceAtLeast(64)
            val ch = ((raw.height / crop).toInt() and 1.inv()).coerceAtLeast(64)
            val left = ((raw.width - cw) / 2) and 1.inv()
            val top = ((raw.height - ch) / 2) and 1.inv()
            rawCrop = Rect(left, top, left + cw, top + ch)
            Log.i(TAG, "raw zoom burst: lens $lastPhysicalId (x$lensScale), zoom ${settings.zoom}, crop $rawCrop of ${raw.width}x${raw.height}")
            rawBurstMode = true
            rawBurstFrames.clear()
            rawBurstResults.clear()
            rawBurstJpegs = null
            // As many RAW frames as fit in memory next to the merge (2 bytes per raw pixel each).
            val frameBytes = cw.toLong() * ch * 2
            val fit = ((Budget.available() * 0.5 - 160_000_000L) / frameBytes).toInt()
            stillCount = count.coerceAtMost(fit.coerceAtLeast(3)).coerceIn(1, MAX_BURST)
            rawBurstCount = stillCount
            stillToMemory = true
            burstToYuv = false
            burstExposure = exposure
            burstMinimal = true
            burstIntervalMs = 0L
            burstFocus = null
            takePictureInternal(deviceOrientation)
        }
    }

    private fun copyRawCrop(image: Image): ShortArray {
        val plane = image.planes[0]
        val buf = plane.buffer.order(java.nio.ByteOrder.nativeOrder())
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val r = rawCrop
        val out = ShortArray(r.width() * r.height())
        val shorts = buf.asShortBuffer()
        for (y in 0 until r.height()) {
            val rowStart = ((r.top + y) * rowStride + r.left * pixelStride) / 2
            if (pixelStride == 2) {
                shorts.position(rowStart)
                shorts.get(out, y * r.width(), r.width())
            } else {
                for (x in 0 until r.width()) out[y * r.width() + x] = shorts.get(rowStart + x * pixelStride / 2)
            }
        }
        return out
    }

    /** Hands over the RAW burst once the JPEGs, RAW frames and their results have all arrived. */
    private fun maybeDeliverRawBurst(force: Boolean = false) {
        val jpegs = rawBurstJpegs ?: return
        val complete = rawBurstFrames.size >= rawBurstCount && rawBurstFrames.keys.all { rawBurstResults.containsKey(it) }
        if (!complete && !force) return
        rawBurstMode = false
        val keys = rawBurstFrames.keys.filter { rawBurstResults.containsKey(it) }.sorted()
        val c = caps
        val chars = characteristics
        val capture = if (keys.size >= 2 && c != null && chars != null) {
            val first = rawBurstResults[keys[0]]!!
            val pattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            val black = first.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
                ?: FloatArray(4) { i -> (pattern?.getOffsetForIndex(i and 1, i shr 1) ?: 64).toFloat() }
            val white = (first.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
                ?: chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
            RawBurstCapture(
                frames = keys.map { rawBurstFrames[it]!! }, w = rawCrop.width(), h = rawCrop.height(),
                timing = keys.map { it to (rawBurstResults[it]?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L) },
                cropLeft = rawCrop.left, cropTop = rawCrop.top, fullW = rawFullW, fullH = rawFullH,
                cfa = c.cfa, result = first, black = black, white = white, rotation = captureOrientation,
            )
        } else null
        Log.i(TAG, "raw zoom burst: ${keys.size} raw frames of $rawBurstCount, ${jpegs.size} JPEGs" + if (capture == null) " (using JPEGs)" else "")
        rawBurstFrames.clear()
        rawBurstResults.clear()
        rawBurstJpegs = null
        main.post { listener.onRawBurst(capture, jpegs) }
    }

    // ------------------------------------------------------------------ open / close

    @SuppressLint("MissingPermission")
    private fun openInternal(cameraId: String, texture: SurfaceTexture) {
        try {
            val c = manager.getCameraCharacteristics(cameraId)
            characteristics = c
            val cp = CameraCaps.from(cameraId, c, manager)
            caps = cp
            oisDataAvailable = cp.ois && (c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES)
                ?.contains(CameraMetadata.STATISTICS_OIS_DATA_MODE_ON) == true)
            synchronized(oisTs) { oisN = 0 }
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IllegalStateException("No stream configuration map")

            if (output.video) {
                openVideo(cameraId, c, cp, map, texture)
                return
            }
            val heicSizes = if (output.heif) map.getOutputSizes(ImageFormat.HEIC) else null
            photoFormat = if (!heicSizes.isNullOrEmpty()) ImageFormat.HEIC else ImageFormat.JPEG
            if (output.heif && photoFormat != ImageFormat.HEIC) postError("HEIF isn't supported on this camera, using JPEG")
            val jpegSize = pickPhotoSize(map.getOutputSizes(photoFormat))
            val previewSize = pickPreviewSize(map.getOutputSizes(SurfaceTexture::class.java), jpegSize)

            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurface = Surface(texture)

            jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, photoFormat, 4).apply {
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

            val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
                ?.filter { abs(it.width.toFloat() / it.height - jpegSize.width.toFloat() / jpegSize.height) < 0.02f }
                .orEmpty()
            // Steadiest moment: full-size frames at video rate, if the phone can stream them.
            val zslSize = if (output.zsl) yuvSizes
                .filter { it.width.toLong() * it.height >= jpegSize.width.toLong() * jpegSize.height * 6 / 10 }
                .filter { (try { map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, it) } catch (e: Exception) { Long.MAX_VALUE }) <= ZSL_MAX_FRAME_NS }
                .maxByOrNull { it.width.toLong() * it.height } else null
            zslCapable = zslSize != null
            zslLastOpen = zslCapable
            (zslSize ?: yuvSizes.filter { it.width <= FAST_MAX_W && it.height <= FAST_MAX_H }.maxByOrNull { it.width.toLong() * it.height })
                ?.let { size ->
                    yuvReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, if (zslSize != null) ZSL_RING + 3 else 4).apply {
                        setOnImageAvailableListener({ onYuvAvailable(it) }, handler)
                    }
                }
            Log.i(TAG, "steadiest moment: ${if (zslSize != null) "full-size frames $zslSize" else if (output.zsl) "not possible (no full-size stream at video rate)" else "off"}")

            previewAspect = previewSize.width.toFloat() / previewSize.height
        main.post { listener.onCameraOpened(cp, previewSize) }

            if (!openLock.tryAcquire(OPEN_WAIT_MS, TimeUnit.MILLISECONDS)) {
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

    /** Video: a 16:9 preview plus the recorder's surface, nothing else. */
    private fun openVideo(cameraId: String, c: CameraCharacteristics, cp: CameraCaps, map: android.hardware.camera2.params.StreamConfigurationMap, texture: SurfaceTexture) {
        val slow = if (output.videoKind == OutputPrefs.VIDEO_SLOWMO) cp.slowMotion else null
        slowMotion = slow != null
        val fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        videoFps = if (slow != null) Range(slow.second, slow.second)
        else fpsRanges.firstOrNull { it.lower == VIDEO_FPS && it.upper == VIDEO_FPS }
            ?: fpsRanges.filter { it.upper == VIDEO_FPS }.maxByOrNull { it.lower }
        val fps = if (slow != null) VIDEO_FPS else videoFps?.upper ?: VIDEO_FPS
        val size = slow?.first ?: VideoRecorder.pickSize(map.getOutputSizes(android.media.MediaRecorder::class.java), output.video4k, fps)
        val stab = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
        videoStabMode = when {
            !output.stabilize || slow != null -> null
            // Preview stabilization (Android 13+) steadies the viewfinder too, so what you see is what's recorded.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                stab.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) ->
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            stab.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else -> null
        }
        // Slow motion: every output of a high-speed session must be the video size.
        val previewSize = if (slow != null) size else pickPreviewSize(map.getOutputSizes(SurfaceTexture::class.java), size)
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface = Surface(texture)
        val rec = try {
            VideoRecorder(appContext, size, fps, captureRate = slow?.second)
        } catch (e: Exception) {
            if (slow == null) throw e
            slowMotionFailed("recorder: ${e.javaClass.simpleName}: ${e.message}", e)
            return
        }
        videoRecorder = rec
        // High speed: preview alone may only run at (30, fps) on some phones; recording uses fps fixed.
        slowPreviewFps = slow?.let { (sz, f) ->
            try {
                map.getHighSpeedVideoFpsRangesFor(sz).firstOrNull { it.lower == 30 && it.upper == f }
            } catch (e: Exception) {
                null
            } ?: Range(f, f)
        }
        videoSize = size
        // Looks, level horizon and time-lapse go through the GPU on the way to the encoder
        // (not slow motion: high-speed sessions only take the preview and the encoder).
        effects = if (slow == null) VideoEffects(size, rec.surface).takeIf { it.start() } else null
        effects?.timelapse = if (output.videoKind == OutputPrefs.VIDEO_TIMELAPSE) TIMELAPSE_FACTOR else 1
        Log.i(TAG, "video: ${size.width}x${size.height} @$fps preview=$previewSize stabilization=$videoStabMode ois=${cp.ois} " +
            "effects=${effects != null} kind=${output.videoKind} slow=${slow?.second}")
        previewAspect = previewSize.width.toFloat() / previewSize.height
        main.post { listener.onCameraOpened(cp, previewSize) }
        if (!openLock.tryAcquire(OPEN_WAIT_MS, TimeUnit.MILLISECONDS)) throw IllegalStateException("Timed out waiting to open camera")
        opening = true
        try {
            manager.openCamera(cameraId, deviceCallback, handler)
        } catch (e: Exception) {
            releaseOpenLock()
            throw e
        }
    }

    private fun createVideoSession() {
        val d = device ?: return
        val preview = previewSurface ?: return
        val rec = videoRecorder ?: return
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (device == null) return
                session = s
                Log.i(TAG, "session: video")
                state = State.PREVIEW
                updateRepeating()
                pendingRecording?.let { (orientation, audio) ->
                    pendingRecording = null
                    startRecording(orientation, audio)
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (slowMotion) slowMotionFailed("high-speed session refused", null)
                else postError("This camera can't record video here")
            }
        }
        try {
            val target = effects?.inputSurface ?: rec.surface
            d.createCaptureSession(SessionConfiguration(
                if (slowMotion) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(preview), OutputConfiguration(target)), sessionExecutor, callback,
            ))
        } catch (e: Exception) {
            Log.e(TAG, "createVideoSession", e)
            if (slowMotion) slowMotionFailed("session: ${e.javaClass.simpleName}: ${e.message}", e)
            else postError("Failed to start video: ${e.message}")
        }
    }

    /** Starts recording (video mode only). [audio] = the microphone permission is granted. */
    fun startRecording(deviceOrientation: Int, audio: Boolean) {
        handler?.post {
            val rec = videoRecorder
            val c = caps
            if (rec != null && c != null && session == null) {
                // The camera is still setting up (e.g. just switched to video): start once it's ready.
                pendingRecording = deviceOrientation to audio
                return@post
            }
            if (rec == null || c == null || rec.recording) {
                main.post { listener.onRecordingStopped(null) }
                return@post
            }
            // Time-lapse and slow motion have no sound (it wouldn't match the picture's speed).
            val sound = audio && output.videoKind == OutputPrefs.VIDEO_NORMAL
            val ok = rec.start(jpegOrientation(c, deviceOrientation), sound, location)
            if (!ok && slowMotion) {
                main.post { listener.onRecordingStopped(null) }
                slowMotionFailed("recorder would not start", null)
                return@post
            }
            if (!ok) {
                postError("Couldn't start recording")
                main.post { listener.onRecordingStopped(null) }
                return@post
            }
            effects?.recording = true
            updateRepeating()
            main.post { listener.onRecordingStarted(rec.withSound) }
        }
    }

    fun stopRecording() {
        handler?.post {
            if (pendingRecording != null) {
                // Stopped before it could start.
                pendingRecording = null
                main.post { listener.onRecordingStopped(null) }
            }
            finishRecording()
        }
    }

    private var pendingRecording: Pair<Int, Boolean>? = null

    private var effects: VideoEffects? = null
    private var slowMotion = false

    /** Look for the video being recorded (null = Natural). */
    fun setVideoLook(look: Looks.Look?) {
        effects?.look = look?.takeUnless { it.isNatural }
    }

    /** Level horizon: rotate the video by [angle] (radians, clockwise), enlarged by [scale] to cover. */
    fun setVideoLevel(angle: Float, scale: Float) {
        effects?.levelAngle = angle
        effects?.levelScale = scale
    }

    /** The open video setup can apply looks / levelling / time-lapse. */
    val videoEffectsAvailable get() = effects != null

    /** Size the open camera records video at (null when open for photos). */
    @Volatile var videoSize: Size? = null
        private set

    private fun finishRecording() {
        val rec = videoRecorder ?: return
        if (!rec.recording) return
        effects?.recording = false
        val uri = rec.stop()
        updateRepeating()
        Log.i(TAG, "video: saved $uri")
        main.post { listener.onRecordingStopped(uri) }
    }

    private fun closeInternal() {
        // Leaving the app while recording keeps what was recorded so far.
        if (pendingRecording != null) {
            pendingRecording = null
            main.post { listener.onRecordingStopped(null) }
        }
        finishRecording()
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        device?.close()
        device = null
        jpegReader?.close()
        jpegReader = null
        yuvReader?.close()
        yuvReader = null
        yuvInSession = false
        yuvExpected = 0
        synchronized(yuvFrames) { yuvFrames.clear() }
        moments.clear()
        momentAfter = 0
        zslRing.forEach { it.close() }
        zslRing.clear()
        zslCapable = false
        rawImages.values.forEach { it.close() }
        rawImages.clear()
        rawResults.clear()
        shotNames.clear()
        rawReader?.close()
        rawReader = null
        effects?.release()
        effects = null
        rawBurstMode = false
        rawBurstFrames.clear()
        rawBurstResults.clear()
        rawBurstJpegs = null
        slowMotion = false
        videoRecorder?.release()
        videoRecorder = null
        videoSize = null
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
            if (videoRecorder != null) createVideoSession()
            else createSession(withRaw = rawReader != null, withYuv = yuvReader != null)
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

    /** Next, simpler configuration to try when one is refused: drop RAW first, then the fast stream. */
    private fun fallback(withRaw: Boolean, withYuv: Boolean, e: String) {
        when {
            withRaw -> {
                main.post { listener.onRawUnavailable() }
                createSession(withRaw = false, withYuv = withYuv)
            }
            withYuv -> createSession(withRaw = false, withYuv = false)
            else -> postError(e)
        }
    }

    private fun createSession(withRaw: Boolean, withYuv: Boolean) {
        val d = device ?: return
        val preview = previewSurface ?: return
        val jpeg = jpegReader ?: return
        val outputs = mutableListOf(OutputConfiguration(preview), OutputConfiguration(jpeg.surface))
        val raw = rawReader
        if (withRaw && raw != null) outputs += OutputConfiguration(raw.surface)
        val yuv = yuvReader
        if (withYuv && yuv != null) outputs += OutputConfiguration(yuv.surface)

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (device == null) return
                session = s
                rawInSession = withRaw && raw != null
                yuvInSession = withYuv && yuv != null
                Log.i(TAG, "session: raw=$rawInSession fast burst=$yuvInSession")
                state = State.PREVIEW
                updateRepeating()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                // Some devices can't run every stream together: drop RAW, then the fast stream.
                fallback(withRaw, withYuv, "Failed to configure the camera")
            }
        }
        try {
            d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, sessionExecutor, callback))
        } catch (e: Exception) {
            Log.e(TAG, "createSession", e)
            fallback(withRaw, withYuv, "Failed to start the camera: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ requests

    private fun updateRepeating() {
        val s = session ?: return
        try {
            val rec = videoRecorder
            if (rec != null && slowMotion) {
                repeatSlowMotion(s, rec)
                return
            }
            if (rec != null) {
                val d = device ?: return
                val surface = previewSurface ?: return
                val b = d.createCaptureRequest(if (rec.recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(surface)
                if (rec.recording) b.addTarget(effects?.inputSurface ?: rec.surface)
                applySettings(b, still = false)
                videoFps?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                videoStabMode?.let { b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, it) }
                // Video AF: smooth, without the hunting of photo AF.
                if (b.get(CaptureRequest.CONTROL_AF_MODE) == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE &&
                    caps?.afModes?.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true
                ) b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                s.setRepeatingRequest(b.build(), previewCallback, handler)
                return
            }
            val b = previewBuilder() ?: return
            val yuv = yuvReader
            if ((momentEnabled || zslCapable) && yuvInSession && yuv != null) b.addTarget(yuv.surface)
            s.setRepeatingRequest(b.build(), previewCallback, handler)
        } catch (e: Exception) {
            Log.w(TAG, "updateRepeating", e)
        }
    }

    /**
     * Slow motion: a minimal request, as high-speed sessions only accept a few controls (frame
     * rate, auto exposure/focus/white balance, zoom). Any problem falls back to normal video.
     */
    private fun repeatSlowMotion(s: CameraCaptureSession, rec: VideoRecorder) {
        try {
            val d = device ?: return
            val c = caps ?: return
            val surface = previewSurface ?: return
            val hs = s as? android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
                ?: throw IllegalStateException("not a high-speed session")
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(surface)
            if (rec.recording) b.addTarget(rec.surface)
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, if (rec.recording) videoFps else slowPreviewFps ?: videoFps)
            if (c.afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            val zoom = settings.zoom.coerceIn(c.zoomRange.lower, c.zoomRange.upper)
            if (c.zoomRatioApi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
            else if (zoom > 1.001f) b.set(CaptureRequest.SCALER_CROP_REGION, cropRegion(c, zoom))
            hs.setRepeatingBurst(hs.createHighSpeedRequestList(b.build()), previewCallback, handler)
        } catch (e: Exception) {
            slowMotionFailed("request: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private var slowPreviewFps: Range<Int>? = null

    private fun slowMotionFailed(reason: String, e: Throwable?) {
        Log.e(TAG, "slow motion failed: $reason", e)
        main.post { listener.onSlowMotionFailed(reason) }
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
        // Where the lens moved: what's left of the shake can then be undone (gyro deblur).
        if (oisDataAvailable) b.set(CaptureRequest.STATISTICS_OIS_DATA_MODE, CameraMetadata.STATISTICS_OIS_DATA_MODE_ON)

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
            recordOis(result)
            result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)?.let { frameSkewNs = it }
            if (zslCapable) {
                val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
                val e = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                if (ts != null && e != null) {
                    frameExposure[ts] = e
                    while (frameExposure.size > 32) frameExposure.remove(frameExposure.keys.first())
                }
            }
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)?.let { lastPhysicalId = it }
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastStatsAt >= STATS_INTERVAL_MS) {
                lastStatsAt = now
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                if (focus != null) lastFocusDistance = focus
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

    /** Preview width / height (sensor orientation): 4:3, or 16:9 for video. */
    @Volatile private var previewAspect = 4f / 3f

    /**
     * Sensor rectangle → 0..1 rectangle in the (portrait) preview view. The preview shows the
     * centre of the sensor at its own shape (a 16:9 preview crops the 4:3 sensor top and bottom),
     * so that crop is taken into account. Faces are padded a little: phones report a tight box
     * (eyes to mouth) that looks too small around a head.
     */
    private fun sensorToView(c: CameraCaps, frame: Rect, r: Rect): RectF {
        val sensorAspect = frame.width().toFloat() / frame.height()
        // Visible fraction of the frame along each sensor axis.
        val fx = if (previewAspect < sensorAspect) previewAspect / sensorAspect else 1f
        val fy = if (previewAspect > sensorAspect) sensorAspect / previewAspect else 1f
        val padX = r.width() * FACE_PAD
        val padY = r.height() * FACE_PAD
        fun map(px: Float, py: Float): Pair<Float, Float> {
            var x = ((px - frame.left) / frame.width() - (1 - fx) / 2) / fx
            var y = ((py - frame.top) / frame.height() - (1 - fy) / 2) / fy
            repeat((c.sensorOrientation % 360) / 90) {
                val t = y
                y = x
                x = 1f - t
            }
            if (c.isFront) x = 1f - x
            return x to y
        }
        val (x1, y1) = map(r.left - padX, r.top - padY)
        val (x2, y2) = map(r.right + padX, r.bottom + padY)
        return RectF(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    }

    // ------------------------------------------------------------------ focus

    private fun focusAtInternal(nx: Float, ny: Float) {
        val c = caps ?: return
        if (session == null || slowMotion) return
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
        // A 16:9 preview shows only the middle of the 4:3 sensor: map into that part.
        val sensorAspect = frame.width().toFloat() / frame.height()
        val fx = if (previewAspect < sensorAspect) previewAspect / sensorAspect else 1f
        val fy = if (previewAspect > sensorAspect) sensorAspect / previewAspect else 1f
        x = (1 - fx) / 2 + x * fx
        y = (1 - fy) / 2 + y * fy
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
        if (slowMotion) return
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
        lastStillTiming = null
        lastStillSkewNs = 0L
        val c = caps
        val s = session
        if (c == null || s == null || state != State.PREVIEW) {
            main.post { listener.onCaptureFinished(false) }
            return
        }
        captureOrientation = jpegOrientation(c, deviceOrientation)
        val needsPrecapture = burstExposure == null && settings.cameraAe && c.hasFlash &&
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
            val yuv = yuvReader
            val toYuv = burstToYuv && yuvInSession && yuv != null
            if (toYuv) b.addTarget(yuv!!.surface) else b.addTarget(jpeg.surface)
            val raw = rawReader
            val burst = stillToMemory
            if (!burst && settings.raw && rawInSession && raw != null) {
                b.addTarget(raw.surface)
                b.setTag(RAW_TAG)
            }
            if (rawBurstMode && rawInSession && raw != null) {
                b.addTarget(raw.surface)
                b.setTag(RAW_BURST_TAG)
                // Lens corner darkening isn't corrected in RAW: ask for the correction map.
                b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            }
            applySettings(b, still = true)
            val override = burstExposure
            if (override != null && caps?.manualSensor == true) {
                b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, override.first)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, override.second)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, override.second)
                b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            }
            val cc = caps
            if (burstMinimal && cc != null) {
                fun pick(modes: IntArray, vararg prefs: Int) = prefs.firstOrNull { modes.contains(it) }
                pick(cc.noiseReductionModes, CameraMetadata.NOISE_REDUCTION_MODE_OFF, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
                    ?.let { b.set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
                pick(cc.edgeModes, CameraMetadata.EDGE_MODE_OFF, CameraMetadata.EDGE_MODE_FAST)
                    ?.let { b.set(CaptureRequest.EDGE_MODE, it) }
                val focus = lastFocusDistance
                if (cc.canManualFocus && !settings.manualFocus && focus != null) {
                    // Same focus for every frame (no hunting mid-burst).
                    b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
                }
            }
            pendingStills = stillCount
            if (toYuv) {
                synchronized(yuvFrames) { yuvFrames.clear() }
                yuvExpected = stillCount
                burstExpected = 0
                if (stillCount == 1) s.capture(b.build(), stillCallback, handler)
                else s.captureBurst(List(stillCount) { b.build() }, stillCallback, handler)
            } else if (burst) {
                burstFrames.clear()
                burstExpected = stillCount
                val interval = burstIntervalMs
                val focus = burstFocus
                if (focus != null && stillCount > 1) {
                    // Focus bracket: each frame focused a little further away; time for the lens to move.
                    b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    val requests = (0 until stillCount).map { k ->
                        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus[k])
                        b.build()
                    }
                    requests.forEachIndexed { k, r ->
                        handler?.postDelayed({
                            try {
                                if (session === s) s.capture(r, stillCallback, handler)
                            } catch (e: Exception) {
                                Log.w(TAG, "focus bracket", e)
                            }
                        }, k * max(interval, FOCUS_STEP_MS))
                    }
                } else if (stillCount == 1) {
                    s.capture(b.build(), stillCallback, handler)
                } else if (interval > 0) {
                    // Spaced out: one frame now, the rest every [interval] ms.
                    val request = b.build()
                    s.capture(request, stillCallback, handler)
                    for (k in 1 until stillCount) {
                        handler?.postDelayed({
                            try {
                                if (session === s) s.capture(request, stillCallback, handler)
                            } catch (e: Exception) {
                                Log.w(TAG, "spaced capture", e)
                            }
                        }, k * interval)
                    }
                } else {
                    s.captureBurst(List(stillCount) { b.build() }, stillCallback, handler)
                }
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
            recordOis(result)
            result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)?.let { lastStillSkewNs = it }
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
            if (ts != null) {
                result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { frameExposure[ts] = it }
                while (frameExposure.size > 32) frameExposure.remove(frameExposure.keys.first())
            }
            if (ts != null && request.tag == RAW_TAG) {
                rawResults[ts] = result
                trimOldest(rawResults)
                tryWriteDng(ts)
            }
            if (ts != null && request.tag == RAW_BURST_TAG) {
                rawBurstResults[ts] = result
                maybeDeliverRawBurst()
            }
            if (--pendingStills <= 0) finishCapture(true)
        }

        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            Log.w(TAG, "capture failed: ${failure.reason}")
            if (burstExpected > 0) {
                burstExpected--
                if (burstExpected > 0 && burstFrames.size >= burstExpected) deliverBurst()
            }
            if (yuvExpected > 0) {
                yuvExpected--
                if (yuvExpected > 0 && yuvFrames.size >= yuvExpected) deliverYuv()
            }
            if (--pendingStills <= 0) finishCapture(burstFrames.isNotEmpty())
        }
    }

    private var burstMime = "image/jpeg"
    // Per-frame timing of the last burst (for gyro blur checks): start of exposure and its length.
    private val burstTimestamps = mutableListOf<Long>()
    private val frameExposure = LinkedHashMap<Long, Long>()

    /** Start (ns) and exposure (ns) of each frame of the last burst, in delivery order. */
    @Volatile var lastBurstTiming: List<Pair<Long, Long>> = emptyList()
        private set

    /** Camera timestamps share the gyroscope's clock (so per-frame shake can be measured). */
    val timestampsMatchSensors: Boolean
        get() = characteristics?.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

    private fun deliverBurst() {
        val frames = burstFrames.toList()
        val mime = burstMime
        val fallbackExposure = burstExposure?.second ?: 0L
        lastBurstTiming = burstTimestamps.map { ts -> ts to (frameExposure[ts] ?: fallbackExposure) }
        burstTimestamps.clear()
        burstFrames.clear()
        burstExpected = 0
        if (rawBurstMode) {
            // RAW zoom burst: wait for the RAW frames too (at most a moment).
            rawBurstJpegs = frames
            maybeDeliverRawBurst()
            handler?.postDelayed({ if (rawBurstJpegs != null) maybeDeliverRawBurst(force = true) }, RAW_WAIT_MS)
            return
        }
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
        if (burstExpected <= 0) lastStillTiming = ts to (frameExposure[ts] ?: burstExposure?.second ?: 0L)
        if (burstExpected > 0) {
            burstMime = if (heic) "image/heic" else "image/jpeg"
            burstFrames += bytes
            burstTimestamps += ts
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

    private fun onYuvAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (e: Exception) {
            null
        } ?: return
        if (yuvExpected <= 0 && zslCapable && !momentEnabled) {
            // Steadiest moment: hold the last few frames (no copy); the oldest goes back to the camera.
            zslRing.addLast(image)
            while (zslRing.size > ZSL_RING) zslRing.removeFirst().close()
            return
        }
        try {
            if (yuvExpected <= 0) {
                if (momentEnabled) onMomentFrame(image)
                return
            }
            val frame = yuvCentreToArgb(image, yuvCrop)
            val ready = synchronized(yuvFrames) {
                yuvFrames += frame
                yuvFrames.size >= yuvExpected
            }
            if (ready) deliverYuv()
        } catch (e: Exception) {
            Log.w(TAG, "yuv frame", e)
        } finally {
            image.close()
        }
    }

    private fun onMomentFrame(image: Image) {
        moments.add(image)
        if (momentAfter <= 0) return
        if (--momentAfter > 0) return
        // A little before the press (people press late) to a little after it.
        val chosen = moments.sharpestBetween(momentPressSeq - MOMENT_BEFORE + 1, momentPressSeq + MOMENT_AFTER)
        val rotation = captureOrientation
        main.post {
            listener.onShutter()
            if (chosen != null) listener.onMoment(chosen, rotation) else listener.onCaptureFinished(false)
        }
    }

    private fun deliverYuv() {
        val frames = synchronized(yuvFrames) { yuvFrames.toList().also { yuvFrames.clear() } }
        yuvExpected = 0
        val rotation = captureOrientation
        main.post { listener.onFastBurst(frames, rotation) }
    }

    private fun onRawAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (e: Exception) {
            null
        } ?: return
        if (rawBurstMode || rawBurstJpegs != null) {
            try {
                rawBurstFrames[image.timestamp] = copyRawCrop(image)
            } catch (e: Exception) {
                Log.w(TAG, "raw crop", e)
            } finally {
                image.close()
            }
            maybeDeliverRawBurst()
            return
        }
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
        /** Whether the last camera opened could keep full-size frames (tests, diagnostics). */
        @Volatile var zslLastOpen = false
        /** About 3 s of lens-shift samples. */
        private const val OIS_HISTORY = 1024
        private const val TAG = "ProCam"
        private const val RAW_TAG = "raw"
        private const val RAW_BURST_TAG = "rawburst"
        /** After the JPEGs, wait at most this long for missing RAW frames. */
        private const val RAW_WAIT_MS = 3000L
        private const val STANDARD_PIXELS = 12_600_000L
        private const val AE_LOCK_DELAY_MS = 700L
        private const val MAX_BURST = 16
        /** Frames kept for Freeze (~1/3 s at 30 fps), of which the window around a press is searched. */
        private const val MOMENT_FRAMES = 10
        private const val MOMENT_BEFORE = 5 // ~165 ms before the press
        private const val MOMENT_AFTER = 2 // ~65 ms after
        private const val FAST_MAX_W = 1920
        private const val FAST_MAX_H = 1440
        private const val VIDEO_FPS = 30
        /** Steadiest moment: frames kept (~0.2 s at 30 fps), and the slowest frame rate accepted. */
        private const val ZSL_RING = 6
        private const val ZSL_MAX_FRAME_NS = 41_700_000L
        /** Face boxes drawn this much larger on each side than the camera reports. */
        private const val FACE_PAD = 0.25f
        /** Time for the lens to move between focus-bracketed frames. */
        private const val FOCUS_STEP_MS = 180L
        /** Time-lapse: one frame in 15 kept (30 fps camera → 2 frames a second, played 15× faster). */
        const val TIMELAPSE_FACTOR = 15
        /** How long to wait for a previous open/close (slow phones take a few seconds). */
        private const val OPEN_WAIT_MS = 6000L

        /**
         * Centre 1/[crop] of a YUV_420_888 image as ARGB pixels (BT.601 full range, as used by
         * JPEG), reading only the rows and columns of the crop.
         */
        fun yuvCentreToArgb(image: Image, crop: Float): SuperRes.Frame {
            val fw = image.width
            val fh = image.height
            val cw = max(2, (fw / crop).toInt()) and 1.inv()
            val ch = max(2, (fh / crop).toInt()) and 1.inv()
            val left = ((fw - cw) / 2) and 1.inv()
            val top = ((fh - ch) / 2) and 1.inv()
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yBuf = yPlane.buffer
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer
            val yRow = yPlane.rowStride
            val yPix = yPlane.pixelStride
            val uvRow = uPlane.rowStride
            val uvPix = uPlane.pixelStride
            val out = IntArray(cw * ch)
            for (y in 0 until ch) {
                val sy = top + y
                val yBase = sy * yRow
                val uvBase = (sy / 2) * uvRow
                for (x in 0 until cw) {
                    val sx = left + x
                    val yy = yBuf.get(yBase + sx * yPix).toInt() and 0xFF
                    val uvi = uvBase + (sx / 2) * uvPix
                    val u = (uBuf.get(uvi).toInt() and 0xFF) - 128
                    val v = (vBuf.get(uvi).toInt() and 0xFF) - 128
                    val r = (yy + ((91881 * v) shr 16)).coerceIn(0, 255)
                    val g = (yy - ((22554 * u + 46802 * v) shr 16)).coerceIn(0, 255)
                    val b = (yy + ((116130 * u) shr 16)).coerceIn(0, 255)
                    out[y * cw + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return SuperRes.Frame(out, cw, ch)
        }
        private const val FACE_UPDATE_MS = 500L
        private const val FACE_HOLD_MS = 1500L
        private const val MAX_PREVIEW_EXPOSURE_NS = 66_666_667L // 1/15 s
        private const val PRECAPTURE_TIMEOUT_FRAMES = 30
        private const val STATS_INTERVAL_MS = 200L
    }
}

/** A RAW zoom burst, cropped to the zoomed view, with what's needed to develop it. */
class RawBurstCapture(
    val frames: List<ShortArray>, val w: Int, val h: Int,
    /** Start and length (ns) of each frame's exposure, for gyro blur checks. */
    val timing: List<Pair<Long, Long>>,
    val cropLeft: Int, val cropTop: Int, val fullW: Int, val fullH: Int,
    val cfa: Int, val result: TotalCaptureResult, val black: FloatArray, val white: Float,
    /** Clockwise degrees to turn the result upright. */
    val rotation: Int,
)

/** The same burst with only some frames ([keep] = indices). */
fun RawBurstCapture.only(keep: List<Int>) = RawBurstCapture(
    frames = keep.map { frames[it] }, w = w, h = h, timing = keep.map { timing[it] },
    cropLeft = cropLeft, cropTop = cropTop, fullW = fullW, fullH = fullH,
    cfa = cfa, result = result, black = black, white = white, rotation = rotation,
)
