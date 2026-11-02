package com.procam.app

import android.content.Context
import java.io.File

/**
 * Keeps the original (unprocessed) photo behind each enhanced one — Portrait, Night, Silky, HDR,
 * Super zoom — privately inside the app, so the review screen can show before/after and redo a
 * portrait's blur. The oldest are removed once they take more than the space chosen in Settings.
 */
object OriginalStore {
    private const val DIR = "originals"

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    /** [displayName] is the enhanced photo's file name in the gallery. */
    fun save(context: Context, displayName: String, jpeg: ByteArray) {
        try {
            File(dir(context), displayName).writeBytes(jpeg)
            prune(dir(context), Prefs(context).originalsMb * 1_000_000L)
        } catch (_: Exception) {
        }
    }

    fun get(context: Context, displayName: String): File? =
        File(dir(context), displayName).takeIf { it.isFile && it.length() > 0 }

    fun delete(context: Context, displayName: String) {
        File(dir(context), displayName).delete()
    }

    /** Bytes used by the kept originals. */
    fun usedBytes(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    fun count(context: Context): Int = dir(context).listFiles()?.size ?: 0

    /** Removes all kept originals (the enhanced photos stay; they just lose before/after). */
    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /** Applies a new limit right away (e.g. after it was lowered in Settings). */
    fun trim(context: Context) = prune(dir(context), Prefs(context).originalsMb * 1_000_000L)

    /** Deletes the oldest files until the rest fit in [maxBytes] (the newest is always kept). */
    fun prune(dir: File, maxBytes: Long) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        files.forEachIndexed { i, f ->
            total += f.length()
            if (i > 0 && total > maxBytes) f.delete()
        }
    }
}
