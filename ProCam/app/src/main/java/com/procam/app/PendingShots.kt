package com.procam.app

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * No photo is lost while it is being made.
 *
 * Night, HDR, zoom, portrait and the other merged photos exist only in memory until the merge
 * is done — seconds later. If the app is closed or killed in between, the shot would be gone.
 * So the moment a burst arrives, one plain frame of it is put aside in the app's own storage
 * ([hold]); when the finished photo is saved the copy is dropped ([release]). A copy still there
 * at the next launch belongs to a photo that was never finished: it goes to the gallery as it
 * is ([recover]).
 */
object PendingShots {
    private const val TAG = "ProCam"
    private val io = Executors.newSingleThreadExecutor()
    private val counter = AtomicInteger()

    private fun dir(context: Context) = File(context.filesDir, "pending")

    /** Puts [frame] aside (written in the background). The result is what [release] takes. */
    fun hold(context: Context, frame: ByteArray?, mimeType: String): File? {
        frame ?: return null
        val ext = if (mimeType == "image/heic") "heic" else "jpg"
        val file = File(dir(context), "${System.currentTimeMillis()}_${counter.incrementAndGet()}.$ext")
        io.execute {
            try {
                file.parentFile?.mkdirs()
                // Written under another name first: a half-written copy is never taken for a photo.
                val tmp = File(file.path + ".tmp")
                tmp.writeBytes(frame)
                if (!tmp.renameTo(file)) tmp.delete()
            } catch (e: Exception) {
                Log.w(TAG, "could not keep a safety copy", e)
            }
        }
        return file
    }

    /** The finished photo is saved: the safety copy isn't needed any more. */
    fun release(held: File?) {
        held ?: return
        io.execute { held.delete() }
    }

    /**
     * Saves the photos that were never finished (from an earlier run of the app) to the gallery.
     * Returns how many. Blocking; [inBackground] does it off the main thread.
     */
    fun recover(context: Context): Int {
        val files = dir(context).listFiles() ?: return 0
        var saved = 0
        for (f in files.sortedBy { it.name }) {
            if (f.name.endsWith(".tmp")) { f.delete(); continue }
            try {
                val heic = f.name.endsWith(".heic")
                val uri = MediaSaver.save(
                    context, "${MediaSaver.newBaseName()}_RECOVERED.${if (heic) "heic" else "jpg"}",
                    if (heic) "image/heic" else "image/jpeg", writer = { out -> f.inputStream().use { it.copyTo(out) } },
                )
                if (uri != null) {
                    saved++
                    f.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not recover ${f.name}", e)
            }
        }
        if (saved > 0) Log.i(TAG, "recovered $saved unfinished photo(s)")
        return saved
    }

    /** [recover] on the storage thread, before any new copy is written; [done] gets the count there. */
    fun inBackground(context: Context, done: (Int) -> Unit) {
        val app = context.applicationContext
        io.execute { done(recover(app)) }
    }

    /** Waits for the copies queued so far to be written or dropped (tests). */
    fun flush() = io.submit {}.get()
}
