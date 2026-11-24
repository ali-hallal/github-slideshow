package com.starsteps.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.starsteps.app.R
import com.starsteps.app.core.AgeGroup
import com.starsteps.app.core.Child
import com.starsteps.app.core.Entry
import com.starsteps.app.core.EntryType
import com.starsteps.app.core.Kind
import com.starsteps.app.core.Ledger

/**
 * One child, for the parents: tap what happened. Good moments add stars (with an undo for a
 * slip of the finger), problems bring the consequence agreed in advance, rewards are bought
 * with what was earned, and the history shows it all.
 */
class ChildActivity : BaseActivity() {
    private lateinit var child: Child
    private lateinit var header: LinearLayout
    private lateinit var notes: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var undoBar: LinearLayout
    private lateinit var undoText: TextView
    private var tab = 0
    private var lastEntry: Entry? = null
    private val group get() = child.group(today())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        child = family.child(intent.getLongExtra("child", -1)) ?: run { finish(); return }
        val root = column(16)
        header = column()
        root.add(header)
        notes = column()
        root.add(notes)
        // Five tabs: in two rows, so all are always in view.
        tabs = column()
        root.add(tabs, top = 14)
        content = column()
        root.add(content, top = 10)
        root.add(text(getString(R.string.hold_hint), 12f, getColor(R.color.ink_soft)), top = 14)

