package com.starsteps.app.core

/**
 * Everything the app keeps, for one family. Plain data: no Android, so it can be tested and
 * saved anywhere.
 */
class Family(
    val children: MutableList<Child> = mutableListOf(),
    val behaviours: MutableList<Behaviour> = mutableListOf(),
    val rewards: MutableList<Reward> = mutableListOf(),
    val entries: MutableList<Entry> = mutableListOf(),
    var settings: Settings = Settings(),
) {
    /**
     * For keeping two phones in step: when each thing was last changed (id → milliseconds), and
     * when things were deleted. Kept up to date by [Changes.stamp] at every save.
     */
    val stamps: MutableMap<Long, Long> = HashMap()
    val tombstones: MutableMap<Long, Long> = HashMap()
    private var lastId = 0L

    /**
     * A new id, unique in this family, and unique across the family's phones too: the time in
     * the top bits (so later things have larger ids) and a random part in the bottom ones.
     */
    fun newId(): Long {
        val taken = HashSet<Long>()
        children.mapTo(taken) { it.id }; behaviours.mapTo(taken) { it.id }; rewards.mapTo(taken) { it.id }; entries.mapTo(taken) { it.id }
        taken += tombstones.keys
        var id: Long
        do {
            id = maxOf((System.currentTimeMillis() shl 20) or kotlin.random.Random.nextLong(1L shl 20), lastId + 1, (taken.maxOrNull() ?: 0L) + 1)
        } while (id in taken)
        lastId = id
        return id
    }

    fun child(id: Long) = children.firstOrNull { it.id == id }
    fun behavioursOf(child: Long, kind: Kind? = null) = behaviours.filter { it.childId == child && (kind == null || it.kind == kind) }
    fun rewardsOf(child: Long) = rewards.filter { it.childId == child }
    fun entriesOf(child: Long) = entries.filter { it.childId == child }

    /** Removes a child and everything that belonged to them. */
    fun remove(child: Long) {
        children.removeAll { it.id == child }
        behaviours.removeAll { it.childId == child }
        rewards.removeAll { it.childId == child }
        entries.removeAll { it.childId == child }
    }
}

/** [birthYear], [birthMonth] (1–12): the age, and so how the app treats the child, follows from them as they grow. */
data class Child(
    val id: Long,
    var name: String,
    var birthYear: Int,
    var birthMonth: Int,
    var avatar: String,
    var colour: Int,
    /** The reward the child is saving towards (shown big on their screen), or null. */
    var goalRewardId: Long? = null,
    /** The rocket the child colours in: the day it belongs to, and which parts are coloured (one bit each). */
    var rocketDay: Day? = null,
    var rocketParts: Int = 0,
) {
    fun age(today: Day): Int {
        var a = today.year - birthYear
        if (today.month < birthMonth) a--
        return a.coerceAtLeast(0)
    }

    fun group(today: Day) = AgeGroup.of(age(today))
}

enum class Kind { GOOD, NOT_GOOD, CHALLENGE }

/**
 * Something the parents want to see more of ([Kind.GOOD], worth [value] stars or points),
 * something that costs points ([Kind.NOT_GOOD], [value] taken off), or a problem behaviour
 * ([Kind.CHALLENGE]) with the [consequence] agreed in advance (and no points taken).
 */
data class Behaviour(
    val id: Long,
    val childId: Long,
    var name: String,
    var emoji: String,
    val kind: Kind,
    var value: Int = 0,
    var consequence: String = "",
)

data class Reward(val id: Long, val childId: Long, var name: String, var emoji: String, var cost: Int)

enum class EntryType { EARNED, LOST, CHALLENGE, SPENT, BONUS }

/** Something that happened: stars earned, a reward bought, a problem with its consequence. */
data class Entry(
    val id: Long,
    val childId: Long,
    /** Milliseconds since 1970 (UTC). */
    val time: Long,
    val day: Day,
    val type: EntryType,
    /** The behaviour or reward it was for (if any). */
    val refId: Long?,
    val label: String,
    val emoji: String,
    /** Stars or points: earned, lost or spent (always positive; the type says which way). 0 for challenges. */
    val amount: Int,
    val note: String = "",
)

data class Settings(
    /** Asked to leave the children's screen (and to change things), or empty for none. */
    var pin: String = "",
    /** "en", "fr", or "" for the phone's language. */
    var language: String = "",
    /** The evening reminder to note the day: on, and its time (minutes after midnight). */
    var reminder: Boolean = true,
    var reminderMinutes: Int = 19 * 60,
)

/** A calendar day, in the family's own time zone. */
data class Day(val year: Int, val month: Int, val dayOfMonth: Int) : Comparable<Day> {
    override fun compareTo(other: Day) = compareValuesBy(this, other, { it.year }, { it.month }, { it.dayOfMonth })
    override fun toString() = "%04d-%02d-%02d".format(year, month, dayOfMonth)

    /** Days since 1970-01-01 (proleptic Gregorian). */
    fun epochDay(): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + dayOfMonth - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468
    }

    fun plus(days: Int) = ofEpochDay(epochDay() + days)

    companion object {
        fun parse(s: String): Day {
            val p = s.split("-")
            return Day(p[0].toInt(), p[1].toInt(), p[2].toInt())
        }

        fun ofEpochDay(e: Long): Day {
            val z = e + 719468
            val era = (if (z >= 0) z else z - 146096) / 146097
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = if (mp < 10) mp + 3 else mp - 9
            val y = yoe + era * 400 + if (m <= 2) 1 else 0
            return Day(y.toInt(), m.toInt(), d.toInt())
        }
    }
}
