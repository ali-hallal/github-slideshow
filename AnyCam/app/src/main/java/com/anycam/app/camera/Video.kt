package com.anycam.app.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recording video with Android's own encoder, the same on every phone. The camera feeds one
 * surface for as long as video mode is open; each recording attaches a new recorder to it and
 * writes straight into the gallery (`Movies/AnyCam`).
 */
class Video(private val context: Context, val size: Size) {
    private val surface: Surface = MediaCodec.createPersistentInputSurface()
    private var recorder: MediaRecorder? = null
    private var file: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var startedAt = 0L

    /** The surface for the camera, ready to use: a recorder must have set it up once before the camera can feed it. */
    fun prepareSurface(): Surface {
        val probe = File(context.cacheDir, "probe.mp4")
        recorder(sound = false, rotation = 0) { it.setOutputFile(probe.absolutePath) }.apply {
            prepare()
            release()
        }
        probe.delete()
        return surface
    }

    private fun recorder(sound: Boolean, rotation: Int, output: (MediaRecorder) -> Unit): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        if (sound) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        output(r)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(size.width, size.height)
        r.setVideoFrameRate(FPS)
        r.setVideoEncodingBitRate(bitrate(size))
        if (sound) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(48_000)
            r.setAudioChannels(2)
            r.setAudioEncodingBitRate(192_000)
        }
        r.setOrientationHint(rotation)
        r.setInputSurface(surface)
        return r
    }

    fun start(rotation: Int, sound: Boolean) {
        val name = "ANYCAM_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AnyCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val u = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("no room in the gallery")
        uri = u
        val pfd = context.contentResolver.openFileDescriptor(u, "w") ?: throw IllegalStateException("can't write the video")
        file = pfd
        val r = try {
            recorder(sound, rotation) { it.setOutputFile(pfd.fileDescriptor) }.apply { prepare() }
        } catch (e: Exception) {
            // Without sound if the microphone won't start (busy, or not allowed).
            if (!sound) throw e
            Log.w("AnyCam", "recording without sound", e)
            recorder(false, rotation) { it.setOutputFile(pfd.fileDescriptor) }.apply { prepare() }
        }
        r.start()
        recorder = r
        startedAt = System.currentTimeMillis()
    }

    /** Ends the recording; the saved video's address and length, or null if nothing was recorded. */
    fun stop(): Pair<Uri?, Int> {
        val r = recorder ?: return null to 0
        recorder = null
        val seconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
        val ok = try {
            r.stop()
            true
        } catch (e: Exception) {
            Log.w("AnyCam", "video too short or failed", e)
            false
        } finally {
            r.release()
        }
        try { file?.close() } catch (_: Exception) {}
        file = null
        val u = uri
        uri = null
        if (u == null) return null to 0
        if (!ok) {
            try { context.contentResolver.delete(u, null, null) } catch (_: Exception) {}
            return null to 0
        }
        context.contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        return u to seconds
    }

    /** A recording that never started: nothing is left behind. */
    fun abandon() {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { file?.close() } catch (_: Exception) {}
        file = null
        uri?.let { try { context.contentResolver.delete(it, null, null) } catch (_: Exception) {} }
        uri = null
    }

    companion object {
        const val UHD = 2160
        const val FULL_HD = 1080
        const val FPS = 30

        /** About what phones' own cameras use: 4K at 45 Mbit/s, full HD at 18. */
        fun bitrate(size: Size): Int = if (size.width.toLong() * size.height >= 3840L * 2160) 45_000_000 else 18_000_000

        /** 16:9 at [quality] lines where the camera gives it and the phone's encoder takes it, else the largest 16:9 below. */
        fun pickSize(sizes: Array<Size>, quality: Int): Size? {
            Log.i("AnyCam", "video sizes offered: ${sizes.joinToString()}")
            fun largest(l: List<Size>) = l.maxByOrNull { it.width.toLong() * it.height }
            val wide = sizes.filter { it.width * 9 == it.height * 16 }
            // The encoder's own answer where it gives one; full HD and below every phone records.
            return largest(wide.filter { it.height <= quality && (it.height <= FULL_HD || encodes(it)) })
                ?: largest(sizes.filter { it.height <= FULL_HD && it.width <= 1920 })
                ?: largest(sizes.toList())
        }

        private fun encodes(size: Size): Boolean = try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate(size))
            }
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) != null
        } catch (e: Exception) {
            false
        }
    }
}
