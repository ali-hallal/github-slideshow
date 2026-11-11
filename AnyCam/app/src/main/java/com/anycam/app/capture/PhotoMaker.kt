package com.anycam.app.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.anycam.app.camera.CameraEngine
import com.anycam.app.core.Detail
import com.anycam.app.core.Merge
import com.anycam.app.core.Yuv
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Turns a burst into a finished photo in the gallery, off the main thread, one at a time. */
class PhotoMaker(context: Context) {

    class Photo(val uri: Uri, val merged: Int, val of: Int, val millis: Long)

    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "anycam-photo") }
    private val main = Handler(Looper.getMainLooper())

    /** [done] is called on the main thread with the photo, or null if it couldn't be saved. */
    fun make(frames: List<Yuv>, shot: CameraEngine.Shot, done: (Photo?) -> Unit) {
        worker.execute {
            val t0 = System.currentTimeMillis()
            val photo = try {
                val result = try {
                    Merge.merge(frames)
                } catch (e: OutOfMemoryError) {
                    // Too little memory for the merge: the first shot on its own.
                    Log.w(TAG, "merge ran out of memory, saving one shot")
                    Merge.Result(frames.first(), 0, 1, 0f)
                }
                // What noise is left after averaging [used] shots; with that out of the way, the detail.
                val finished = Detail.sharpen(result.photo, result.noise / kotlin.math.sqrt(result.used.toFloat()))
                val jpeg = encode(finished)
                val uri = save(jpeg, shot)
                Log.i(TAG, "photo: ${result.photo.w}x${result.photo.h}, ${result.used} of ${frames.size} shots merged " +
                    "(noise ${"%.1f".format(Locale.US, result.noise)}), ISO ${shot.iso}, ${System.currentTimeMillis() - t0} ms")
                uri?.let { Photo(it, result.used, frames.size, System.currentTimeMillis() - t0) }
            } catch (e: Throwable) {
                Log.e(TAG, "photo failed", e)
                null
            }
            main.post { done(photo) }
        }
    }

    private fun encode(photo: Yuv): ByteArray {
        val out = ByteArrayOutputStream(photo.w * photo.h / 4)
        YuvImage(photo.toNv21(), ImageFormat.NV21, photo.w, photo.h, null).compressToJpeg(Rect(0, 0, photo.w, photo.h), JPEG_QUALITY, out)
        return out.toByteArray()
    }

    private fun save(jpeg: ByteArray, shot: CameraEngine.Shot): Uri? {
        val resolver = app.contentResolver
        val name = "ANYCAM_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(shot.takenAt))}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
            put(MediaStore.Images.Media.DATE_TAKEN, shot.takenAt)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: throw IllegalStateException("no stream")
            // The pixels stay as the sensor delivered them; the photo is marked with how to turn it.
            try {
                resolver.openFileDescriptor(uri, "rw")?.use { fd ->
                    val exif = ExifInterface(fd.fileDescriptor)
                    exif.setAttribute(ExifInterface.TAG_ORIENTATION, when (shot.rotation) {
                        90 -> ExifInterface.ORIENTATION_ROTATE_90
                        180 -> ExifInterface.ORIENTATION_ROTATE_180
                        270 -> ExifInterface.ORIENTATION_ROTATE_270
                        else -> ExifInterface.ORIENTATION_NORMAL
                    }.toString())
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(shot.takenAt)))
                    exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                    exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
                    shot.iso?.let { exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, it.toString()) }
                    shot.exposureNs?.let { exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, (it / 1e9).toString()) }
                    exif.saveAttributes()
                }
            } catch (e: Exception) {
                Log.w(TAG, "photo details not written", e)
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "save", e)
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            null
        }
    }

    companion object {
        private const val TAG = "AnyCam"
        const val FOLDER = "AnyCam"
        private const val JPEG_QUALITY = 95
    }
}
