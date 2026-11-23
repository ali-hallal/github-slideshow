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
        context.getSharedPreferences("starsteps", Context.MODE_PRIVATE).edit().putString("language", "en").commit()
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
            assertEquals(5, Ledger.balance(f, sam.id)) // 3 + 2; the problem took nothing away
            assertEquals(1, Ledger.consequencesToday(f, sam.id, today()).size)

            onView(withContentDescription("Show Sam")).perform(scrollTo(), click())
            SystemClock.sleep(800)
            screen("08_kid_screen_child")
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
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            SystemClock.sleep(500)
            screen("13_home_three_children")
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
