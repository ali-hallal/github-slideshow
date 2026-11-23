package com.starsteps.app.core

/**
 * The rules, kept the same for every child, so that the app is fair:
 *
 * - Stars earned are never taken away. A problem behaviour brings its agreed consequence, not a
 *   loss of what was earned: effort always counts.
 * - Consequences last the day they happen; every day starts fresh.
 * - The balance is what was earned minus what was spent on rewards, never below zero.
 * - Parents are told when the problems logged outnumber the praise: about four good moments
 *   noticed for each problem is what helps children change.
 */
object Ledger {
    /** Good moments noticed per problem logged, below which parents get a gentle hint. */
    const val HEALTHY_RATIO = 3.0

    fun balance(f: Family, child: Long): Int {
        var b = 0
        for (e in f.entriesOf(child)) when (e.type) {
            EntryType.EARNED, EntryType.BONUS -> b += e.amount
            EntryType.SPENT -> b -= e.amount
            EntryType.CHALLENGE -> Unit
        }
        return b.coerceAtLeast(0)
    }

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
        val healthy get() = problems == 0 || good >= problems * HEALTHY_RATIO
    }

    fun mix(f: Family, child: Long, today: Day, days: Int = 7): Mix {
        val from = today.plus(-(days - 1))
        val recent = f.entriesOf(child).filter { it.day >= from && it.day <= today }
        return Mix(recent.count { it.type == EntryType.EARNED || it.type == EntryType.BONUS }, recent.count { it.type == EntryType.CHALLENGE })
    }

    /** Stars earned on each of the last [days] days, oldest first (for the week chart). */
    fun week(f: Family, child: Long, today: Day, days: Int = 7) = (days - 1 downTo 0).map { earnedOn(f, child, today.plus(-it)) }

    /** How far the child is towards their goal reward (0..1), or null without one. */
    fun goalProgress(f: Family, c: Child): Float? {
        val r = f.rewards.firstOrNull { it.id == c.goalRewardId } ?: return null
        if (r.cost <= 0) return 1f
        return (balance(f, c.id).toFloat() / r.cost).coerceIn(0f, 1f)
    }
}
