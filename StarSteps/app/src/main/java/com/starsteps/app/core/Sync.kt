package com.starsteps.app.core

/**
 * What changed since the last save, so that two phones can be brought into step. Each thing
 * (child, behaviour, reward, entry) has the time it was last changed; deleted things leave a
 * tombstone with the time of their deletion. Found by comparing with the last save, so no other
 * code has to remember to say what it changed.
 */
object Changes {
    /** Every thing's id and its contents as text. */
    fun snapshot(f: Family): Map<Long, String> {
        val m = HashMap<Long, String>()
        for (c in f.children) m[c.id] = Json.write(Storage.child(c))
        for (b in f.behaviours) m[b.id] = Json.write(Storage.behaviour(b))
        for (r in f.rewards) m[r.id] = Json.write(Storage.reward(r))
        for (e in f.entries) m[e.id] = Json.write(Storage.entry(e))
        return m
    }

    /** Stamps what is new or changed since [before] with [now], tombstones what is gone; the new snapshot. */
    fun stamp(f: Family, before: Map<Long, String>, now: Long): Map<Long, String> {
        val after = snapshot(f)
        for ((id, text) in after) if (before[id] != text || id !in f.stamps) f.stamps[id] = maxOf(now, (f.stamps[id] ?: 0L) + 1)
        for (id in before.keys) if (id !in after) {
            f.tombstones[id] = maxOf(now, (f.stamps[id] ?: 0L) + 1)
            f.stamps.remove(id)
        }
        return after
    }
}

/**
 * Two phones' versions of the family made one. Everything either phone added is kept; where
 * both changed the same thing, the later change wins; what either deleted (after its last
 * change) is gone, with everything that belonged to a deleted child. Merging is the same
 * whichever phone does it, and merging again changes nothing.
 */
object Merge {
    fun merge(local: Family, remote: Family): Family {
        val out = Family(settings = local.settings)
        for ((id, t) in local.tombstones + remote.tombstones.filter { (id, t) -> t > (local.tombstones[id] ?: 0L) }) out.tombstones[id] = t
        fun <T> pick(a: List<T>, b: List<T>, id: (T) -> Long, text: (T) -> String, into: MutableList<T>) {
            val byId = LinkedHashMap<Long, T>()
            val stamp = HashMap<Long, Long>()
            fun offer(x: T, s: Long) {
                val k = id(x)
                val have = byId[k]
                val hs = stamp[k]
                if (have == null || hs == null || s > hs || (s == hs && text(x) > text(have))) { byId[k] = x; stamp[k] = s }
            }
            for (x in a) offer(x, local.stamps[id(x)] ?: 0L)
            for (x in b) offer(x, remote.stamps[id(x)] ?: 0L)
            for ((k, x) in byId.entries.sortedBy { it.key }) {
                val s = stamp[k] ?: 0L
                if ((out.tombstones[k] ?: -1L) >= s) continue
                into += x
                out.stamps[k] = s
            }
        }
        pick(local.children, remote.children, { it.id }, { Json.write(Storage.child(it)) }, out.children)
        pick(local.behaviours, remote.behaviours, { it.id }, { Json.write(Storage.behaviour(it)) }, out.behaviours)
        pick(local.rewards, remote.rewards, { it.id }, { Json.write(Storage.reward(it)) }, out.rewards)
        pick(local.entries, remote.entries, { it.id }, { Json.write(Storage.entry(it)) }, out.entries)
        // Nothing left over from a child that is gone.
        val kids = out.children.map { it.id }.toSet()
        out.behaviours.removeAll { it.childId !in kids }
        out.rewards.removeAll { it.childId !in kids }
        out.entries.removeAll { it.childId !in kids }
        out.entries.sortBy { it.time }
        val alive = HashSet<Long>()
        out.children.mapTo(alive) { it.id }; out.behaviours.mapTo(alive) { it.id }; out.rewards.mapTo(alive) { it.id }; out.entries.mapTo(alive) { it.id }
        out.stamps.keys.retainAll(alive)
        return out
    }
}
