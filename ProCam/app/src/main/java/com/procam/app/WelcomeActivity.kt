package com.procam.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * First-launch guide: three short pages (what the modes do, holding steady, reviewing & Pro),
 * shown once before the camera permission prompt. Can be reopened from Settings.
 */
class WelcomeActivity : Activity() {

    private lateinit var pageHost: FrameLayout
    private lateinit var dots: LinearLayout
    private lateinit var nextButton: TextView
    private lateinit var skipButton: TextView
    private var page = 0
    private val pages = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.black))
        }

        skipButton = TextView(this).apply {
            text = "Skip"
            textSize = 15f
            setTextColor(getColor(R.color.dim))
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setOnClickListener { finishGuide() }
        }
        root.addView(skipButton, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })

        pageHost = FrameLayout(this)
        root.addView(pageHost, LinearLayout.LayoutParams(-1, 0, 1f))

        dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        root.addView(dots, LinearLayout.LayoutParams(-1, dp(24)))

        nextButton = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.black))
            setBackgroundResource(R.drawable.pill_selected_bg)
            setOnClickListener { if (page < pages - 1) go(page + 1) else finishGuide() }
        }
        root.addView(nextButton, LinearLayout.LayoutParams(-1, dp(52)).apply {
            setMargins(dp(24), dp(12), dp(24), dp(24))
        })

        setContentView(root)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }

        // Swipe between pages.
        val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) < abs(vy) || abs(vx) < 400) return false
                if (vx < 0 && page < pages - 1) go(page + 1) else if (vx > 0 && page > 0) go(page - 1)
                return true
            }
        })
        pageHost.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP) v.performClick()
            swipe.onTouchEvent(e)
        }

        go(savedInstanceState?.getInt("page") ?: 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", page)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (page > 0) go(page - 1) else finishGuide()
    }

    private fun finishGuide() {
        Prefs(this).onboarded = true
        finish()
    }

    private fun go(p: Int) {
        page = p.coerceIn(0, pages - 1)
        pageHost.removeAllViews()
        val content = when (page) {
            0 -> modesPage()
            1 -> steadyPage()
            else -> reviewPage()
        }
        content.alpha = 0f
        pageHost.addView(content, FrameLayout.LayoutParams(-1, -1))
        content.animate().alpha(1f).setDuration(200).start()
        nextButton.text = if (page < pages - 1) "Next" else "Start shooting"
        skipButton.visibility = if (page < pages - 1) View.VISIBLE else View.INVISIBLE
        dots.removeAllViews()
        for (i in 0 until pages) {
            dots.addView(View(this).apply {
                setBackgroundResource(if (i == page) R.drawable.pill_selected_bg else R.drawable.circle_bg)
            }, LinearLayout.LayoutParams(if (i == page) dp(20) else dp(8), dp(8)).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
    }

    // ------------------------------------------------------------------ pages

    private fun pageLayout(title: String, subtitle: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(28), dp(8), dp(28), dp(8))
        addView(TextView(context).apply {
            text = title
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.white))
        })
        addView(TextView(context).apply {
            text = subtitle
            textSize = 15f
            setTextColor(getColor(R.color.dim))
            setPadding(0, dp(8), 0, dp(20))
        })
    }

    private fun row(parent: LinearLayout, label: String, text: String, highlight: Boolean = false) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        r.addView(TextView(this).apply {
            this.text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(if (highlight) R.color.black else R.color.white))
            setBackgroundResource(if (highlight) R.drawable.pill_selected_bg else R.drawable.pill_bg)
        }, LinearLayout.LayoutParams(dp(92), dp(38)))
        r.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(getColor(R.color.white))
            setPadding(dp(14), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(r)
    }

    private fun modesPage(): View = pageLayout(
        "Say what you want to shoot",
        "Pick a mode under the viewfinder. ProCam sets everything else for you.",
    ).apply {
        row(this, "Auto", "Everyday photos · finds faces and focuses on them", highlight = true)
        row(this, "Portrait", "Blurs the background behind a person")
        row(this, "Night", "Brighter, cleaner photos in low light")
        row(this, "Silky", "Smooth water, clouds and light trails")
        row(this, "Freeze", "Sharp kids, pets and sports · even if you press late")
        row(this, "Group", "Waits until everyone's eyes are open")
        row(this, "Video", "Steady videos with sound · tap red to start and stop")
    }

    private fun steadyPage(): View = pageLayout(
        "Hold steady, get it sharp",
        "The meter at the top shows how steady your hands are right now.",
    ).apply {
        val examples = listOf(
            Triple(5, ShakeView.GREEN, "Steady" to "sharp to 1/30s"),
            Triple(3, ShakeView.YELLOW, "A bit shaky" to "sharp to 1/125s"),
            Triple(1, ShakeView.RED, "Too shaky" to "lean on something"),
        )
        for ((bars, color, words) in examples) {
            addView(ShakeView(context).apply { show(bars, color, words.first, words.second) },
                LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, dp(6), 0, dp(6)) })
        }
        addView(TextView(context).apply {
            text = "• If your hands shake when you press, ProCam waits a moment for them to settle.\n\n" +
                "• Tuck your elbows in or lean on a wall, and take the photo while breathing out.\n\n" +
                "• Tap the picture to focus on something; hold your finger down to lock focus and brightness."
            textSize = 14f
            setTextColor(getColor(R.color.white))
            setPadding(0, dp(18), 0, 0)
        })
    }

    private fun reviewPage(): View = pageLayout(
        "Compare, share, or go Pro",
        "A few more things worth knowing.",
    ).apply {
        row(this, "Photo", "Tap the round thumbnail to see your photos · drag the line to compare before and after · share in one tap")
        row(this, "1× · 2", "Tap a lens to switch · tap the highlighted one for the zoom slider · past ✦ is super zoom")
        row(this, "PRO", "Top bar: full manual control (ISO, shutter, focus, white balance, RAW)")
        row(this, "⚙", "Settings: photo format, grid, location, sounds, and this guide again")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
