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
import com.anycam.app.core.Develop
import com.anycam.app.core.Look
import com.anycam.app.core.Merge
import com.anycam.app.core.RawFrame
import com.anycam.app.core.RawInfo
import com.anycam.app.core.RawMerge
import com.anycam.app.core.Yuv
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Turns a burst into a finished photo in the gallery, off the main thread, one at a time. */
class PhotoMaker(context: Context) {

    class Photo(val uri: Uri, val merged: Int, val of: Int, val millis: Long, val fromSensor: Boolean = false, val megapixels: Float = 0f)

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

    /**
     * The same from sensor (RAW) frames: merged before any processing, developed, and given the
     * look of the phone's own picture of the scene ([guide]). If anything goes wrong on the way,
     * the phone's own picture is saved instead: a photo is never lost to this.
     */
    fun makeRaw(frames: List<RawFrame>, guide: Yuv?, info: RawInfo, shot: CameraEngine.Shot, done: (Photo?) -> Unit) {
        worker.execute {
            val t0 = System.currentTimeMillis()
            val photo = try {
                val merged = RawMerge.merge(frames, info)
                val t1 = System.currentTimeMillis()
                // The two pictures must show the same view for the look to be taken from one to the other.
                val sameShape = guide != null && kotlin.math.abs(guide.w.toFloat() / guide.h - merged.w.toFloat() / merged.h) < 0.03f
                val look = if (guide != null && sameShape) {
                    Look.fit(Develop.coarse(merged, info, Look.GRID_W, Look.GRID_H), Look.coarse(guide))
                } else Look.PLAIN
                val developed = Develop.develop(merged, info, look)
                val t2 = System.currentTimeMillis()
                val finished = Detail.sharpen(developed, merged.noise / kotlin.math.sqrt(merged.used.toFloat()))
                val uri = save(encode(finished), shot)
                Log.i(TAG, "photo from the sensor: ${merged.w}x${merged.h}, ${merged.used} of ${frames.size} shots merged " +
                    "(noise ${"%.1f".format(Locale.US, merged.noise)}), look ${if (look === Look.PLAIN) "plain" else "from the phone"}, " +
                    "wb ${info.wb.joinToString { "%.2f".format(Locale.US, it) }}, lens map ${info.shading != null}, ISO ${shot.iso}; " +
                    "merge ${t1 - t0} ms, develop ${t2 - t1} ms, all ${System.currentTimeMillis() - t0} ms")
                uri?.let { Photo(it, merged.used, frames.size, System.currentTimeMillis() - t0, fromSensor = true, megapixels = merged.w.toLong() * merged.h / 1_000_000f) }
            } catch (e: Throwable) {
                Log.e(TAG, "sensor photo failed, keeping the phone's own picture", e)
                try {
                    guide?.let { g -> save(encode(g), shot)?.let { Photo(it, 1, frames.size, System.currentTimeMillis() - t0) } }
                } catch (e2: Throwable) {
                    null
                }
            }
            main.post { done(photo) }
        }
    }

    /** Saves a photo that is already finished (made by the phone maker's own processing). */
    fun keep(jpeg: ByteArray, shot: CameraEngine.Shot, done: (Photo?) -> Unit) {
        worker.execute {
            val t0 = System.currentTimeMillis()
            val uri = try {
                save(jpeg, shot, alreadyUpright = true)
            } catch (e: Throwable) {
                Log.e(TAG, "photo failed", e)
                null
            }
            main.post { done(uri?.let { Photo(it, 1, 1, System.currentTimeMillis() - t0) }) }
        }
    }

    private fun encode(photo: Yuv): ByteArray {
        val out = ByteArrayOutputStream(photo.w * photo.h / 4)
        YuvImage(photo.toNv21(), ImageFormat.NV21, photo.w, photo.h, null).compressToJpeg(Rect(0, 0, photo.w, photo.h), JPEG_QUALITY, out)
        return out.toByteArray()
    }

    /** [alreadyUpright]: the JPEG carries its own orientation (it came from the camera as a JPEG). */
    private fun save(jpeg: ByteArray, shot: CameraEngine.Shot, alreadyUpright: Boolean = false): Uri? {
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
            if (!alreadyUpright) try {
                resolver.openFileDescriptor(uri, "rw")?.use { fd ->
                    val exif = ExifInterface(fd.fileDescriptor)
                    if (!alreadyUpright) exif.setAttribute(ExifInterface.TAG_ORIENTATION, when (shot.rotation) {
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