        undoBar = row(12).apply {
            background = rounded(getColor(R.color.ink), 14f)
            visibility = View.GONE
        }
        undoText = text("", 15f, Color.WHITE)
        undoBar.addView(undoText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        undoBar.addView(text(getString(R.string.undo), 15f, getColor(R.color.accent), bold = true).apply {
            setPadding(dpi(12), dpi(4), dpi(4), dpi(4))
            setOnClickListener {
                lastEntry?.let { Ledger.undo(family, it.id); save() }
                lastEntry = null
                undoBar.visibility = View.GONE
                refresh()
            }
        })
        val frame = FrameLayout(this)
        frame.addView(scrolling(root))
        frame.addView(undoBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dpi(14), 0, dpi(14), dpi(14))
        })
        setContentView(frame.padForBars())
    }

    private val synced: () -> Unit = { refresh() }

    override fun onResume() {
        super.onResume()
        PhoneLink.listen(synced)
        refresh()
    }

    override fun onPause() {
        PhoneLink.unlisten(synced)
        super.onPause()
    }

    private fun refresh() {
        // After the other phone's changes came in, the family is a new one: find the child again.
        child = family.child(child.id) ?: run { finish(); return }
        val t = today()
        val g = group
        val bal = Ledger.balance(family, child.id)
        // Who, how many stars, and the button to show the child.
        header.removeAllViews()
        val top = row(16).apply { background = rounded(child.colour, 22f) }
        top.addView(text(child.avatar, 44f), LinearLayout.LayoutParams(dpi(70), LinearLayout.LayoutParams.WRAP_CONTENT))
        val who = column()
        who.add(text(child.name, 24f, getColor(R.color.ink), bold = true))
        who.add(text(getString(R.string.age_years, child.age(t)) + " · " + groupName(g), 13f, getColor(R.color.ink)))
        val net = Ledger.netOn(family, child.id, t)
        who.add(text("★ " + getString(R.string.balance, bal, unitWord(g, bal)) + "   ·   " + getString(R.string.today_earned, net),
            16f, if (bal < 0) getColor(R.color.problem) else getColor(R.color.ink), bold = true), top = 4)
        val streak = Ledger.streak(family, child.id, t)
        if (streak >= 2) who.add(text("🔥 " + getString(R.string.streak, streak), 14f, getColor(R.color.ink)))
        top.addView(who, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(text("✏️", 22f).apply {
            setPadding(dpi(8), dpi(8), dpi(8), dpi(8))
            contentDescription = getString(R.string.edit_child)
            setOnClickListener { childEditor(child) { c -> if (c == null) finish() else refresh() } }
        })
        header.add(top)
        header.add(pill("👀  " + getString(R.string.show_to, child.name)) {
            startActivity(Intent(this, KidScreenActivity::class.java).putExtra("child", child.id))
        }.also { it.contentDescription = getString(R.string.show_to, child.name) }, top = 10)

        // A gentle hint when problems outnumber praise; today's consequences; the tip for the age.
        notes.removeAllViews()
        val mix = Ledger.mix(family, child.id, t)
        if (!mix.healthy) notes.add(card(0xFFFFF1D6.toInt()).apply { addView(text("💡 " + getString(R.string.hint_ratio), 14f)) }, top = 10)
        val today = Ledger.consequencesToday(family, child.id, t)
        if (today.isNotEmpty()) notes.add(card(0xFFFDE5DD.toInt()).apply {
            addView(text(getString(R.string.consequences_today), 13f, getColor(R.color.problem), bold = true))
            for (e in today) addView(text(e.emoji + "  " + e.label + " → " + e.note, 14f))
        }, top = 10)
        if (family.entriesOf(child.id).size < 3) notes.add(card(0xFFE8F1FB.toInt()).apply { addView(text("ℹ️ " + tip(g), 14f)) }, top = 10)
        weekCard(g)?.let { notes.add(it, top = 10) }

        // Tabs.
        tabs.removeAllViews()
        val names = listOf(getString(R.string.tab_good), getString(R.string.tab_not_good), problemsTab(g), rewardsTab(g), getString(R.string.tab_history))
        val rows = listOf(row(), row())
        names.forEachIndexed { i, n ->
            val on = i == tab
            rows[if (i < 3) 0 else 1].addView(text(n, 14f, if (on) Color.WHITE else getColor(R.color.ink), bold = true).apply {
                gravity = android.view.Gravity.CENTER
                maxLines = 1
                background = rounded(if (on) getColor(R.color.icon_bg) else 0x14000000, 20f)
                setPadding(dpi(8), dpi(9), dpi(8), dpi(9))
                setOnClickListener { tab = i; refresh() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpi(6) })
        }
        tabs.add(rows[0])
        tabs.add(rows[1], top = 6)
        content.removeAllViews()
        when (tab) {
            0 -> goodTab(g)
            1 -> notGoodTab(g)
            2 -> problemTab()
            3 -> rewardTab(g, bal)
            else -> historyTab(g)
        }
    }

    /** A grid of big tiles, three across. */
    private fun grid(): GridLayout = GridLayout(this).apply { columnCount = 3; useDefaultMargins = false }

    private fun tile(emoji: String, title: String, sub: String, bg: Int, subColour: Int = getColor(R.color.ink_soft), click: () -> Unit, hold: (() -> Unit)?): View {
        val v = column(10).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(bg, 16f)
            minimumHeight = dpi(116)
            isClickable = true
            foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000), null, rounded(Color.WHITE, 16f))
            setOnClickListener { click() }
            hold?.let { h -> setOnLongClickListener { h(); true } }
            contentDescription = title
        }
        v.add(text(emoji, 32f).apply { gravity = Gravity.CENTER })
        v.add(text(title, 13f, getColor(R.color.ink), bold = true).apply { gravity = Gravity.CENTER; maxLines = 3 })
        if (sub.isNotEmpty()) v.add(text(sub, 12f, subColour).apply { gravity = Gravity.CENTER; maxLines = 3 })
        return v
    }

    private fun GridLayout.put(v: View) {
        addView(v, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
            width = 0
            setMargins(dpi(4), dpi(4), dpi(4), dpi(4))
        })
    }

    private fun goodTab(g: AgeGroup) {
        val gr = grid()
        for (b in family.behavioursOf(child.id, Kind.GOOD)) {
            gr.put(tile(b.emoji, b.name, "+${b.value} ${unitWord(g, b.value)}", getColor(R.color.card), getColor(R.color.good), click = {
                val e = Ledger.earn(family, b, System.currentTimeMillis(), today())
                save()
                showUndo(e, g)
                refresh()
            }, hold = { behaviourEditor(child, Kind.GOOD, b) { refresh() } }))
        }
        gr.put(tile("🎉", getString(R.string.bonus), "", 0xFFFFF1D6.toInt(), click = { askBonus(g) }, hold = null))
        gr.put(tile("＋", getString(R.string.add), "", 0x14000000, click = { behaviourEditor(child, Kind.GOOD, null) { refresh() } }, hold = null))
        content.add(gr)
    }

    /** Not well done: each tap takes its points off (with Undo, as for the good ones). */
    private fun notGoodTab(g: AgeGroup) {
        val gr = grid()
        for (b in family.behavioursOf(child.id, Kind.NOT_GOOD)) {
            gr.put(tile(b.emoji, b.name, "−${b.value} ${unitWord(g, b.value)}", 0xFFFFF4F1.toInt(), getColor(R.color.problem), click = {
                val e = Ledger.lose(family, b, System.currentTimeMillis(), today())
                save()
                showUndo(e, g)
                refresh()
            }, hold = { behaviourEditor(child, Kind.NOT_GOOD, b) { refresh() } }))
        }
        gr.put(tile("＋", getString(R.string.add), "", 0x14000000, click = { behaviourEditor(child, Kind.NOT_GOOD, null) { refresh() } }, hold = null))
        content.add(gr)
    }

    /** The week for the parents: points, good moments and problems against the week before, the best day, the most noticed. */
    private fun weekCard(g: AgeGroup): View? {
        val t = today()
        val w = Ledger.weekSummary(family, child.id, t)
        if (w.good + w.problems + w.goodBefore + w.problemsBefore == 0) return null
        fun signed(n: Int) = if (n > 0) "+$n" else n.toString()
        fun arrow(now: Int, before: Int, upIsGood: Boolean) = when {
            now == before -> "→"
            (now > before) == upIsGood -> "📈"
            else -> "📉"
        }
        val c = card()
        c.addView(text(getString(R.string.week_title), 13f, getColor(R.color.ink_soft), bold = true))
        c.addView(text(arrow(w.net, w.netBefore, true) + "  " + getString(R.string.week_points, signed(w.net), unitWord(g, 2), signed(w.netBefore)), 14f))
        c.addView(text(arrow(w.good, w.goodBefore, true) + "  " + getString(R.string.week_good, w.good, w.goodBefore), 14f))
        c.addView(text(arrow(w.problems, w.problemsBefore, false) + "  " + getString(R.string.week_problems, w.problems, w.problemsBefore), 14f))
        w.bestDay?.let { d ->
            val name = java.time.LocalDate.of(d.year, d.month, d.dayOfMonth).dayOfWeek
                .getDisplayName(java.time.format.TextStyle.FULL, resources.configuration.locales[0])
            c.addView(text("🏆  " + getString(R.string.week_best, name, signed(w.bestDayNet), unitWord(g, 2)), 14f))
        }
        if (w.top != null) c.addView(text("💛  " + getString(R.string.week_top, w.topEmoji ?: "", w.top, w.topCount), 14f))
        return c
    }

    private fun askBonus(g: AgeGroup) {
        val picker = android.widget.NumberPicker(this).apply { minValue = 1; maxValue = g.maxValue * 2; value = maxOf(1, g.maxValue / 2) }
        AlertDialog.Builder(this).setTitle(R.string.bonus_title).setMessage(getString(R.string.how_many, unitWord(g, 2))).setView(picker)
            .setPositiveButton(R.string.ok) { _, _ ->
                val e = Ledger.bonus(family, child.id, picker.value, getString(R.string.bonus), System.currentTimeMillis(), today())
                save(); showUndo(e, g); refresh()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun showUndo(e: Entry, g: AgeGroup) {
        lastEntry = e
        undoText.text = getString(if (e.type == EntryType.LOST) R.string.lost else R.string.earned, e.amount, unitWord(g, e.amount), e.label)
        undoBar.visibility = View.VISIBLE
        undoBar.removeCallbacks(hideUndo)
        undoBar.postDelayed(hideUndo, 5000)
    }

    private val hideUndo = Runnable { undoBar.visibility = View.GONE; lastEntry = null }

    private fun problemTab() {
        val gr = grid()
        for (b in family.behavioursOf(child.id, Kind.CHALLENGE)) {
            gr.put(tile(b.emoji, b.name, b.consequence, getColor(R.color.card), getColor(R.color.problem), click = {
                AlertDialog.Builder(this).setTitle(b.emoji + "  " + getString(R.string.consequence_title, b.name))
                    .setMessage(getString(R.string.consequence_message, b.consequence))
                    .setPositiveButton(R.string.apply) { _, _ ->
                        Ledger.challenge(family, b, System.currentTimeMillis(), today())
                        save(); refresh()
                    }
                    .setNegativeButton(R.string.cancel, null).show()
            }, hold = { behaviourEditor(child, Kind.CHALLENGE, b) { refresh() } }))
        }
        gr.put(tile("＋", getString(R.string.add), "", 0x14000000, click = { behaviourEditor(child, Kind.CHALLENGE, null) { refresh() } }, hold = null))
        content.add(gr)
    }

    private fun rewardTab(g: AgeGroup, bal: Int) {
        val goal = family.rewards.firstOrNull { it.id == child.goalRewardId }
        content.add(text(if (goal != null) "🎯 " + getString(R.string.goal, goal.emoji + " " + goal.name) else getString(R.string.goal_none), 14f, getColor(R.color.ink_soft)))
        val gr = grid()
        for (r in family.rewardsOf(child.id).sortedBy { it.cost }) {
            val affordable = bal >= r.cost
            gr.put(tile(r.emoji, (if (r.id == child.goalRewardId) "🎯 " else "") + r.name, "${r.cost} ${unitWord(g, r.cost)}",
                if (affordable) 0xFFE3F5EA.toInt() else getColor(R.color.card), if (affordable) getColor(R.color.good) else getColor(R.color.ink_soft), click = {
                    if (!affordable) {
                        android.widget.Toast.makeText(this, getString(R.string.not_enough, r.cost - bal, unitWord(g, r.cost - bal)), android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        confirm(r.emoji + "  " + getString(R.string.spend_title, r.name), getString(R.string.spend_message, r.cost, unitWord(g, r.cost))) {
                            Ledger.spend(family, r, System.currentTimeMillis(), today())
                            save(); refresh()
                        }
                    }
                }, hold = { rewardMenu(child, r) { refresh() } }))
        }
        gr.put(tile("＋", getString(R.string.add), "", 0x14000000, click = { rewardEditor(child, null) { refresh() } }, hold = null))
        content.add(gr, top = 6)
    }

    private fun historyTab(g: AgeGroup) {
        val df = android.text.format.DateFormat.getMediumDateFormat(this)
        val tf = android.text.format.DateFormat.getTimeFormat(this)
        for (e in family.entriesOf(child.id).sortedByDescending { it.time }.take(100)) {
            val r = row(10).apply { background = rounded(getColor(R.color.card), 12f) }
            r.addView(text(e.emoji, 22f), LinearLayout.LayoutParams(dpi(40), LinearLayout.LayoutParams.WRAP_CONTENT))
            val mid = column()
            mid.add(text(e.label, 15f, bold = true))
            val whenText = df.format(java.util.Date(e.time)) + " " + tf.format(java.util.Date(e.time))
            mid.add(text(if (e.type == EntryType.CHALLENGE) e.note + " · " + whenText else whenText, 12f, getColor(R.color.ink_soft)))
            r.addView(mid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val (amount, colour) = when (e.type) {
                EntryType.EARNED, EntryType.BONUS -> "+${e.amount}" to getColor(R.color.good)
                EntryType.SPENT -> "−${e.amount}" to getColor(R.color.ink_soft)
                EntryType.LOST -> "−${e.amount}" to getColor(R.color.problem)
                EntryType.CHALLENGE -> "!" to getColor(R.color.problem)
            }
            r.addView(text(amount, 17f, colour, bold = true))
            r.setOnLongClickListener {
                confirm(getString(R.string.undo_entry), e.emoji + "  " + e.label) { Ledger.undo(family, e.id); save(); refresh() }
                true
            }
            content.add(r, top = 6)
        }
    }
}
