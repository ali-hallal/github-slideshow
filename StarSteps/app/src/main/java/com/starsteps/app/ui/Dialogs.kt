package com.starsteps.app.ui

import android.app.AlertDialog
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import com.starsteps.app.R
import com.starsteps.app.core.AgeGroup
import com.starsteps.app.core.Behaviour
import com.starsteps.app.core.Child
import com.starsteps.app.core.Day
import com.starsteps.app.core.Kind
import com.starsteps.app.core.Presets
import com.starsteps.app.core.Reward
import java.text.DateFormatSymbols

fun BaseActivity.confirm(title: String, message: String?, yes: String = getString(R.string.yes), onYes: () -> Unit) {
    AlertDialog.Builder(this).setTitle(title).setMessage(message)
        .setPositiveButton(yes) { _, _ -> onYes() }
        .setNegativeButton(R.string.cancel, null).show()
}

/** Asks the parent code (if one is set), then runs [ok]. */
fun BaseActivity.withParentCode(ok: () -> Unit) {
    val pin = family.settings.pin
    if (pin.isEmpty()) { ok(); return }
    val input = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        gravity = Gravity.CENTER
        textSize = 24f
    }
    AlertDialog.Builder(this).setTitle(R.string.parent_code).setView(padded(input))
        .setPositiveButton(R.string.ok) { _, _ ->
            if (input.text.toString() == pin) ok() else android.widget.Toast.makeText(this, R.string.wrong_code, android.widget.Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton(R.string.cancel, null).show()
}

fun BaseActivity.padded(v: android.view.View) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dpi(22), dpi(8), dpi(22), 0)
    addView(v)
}

private fun BaseActivity.label(s: String) = text(s, 13f, getColor(R.color.ink_soft), bold = true).apply { setPadding(0, dpi(14), 0, dpi(4)) }

/** A row of choices to pick one from (emojis or colours). */
private fun <T> BaseActivity.choices(items: List<T>, selected: T, show: (T, TextView) -> Unit, picked: (T) -> Unit): HorizontalScrollView {
    val rowView = row()
    val views = ArrayList<TextView>()
    fun mark(sel: T) = views.forEachIndexed { i, v -> v.background = rounded(if (items[i] == sel) 0x22000000 else Color.TRANSPARENT, 12f, if (items[i] == sel) getColor(R.color.icon_bg) else null) }
    for (it in items) {
        val v = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 26f
            show(it, this)
            setOnClickListener { _ -> picked(it); mark(it) }
        }
        views += v
        rowView.addView(v, LinearLayout.LayoutParams(dpi(50), dpi(50)).apply { marginEnd = dpi(4) })
    }
    mark(selected)
    return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(rowView) }
}

/**
 * Adding a child (or changing one: [existing]). The birth month and year give the age, and the
 * age sets up the rest; the group it falls in is shown as the date is picked.
 */
