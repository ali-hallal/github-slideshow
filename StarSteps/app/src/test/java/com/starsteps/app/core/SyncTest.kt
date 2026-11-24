package com.starsteps.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class SyncTest {
    private val today = Day(2026, 11, 24)

    /** A phone: its family and the snapshot of its last save. */
    private class Phone(var f: Family) {
        var snap = Changes.snapshot(f)
        var clock = 1_000L
        fun save() { clock += 10; snap = Changes.stamp(f, snap, clock) }
        fun take(other: Family) { f = other; snap = Changes.snapshot(f) }
    }

    private fun twoPhones(): Pair<Phone, Phone> {
        val a = Phone(Family())
        Presets.addChild(a.f, "Sam", 2019, 1, "🦊", 0, today) { it }
        a.save()
        val b = Phone(Storage.decode(Storage.encode(a.f)))
        return a to b
    }

    private fun sync(a: Phone, b: Phone) {
        val merged = Merge.merge(a.f, Storage.decode(Storage.encode(b.f)))
        val back = Merge.merge(b.f, Storage.decode(Storage.encode(merged)))
        a.take(merged); b.take(back)
    }

    @Test
    fun changesOnBothPhonesAreAllKept() {
        val (a, b) = twoPhones()
        val sam = a.f.children[0].id
        // Apart: each notes something; A renames a reward, B deletes another; B renames Sam.
        Ledger.earn(a.f, a.f.behavioursOf(sam, Kind.GOOD)[0], 1, today); a.save()
        Ledger.lose(b.f, b.f.behavioursOf(sam, Kind.NOT_GOOD)[0], 2, today); b.save()
        val r0 = a.f.rewardsOf(sam)[0].id
        val r1 = a.f.rewardsOf(sam)[1].id
        a.f.rewards.first { it.id == r0 }.name = "Park!"; a.save()
        b.f.rewards.removeAll { it.id == r1 }; b.save()
        b.f.children[0].name = "Samuel"; b.save()
        sync(a, b)
        for (p in listOf(a, b)) {
            assertEquals(2, p.f.entries.size)
            assertEquals("Park!", p.f.rewards.first { it.id == r0 }.name)
            assertNull(p.f.rewards.firstOrNull { it.id == r1 })
            assertEquals("Samuel", p.f.children[0].name)
        }
        // Both phones now hold exactly the same family.
        assertEquals(Storage.encode(a.f), Storage.encode(b.f))
        // Syncing again changes nothing.
        val before = Storage.encode(a.f)
        sync(a, b)
        assertEquals(before, Storage.encode(a.f))
    }

    @Test
    fun theLaterChangeWinsAndUndoneEntriesStayUndone() {
        val (a, b) = twoPhones()
        val sam = a.f.children[0].id
        a.f.children[0].name = "Sammy"; a.save()
        b.clock += 100 // B's edit happens later
        b.f.children[0].name = "Samuel"; b.save()
        val e = Ledger.earn(a.f, a.f.behavioursOf(sam, Kind.GOOD)[0], 1, today); a.save()
        sync(a, b)
        assertEquals("Samuel", a.f.children[0].name)
        assertEquals(1, b.f.entries.size)
        // B takes it back; after syncing it is gone from A too (and stays gone).
        Ledger.undo(b.f, e.id); b.save()
        sync(a, b)
        assertTrue(a.f.entries.isEmpty())
        sync(a, b)
        assertTrue(a.f.entries.isEmpty() && b.f.entries.isEmpty())
        // A child deleted on one phone goes with everything of theirs.
        b.f.remove(sam); b.save()
        sync(a, b)
        assertTrue(a.f.children.isEmpty() && a.f.behaviours.isEmpty() && a.f.rewards.isEmpty())
        // New ids from two phones don't collide.
        val ids = (1..2000).map { a.f.newId() } + (1..2000).map { b.f.newId() }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun linkingAndSyncingOverTheNetwork() {
        val (a, _) = twoPhones()
        val fid = Link.newFamilyId()
        val key = Link.newKey()
        // Linking: both phones show the same code; the joining one gets the key and the data.
        ServerSocket(0).use { server ->
            var hostCode = ""
            val host = thread {
                server.accept().use { s -> Link.hostPairing(s, fid, key, { Storage.encode(a.f) }, confirm = { code -> hostCode = code; true }) }
            }
            var joinCode = ""
            val joined = Socket("127.0.0.1", server.localPort).use { s -> Link.joinPairing(s) { joinCode = it } }
            host.join()
            assertNotNull(joined)
            assertEquals(6, joinCode.length)
            assertEquals(hostCode, joinCode)
            assertEquals(fid, joined!!.familyId)
            assertTrue(key.contentEquals(joined.key))
            assertEquals(Storage.encode(a.f), joined.familyJson)
        }
        // The other parent says the codes don't match: nothing is sent.
        ServerSocket(0).use { server ->
            val host = thread { server.accept().use { s -> Link.hostPairing(s, fid, key, { "secret" }, confirm = { false }) } }
            val joined = Socket("127.0.0.1", server.localPort).use { s -> Link.joinPairing(s) {} }
            host.join()
            assertNull(joined)
        }
        // Syncing, sealed with the family's key.
        val b = Storage.decode(Storage.encode(a.f))
        Ledger.earn(b, b.behavioursOf(b.children[0].id, Kind.GOOD)[0], 5, today)
        b.stamps[b.entries.last().id] = 99_999
        ServerSocket(0).use { server ->
            val host = thread {
                server.accept().use { s -> Link.syncAsServer(s, fid, key, { theirs -> Storage.encode(Merge.merge(a.f, Storage.decode(theirs))) }) }
            }
            val merged = Socket("127.0.0.1", server.localPort).use { s -> Link.syncAsClient(s, fid, key, Storage.encode(b)) }
            host.join()
            assertEquals(1, Storage.decode(merged).entries.size)
        }
        // Someone without the key gets nothing.
        ServerSocket(0).use { server ->
            var refused = false
            val host = thread {
                server.accept().use { s ->
                    try { Link.syncAsServer(s, fid, key, { it }) } catch (e: Exception) { refused = true }
                }
            }
            val failed = runCatching { Socket("127.0.0.1", server.localPort).use { s -> Link.syncAsClient(s, fid, Link.newKey(), "{}") } }.isFailure
            host.join()
            assertTrue(refused && failed)
        }
        assertFalse(Link.tag(fid).contains(fid))
    }
}
