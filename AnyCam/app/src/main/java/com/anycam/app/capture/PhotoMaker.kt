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
import com.anycam.app.core.Stack
import com.anycam.app.core.Yuv
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Turns a burst into a finished photo in the gallery, off the main thread, one at a time. */
class PhotoMaker(context: Context) {

    /** [sensorFailed]: the sensor's frames couldn't be turned into a trustworthy photo on this phone (the phone's own picture was saved). */
    class Photo(val uri: Uri, val merged: Int, val of: Int, val millis: Long, val fromSensor: Boolean = false, val megapixels: Float = 0f, val sensorFailed: Boolean = false)

    private class SensorNotTrusted(message: String) : Exception(message)

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
                val uri = save(jpeg, shot, notes = "AnyCam photo from ordinary frames; ISO ${shot.iso} ${shot.exposureNs?.let { "1/${Math.round(1e9 / it)}" }}; " +
                    "${result.used} of ${frames.size} merged; noise ${"%.1f".format(Locale.US, result.noise)}; ${System.currentTimeMillis() - t0} ms")
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
                // The sharpest frame on its own first: the photo is built around it, and its
                // colours (coarsely) are what the look is fitted from.
                val reference = RawMerge.sharpest(frames, info)
                val base = RawMerge.merge(listOf(frames[reference]), info)
                val t1 = System.currentTimeMillis()
                // The two pictures must show the same view for the look to be taken from one to the other.
                val sameShape = guide != null && kotlin.math.abs(guide.w.toFloat() / guide.h - base.w.toFloat() / base.h) < 0.03f
                val look = if (guide != null && sameShape) {
                    Look.fit(Develop.coarse(base, info, Look.GRID_W, Look.GRID_H), Look.coarse(guide))
                } else Look.PLAIN
                // Then every frame's samples laid down where they really were.
                val merged = Stack.develop(frames, info, base, reference, look)
                val developed = merged.photo
                val t2 = System.currentTimeMillis()
                // Before trusting it: does it show what the phone's own picture shows? A phone that
                // reports its sensor oddly would give a wrong-looking photo, and that mustn't be saved.
                if (guide != null && sameShape) {
                    val apart = Look.distance(Look.coarse(developed), Look.coarse(guide))
                    Log.i(TAG, "sensor photo check: $apart from the phone's picture (limit ${Look.TRUSTED_WITHIN}); filters ${info.cfa}, " +
                        "black ${info.black.joinToString { it.toInt().toString() }}, white ${info.white.toInt()}, noise figures ${info.noiseS}, ${info.noiseO}, " +
                        "matrix ${info.ccm.joinToString { "%.2f".format(Locale.US, it) }}, look colour ${look.colour.joinToString { "%.2f".format(Locale.US, it) }}, " +
                        "curve ${look.gain.filterIndexed { i, _ -> i % 51 == 0 }.joinToString { "%.2f".format(Locale.US, it) }}")
                    if (apart > Look.TRUSTED_WITHIN) throw SensorNotTrusted("developed photo is $apart from the phone's picture")
                }
                val finished = Detail.sharpen(Detail.calmColour(developed), merged.noise / kotlin.math.sqrt(merged.used.toFloat()), Detail.SENSOR_AMOUNT)
                val notes = "AnyCam sensor photo; ISO ${shot.iso} ${shot.exposureNs?.let { "1/${Math.round(1e9 / it)}" }}; ${frames.size} frames; " +
                    "look ${if (look === Look.PLAIN) "plain" else "from phone"}; reference ${t1 - t0} ms, stack ${t2 - t1} ms; ${merged.notes}"
                val uri = save(encode(finished), shot, notes = notes)
                Log.i(TAG, notes)
                Log.i(TAG, "photo from the sensor: ${base.w}x${base.h}, ${merged.used} of ${frames.size} shots merged " +
                    "(noise ${"%.1f".format(Locale.US, merged.noise)}), look ${if (look === Look.PLAIN) "plain" else "from the phone"}, " +
                    "wb ${info.wb.joinToString { "%.2f".format(Locale.US, it) }}, lens map ${info.shading != null}, ISO ${shot.iso}; " +
                    "reference ${t1 - t0} ms, stack ${t2 - t1} ms, all ${System.currentTimeMillis() - t0} ms")
                uri?.let { Photo(it, merged.used, frames.size, System.currentTimeMillis() - t0, fromSensor = true, megapixels = base.w.toLong() * base.h / 1_000_000f) }
            } catch (e: Throwable) {
                Log.e(TAG, "sensor photo failed, keeping the phone's own picture", e)
                try {
                    guide?.let { g -> save(encode(g), shot, notes = "AnyCam: the phone's own frame (sensor photo not used: ${e.message})")?.let { Photo(it, 1, frames.size, System.currentTimeMillis() - t0, sensorFailed = true) } }
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
    private fun save(jpeg: ByteArray, shot: CameraEngine.Shot, alreadyUpright: Boolean = false, notes: String? = null): Uri? {
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
                    // How the photo was made, kept with it (plain text; nothing about the place or the person).
                    notes?.let { exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, it.take(1800)) }
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
