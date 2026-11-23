package com.starsteps.app.core

/**
 * The rocket the child colours in on their screen.
 *
 * Paint comes from today's good moments: the more of the day's stars or points earned, the more
 * parts can be coloured (all of them at the day's target). The colour is today's score: green
 * for a good day, orange for a mixed one, red when problems outnumber the good moments. Parts
 * already coloured take the current colour, so the rocket always says how the day is going.
 * Every day starts with a blank rocket.
 */
object Rocket {
    const val PARTS = 8

    enum class Mood { NONE, GREEN, ORANGE, RED }

    /** Today's score: nothing yet, green, orange or red. */
    fun mood(f: Family, child: Long, day: Day): Mood {
        val today = f.entriesOf(child).filter { it.day == day }
        val good = today.count { it.type == EntryType.EARNED || it.type == EntryType.BONUS }
        val problems = today.count { it.type == EntryType.CHALLENGE }
        return when {
            good == 0 && problems == 0 -> Mood.NONE
            problems == 0 -> Mood.GREEN
            good >= problems * 3 -> Mood.GREEN
            good >= problems -> Mood.ORANGE
            else -> Mood.RED
        }
    }

    /** How many parts may be coloured today: in proportion to what was earned towards the day's target. */
    fun allowed(f: Family, c: Child, day: Day): Int {
        val target = c.group(day).dailyTarget.coerceAtLeast(1)
        val earned = Ledger.earnedOn(f, c.id, day)
        if (earned <= 0) return 0
        return ((earned * PARTS + target - 1) / target).coerceIn(1, PARTS)
    }

    /** The parts coloured today (yesterday's rocket is gone). */
    fun coloured(c: Child, day: Day): Int = if (c.rocketDay == day) c.rocketParts else 0

    fun count(parts: Int) = Integer.bitCount(parts)

    /** Colours [part] if there is paint left; true if it was coloured. */
    fun paint(f: Family, c: Child, day: Day, part: Int): Boolean {
        require(part in 0 until PARTS)
        val now = coloured(c, day)
        if (now and (1 shl part) != 0) return false
        if (count(now) >= allowed(f, c, day)) return false
        c.rocketDay = day
        c.rocketParts = now or (1 shl part)
        return true
    }

    fun complete(c: Child, day: Day) = count(coloured(c, day)) == PARTS
}
