package com.procam.app

import android.content.Context
import java.io.File

/**
 * Keeps the original (unprocessed) photo behind each enhanced one — Portrait, Night, Silky,
 * Super zoom — privately inside the app, so the review screen can show before/after and redo a
 * portrait's blur. Only the most recent ones are kept, to bound storage.
 */
object OriginalStore {
    private const val DIR = "originals"
    private const val KEEP = 30

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    /** [displayName] is the enhanced photo's file name in the gallery. */
    fun save(context: Context, displayName: String, jpeg: ByteArray) {
        try {
            File(dir(context), displayName).writeBytes(jpeg)
            prune(context)
        } catch (_: Exception) {
        }
    }

    fun get(context: Context, displayName: String): File? =
        File(dir(context), displayName).takeIf { it.isFile && it.length() > 0 }

    fun delete(context: Context, displayName: String) {
        File(dir(context), displayName).delete()
    }

    private fun prune(context: Context) {
        val files = dir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(KEEP).forEach { it.delete() }
    }
}