fun BaseActivity.childEditor(existing: Child?, done: (Child?) -> Unit) {
    val t = today()
    val form = column(0)
    val name = EditText(this).apply {
        hint = getString(R.string.name)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        setText(existing?.name ?: "")
        contentDescription = getString(R.string.name)
    }
    form.addView(name)
    form.addView(label(getString(R.string.born)))
    val months = DateFormatSymbols.getInstance(resources.configuration.locales[0]).months.take(12).toTypedArray()
    val month = NumberPicker(this).apply { minValue = 1; maxValue = 12; displayedValues = months; value = existing?.birthMonth ?: 1; wrapSelectorWheel = true }
    val year = NumberPicker(this).apply { minValue = t.year - 19; maxValue = t.year; value = existing?.birthYear ?: (t.year - 6); wrapSelectorWheel = false }
    val group = text("", 14f, getColor(R.color.ink_soft))
    fun showGroup() {
        val c = Child(0, "", year.value, month.value, "", 0)
        group.text = getString(R.string.age_years, c.age(t)) + " · " + groupName(c.group(t))
    }
    month.setOnValueChangedListener { _, _, _ -> showGroup() }
    year.setOnValueChangedListener { _, _, _ -> showGroup() }
    val pickers = row().apply {
        gravity = Gravity.CENTER
        addView(month, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
        addView(year, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }
    form.addView(pickers)
    form.addView(group)
    showGroup()
    var avatar = existing?.avatar ?: BaseActivity.AVATARS[family.children.size % BaseActivity.AVATARS.size]
    var colour = existing?.colour ?: BaseActivity.COLOURS[family.children.size % BaseActivity.COLOURS.size]
    form.addView(label(getString(R.string.picture)))
    form.addView(choices(BaseActivity.AVATARS, avatar, { a, v -> v.text = a }) { avatar = it })
    form.addView(label(getString(R.string.colour)))
    form.addView(choices(BaseActivity.COLOURS.toList(), colour, { c, v -> v.text = "●"; v.setTextColor(c); v.textSize = 34f }) { colour = it })

    val b = AlertDialog.Builder(this)
        .setTitle(if (existing == null) R.string.add_child else R.string.edit_child)
        .setView(scrolling(padded(form)))
        .setPositiveButton(R.string.save, null)
        .setNegativeButton(R.string.cancel, null)
    if (existing != null) b.setNeutralButton(R.string.delete) { _, _ ->
        confirm(getString(R.string.delete_child_confirm, existing.name), null, getString(R.string.delete)) {
            family.remove(existing.id)
            save()
            done(null)
        }
    }
    val d = b.create()
    d.setOnShowListener {
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = name.text.toString().trim()
            if (n.isEmpty()) { name.error = getString(R.string.name); return@setOnClickListener }
            val child = if (existing == null) {
                Presets.addChild(family, n, year.value, month.value, avatar, colour, t) { presetText(it) }
            } else existing.also {
                it.name = n; it.birthYear = year.value; it.birthMonth = month.value; it.avatar = avatar; it.colour = colour
            }
            save()
            d.dismiss()
            done(child)
        }
    }
    d.show()
}

private val EMOJIS = listOf("⭐", "🪥", "🧸", "📚", "📖", "🧹", "🧺", "🤝", "💛", "🛏️", "⚽", "🎨", "🎵", "🥕", "⏰", "📵", "🐶", "🚿", "✋", "😤", "🤥", "👂", "🌪️",
    "🎮", "📺", "🍕", "🍦", "🛝", "🎬", "👫", "💶", "🌙", "🎁", "🏆", "🗓️", "🚲")

private fun BaseActivity.numberField(value: Int) = EditText(this).apply {
    inputType = InputType.TYPE_CLASS_NUMBER
    setText(value.toString())
}

/** Adding or changing a good behaviour or a problem ([kind]) for [child]. */
fun BaseActivity.behaviourEditor(child: Child, kind: Kind, existing: Behaviour?, done: () -> Unit) {
    val g = child.group(today())
    val form = column(0)
    val name = EditText(this).apply { hint = getString(R.string.item_name); setText(existing?.name ?: ""); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
    form.addView(name)
    var emoji = existing?.emoji ?: if (kind == Kind.GOOD) "⭐" else "✋"
    form.addView(label(getString(R.string.item_emoji)))
    form.addView(choices(EMOJIS, emoji, { e, v -> v.text = e }) { emoji = it })
    val value = numberField(existing?.value ?: 1)
    val consequence = EditText(this).apply { setText(existing?.consequence ?: ""); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
    if (kind == Kind.GOOD) {
        form.addView(label(getString(R.string.item_value, unitWord(g, 2))))
        form.addView(value)
    } else {
        form.addView(label(getString(R.string.item_consequence)))
        form.addView(consequence)
    }
    val b = AlertDialog.Builder(this)
        .setTitle(existing?.name ?: getString(if (kind == Kind.GOOD) R.string.good_new else R.string.problem_new))
        .setView(scrolling(padded(form)))
        .setPositiveButton(R.string.save) { _, _ ->
            val n = name.text.toString().trim().ifEmpty { return@setPositiveButton }
            val v = (value.text.toString().toIntOrNull() ?: 1).coerceIn(1, g.maxValue * 5)
            if (existing == null) family.behaviours += Behaviour(family.newId(), child.id, n, emoji, kind, v, consequence.text.toString().trim())
            else { existing.name = n; existing.emoji = emoji; existing.value = v; existing.consequence = consequence.text.toString().trim() }
            save(); done()
        }
        .setNegativeButton(R.string.cancel, null)
    if (existing != null) b.setNeutralButton(R.string.delete) { _, _ ->
        family.behaviours.remove(existing); save(); done()
    }
    b.show()
}

fun BaseActivity.rewardEditor(child: Child, existing: Reward?, done: () -> Unit) {
    val g = child.group(today())
    val form = column(0)
    val name = EditText(this).apply { hint = getString(R.string.item_name); setText(existing?.name ?: ""); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
    form.addView(name)
    var emoji = existing?.emoji ?: "🎁"
    form.addView(label(getString(R.string.item_emoji)))
    form.addView(choices(EMOJIS, emoji, { e, v -> v.text = e }) { emoji = it })
    form.addView(label(getString(R.string.item_cost, unitWord(g, 2))))
    val cost = numberField(existing?.cost ?: (g.dailyTarget * 2))
    form.addView(cost)
    val b = AlertDialog.Builder(this)
        .setTitle(existing?.name ?: getString(R.string.reward_new))
        .setView(scrolling(padded(form)))
        .setPositiveButton(R.string.save) { _, _ ->
            val n = name.text.toString().trim().ifEmpty { return@setPositiveButton }
            val c = (cost.text.toString().toIntOrNull() ?: 1).coerceIn(1, 100_000)
            if (existing == null) family.rewards += Reward(family.newId(), child.id, n, emoji, c)
            else { existing.name = n; existing.emoji = emoji; existing.cost = c }
            save(); done()
        }
        .setNegativeButton(R.string.cancel, null)
    if (existing != null) {
        b.setNeutralButton(R.string.make_goal) { _, _ -> child.goalRewardId = existing.id; save(); done() }
    }
    b.show()
}

/** Deleting a reward: from its long-press menu. */
fun BaseActivity.rewardMenu(child: Child, r: Reward, done: () -> Unit) {
    val items = arrayOf(getString(R.string.edit_child), getString(R.string.make_goal), getString(R.string.delete))
    AlertDialog.Builder(this).setTitle(r.emoji + "  " + r.name).setItems(items) { _, i ->
        when (i) {
            0 -> rewardEditor(child, r, done)
            1 -> { child.goalRewardId = r.id; save(); done() }
            2 -> confirm(getString(R.string.delete_confirm, r.name), null, getString(R.string.delete)) {
                family.rewards.remove(r)
                if (child.goalRewardId == r.id) child.goalRewardId = null
                save(); done()
            }
        }
    }.show()
}

