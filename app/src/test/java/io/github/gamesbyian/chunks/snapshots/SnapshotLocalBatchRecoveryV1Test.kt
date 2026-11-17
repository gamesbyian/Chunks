package io.github.gamesbyian.chunks.snapshots

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SnapshotLocalBatchRecoveryV1Test {
    @get:Rule val temp = TemporaryFolder()
    private val source = "a".repeat(64)
    private val targetId = "b".repeat(64)
    private fun placeFingerprint(folder: File) = LocalSnapshotRestoreFingerprint.of(
        "chunks-place-v1:" + folder.canonicalPath)
    private val one = "1".repeat(40)
    private val two = "2".repeat(40)
    private val three = "3".repeat(40)

    private class Store : SnapshotRestoreJournalV1.Store {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun compareAndCommit(key: String, expected: String?, value: String): Boolean {
            if (values[key] != expected) return false
            values[key] = value
            return true
        }
    }

    private class FakeTarget : SnapshotRestoreOnceV1.LocalTarget {
        val jobs = linkedMapOf<String, File?>()
        var submissions = 0
        var inventoryReads = 0
        var replaceOnRead: Pair<Int, List<String>>? = null
        override fun completeHashes(): List<String> {
            inventoryReads++
            return replaceOnRead?.takeIf { it.first == inventoryReads }?.second
                ?: jobs.keys.toList()
        }
        override fun observedStoppedPlace(infoHashV1: String): File? = jobs[infoHashV1]
        override fun submitStopped(originalTorrent: ByteArray, chosenPlace: File): Boolean {
            submissions++
            error("Batch recovery must never submit, Stop, Start or delete")
        }
    }

    private fun journal(store: Store, hash: String): SnapshotRestoreJournalV1 =
        SnapshotRestoreJournalV1.open(
            store, SnapshotRestoreOnceV1.itemArchiveFingerprint(source, hash),
            targetId, listOf(hash))

    private fun recovery(
        hashes: List<String>, folder: File, store: Store, target: FakeTarget,
        frozen: List<String> = target.jobs.keys.toList(),
        fingerprint: String = placeFingerprint(folder),
        mayContinue: () -> Boolean = { true }
    ) = SnapshotLocalBatchRecoveryV1.inspect(
        hashes = hashes, archiveFingerprint = source, targetFingerprint = targetId,
        chosenPlace = folder, verifiedPlaceFingerprint = fingerprint,
        frozenTargetHashes = frozen, store = store, target = target,
        mayContinue = mayContinue)

    @Test fun recoversPendingAcknowledgementByIndependentStoppedPlaceReadback() {
        val folder = temp.newFolder("same")
        val store = Store()
        val target = FakeTarget()
        val pending = journal(store, one)
        pending.prepare(one, placeFingerprint(folder))
        pending.beforeAdd(one, placeFingerprint(folder))
        pending.acknowledged(one)
        target.jobs[one] = folder

        val report = recovery(listOf(one), folder, store, target)
        assertTrue(report.completed)
        assertEquals(1, report.verifiedCount)
        assertEquals(0, report.unresolvedCount)
        assertEquals(SnapshotLocalBatchRecoveryV1.State.VERIFIED_STOPPED,
            report.items.single().state)
        assertEquals(SnapshotRestoreJournalV1.Phase.VERIFIED,
            journal(store, one).entries().single().phase)
        assertEquals(0, target.submissions)
        assertEquals("One initial and one final complete inventory for the group",
            2, target.inventoryReads)
    }

    @Test fun groupedReviewDistinguishesVerifiedPendingUnsentAndMissingJournals() {
        val folder = temp.newFolder("mix")
        val store = Store()
        val target = FakeTarget()
        val verified = journal(store, one)
        verified.prepare(one, placeFingerprint(folder))
        verified.beforeAdd(one, placeFingerprint(folder))
        verified.acknowledged(one)
        target.jobs[one] = folder

        val reviewedOnly = journal(store, two)
        reviewedOnly.prepare(two, placeFingerprint(folder))
        // No journal for three. The result must not imply no earlier Add.

        val report = recovery(listOf(three, one, two), folder, store, target)
        assertTrue(report.completed)
        assertEquals(1, report.verifiedCount)
        assertEquals(1, report.reviewOnlyCount)
        assertEquals(1, report.unresolvedCount)
        assertEquals(listOf(one, two, three), report.items.map { it.infoHashV1 })
        assertEquals(listOf(
            SnapshotLocalBatchRecoveryV1.State.VERIFIED_STOPPED,
            SnapshotLocalBatchRecoveryV1.State.NO_SUBMISSION_IN_THIS_JOURNAL,
            SnapshotLocalBatchRecoveryV1.State.JOURNAL_UNAVAILABLE),
            report.items.map { it.state })
        assertEquals(0, target.submissions)
    }

    @Test fun uncertainUnobservedAddNeverBecomesVerifiedAndNeverRetries() {
        val folder = temp.newFolder("missing")
        val store = Store()
        val target = FakeTarget()
        val pending = journal(store, one)
        pending.prepare(one, placeFingerprint(folder))
        pending.beforeAdd(one, placeFingerprint(folder))
        val report = recovery(listOf(one), folder, store, target)
        assertEquals(SnapshotLocalBatchRecoveryV1.State.UNRESOLVED,
            report.items.single().state)
        assertEquals(SnapshotRestoreJournalV1.Phase.IN_FLIGHT,
            journal(store, one).entries().single().phase)
        assertEquals(0, target.submissions)
    }

    @Test fun stoppedInWrongPlaceOrCurrentActiveStateCannotConfirmOldReceipt() {
        val folder = temp.newFolder("reviewed")
        val other = temp.newFolder("other")
        val store = Store()
        val target = FakeTarget()
        val pending = journal(store, one)
        pending.prepare(one, placeFingerprint(folder))
        pending.beforeAdd(one, placeFingerprint(folder))
        target.jobs[one] = other
        assertEquals(SnapshotLocalBatchRecoveryV1.State.UNRESOLVED,
            recovery(listOf(one), folder, store, target).items.single().state)
        target.jobs[one] = null // complete inventory sees hash but not STOPPED Place
        assertEquals(SnapshotLocalBatchRecoveryV1.State.UNRESOLVED,
            recovery(listOf(one), folder, store, target).items.single().state)
        assertEquals(0, target.submissions)
    }

    @Test fun differentReviewedPlaceBlocksPromotionOfPendingJournal() {
        val original = temp.newFolder("original")
        val folder = temp.newFolder("different")
        val store = Store()
        val target = FakeTarget()
        val pending = journal(store, one)
        pending.prepare(one, placeFingerprint(original))
        pending.beforeAdd(one, placeFingerprint(original))
        target.jobs[one] = original
        val report = recovery(listOf(one), folder, store, target)
        assertEquals(SnapshotLocalBatchRecoveryV1.State.DIFFERENT_REVIEWED_PLACE,
            report.items.single().state)
        assertEquals(SnapshotRestoreJournalV1.Phase.IN_FLIGHT,
            journal(store, one).entries().single().phase)
    }

    @Test fun forgedPlaceFingerprintCannotBePairedWithDifferentFolder() {
        val folder = temp.newFolder("forged")
        val store = Store()
        val target = FakeTarget()
        assertEquals(SnapshotLocalBatchRecoveryV1.Blocker.INVALID_REVIEW,
            recovery(listOf(one), folder, store, target,
                fingerprint = "d".repeat(64)).blocker)
        assertEquals(0, target.inventoryReads)
        assertEquals(0, target.submissions)
    }

    @Test fun staleInventoryOrChangedTargetDuringInspectionBlocksWholeSummary() {
        val folder = temp.newFolder("stale")
        val store = Store()
        val target = FakeTarget()
        target.jobs[one] = folder
        assertEquals(SnapshotLocalBatchRecoveryV1.Blocker.TARGET_CHANGED,
            recovery(listOf(one), folder, store, target, frozen = emptyList()).blocker)
        target.inventoryReads = 0 // Stage the changed list for FINAL readback.
        target.replaceOnRead = 2 to listOf(one, two)
        assertEquals(SnapshotLocalBatchRecoveryV1.Blocker.TARGET_CHANGED,
            recovery(listOf(one), folder, store, target).blocker)
        assertEquals(2, target.inventoryReads)
        assertEquals(0, target.submissions)
    }

    @Test fun corruptOrAbsentJournalNeverGetsReinitializedByRecovery() {
        val folder = temp.newFolder("corrupt")
        val store = Store()
        val target = FakeTarget()
        val previous = journal(store, one)
        val keys = store.values.keys.toList()
        store.values[keys.single()] = "corrupted persisted journal"
        val previousBytes = store.values.toMap()
        val report = recovery(listOf(one, two), folder, store, target)
        assertEquals(2, report.unresolvedCount)
        assertEquals(listOf(
            SnapshotLocalBatchRecoveryV1.State.JOURNAL_UNAVAILABLE,
            SnapshotLocalBatchRecoveryV1.State.JOURNAL_UNAVAILABLE),
            report.items.map { it.state })
        assertEquals(previousBytes, store.values)
        assertEquals(0, target.submissions)
    }

    @Test fun invalidDuplicateHashesOrCancelledReviewCannotReadOrWriteEngine() {
        val folder = temp.newFolder("denied")
        val store = Store()
        val target = FakeTarget()
        assertEquals(SnapshotLocalBatchRecoveryV1.Blocker.INVALID_REVIEW,
            recovery(listOf(one, one), folder, store, target).blocker)
        assertEquals(SnapshotLocalBatchRecoveryV1.Blocker.INVALID_REVIEW,
            recovery(listOf("x"), folder, store, target).blocker)
        assertEquals(SnapshotLocalBatchRecoveryV1.Blocker.CANCELLED,
            recovery(listOf(one), folder, store, target, mayContinue = { false }).blocker)
        assertEquals(0, target.inventoryReads)
        assertEquals(0, target.submissions)
    }
}
