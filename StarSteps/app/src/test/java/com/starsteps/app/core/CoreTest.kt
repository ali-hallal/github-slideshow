package com.starsteps.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private val today = Day(2026, 11, 23)
    private fun family(age: Int = 7): Pair<Family, Child> {
        val f = Family()
        val c = Presets.addChild(f, "Sam", today.year - age, 1, "🦊", 0xFF8844, today) { "[$it]" }
        return f to c
    }

    @Test
    fun theAgeDecidesHowTheChildIsTreated() {
        assertEquals(AgeGroup.LITTLE, AgeGroup.of(3))
        assertEquals(AgeGroup.CHILD, AgeGroup.of(6))
        assertEquals(AgeGroup.PRETEEN, AgeGroup.of(12))
        assertEquals(AgeGroup.TEEN, AgeGroup.of(15))
        // Birthday not yet reached this year: a year younger.
        assertEquals(5, Child(1, "A", 2020, 12, "", 0).age(today))
        assertEquals(6, Child(1, "A", 2020, 11, "", 0).age(today))
        // Young children: stars, drawn one by one; teenagers: points.
        assertFalse(AgeGroup.LITTLE.numbers)
        assertEquals(AgeGroup.Unit.POINTS, AgeGroup.TEEN.unit)
        // Each group's suggestions stay within its own scale.
        for (g in AgeGroup.values()) {
            assertTrue(Presets.good(g).all { it.value in 1..g.maxValue })
            assertTrue(Presets.prizes(g).isNotEmpty() && Presets.challenges(g).isNotEmpty())
        }
        val (f, c) = family(4)
        assertEquals(Presets.good(AgeGroup.LITTLE).size, f.behavioursOf(c.id, Kind.GOOD).size)
        assertEquals("[good_teeth]", f.behavioursOf(c.id, Kind.GOOD).first().name)
        assertNotNull(c.goalRewardId)
    }

    @Test
    fun earnedStarsAreNeverTakenAwayAndEveryDayStartsFresh() {
        val (f, c) = family()
        val homework = f.behavioursOf(c.id, Kind.GOOD).first { it.name == "[good_homework]" }
        val hitting = f.behavioursOf(c.id, Kind.CHALLENGE).first()
        repeat(4) { Ledger.earn(f, homework, 0, today) }
        assertEquals(12, Ledger.balance(f, c.id))
        // A problem: its consequence today, the stars stay.
        val e = Ledger.challenge(f, hitting, 0, today)
        assertEquals(hitting.consequence, e.note)
        assertEquals(12, Ledger.balance(f, c.id))
        assertEquals(1, Ledger.consequencesToday(f, c.id, today).size)
        assertEquals(0, Ledger.consequencesToday(f, c.id, today.plus(1)).size)
        // Rewards: only what can be afforded.
        val cheap = f.rewardsOf(c.id).minBy { it.cost }
        val dear = f.rewardsOf(c.id).maxBy { it.cost }
        assertNotNull(Ledger.spend(f, cheap, 0, today))
        assertEquals(12 - cheap.cost, Ledger.balance(f, c.id))
        assertNull(Ledger.spend(f, dear, 0, today))
        // Undo.
        val last = Ledger.earn(f, homework, 0, today)
        Ledger.undo(f, last.id)
        assertEquals(12 - cheap.cost, Ledger.balance(f, c.id))
    }

    @Test
    fun streaksWeeksAndTheBalanceOfPraise() {
        val (f, c) = family(10)
        val good = f.behavioursOf(c.id, Kind.GOOD).first()
        val bad = f.behavioursOf(c.id, Kind.CHALLENGE).first()
        for (d in 0..3) Ledger.earn(f, good, 0, today.plus(-d))
        assertEquals(4, Ledger.streak(f, c.id, today))
        assertEquals(4, Ledger.streak(f, c.id, today.plus(1))) // today not yet logged: yesterday's streak still counts
        assertEquals(0, Ledger.streak(f, c.id, today.plus(2)))
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 1).map { it * good.value }, Ledger.week(f, c.id, today))
        assertTrue(Ledger.mix(f, c.id, today).healthy)
        Ledger.challenge(f, bad, 0, today)
        Ledger.challenge(f, bad, 0, today)
        assertFalse(Ledger.mix(f, c.id, today).healthy) // 4 good moments for 2 problems
        assertEquals(Day(2026, 3, 1), Day(2026, 2, 28).plus(1))
        assertEquals(Day(2024, 2, 29), Day(2024, 3, 1).plus(-1))
        assertEquals(Day(2027, 1, 1), Day(2026, 12, 31).plus(1))
    }

    @Test
    fun everythingIsSavedAndReadBack() {
        val (f, c) = family()
        f.settings = Settings("1234", "fr")
        c.name = "Léa \"Lou\" \\ é\n"
        Ledger.earn(f, f.behavioursOf(c.id, Kind.GOOD).first(), 1_700_000_000_000, today, note = "bravo")
        Ledger.challenge(f, f.behavioursOf(c.id, Kind.CHALLENGE).first(), 1_700_000_000_001, today)
        val back = Storage.decode(Storage.encode(f))
        assertEquals(Storage.encode(f), Storage.encode(back))
        assertEquals(c.name, back.children[0].name)
        assertEquals("fr", back.settings.language)
        assertEquals(Ledger.balance(f, c.id), Ledger.balance(back, c.id))
        // New ids keep going after the loaded ones.
        assertTrue(back.newId() > f.entries.maxOf { it.id })
        // Not a StarSteps file.
        assertTrue(runCatching { Storage.decode("{\"app\":\"Other\"}") }.isFailure)
    }

    @Test
    fun bothLanguagesHaveEveryText() {
        // Run from the app module (Gradle) or from elsewhere with the absolute path.
        val base = listOf(java.io.File("src/main/res"), java.io.File("/home/user/github-slideshow/StarSteps/app/src/main/res")).first { it.exists() }
        fun keys(folder: String) = Regex("<string name=\"([a-z0-9_]+)\"").findAll(java.io.File(base, "$folder/strings.xml").readText()).map { it.groupValues[1] }.toSet()
        val en = keys("values")
        val fr = keys("values-fr")
        for (k in Presets.allKeys()) {
            assertTrue("English text for $k", k in en)
            assertTrue("French text for $k", k in fr)
        }
        assertEquals(emptySet<String>(), en - fr - setOf("app_name"))
    }
}
