package com.starsteps.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.starsteps.app.R
import com.starsteps.app.core.Child
import com.starsteps.app.core.Ledger

/** Home: every child at a glance (stars, today, the goal); tap one to note what happened. */
class MainActivity : BaseActivity() {
    private lateinit var list: LinearLayout
    private lateinit var status: android.widget.TextView
    private val synced: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = column(18)
        val top = row()
        top.add(text(getString(R.string.app_name), 28f, bold = true), 0, weight = 1f)
        val gear = text("⚙️", 24f).apply {
            setPadding(dpi(10), dpi(6), dpi(10), dpi(6))
            contentDescription = getString(R.string.settings)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        top.addView(gear)
        root.add(top)
        status = text("", 12f, getColor(R.color.ink_soft))
        root.add(status)
        list = column()
        root.add(list, top = 12)
        root.add(pill("＋  " + getString(R.string.add_child), getColor(R.color.accent), getColor(R.color.ink)) {
            childEditor(null) { c -> c?.let { open(it) } ?: refresh() }
        }.also { it.contentDescription = getString(R.string.add_child) }, top = 16)
        setContentView(FrameLayout(this).apply { addView(scrolling(root)) }.padForBars())
    }

    override fun onPause() {
        PhoneLink.unlisten(synced)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        PhoneLink.listen(synced)
        refresh()
        Reminder.schedule(this)
        // The evening reminder needs leave to notify (asked once, once there is a child to remind about).
        if (family.settings.reminder && family.children.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val prefs = getSharedPreferences("starsteps", MODE_PRIVATE)
            if (!prefs.getBoolean("askedNotify", false)) {
                prefs.edit().putBoolean("askedNotify", true).apply()
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    private fun open(c: Child) = startActivity(Intent(this, ChildActivity::class.java).putExtra("child", c.id))

    private fun refresh() {
        val last = PhoneLink.lastSync(this)
        status.text = if (PhoneLink.linked(this) && last > 0) getString(R.string.home_synced, android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(last))) else ""
        status.visibility = if (status.text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        list.removeAllViews()
        val t = today()
        if (family.children.isEmpty()) {
            list.add(card().apply { addView(text(getString(R.string.no_children), 16f, getColor(R.color.ink_soft))) })
            return
        }
        for (c in family.children) {
            val g = c.group(t)
            val bal = Ledger.balance(family, c.id)
            val cardView = row(14).apply { background = rounded(getColor(R.color.card)) }
            val face = text(c.avatar, 34f).apply {
                gravity = Gravity.CENTER
                background = rounded(c.colour, 32f)
            }
            cardView.addView(face, LinearLayout.LayoutParams(dpi(64), dpi(64)))
            val info = column().apply { setPadding(dpi(14), 0, 0, 0) }
            info.add(text(c.name, 20f, bold = true))
            info.add(text(groupName(g), 13f, getColor(R.color.ink_soft)))
            val net = Ledger.netOn(family, c.id, t)
            info.add(text("★ " + getString(R.string.balance, bal, unitWord(g, bal)) + "   ·   " + getString(R.string.today_earned, net), 15f,
                if (bal < 0) getColor(R.color.problem) else getColor(R.color.ink)), top = 4)
            Ledger.goalProgress(family, c)?.let { p ->
                val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100; progress = (p * 100).toInt()
                    progressTintList = android.content.res.ColorStateList.valueOf(c.colour)
                }
                info.add(bar, top = 6)
            }
            cardView.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            cardView.addView(text("›", 28f, getColor(R.color.ink_soft)))
            cardView.setOnClickListener { open(c) }
            cardView.contentDescription = c.name
            list.add(cardView, top = 10)
        }
    }
}
