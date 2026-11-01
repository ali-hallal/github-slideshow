package com.procam.app

import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.exifinterface.media.ExifInterface
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Review screen: the latest ProCam photos, with before/after for enhanced ones (Portrait, Night,
 * Silky, Super zoom), how each photo was made, portrait blur that can be changed afterwards,
 * and share / save original / delete / open in gallery.
 */
class ReviewActivity : Activity() {

    private class Photo(val id: Long, val name: String, val mime: String) {
        val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    }

    private lateinit var prefs: Prefs
    private lateinit var view: BeforeAfterView
    private lateinit var title: TextView
    private lateinit var counter: TextView
    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var blurRow: LinearLayout
    private lateinit var blurButtons: List<TextView>
    private lateinit var saveOriginal: TextView
    private lateinit var prev: TextView
    private lateinit var next: TextView

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val portrait by lazy { PortraitProcessor() }
    private var photos = emptyList<Photo>()
    private var index = 0
    private var loadToken = 0
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        buildUi()
        val wanted = intent.data
        io.execute {
            val list = queryPhotos()
            main.post {
                photos = list
                index = wanted?.let { u -> list.indexOfFirst { it.uri == u || it.id.toString() == u.lastPathSegment } }
                    ?.takeIf { it >= 0 } ?: 0
                show()
            }
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.black))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        top.addView(button("←", 24f) { finish() }.apply { contentDescription = "Back" })
        title = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.white))
        }
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        prev = button("‹", 28f) { step(-1) }.apply { contentDescription = "Newer photo" }
        counter = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.dim))
        }
        next = button("›", 28f) { step(1) }.apply { contentDescription = "Older photo" }
        top.addView(prev)
        top.addView(counter)
        top.addView(next)
        root.addView(top)

        view = BeforeAfterView(this)
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))

        info = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.white))
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(4))
        }
        root.addView(info)
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(R.color.accent))
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), dp(4))
        }
        root.addView(status)

        // Portrait: change the blur afterwards.
        blurRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.segment_bg)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            visibility = View.GONE
        }
        blurButtons = listOf("Soft blur", "Medium", "Strong").mapIndexed { i, label ->
            TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                minWidth = dp(72)
                setOnClickListener { reblur(i) }
            }.also { blurRow.addView(it, LinearLayout.LayoutParams(-2, dp(36))) }
        }
        root.addView(blurRow, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(6)
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(10), dp(8), dp(14))
        }
        fun action(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.white))
            setPadding(0, dp(12), 0, dp(12))
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }
        }.also { actions.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        action("Share") { share() }
        saveOriginal = action("Save original") { saveOriginal() }
        action("Delete") { confirmDelete() }
        action("Gallery") { openInGallery() }
        root.addView(actions)

        setContentView(root)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
    }

    private fun button(label: String, size: Float, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(getColor(R.color.white))
        setPadding(dp(14), dp(2), dp(14), dp(2))
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ showing photos

    private fun step(delta: Int) {
        if (busy || photos.isEmpty()) return
        val n = (index + delta).coerceIn(0, photos.size - 1)
        if (n != index) {
            index = n
            show()
        }
    }

    private fun show() {
        if (photos.isEmpty()) {
            title.text = "No photos yet"
            counter.text = ""
            info.text = "Photos you take with ProCam appear here."
            view.after = null
            view.before = null
            blurRow.visibility = View.GONE
            saveOriginal.visibility = View.GONE
            return
        }
        val p = photos[index]
        val (mode, what) = describe(p.name)
        title.text = mode
        counter.text = "${index + 1} / ${photos.size}"
        prev.alpha = if (index > 0) 1f else 0.3f
        next.alpha = if (index < photos.size - 1) 1f else 0.3f
        status.text = ""
        val token = ++loadToken
        val target = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        io.execute {
            val after = decode(p.uri, target)
            val original = OriginalStore.get(this, p.name)
            val before = original?.let { decodeFile(it, target) }
            val details = exifLine(p.uri)
            main.post {
                if (token != loadToken) return@post
                view.after = after
                view.before = before
                info.text = listOfNotNull(what.ifEmpty { null }, details.ifEmpty { null }).joinToString("\n")
                if (before != null) status.text = "Drag the line to compare before and after"
                val isPortrait = p.name.contains("_PORTRAIT") && original != null
                blurRow.visibility = if (isPortrait) View.VISIBLE else View.GONE
                if (isPortrait) highlightBlur(prefs.portraitStrength)
                saveOriginal.visibility = if (original != null) View.VISIBLE else View.GONE
                if (after == null) info.text = "This photo can't be shown here · tap Gallery"
            }
        }
    }

    private fun highlightBlur(selected: Int) {
        blurButtons.forEachIndexed { i, v ->
            v.setBackgroundResource(if (i == selected) R.drawable.segment_selected_bg else 0)
            v.setTextColor(getColor(if (i == selected) R.color.white else R.color.dim))
        }
    }

    private fun decode(uri: Uri, target: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ -> sizeDecoder(d, info, target) }
    } catch (e: Exception) {
        Log.w(TAG, "decode $uri", e)
        null
    }

    private fun decodeFile(file: java.io.File, target: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, info, _ -> sizeDecoder(d, info, target) }
    } catch (e: Exception) {
        null
    }

    private fun sizeDecoder(d: ImageDecoder, info: ImageDecoder.ImageInfo, target: Int) {
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = max(info.size.width, info.size.height)
        if (longest > target) {
            val s = target.toFloat() / longest
            d.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
        }
    }

    /** "ISO 3200 · 1/30 s · f/1.8" from the photo's EXIF (what the camera did). */
    private fun exifLine(uri: Uri): String = try {
        contentResolver.openInputStream(uri)?.use { input ->
            val e = ExifInterface(input)
            val parts = mutableListOf<String>()
            e.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 }?.let { parts += "ISO $it" }
            e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let { parts += "${Stops.shutterLabel((it * 1e9).toLong())} s" }
            e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { parts += String.format(Locale.US, "f/%.1f", it) }
            parts.joinToString(" · ")
        } ?: ""
    } catch (e: Exception) {
        ""
    }

    // ------------------------------------------------------------------ actions

    private fun reblur(strength: Int) {
        if (busy || photos.isEmpty()) return
        val p = photos[index]
        val original = OriginalStore.get(this, p.name) ?: return
        busy = true
        highlightBlur(strength)
        prefs.portraitStrength = strength
        status.text = "Re-creating portrait…"
        val quality = prefs.jpegQuality
        io.execute {
            val ok = try {
                val bytes = original.readBytes()
                val result = portrait.process(bytes, strength, quality)
                val jpeg = result.jpeg
                if (jpeg != null) {
                    contentResolver.openOutputStream(p.uri, "wt")?.use { it.write(jpeg) }
                    MediaSaver.copyExif(this, p.uri, bytes)
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "reblur", e)
                false
            }
            main.post {
                busy = false
                if (ok) show() else status.text = "Couldn't change this portrait"
            }
        }
    }

    private fun share() {
        if (photos.isEmpty()) return
        val p = photos[index]
        val send = Intent(Intent.ACTION_SEND)
            .setType(p.mime)
            .putExtra(Intent.EXTRA_STREAM, p.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share photo"))
    }

    private fun saveOriginal() {
        if (photos.isEmpty()) return
        val p = photos[index]
        val file = OriginalStore.get(this, p.name) ?: return
        io.execute {
            val name = p.name.substringBeforeLast('.') + "_ORIGINAL.jpg"
            val uri = MediaSaver.save(this, name, "image/jpeg", writer = { it.write(file.readBytes()) })
            main.post { toast(if (uri != null) "Original saved to the gallery" else getString(R.string.save_failed)) }
        }
    }

    private fun openInGallery() {
        if (photos.isEmpty()) return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(photos[index].uri, photos[index].mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_viewer))
        }
    }

    private fun confirmDelete() {
        if (photos.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Delete this photo?")
            .setMessage("It will be removed from your gallery.")
            .setPositiveButton("Delete") { _, _ -> delete() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun delete() {
        val p = photos[index]
        try {
            if (contentResolver.delete(p.uri, null, null) > 0) deleted(p)
        } catch (e: SecurityException) {
            // Photos from before a reinstall belong to the old install: Android asks the user.
            try {
                val sender = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                        MediaStore.createDeleteRequest(contentResolver, listOf(p.uri)).intentSender
                    e is RecoverableSecurityException -> e.userAction.actionIntent.intentSender
                    else -> null
                }
                if (sender != null) startIntentSenderForResult(sender, REQUEST_DELETE, null, 0, 0, 0)
                else toast("Can't delete this photo here · use your gallery app")
            } catch (e2: Exception) {
                toast("Can't delete this photo here · use your gallery app")
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DELETE && resultCode == RESULT_OK && photos.isNotEmpty()) deleted(photos[index])
    }

    private fun deleted(p: Photo) {
        OriginalStore.delete(this, p.name)
        photos = photos.filter { it.id != p.id }
        index = index.coerceAtMost(photos.size - 1).coerceAtLeast(0)
        toast("Deleted")
        show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ data

    private fun queryPhotos(): List<Photo> = try {
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("Pictures/ProCam%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC",
        )?.use { c ->
            val out = mutableListOf<Photo>()
            while (c.moveToNext()) out += Photo(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "image/jpeg")
            out
        } ?: emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "query", e)
        emptyList()
    }

    companion object {
        private const val TAG = "Review"
        private const val REQUEST_DELETE = 7

        /** What kind of photo this is, from the name ProCam gave it, and a one-line explanation. */
        fun describe(name: String): Pair<String, String> = when {
            name.contains("_PORTRAIT") -> "Portrait" to "Background blurred to make the person stand out"
            name.contains("_NIGHT") -> "Night" to "Several shots merged for a brighter, cleaner photo"
            name.contains("_SILKY") -> "Silky" to "Shots blended so moving things become smooth streaks"
            name.contains("_ZOOM") -> "Super zoom" to "Built from a burst for more detail than digital zoom"
            name.contains("_ACTION") -> "Freeze" to "The sharpest moment around your press"
            name.contains("_ORIGINAL") -> "Original" to "Saved without ProCam's enhancement"
            name.endsWith(".dng", ignoreCase = true) -> "RAW" to "Unprocessed sensor data, for editing apps"
            else -> "Photo" to ""
        }
    }
}
