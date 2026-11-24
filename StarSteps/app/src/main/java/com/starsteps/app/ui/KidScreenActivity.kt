package com.starsteps.app.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import com.starsteps.app.R
import com.starsteps.app.core.AgeGroup
import com.starsteps.app.core.Child
import com.starsteps.app.core.EntryType
import com.starsteps.app.core.Ledger
import com.starsteps.app.core.Rocket

/**
 * What the child sees: the parent hands over the phone. Nothing can be changed here; going
 * back needs a long press (and the parent code, if one is set).
 *
 * The look follows the age: big stars and pictures for the little ones, stars and a goal for
 * children, points, streaks and the week for pre-teens, and a calm, grown-up page for teenagers.
 */
class KidScreenActivity : BaseActivity() {
    private lateinit var child: Child

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        child = family.child(intent.getLongExtra("child", -1)) ?: run { finish(); return }
        build()
    }

    @Deprecated("Back is ignored: the child stays on their page")
    override fun onBackPressed() {
        Toast.makeText(this, R.string.hold_to_exit, Toast.LENGTH_SHORT).show()
    }

    private fun build() {
        val t = today()
        val g = child.group(t)
        val style = g.screen
        val bal = Ledger.balance(family, child.id)
        // What today is worth: points won minus points lost.
        val todayEarned = Ledger.netOn(family, child.id, t)
        val dark = style == AgeGroup.Screen.SPORTY
        val ink = if (dark) Color.WHITE else getColor(R.color.ink)
        val soft = if (dark) 0xCCFFFFFF.toInt() else getColor(R.color.ink_soft)
        val bg: android.graphics.drawable.Drawable = when (style) {
            AgeGroup.Screen.PLAYFUL -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(lighten(child.colour, 0.55f), child.colour))
            AgeGroup.Screen.SPORTY -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF1E2A4A.toInt(), 0xFF0F1629.toInt()))
            AgeGroup.Screen.CALM -> GradientDrawable().apply { setColor(0xFFF4F4F2.toInt()) }
        }
        val root = column(24).apply { gravity = Gravity.CENTER_HORIZONTAL }

        // Who: a big picture for the young ones; for teenagers, just the name.
        if (style != AgeGroup.Screen.CALM) root.add(text(child.avatar, if (g == AgeGroup.LITTLE) 96f else 72f).apply { gravity = Gravity.CENTER }, top = 12)
        root.add(text(getString(R.string.kid_hello, child.name), if (style == AgeGroup.Screen.CALM) 26f else 30f, ink, bold = true).apply { gravity = Gravity.CENTER }, top = 8)

        // Today: drawn stars to fill up for the little ones; a bar to the day's target for the others.
        root.add(text(getString(R.string.kid_today), 15f, soft, bold = true).apply { gravity = Gravity.CENTER }, top = 22)
        if (!g.numbers) {
            val shown = todayEarned.coerceIn(0, g.dailyTarget)
            val stars = "★".repeat(shown) + "☆".repeat(g.dailyTarget - shown) + if (todayEarned > g.dailyTarget) "  +" + (todayEarned - g.dailyTarget) else ""
            root.add(text(stars, 44f, 0xFFFFB300.toInt()).apply { gravity = Gravity.CENTER; setShadowLayer(4f, 0f, 2f, 0x55000000) })
        } else {
            root.add(text("$todayEarned / ${g.dailyTarget} ${unitWord(g, 2)}", 22f, ink, bold = true).apply { gravity = Gravity.CENTER })
            root.add(bar(todayEarned.toFloat() / g.dailyTarget, if (dark) 0xFF4FC3F7.toInt() else 0xFFFFB300.toInt(), dark), top = 6)
        }

        // The rocket to colour in.
        run {
            root.add(text(getString(R.string.rocket_title), 15f, soft, bold = true).apply { gravity = Gravity.CENTER }, top = 22)
            val caption = text("", 16f, ink, bold = true).apply { gravity = Gravity.CENTER }
            val rocket = RocketView(this)
            fun show() {
                val mood = Rocket.mood(family, child.id, t)
                val done = Rocket.coloured(child, t)
                val allowed = Rocket.allowed(family, child, t)
                rocket.show(mood, done, allowed)
                val left = allowed - Rocket.count(done)
                val lines = ArrayList<String>()
                lines += when {
                    Rocket.complete(child, t) -> getString(R.string.rocket_done)
                    allowed == 0 -> getString(R.string.rocket_none)
                    left > 0 -> getString(R.string.rocket_paint, left)
                    else -> getString(R.string.rocket_more, unitWord(g, 2))
                }
                if (mood == Rocket.Mood.ORANGE) lines += getString(R.string.rocket_orange)
                if (mood == Rocket.Mood.RED) lines += getString(R.string.rocket_red)
                caption.text = lines.joinToString("\n")
            }
            rocket.onPaint = { part ->
                val ok = Rocket.paint(family, child, t, part)
                if (ok) { save(); show() }
                ok
            }
            show()
            root.add(rocket, top = 4)
            root.add(caption, top = 4)
        }

        // What they did well today: the point of the page.
        val good = family.entriesOf(child.id).filter { it.day == t && (it.type == EntryType.EARNED || it.type == EntryType.BONUS) }
        if (good.isNotEmpty()) {
            root.add(text(getString(R.string.kid_good_today), 15f, soft, bold = true).apply { gravity = Gravity.CENTER }, top = 22)
            val faces = good.takeLast(6).joinToString("   ") { it.emoji }
            root.add(text(faces, if (g == AgeGroup.LITTLE) 40f else 30f, ink).apply { gravity = Gravity.CENTER })
            if (g != AgeGroup.LITTLE) root.add(text(good.takeLast(4).joinToString(" · ") { it.label }, 14f, soft).apply { gravity = Gravity.CENTER })
        } else {
            root.add(text(getString(R.string.kid_keep_going), 18f, soft).apply { gravity = Gravity.CENTER }, top = 22)
        }

        // Their stars or points, and the goal.
        val total = card(if (dark) 0x22FFFFFF else 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL }
        total.add(text(getString(if (g.unit == AgeGroup.Unit.STARS) R.string.kid_stars else R.string.kid_points), 15f, soft, bold = true).apply { gravity = Gravity.CENTER })
        total.add(text((if (g.unit == AgeGroup.Unit.STARS) "★ " else "") + bal, 52f, if (bal < 0) 0xFFE53935.toInt() else ink, bold = true).apply { gravity = Gravity.CENTER })
        family.rewards.firstOrNull { it.id == child.goalRewardId }?.let { goal ->
            total.add(text("🎯  " + goal.emoji + " " + goal.name, 18f, ink, bold = true).apply { gravity = Gravity.CENTER }, top = 8)
            total.add(bar(bal.toFloat() / goal.cost, child.colour, dark), top = 6)
            val left = goal.cost - bal
            total.add(text(if (left <= 0) getString(R.string.kid_goal_reached) else getString(R.string.to_go, left), 15f, soft).apply { gravity = Gravity.CENTER }, top = 4)
        }
        root.add(total, top = 22)

        // Older children: their streak and their week.
        if (g == AgeGroup.PRETEEN || g == AgeGroup.TEEN || g == AgeGroup.CHILD) {
            val streak = Ledger.streak(family, child.id, t)
            if (streak >= 2) root.add(text("🔥 " + getString(R.string.streak, streak), 20f, ink, bold = true).apply { gravity = Gravity.CENTER }, top = 18)
        }
        if (g == AgeGroup.PRETEEN || g == AgeGroup.TEEN) {
            root.add(text(getString(R.string.kid_week), 15f, soft, bold = true).apply { gravity = Gravity.CENTER }, top = 18)
            root.add(week(Ledger.week(family, child.id, t), if (dark) 0xFF4FC3F7.toInt() else child.colour, soft), h = dpi(110), top = 6)
        }

        // Today's agreed consequence, said plainly (not for the youngest: theirs is over in minutes).
        if (g != AgeGroup.LITTLE) {
            for (e in Ledger.consequencesToday(family, child.id, t)) root.add(text("• " + e.note, 14f, soft).apply { gravity = Gravity.CENTER }, top = 10)
        }

        val exit = text(getString(R.string.hold_to_exit), 13f, soft).apply {
            gravity = Gravity.CENTER
            setPadding(dpi(16), dpi(10), dpi(16), dpi(10))
            background = rounded(if (dark) 0x22FFFFFF else 0x14000000, 20f)
            contentDescription = getString(R.string.hold_to_exit)
            setOnLongClickListener { withParentCode { finish() }; true }
        }
        root.add(exit, w = LinearLayout.LayoutParams.WRAP_CONTENT, top = 28)

        // The page fills the screen behind the status bar (hiding the bars makes Android cover the
        // top of the page with a notice the first time).
        // The page's colour goes right to the screen's edges (behind the status and navigation
        // bars), with light bar icons on the dark page and dark ones elsewhere.
        val frame = FrameLayout(this)
        frame.addView(scrolling(root))
        frame.padForBars()
        window.setBackgroundDrawable(bg)
        window.decorView.background = bg
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContentView(frame)
        val lightBars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (dark) 0 else lightBars, lightBars)
    }

    private fun bar(p: Float, colour: Int, dark: Boolean) = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = (p.coerceIn(0f, 1f) * 100).toInt()
        progressTintList = android.content.res.ColorStateList.valueOf(colour)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(if (dark) 0x44FFFFFF else 0x33000000)
        scaleY = 3f
    }

    /** Seven bars, oldest day first. */
    private fun week(values: List<Int>, colour: Int, labelColour: Int): View {
        val most = (values.maxOrNull() ?: 0).coerceAtLeast(1)
        val r = row().apply { gravity = Gravity.BOTTOM }
        for (v in values) {
            val col = column().apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
            col.add(text(if (v > 0) v.toString() else "", 11f, labelColour).apply { gravity = Gravity.CENTER })
            col.add(View(this).apply { background = rounded(colour, 6f) }, h = dpi((8 + 70 * v / most).coerceAtLeast(4)))
            r.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dpi(4); marginEnd = dpi(4) })
        }
        return r
    }

    private fun lighten(c: Int, f: Float): Int {
        fun ch(x: Int) = (x + (255 - x) * f).toInt()
        return Color.rgb(ch(Color.red(c)), ch(Color.green(c)), ch(Color.blue(c)))
    }
}
