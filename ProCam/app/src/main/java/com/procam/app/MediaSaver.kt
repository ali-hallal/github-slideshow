package com.procam.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes photos into the shared gallery under Pictures/ProCam (no storage permission needed on API 29+). */
object MediaSaver {
    private const val TAG = "MediaSaver"
    private const val FOLDER = "ProCam"

    fun newBaseName(): String =
        "PROCAM_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    fun save(context: Context, displayName: String, mimeType: String, writer: (OutputStream) -> Unit): Uri? {
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
