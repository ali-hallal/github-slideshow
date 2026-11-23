package com.starsteps.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import com.starsteps.app.R
import com.starsteps.app.core.Storage

/** Language, the parent code, and backups (a file the parents keep wherever they like). */
class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = column(18)
        root.add(text(getString(R.string.settings), 26f, bold = true))

        root.add(text(getString(R.string.language), 14f, getColor(R.color.ink_soft), bold = true), top = 18)
        val langs = listOf("" to getString(R.string.language_phone), "en" to getString(R.string.language_en), "fr" to getString(R.string.language_fr))
        val langRow = row()
        for ((code, name) in langs) {
            val on = family.settings.language == code
            langRow.addView(text(name, 15f, if (on) android.graphics.Color.WHITE else getColor(R.color.ink), bold = true).apply {
                background = rounded(if (on) getColor(R.color.icon_bg) else 0x14000000, 20f)
                setPadding(dpi(14), dpi(9), dpi(14), dpi(9))
                setOnClickListener { setLanguage(code) }
            }, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpi(8) })
        }
        root.add(android.widget.HorizontalScrollView(this).apply { addView(langRow) }, top = 6)

        root.add(text(getString(R.string.pin_title), 14f, getColor(R.color.ink_soft), bold = true), top = 22)
        root.add(text(getString(R.string.pin_hint), 13f, getColor(R.color.ink_soft)))
        root.add(pill(getString(if (family.settings.pin.isEmpty()) R.string.pin_none else R.string.pin_set) + "  ›", 0x14000000, getColor(R.color.ink)) { askPin() }, top = 8)

        root.add(pill("💾  " + getString(R.string.backup)) {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "starsteps-backup-${today()}.json"), BACKUP)
        }, top = 26)
        root.add(pill("📂  " + getString(R.string.restore), 0x14000000, getColor(R.color.ink)) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), RESTORE)
        }, top = 10)
        root.add(text(getString(R.string.about), 13f, getColor(R.color.ink_soft)), top = 26)
        setContentView(FrameLayout(this).apply { addView(scrolling(root)) }.padForBars())
    }

    private fun setLanguage(code: String) {
        family.settings.language = code
        save()
        // Every screen again, in the new language.
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun askPin() {
        withParentCode {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                gravity = Gravity.CENTER
                textSize = 24f
            }
            AlertDialog.Builder(this).setTitle(R.string.pin_title).setMessage(R.string.pin_hint).setView(padded(input))
                .setPositiveButton(R.string.save) { _, _ ->
                    family.settings.pin = input.text.toString().filter { it.isDigit() }.take(8)
                    save(); recreate()
                }
                .setNegativeButton(R.string.cancel, null).show()
        }
    }

    @Deprecated("Simple document picker results")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            BACKUP -> try {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(Storage.encode(family).toByteArray()) }
                Toast.makeText(this, R.string.backup_done, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            }
            RESTORE -> {
                val restored = try {
                    Storage.decode(contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
                } catch (e: Exception) {
                    null
                }
                if (restored == null) { Toast.makeText(this, R.string.restore_bad, Toast.LENGTH_LONG).show(); return }
                confirm(getString(R.string.restore), getString(R.string.restore_confirm)) {
                    Store.replace(this, restored)
                    Toast.makeText(this, R.string.restore_done, Toast.LENGTH_SHORT).show()
                    setLanguage(restored.settings.language)
                }
            }
        }
    }

    private companion object {
        const val BACKUP = 1
        const val RESTORE = 2
    }
}
