package com.starsteps.app.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.starsteps.app.R
import com.starsteps.app.core.AgeGroup
import com.starsteps.app.core.Day
import com.starsteps.app.core.Family
import com.starsteps.app.core.Storage
import java.io.File
import java.time.LocalDate
import java.util.Locale

/** The family, kept in one file on the phone; saved after every change. */
object Store {
    private var family: Family? = null
    private var file: File? = null

    fun get(c: Context): Family {
        family?.let { return it }
        val f = File(c.applicationContext.filesDir, "family.json")
        file = f
        val loaded = try {
            if (f.exists()) Storage.decode(f.readText()) else Family()
        } catch (e: Exception) {
            // A damaged file is kept aside rather than lost.
            f.renameTo(File(f.parentFile, "family-damaged-${System.currentTimeMillis()}.json"))
            Family()
        }
        family = loaded
        return loaded
    }

    fun save(c: Context) {
        val fam = family ?: return
        val f = file ?: File(c.applicationContext.filesDir, "family.json")
        val tmp = File(f.parentFile, "family.json.tmp")
        tmp.writeText(Storage.encode(fam))
        tmp.renameTo(f)
        c.getSharedPreferences("starsteps", Context.MODE_PRIVATE).edit().putString("language", fam.settings.language).apply()
    }

    /** Replaces everything (a restored backup). */
    fun replace(c: Context, fam: Family) {
        family = fam
        save(c)
    }

    /** For tests: start from nothing. */
    fun reset(c: Context) {
        family = Family()
        save(c)
    }
}

fun today(): Day = LocalDate.now().let { Day(it.year, it.monthValue, it.dayOfMonth) }

/** Every screen: in the family's chosen language (English, French, or the phone's). */
open class BaseActivity : Activity() {
    override fun attachBaseContext(base: Context) {
        val lang = base.getSharedPreferences("starsteps", Context.MODE_PRIVATE).getString("language", "") ?: ""
        if (lang.isEmpty()) { super.attachBaseContext(base); return }
        val conf = android.content.res.Configuration(base.resources.configuration)
        conf.setLocale(Locale(lang))
        super.attachBaseContext(base.createConfigurationContext(conf))
    }

    val family: Family get() = Store.get(this)
    fun save() = Store.save(this)

    fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    fun dpi(v: Int) = dp(v.toFloat()).toInt()

    // ------------------------------------------------------------------ building screens in code

    fun column(pad: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpi(pad), dpi(pad), dpi(pad), dpi(pad))
    }

    fun row(pad: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpi(pad), dpi(pad), dpi(pad), dpi(pad))
    }

    fun text(s: CharSequence, size: Float = 16f, colour: Int = getColor(R.color.ink), bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(colour)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun rounded(colour: Int, radius: Float = 18f, stroke: Int? = null) = GradientDrawable().apply {
        setColor(colour)
        cornerRadius = dp(radius)
        stroke?.let { setStroke(dpi(2), it) }
    }

    /** A pill-shaped button. */
    fun pill(s: CharSequence, colour: Int = getColor(R.color.icon_bg), textColour: Int = Color.WHITE, onClick: () -> Unit) = text(s, 15f, textColour, bold = true).apply {
        gravity = Gravity.CENTER
        background = rounded(colour, 24f)
        setPadding(dpi(18), dpi(11), dpi(18), dpi(11))
        isClickable = true
        foreground = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33000000), null, rounded(Color.WHITE, 24f))
        setOnClickListener { onClick() }
    }

    fun card(bg: Int = getColor(R.color.card)) = column(14).apply { background = rounded(bg) }

    fun LinearLayout.add(v: View, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, weight: Float = 0f): View {
        addView(v, LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dpi(top) })
        return v
    }

    fun scrolling(content: View) = ScrollView(this).apply {
        isFillViewport = true
        addView(content)
    }

    /** Leaves room for the status and navigation bars (the app draws edge to edge on new Android). */
    fun View.padForBars(): View {
        setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        return this
    }

    // ------------------------------------------------------------------ words that depend on the age

    fun unitWord(g: AgeGroup, n: Int) = getString(when {
        g.unit == AgeGroup.Unit.STARS && n == 1 -> R.string.unit_star
        g.unit == AgeGroup.Unit.STARS -> R.string.unit_stars
        n == 1 -> R.string.unit_point
        else -> R.string.unit_points
    })

    fun groupName(g: AgeGroup) = getString(when (g) {
        AgeGroup.LITTLE -> R.string.group_little
        AgeGroup.CHILD -> R.string.group_child
        AgeGroup.PRETEEN -> R.string.group_preteen
        AgeGroup.TEEN -> R.string.group_teen
    })

    fun tip(g: AgeGroup) = getString(when (g) {
        AgeGroup.LITTLE -> R.string.tip_little
        AgeGroup.CHILD -> R.string.tip_child
        AgeGroup.PRETEEN -> R.string.tip_preteen
        AgeGroup.TEEN -> R.string.tip_teen
    })

    /** Problems are "agreements" with teenagers, and rewards "privileges". */
    fun problemsTab(g: AgeGroup) = getString(if (g == AgeGroup.TEEN) R.string.tab_agreements else R.string.tab_problems)
    fun rewardsTab(g: AgeGroup) = getString(if (g == AgeGroup.TEEN) R.string.tab_privileges else R.string.tab_rewards)

    /** Text of a preset, by key, in the current language. */
    fun presetText(key: String): String {
        val id = resources.getIdentifier(key, "string", packageName)
        return if (id != 0) getString(id) else key
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(getColor(R.color.bg))
    }

    companion object {
        /** Children's colours (light enough for dark text, strong enough to tell apart). */
        val COLOURS = intArrayOf(0xFFFFB74D.toInt(), 0xFF81C784.toInt(), 0xFF64B5F6.toInt(), 0xFFBA68C8.toInt(),
            0xFFF06292.toInt(), 0xFF4DB6AC.toInt(), 0xFFFFD54F.toInt(), 0xFF9575CD.toInt())
        val AVATARS = listOf("🦊", "🐻", "🐼", "🐯", "🦁", "🐸", "🐵", "🐰", "🦄", "🐶", "🐱", "🐨", "🐧", "🐢", "🚀", "⚽", "🎨", "🌟", "🎸", "🦖")
    }
}
