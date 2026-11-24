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

        root.add(text(getString(R.string.reminder_title), 14f, getColor(R.color.ink_soft), bold = true), top = 22)
        root.add(text(getString(R.string.reminder_hint), 13f, getColor(R.color.ink_soft)))
        val s = family.settings
        val at = String.format(java.util.Locale.US, "%02d:%02d", s.reminderMinutes / 60, s.reminderMinutes % 60)
        val remRow = row()
        for ((on, label) in listOf(false to getString(R.string.reminder_off), true to getString(R.string.reminder_at, at))) {
            val lit = s.reminder == on
            remRow.addView(text(label, 15f, if (lit) android.graphics.Color.WHITE else getColor(R.color.ink), bold = true).apply {
                background = rounded(if (lit) getColor(R.color.icon_bg) else 0x14000000, 20f)
                setPadding(dpi(14), dpi(9), dpi(14), dpi(9))
                setOnClickListener {
                    if (!on) { s.reminder = false; save(); Reminder.schedule(this@SettingsActivity); recreate() }
                    else android.app.TimePickerDialog(this@SettingsActivity, { _, h, m ->
                        s.reminder = true; s.reminderMinutes = h * 60 + m; save(); Reminder.schedule(this@SettingsActivity); recreate()
                    }, s.reminderMinutes / 60, s.reminderMinutes % 60, android.text.format.DateFormat.is24HourFormat(this@SettingsActivity)).show()
                }
            }, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpi(8) })
        }
        root.add(remRow, top = 8)

        // The other parent's phone.
        root.add(text(getString(R.string.link_title), 14f, getColor(R.color.ink_soft), bold = true), top = 22)
        root.add(text(getString(R.string.link_hint), 13f, getColor(R.color.ink_soft)))
        if (PhoneLink.linked(this)) {
            val last = PhoneLink.lastSync(this)
            root.add(text("✔ " + getString(R.string.link_linked), 15f, getColor(R.color.good), bold = true), top = 8)
            root.add(text(if (last == 0L) getString(R.string.link_never) else getString(R.string.link_last, whenText(last)), 13f, getColor(R.color.ink_soft)))
            root.add(pill(getString(R.string.link_sync)) { syncNow() }, top = 8)
            root.add(pill(getString(R.string.link_unlink), 0x14000000, getColor(R.color.ink)) {
                confirm(getString(R.string.link_unlink), getString(R.string.link_unlink_confirm)) { PhoneLink.unlink(this); recreate() }
            }, top = 8)
        } else {
            root.add(pill(getString(R.string.link_host)) { hostLink() }, top = 8)
            root.add(pill(getString(R.string.link_join), 0x14000000, getColor(R.color.ink)) { joinLink() }, top = 8)
        }

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

    private fun whenText(t: Long) = android.text.format.DateFormat.getMediumDateFormat(this).format(java.util.Date(t)) + " " +
        android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(t))

    private fun syncNow() {
        Toast.makeText(this, R.string.link_syncing, Toast.LENGTH_SHORT).show()
        PhoneLink.syncNow(this) { ok ->
            Toast.makeText(this, if (ok) R.string.link_synced else R.string.link_not_found, Toast.LENGTH_LONG).show()
            if (ok) recreate()
        }
    }

    /** This phone has the family: it waits for the other to join, then both check the code. */
    private fun hostLink() {
        val wait = AlertDialog.Builder(this).setTitle(R.string.link_host).setMessage(R.string.link_waiting)
            .setNegativeButton(R.string.cancel) { _, _ -> PhoneLink.stopHosting(this) }
            .setOnCancelListener { PhoneLink.stopHosting(this) }
            .show()
        PhoneLink.host(this) { code, answer ->
            wait.dismiss()
            AlertDialog.Builder(this).setTitle(R.string.link_code_title).setMessage(bigCode(code)).setCancelable(false)
                .setPositiveButton(R.string.link_match) { _, _ ->
                    answer(true)
                    Toast.makeText(this, R.string.link_done, Toast.LENGTH_LONG).show()
                    main.postDelayed({ recreate() }, 800)
                }
                .setNegativeButton(R.string.link_no_match) { _, _ -> answer(false); PhoneLink.stopHosting(this) }
                .show()
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun bigCode(code: String) = android.text.SpannableString(code.substring(0, 3) + " " + code.substring(3)).apply {
        setSpan(android.text.style.RelativeSizeSpan(2.4f), 0, length, 0)
        setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0)
    }

    /** This phone joins: it finds the waiting phone, both show the code, and it takes the family. */
    private fun joinLink() {
        confirm(getString(R.string.link_join), getString(R.string.link_join_warning)) {
            val looking = AlertDialog.Builder(this).setTitle(R.string.link_join).setMessage(R.string.link_looking)
                .setNegativeButton(R.string.cancel, null).show()
            var codeDialog: AlertDialog? = null
            PhoneLink.join(this, onCode = { code ->
                looking.dismiss()
                codeDialog = AlertDialog.Builder(this).setTitle(R.string.link_code_wait).setMessage(bigCode(code)).setCancelable(false).show()
            }, onJoined = { joined ->
                codeDialog?.dismiss()
                looking.dismiss()
                if (joined == null) { Toast.makeText(this, R.string.link_failed, Toast.LENGTH_LONG).show(); return@join }
                AlertDialog.Builder(this).setTitle(R.string.link_code_title).setMessage(getString(R.string.link_join_warning)).setCancelable(false)
                    .setPositiveButton(R.string.link_match) { _, _ ->
                        PhoneLink.accept(this, joined)
                        Toast.makeText(this, R.string.link_done, Toast.LENGTH_LONG).show()
                        recreate()
                    }
                    .setNegativeButton(R.string.link_no_match) { _, _ -> Toast.makeText(this, R.string.link_failed, Toast.LENGTH_LONG).show() }
                    .show()
            }, onNone = {
                looking.dismiss()
                Toast.makeText(this, R.string.link_none, Toast.LENGTH_LONG).show()
            })
        }
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
