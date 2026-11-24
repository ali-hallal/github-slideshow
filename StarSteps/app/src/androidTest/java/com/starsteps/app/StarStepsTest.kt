package com.starsteps.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.starsteps.app.core.EntryType
import com.starsteps.app.core.Kind
import com.starsteps.app.core.Ledger
import com.starsteps.app.core.Presets
import com.starsteps.app.ui.KidScreenActivity
import com.starsteps.app.ui.MainActivity
import com.starsteps.app.ui.Store
import com.starsteps.app.ui.today
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The app as a parent uses it, on an emulated phone, with pictures of every screen. */
@RunWith(AndroidJUnit4::class)
class StarStepsTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun startEmpty() {
        // The notification permission is not asked here (its system dialog would sit over the screens).
        context.getSharedPreferences("starsteps", Context.MODE_PRIVATE).edit().putString("language", "en").putBoolean("askedNotify", true).commit()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { Store.reset(context); Store.get(context).settings.language = "en"; Store.save(context) }
    }

    @Test
    fun aParentAddsAChildNotesTheDayAndShowsIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            screen("01_home_empty")
            onView(withContentDescription("Add a child")).perform(scrollTo(), click())
            onView(withHint("First name")).perform(typeText("Sam"), closeSoftKeyboard())
            screen("02_add_child")
            onView(withText("Save")).perform(click())
            SystemClock.sleep(800)
            screen("03_child")
            // Two good moments, one problem.
            onView(withContentDescription("Homework done")).perform(scrollTo(), click())
            onView(withContentDescription("Tidied room")).perform(scrollTo(), click())
            screen("04_earned_with_undo")
            // Not well done: its points come off.
            onView(withText("Not well done")).perform(click())
            SystemClock.sleep(300)
            onView(withContentDescription("Shouting")).perform(scrollTo(), click())
            screen("04b_not_well_done")
            onView(withText("Problems")).perform(click())
            onView(withContentDescription("Hitting")).perform(scrollTo(), click())
            screen("05_consequence")
            onView(withText("Apply")).perform(click())
            onView(withText("Rewards")).perform(click())
            SystemClock.sleep(300)
            screen("06_rewards")
            onView(withText("History")).perform(click())
            SystemClock.sleep(300)
            screen("07_history")

            val f = Store.get(context)
            val sam = f.children.single()
            assertEquals(4, Ledger.balance(f, sam.id)) // 3 + 2 − 1; the problem itself took nothing away
            // The evening reminder would name nobody: Sam has things noted today.
            assertTrue(com.starsteps.app.ui.Reminder.quiet(context).isEmpty())
            assertEquals(1, Ledger.consequencesToday(f, sam.id, today()).size)

            onView(withContentDescription("Show Sam")).perform(scrollTo(), click())
            SystemClock.sleep(800)
            screen("08_kid_screen_child")
            // Sam colours his rocket: the day is worth 4 of 8 stars, so 4 parts; orange after a problem and a slip.
            onView(withContentDescription("Rocket")).perform(scrollTo())
            repeat(6) { InstrumentationRegistry.getInstrumentation().runOnMainSync { rocketOnScreen()?.paintNext() } }
            SystemClock.sleep(400)
            screen("09_rocket_coloured")
            assertEquals(4, com.starsteps.app.core.Rocket.count(com.starsteps.app.core.Rocket.coloured(Store.get(context).children.single(), today())))
            onView(withContentDescription("Hold to go back")).perform(scrollTo(), longClick())
            SystemClock.sleep(500)
        }
    }

    /** One child of each age: their own words, suggestions and screen. */
    @Test
    fun everyAgeGetsItsOwnScreen() {
        val t = today()
        val ages = listOf(4 to "Mia", 10 to "Leo", 15 to "Noa")
        val ids = ArrayList<Long>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val f = Store.get(context)
            for ((age, name) in ages) {
                val c = Presets.addChild(f, name, t.year - age, 1, "🐯", 0xFF64B5F6.toInt(), t) { key ->
                    context.resources.getIdentifier(key, "string", context.packageName).let { if (it != 0) context.getString(it) else key }
                }
                ids += c.id
                val good = f.behavioursOf(c.id, Kind.GOOD)
                for (d in 0..5) for (b in good.take(1 + d % 3)) Ledger.earn(f, b, System.currentTimeMillis(), t.plus(-d))
                // The little one had a very good day: enough stars for the whole rocket.
                if (age == 4) repeat(4) { Ledger.earn(f, good[it + 1], System.currentTimeMillis(), t) }
            }
            Store.save(context)
        }
        val f = Store.get(context)
        // Little ones: stars; teenagers: points, and bigger numbers.
        assertTrue(f.behavioursOf(ids[0], Kind.GOOD).all { it.value <= 3 })
        assertTrue(f.rewardsOf(ids[2]).any { it.cost >= 100 })
        assertTrue(f.entries.all { it.type == EntryType.EARNED })
        ids.forEachIndexed { i, id ->
            ActivityScenario.launch<KidScreenActivity>(Intent(context, KidScreenActivity::class.java).putExtra("child", id)).use {
                SystemClock.sleep(800)
                screen("1${i}_kid_screen_age_${ages[i].first}")
                // Every part the day's stars allow, coloured (the little one's good day: a rocket that blasts off).
                repeat(8) { InstrumentationRegistry.getInstrumentation().runOnMainSync { rocketOnScreen()?.paintNext() } }
                if (i < 2) {
                    onView(withContentDescription("Rocket")).perform(scrollTo())
                    SystemClock.sleep(900)
                    screen("1${i}b_rocket_age_${ages[i].first}")
                }
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            SystemClock.sleep(500)
            screen("13_home_three_children")
            onView(withContentDescription("Settings")).perform(click())
            SystemClock.sleep(600)
            screen("14_settings")
        }
    }

    @Test
    fun inFrench() {
        context.getSharedPreferences("starsteps", Context.MODE_PRIVATE).edit().putString("language", "fr").commit()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val f = Store.get(context)
            f.settings.language = "fr"
            val t = today()
            val conf = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale("fr")) }
            val fr = context.createConfigurationContext(conf)
            Presets.addChild(f, "Léa", t.year - 7, 3, "🦊", 0xFFF06292.toInt(), t) { key ->
                fr.resources.getIdentifier(key, "string", context.packageName).let { if (it != 0) fr.getString(it) else key }
            }
            Store.save(context)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription("Ajouter un enfant")).check(matches(isDisplayed()))
            screen("20_home_french")
            onView(withContentDescription("Léa")).perform(click())
            SystemClock.sleep(800)
            screen("21_child_french")
        }
        context.getSharedPreferences("starsteps", Context.MODE_PRIVATE).edit().putString("language", "en").commit()
    }

    /** The rocket on the children's page in front, if there is one. */
    private fun rocketOnScreen(): com.starsteps.app.ui.RocketView? {
        val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull() ?: return null
        fun find(v: android.view.View): com.starsteps.app.ui.RocketView? = when (v) {
            is com.starsteps.app.ui.RocketView -> v
            is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(activity.window.decorView)
    }

    private fun screen(name: String) {
        try {
            SystemClock.sleep(250)
            val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
            val small = Bitmap.createScaledBitmap(bmp, 480, 480 * bmp.height / bmp.width, true)
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$name.jpg")
                put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/starsteps-screens")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            context.contentResolver.openOutputStream(uri)?.use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        } catch (e: Exception) {
            Log.w("StarStepsTest", "screenshot failed", e)
        }
    }
}
