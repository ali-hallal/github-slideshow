package com.starsteps.app.core

/**
 * The rules, kept the same for every child, so that the app is fair:
 *
 * - Points come off only through the "not well done" items the parents chose (each worth a
 *   set amount, smaller than the good ones); a problem behaviour brings its agreed consequence
 *   and takes nothing.
 * - Consequences last the day they happen; every day starts fresh.
 * - The balance is what was earned, minus what was lost and what was spent on rewards. It can
 *   go below zero; rewards can only be bought from a positive balance.
 * - Parents are told when problems and "not well done" outnumber the praise: about four good
 *   moments noticed for each problem is what helps children change.
 */
object Ledger {
    /** Good moments noticed per problem logged, below which parents get a gentle hint. */
    const val HEALTHY_RATIO = 3.0

    fun balance(f: Family, child: Long): Int {
        var b = 0
        for (e in f.entriesOf(child)) when (e.type) {
            EntryType.EARNED, EntryType.BONUS -> b += e.amount
            EntryType.SPENT, EntryType.LOST -> b -= e.amount
            EntryType.CHALLENGE -> Unit
        }
        return b
    }

    /** Points won minus points lost on [day] (what the day was worth; spending doesn't count). */
    fun netOn(f: Family, child: Long, day: Day) = f.entriesOf(child).filter { it.day == day }.sumOf {
        when (it.type) {
            EntryType.EARNED, EntryType.BONUS -> it.amount
            EntryType.LOST -> -it.amount
            else -> 0
        }
    }

    /** Points lost on [day]. */
    fun lostOn(f: Family, child: Long, day: Day) =
        f.entriesOf(child).filter { it.day == day && it.type == EntryType.LOST }.sumOf { it.amount }

    fun earnedOn(f: Family, child: Long, day: Day) =
        f.entriesOf(child).filter { it.day == day && (it.type == EntryType.EARNED || it.type == EntryType.BONUS) }.sumOf { it.amount }

    fun earnedBetween(f: Family, child: Long, from: Day, to: Day) =
        f.entriesOf(child).filter { it.day >= from && it.day <= to && (it.type == EntryType.EARNED || it.type == EntryType.BONUS) }.sumOf { it.amount }

    fun earn(f: Family, b: Behaviour, time: Long, day: Day, note: String = ""): Entry {
        require(b.kind == Kind.GOOD)
        val e = Entry(f.newId(), b.childId, time, day, EntryType.EARNED, b.id, b.name, b.emoji, b.value, note)
        f.entries += e
        return e
    }

    fun bonus(f: Family, child: Long, amount: Int, label: String, time: Long, day: Day): Entry {
        val e = Entry(f.newId(), child, time, day, EntryType.BONUS, null, label, "🎉", amount.coerceAtLeast(1))
        f.entries += e
        return e
    }

    /** Something not well done: its points come off the balance. */
    fun lose(f: Family, b: Behaviour, time: Long, day: Day, note: String = ""): Entry {
        require(b.kind == Kind.NOT_GOOD)
        val e = Entry(f.newId(), b.childId, time, day, EntryType.LOST, b.id, b.name, b.emoji, b.value.coerceAtLeast(1), note)
        f.entries += e
        return e
    }

    /** A problem behaviour: recorded with its consequence (which lasts today only). Nothing earned is taken. */
    fun challenge(f: Family, b: Behaviour, time: Long, day: Day, note: String = ""): Entry {
        require(b.kind == Kind.CHALLENGE)
        val e = Entry(f.newId(), b.childId, time, day, EntryType.CHALLENGE, b.id, b.name, b.emoji, 0, note.ifEmpty { b.consequence })
        f.entries += e
        return e
    }

    /** Buys a reward if the balance allows; null if it doesn't. */
    fun spend(f: Family, r: Reward, time: Long, day: Day): Entry? {
        if (balance(f, r.childId) < r.cost) return null
        val e = Entry(f.newId(), r.childId, time, day, EntryType.SPENT, r.id, r.name, r.emoji, r.cost)
        f.entries += e
        return e
    }

