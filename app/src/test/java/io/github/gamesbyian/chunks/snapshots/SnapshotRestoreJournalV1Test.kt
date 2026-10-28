package io.github.gamesbyian.chunks.snapshots

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SnapshotRestoreJournalV1Test {
    private val archive = "a".repeat(64)
    private val target = "b".repeat(64)
    private val place = "c".repeat(64)
    private val alternatePlace = "d".repeat(64)
    private val one = "1".repeat(40)
    private val two = "2".repeat(40)

    private class MemoryStore : SnapshotRestoreJournalV1.Store {
        val content = mutableMapOf<String, String>()
        var canCommit = true
        override fun read(key: String): String? = content[key]
        override fun commit(key: String, value: String): Boolean {
            if (!canCommit) return false
            content[key] = value
            return true
        }
    }

    private fun open(store: MemoryStore, names: List<String> = listOf(one, two)) =
        SnapshotRestoreJournalV1.open(store, archive, target, names)

    @Test fun journalSurvivesProcessDeathWithoutRetryingUnacknowledgedAdds() {
        val store = MemoryStore()
        var journal = open(store)
        assertEquals(2, journal.entries().size)
        assertEquals(SnapshotRestoreJournalV1.Phase.REVIEW, journal.entries()[0].phase)
        reject { journal.beforeAdd(one, place) }
        journal.prepare(one, place)
        journal.beforeAdd(one, place)
        journal = open(store)
        assertEquals(SnapshotRestoreJournalV1.Phase.IN_FLIGHT, journal.entries()[0].phase)
        reject { journal.beforeAdd(one, place) }
        journal.reconcileWithCompleteTarget(emptyList(), true)
        assertEquals(SnapshotRestoreJournalV1.Phase.IN_FLIGHT, open(store).entries()[0].phase)
        reject { journal.explicitlyRetryAfterAbsence(one, emptyList(), true, false) }
        reject { journal.explicitlyRetryAfterAbsence(one, listOf(one), true, true) }
        reject { journal.explicitlyRetryAfterAbsence(one, emptyList(), false, true) }
        journal.explicitlyRetryAfterAbsence(one, emptyList(), true, true)
        assertEquals(SnapshotRestoreJournalV1.Phase.READY, open(store).entries()[0].phase)
        reject { journal.beforeAdd(one, alternatePlace) }
        journal.beforeAdd(one, place)
        journal.acknowledged(one)
        journal.reconcileWithCompleteTarget(listOf(one), true)
        assertEquals(SnapshotRestoreJournalV1.Phase.VERIFIED, open(store).entries()[0].phase)
        reject { journal.beforeAdd(one, place) }
        reject { journal.explicitlyRetryAfterAbsence(one, emptyList(), true, true) }
    }

    @Test fun aFailedDurableCommitNeverAdvancesAnInMemoryState() {
        val store = MemoryStore()
        val journal = open(store, listOf(one))
        journal.prepare(one, place)
        store.canCommit = false
        try {
            journal.beforeAdd(one, place)
            fail("No Add may begin if IN_FLIGHT could not be durably stored")
        } catch (_: IOException) { }
        assertEquals(SnapshotRestoreJournalV1.Phase.READY, journal.entries()[0].phase)
        assertEquals(SnapshotRestoreJournalV1.Phase.READY, open(store).entries()[0].phase)
        store.canCommit = true
        journal.beforeAdd(one, place)
        assertEquals(SnapshotRestoreJournalV1.Phase.IN_FLIGHT, open(store).entries()[0].phase)
    }

    @Test fun immutableArchiveTargetAndRosterPreventWrongProfileReplay() {
        val store = MemoryStore()
        val journal = open(store)
        journal.prepare(one, place)
        reject { SnapshotRestoreJournalV1.open(store, archive, target, listOf(one)) }
        reject { SnapshotRestoreJournalV1.open(store, archive, target, listOf(one, one)) }
        reject { SnapshotRestoreJournalV1.open(store, archive, target, listOf("a".repeat(64))) }
        reject { SnapshotRestoreJournalV1.open(store, archive, target, List(513) { one }) }
        val differentProfile = SnapshotRestoreJournalV1.open(
            store, archive, "e".repeat(64), listOf(one, two))
        assertEquals(SnapshotRestoreJournalV1.Phase.REVIEW,
            differentProfile.entries().first().phase)
        val differentArchive = SnapshotRestoreJournalV1.open(
            store, "f".repeat(64), target, listOf(one, two))
        assertEquals(SnapshotRestoreJournalV1.Phase.REVIEW,
            differentArchive.entries().first().phase)
    }

    @Test fun partialOrCorruptObservationsCannotResolveUncertainWrites() {
        val store = MemoryStore()
        val journal = open(store)
        journal.prepare(one, place)
        journal.beforeAdd(one, place)
        reject { journal.reconcileWithCompleteTarget(listOf(one), false) }
        reject { journal.reconcileWithCompleteTarget(listOf("bad"), true) }
        reject { journal.reconcileWithCompleteTarget(listOf(one, one.uppercase()), true) }
        assertEquals(SnapshotRestoreJournalV1.Phase.IN_FLIGHT,
            journal.entries()[0].phase)
        journal.reconcileWithCompleteTarget(listOf(two), true)
        assertEquals(SnapshotRestoreJournalV1.Phase.IN_FLIGHT,
            journal.entries()[0].phase)
        journal.reconcileWithCompleteTarget(listOf(one.uppercase()), true)
        assertEquals(SnapshotRestoreJournalV1.Phase.VERIFIED,
            journal.entries()[0].phase)
    }

    @Test fun persistedTamperingOrUnknownVersionIsRejectedBeforeUse() {
        val store = MemoryStore()
        open(store)
        val key = store.content.keys.single()
        store.content[key] = store.content.getValue(key).replace(
            "chunks-import-journal-v1", "chunks-import-journal-v2")
        reject { open(store) }
        store.content[key] = "not-a-journal"
        reject { open(store) }
    }

    @Test fun anExplicitRefusalCannotBeResumedAsAnAdd() {
        val store = MemoryStore()
        val journal = open(store)
        journal.refuse(one)
        assertEquals(SnapshotRestoreJournalV1.Phase.REFUSED,
            open(store).entries().first().phase)
        reject { journal.prepare(one, place) }
        reject { journal.beforeAdd(one, place) }
        journal.prepare(two, alternatePlace)
        assertEquals(SnapshotRestoreJournalV1.Phase.READY,
            open(store).entries()[1].phase)
    }

    private fun reject(block: () -> Unit) {
        try {
            block()
            fail("Unsafe transition or input should have been rejected")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        }
    }
}
