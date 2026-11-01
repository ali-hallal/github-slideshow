package com.procam.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes photos into the shared gallery under Pictures/ProCam (no storage permission needed on API 29+). */
object MediaSaver {
    private const val TAG = "MediaSaver"
    private const val FOLDER = "ProCam"

    /** Marks a JPEG as horizontally mirrored via EXIF (lossless), for selfies that match the preview. */
    fun mirrorJpeg(context: Context, uri: Uri) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.flipHorizontally()
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "mirror failed", e)
        }
    }

    /**
     * Copies the useful photo details (date, camera, exposure, GPS) from the original JPEG into a
     * processed copy, which is saved upright.
     */
    fun copyExif(context: Context, uri: Uri, original: ByteArray) {
        try {
            val src = ExifInterface(java.io.ByteArrayInputStream(original))
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val dst = ExifInterface(pfd.fileDescriptor)
                for (tag in COPIED_TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
                dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                dst.saveAttributes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "exif copy failed", e)
        }
    }

    private val COPIED_TAGS = arrayOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    )

    /** Date taken (and GPS position if known) for photos built from raw frames. */
    fun writeBasicExif(context: Context, uri: Uri, location: android.location.Location?) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                val now = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
                exif.setAttribute(ExifInterface.TAG_DATETIME, now)
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "ProCam super zoom")
                if (location != null) exif.setGpsInfo(location)
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "exif write failed", e)
        }
    }

    fun newBaseName(): String =
        "PROCAM_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    fun save(
        context: Context,
        displayName: String,
        mimeType: String,
        writer: (OutputStream) -> Unit,
        afterWrite: ((Uri) -> Unit)? = null,
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            Log.e(TAG, "insert failed", e)
            null
        } ?: return null

        return try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("No output stream for $uri")
            stream.use(writer)
            afterWrite?.invoke(uri)
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "write failed", e)
            resolver.delete(uri, null, null)
            null
        }
    }
}
