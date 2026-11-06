package com.procam.app

import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records video from the camera into Movies/ProCam (MP4, H.264 + AAC).
 *
 * The camera draws into [surface], a persistent encoder surface: it exists before recording, so
 * the camera session is set up once and each recording just attaches a new MediaRecorder to it.
 */
class VideoRecorder(
    context: Context,
    val size: Size,
    val fps: Int,
    /** Slow motion: frames captured at this rate, played back at [fps] (null = normal). */
    private val captureRate: Int? = null,
) {

    private val app = context.applicationContext
    val surface: Surface = MediaCodec.createPersistentInputSurface()
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    var recording = false
        private set
    var withSound = false
        private set

    init {
        // A first recorder configures the surface (size, format) so the camera can use it.
        val tmp = java.io.File(app.cacheDir, "prime.mp4")
        try {
            newRecorder().apply {
                configure(this, audio = false, orientation = 0, location = null)
                setOutputFile(tmp.absolutePath)
                prepare()
                release()
            }
        } finally {
            tmp.delete()
        }
    }

    /** Starts writing a new video. Without the microphone (no permission, or it fails) it's silent. */
    fun start(orientation: Int, audio: Boolean, location: Location?): Boolean {
        if (recording) return true
        val name = "PROCAM_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ProCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val u = app.contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return false
        for (sound in if (audio) listOf(true, false) else listOf(false)) {
            val fd = try {
                app.contentResolver.openFileDescriptor(u, "w")
            } catch (e: Exception) {
                null
            } ?: break
            val r = newRecorder()
            try {
                configure(r, sound, orientation, location)
                r.setOutputFile(fd.fileDescriptor)
                r.prepare()
                r.start()
                recorder = r
                pfd = fd
                uri = u
                recording = true
                withSound = sound
                Log.i(TAG, "video: recording ${size.width}x${size.height} @$fps sound=$sound")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "video: start failed (sound=$sound)", e)
                r.release()
                fd.close()
            }
        }
        app.contentResolver.delete(u, null, null)
        return false
    }

    /** Stops and publishes the video; null if nothing usable was recorded (e.g. stopped instantly). */
    fun stop(): Uri? {
        if (!recording) return null
        recording = false
        val r = recorder
        val u = uri
        recorder = null
        uri = null
        val ok = try {
            r?.stop()
            true
        } catch (e: Exception) {
            Log.w(TAG, "video: stop failed (too short?)", e)
            false
        }
        r?.release()
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        pfd = null
        if (u == null) return null
        if (!ok) {
            app.contentResolver.delete(u, null, null)
            return null
        }
        app.contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        return u
    }

    fun release() {
        stop()
        surface.release()
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(app) else MediaRecorder()

    private fun configure(r: MediaRecorder, audio: Boolean, orientation: Int, location: Location?) {
        if (audio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(size.width, size.height)
        r.setVideoFrameRate(fps)
        if (captureRate != null) r.setCaptureRate(captureRate.toDouble())
        r.setVideoEncodingBitRate(bitRate(size, captureRate ?: fps).coerceAtMost(100_000_000))
        if (audio) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(192_000)
            r.setAudioSamplingRate(48_000)
            r.setAudioChannels(2)
        }
        r.setOrientationHint(orientation)
        location?.let { r.setLocation(it.latitude.toFloat(), it.longitude.toFloat()) }
        r.setInputSurface(surface)
    }

    companion object {
        private const val TAG = "ProCam"

        /** About what phones use: ~17 Mbit/s for 1080p30, ~50 for 4K30. */
        fun bitRate(size: Size, fps: Int): Int =
            (size.width.toLong() * size.height * fps * 0.27).toLong().coerceIn(2_000_000L, 100_000_000L).toInt()

        /**
         * The video size to record: the largest 16:9 size the camera offers up to 1080p (or 4K when
         * [allow4k]) that the phone's H.264 encoder can handle.
         */
        fun pickSize(offered: Array<Size>?, allow4k: Boolean, fps: Int): Size {
            val maxW = if (allow4k) 3840 else 1920
            val candidates = (offered ?: emptyArray())
                .filter { it.width <= maxW && it.height <= maxW * 9 / 16 && kotlin.math.abs(it.width * 9 - it.height * 16) <= 16 * 2 }
                .sortedByDescending { it.width.toLong() * it.height }
            return candidates.firstOrNull { encoderSupports(it, fps) }
                ?: candidates.firstOrNull { it.width <= 1920 }
                ?: offered?.filter { it.width <= 1920 }?.maxByOrNull { it.width.toLong() * it.height }
                ?: Size(1280, 720)
        }

        private fun encoderSupports(s: Size, fps: Int): Boolean = try {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, s.width, s.height)
            f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(f) != null
        } catch (e: Exception) {
            false
        }

        /** "1:05" */
        fun durationLabel(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }
    }
}