    /** Takes back an entry made by mistake. */
    fun undo(f: Family, entryId: Long) = f.entries.removeAll { it.id == entryId }

    /** Today's consequences still standing (tomorrow they are gone). */
    fun consequencesToday(f: Family, child: Long, today: Day) =
        f.entriesOf(child).filter { it.type == EntryType.CHALLENGE && it.day == today }

    /** Days in a row, up to [today], with at least one good moment (today counts once something is logged). */
    fun streak(f: Family, child: Long, today: Day, behaviour: Long? = null): Int {
        val days = f.entriesOf(child).filter { it.type == EntryType.EARNED && (behaviour == null || it.refId == behaviour) }.map { it.day }.toSet()
        var d = if (today in days) today else today.plus(-1)
        var n = 0
        while (d in days) { n++; d = d.plus(-1) }
        return n
    }

    /** Good moments and problems over the last [days] days, and whether the balance between them is healthy. */
    class Mix(val good: Int, val problems: Int) {
        /** One problem alone says nothing yet; from two on, praise should clearly outweigh them. */
        val healthy get() = problems < 2 || good >= problems * HEALTHY_RATIO
    }

    fun mix(f: Family, child: Long, today: Day, days: Int = 7): Mix {
        val from = today.plus(-(days - 1))
        val recent = f.entriesOf(child).filter { it.day >= from && it.day <= today }
        return Mix(recent.count { it.type == EntryType.EARNED || it.type == EntryType.BONUS },
            recent.count { it.type == EntryType.CHALLENGE || it.type == EntryType.LOST })
    }

    /** What each of the last [days] days was worth (won minus lost), oldest first (for the week chart). */
    fun week(f: Family, child: Long, today: Day, days: Int = 7) = (days - 1 downTo 0).map { netOn(f, child, today.plus(-it)) }

    /** The last seven days against the seven before, for the parents. */
    class WeekSummary(
        val net: Int, val netBefore: Int,
        val good: Int, val goodBefore: Int,
        val problems: Int, val problemsBefore: Int,
        /** The best day of the week and what it was worth (null if nothing was earned). */
        val bestDay: Day?, val bestDayNet: Int,
        /** The good behaviour noticed most often this week, and how often. */
        val top: String?, val topEmoji: String?, val topCount: Int,
    )

    fun weekSummary(f: Family, child: Long, today: Day): WeekSummary {
        val from = today.plus(-6)
        val before = today.plus(-13)
        val all = f.entriesOf(child)
        val week = all.filter { it.day >= from && it.day <= today }
        val prev = all.filter { it.day >= before && it.day < from }
        fun net(l: List<Entry>) = l.sumOf { when (it.type) { EntryType.EARNED, EntryType.BONUS -> it.amount; EntryType.LOST -> -it.amount; else -> 0 } }
        fun good(l: List<Entry>) = l.count { it.type == EntryType.EARNED || it.type == EntryType.BONUS }
        fun problems(l: List<Entry>) = l.count { it.type == EntryType.CHALLENGE || it.type == EntryType.LOST }
        val days = (0..6).map { today.plus(-it) }
        val best = days.maxByOrNull { netOn(f, child, it) }?.takeIf { netOn(f, child, it) > 0 }
        val top = week.filter { it.type == EntryType.EARNED }.groupBy { it.label }.maxByOrNull { it.value.size }
        return WeekSummary(net(week), net(prev), good(week), good(prev), problems(week), problems(prev),
            best, best?.let { netOn(f, child, it) } ?: 0, top?.key, top?.value?.first()?.emoji, top?.value?.size ?: 0)
    }

    /** How far the child is towards their goal reward (0..1), or null without one. */
    fun goalProgress(f: Family, c: Child): Float? {
        val r = f.rewards.firstOrNull { it.id == c.goalRewardId } ?: return null
        if (r.cost <= 0) return 1f
        return (balance(f, c.id).toFloat() / r.cost).coerceIn(0f, 1f)
    }
}
