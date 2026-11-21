package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SnapshotLocalBatchRestoreV1Test {
    @get:Rule val temp = TemporaryFolder()
    private val archive = "a".repeat(64)
    private val targetId = "b".repeat(64)
    private val placeId = "c".repeat(64)

    private fun original(name: String): SnapshotVerifiedTorrentDocumentV1.Prepared {
        val info = "d6:lengthi5e4:name" + name.toByteArray().size + ":" + name +
            "12:piece lengthi16384e6:pieces20:01234567890123456789e"
        val bytes = ("d8:announce14:http://ignored4:info" + info + "e")
            .toByteArray(Charsets.UTF_8)
        val hash = TorrentMetainfoReader.preview(bytes).infoHashV1
        return SnapshotVerifiedTorrentDocumentV1.Prepared(
            hash, name, "chunks-" + hash.take(12) + ".torrent", bytes)
    }

    private class Store : SnapshotRestoreJournalV1.Store {
        private val records = mutableMapOf<String, String>()
        override fun read(key: String): String? = records[key]
        override fun compareAndCommit(key: String, expected: String?, value: String): Boolean {
            if (records[key] != expected) return false
            records[key] = value
            return true
        }
    }

    private class FakeTarget(private val folder: File) : SnapshotRestoreOnceV1.LocalTarget {
        val entries = linkedMapOf<String, File>()
        var calls = 0
        var acceptWrites = true
        var addConcurrentIdentity: String? = null
        var onFirstInventory: (() -> Unit)? = null

        override fun completeHashes(): List<String> {
            onFirstInventory?.let { action ->
                onFirstInventory = null
                action()
            }
            return entries.keys.toList()
        }
        override fun observedStoppedPlace(infoHashV1: String): File? = entries[infoHashV1]
        override fun submitStopped(originalTorrent: ByteArray, chosenPlace: File): Boolean {
            calls++
            if (!acceptWrites) return false
            val hash = TorrentMetainfoReader.preview(originalTorrent).infoHashV1
            entries[hash] = chosenPlace
            addConcurrentIdentity?.let { other ->
                entries[other] = chosenPlace
                addConcurrentIdentity = null
            }
            return true
        }
    }

    private fun execute(
        originals: List<SnapshotVerifiedTorrentDocumentV1.Prepared>,
        folder: File, store: Store, target: FakeTarget,
        approved: Boolean = true, initial: List<String> = emptyList(),
        mayContinue: () -> Boolean = { true }
    ) = SnapshotLocalBatchRestoreV1.execute(
        reviewed = originals, archiveFingerprint = archive,
        targetFingerprint = targetId, chosenPlace = folder,
        verifiedPlaceFingerprint = placeId, frozenHashes = initial,
        userApproved = approved, store = store, target = target,
        mayContinue = mayContinue)

    @Test fun twoVerifiedDefinitionsRestoreStoppedInDeterministicOrderWithoutStart() {
        val folder = temp.newFolder("two")
        val store = Store()
        val target = FakeTarget(folder)
        val originals = listOf(original("episode-2"), original("episode-1"))
        val result = execute(originals.reversed(), folder, store, target)
        assertTrue(result.complete)
        assertEquals(2, result.verified)
        assertEquals(0, result.remaining)
        assertNull(result.stop)
        assertNull(result.stoppedAtInfoHash)
        assertEquals(2, target.calls)
        assertEquals(originals.map { it.infoHashV1 }.toSet(), target.entries.keys.toSet())
        for (prepared in originals) {
            val journal = SnapshotRestoreJournalV1.openExisting(
                store, SnapshotRestoreOnceV1.itemArchiveFingerprint(archive, prepared.infoHashV1),
                targetId, listOf(prepared.infoHashV1))
            assertEquals(SnapshotRestoreJournalV1.Phase.VERIFIED,
                journal.entries().single().phase)
        }
    }

    @Test fun reviewBuffersChangingAfterBatchFreezeCannotAlterSubmittedDefinitions() {
        val folder = temp.newFolder("frozen")
        val store = Store()
        val target = FakeTarget(folder)
        val originals = listOf(original("first"), original("second"))
        val second = originals.last()
        target.onFirstInventory = { second.bytes.fill(0) }

        val result = execute(originals, folder, store, target)
        assertTrue(result.complete)
        assertEquals(2, target.calls)
        assertTrue("Regression must mutate the caller's source after preflight",
            second.bytes.all { it == 0.toByte() })
        // FakeTarget independently parses every submitted original; successful
        // verified stopped Adds prove the coordinator used frozen copies.
    }

    @Test fun corruptLaterOriginalRefusesEntireGroupBeforeFirstNativeMutation() {
        val folder = temp.newFolder("bad")
        val store = Store()
        val target = FakeTarget(folder)
        val good = original("good")
        val bad = original("bad")
        bad.bytes[0] = 'x'.code.toByte()
        val result = execute(listOf(good, bad), folder, store, target)
        assertEquals(SnapshotRestoreOnceV1.Outcome.REFUSED, result.stop)
        assertEquals(0, result.verified)
        assertEquals(0, target.calls)
        assertTrue(target.entries.isEmpty())
    }

    @Test fun changedTrackerOnLaterOriginalRefusesBeforeTheFirstAdd() {
        val folder = temp.newFolder("changed-tracker")
        val target = FakeTarget(folder)
        val second = original("second")
        val changed = second.bytes.toString(Charsets.UTF_8).replace("http://ignored", "http://changed")
            .toByteArray(Charsets.UTF_8)
        assertEquals(second.infoHashV1, TorrentMetainfoReader.preview(changed).infoHashV1)
        assertFalse(second.bytes.contentEquals(changed))

        val result = execute(listOf(original("first"), second.copy(bytes = changed)), folder, Store(), target)
        assertEquals(SnapshotRestoreOnceV1.Outcome.REFUSED, result.stop)
        assertEquals(0, target.calls)
    }

    @Test fun uncertainFirstAddStopsBatchAndIsNeverReplayedWithNewGroupReview() {
        val folder = temp.newFolder("uncertain")
        val store = Store()
        val target = FakeTarget(folder)
        target.acceptWrites = false
        val originals = listOf(original("one"), original("two"))
        val first = execute(originals, folder, store, target)
        assertEquals(SnapshotRestoreOnceV1.Outcome.UNCERTAIN, first.stop)
        assertEquals(0, first.verified)
        assertEquals(1, target.calls)
        val second = execute(originals.reversed(), folder, store, target)
        assertEquals(SnapshotRestoreOnceV1.Outcome.JOURNAL_BLOCKED, second.stop)
        assertEquals(0, second.verified)
        assertEquals(1, target.calls)
    }

    @Test fun independentConcurrentTransferInsertionBlocksRemainingAdds() {
        val folder = temp.newFolder("concurrent")
        val store = Store()
        val target = FakeTarget(folder)
        val originals = listOf(original("one"), original("two"))
        target.addConcurrentIdentity = "d".repeat(40)
        val result = execute(originals, folder, store, target)
        assertEquals(SnapshotRestoreOnceV1.Outcome.STALE_TARGET, result.stop)
        assertEquals(1, result.verified)
        assertEquals(1, result.remaining)
        assertEquals(1, target.calls)
        assertEquals(2, target.entries.size)
    }

    @Test fun cancelledReviewDoesNotBeginASecondNativeAdd() {
        val folder = temp.newFolder("cancelled")
        val store = Store()
        val target = FakeTarget(folder)
        val result = execute(listOf(original("one"), original("two")),
            folder, store, target, mayContinue = { target.calls == 0 })
        assertEquals(SnapshotRestoreOnceV1.Outcome.REFUSED, result.stop)
        assertEquals(1, result.verified)
        assertEquals(1, target.calls)
        assertEquals(1, result.remaining)
    }

    @Test fun oldPendingJournalOnLaterItemBlocksWholeBatchBeforeAnyAdd() {
        val folder = temp.newFolder("old-pending")
        val store = Store()
        val target = FakeTarget(folder)
        val originals = listOf(original("one"), original("two"))
            .sortedBy { it.infoHashV1 }
        val later = originals.last()
        val journal = SnapshotRestoreJournalV1.open(
            store, SnapshotRestoreOnceV1.itemArchiveFingerprint(archive, later.infoHashV1),
            targetId, listOf(later.infoHashV1))
        journal.prepare(later.infoHashV1, placeId)
        journal.beforeAdd(later.infoHashV1, placeId)

        val result = execute(originals, folder, store, target)
        assertEquals(SnapshotRestoreOnceV1.Outcome.JOURNAL_BLOCKED, result.stop)
        assertEquals(later.infoHashV1, result.stoppedAtInfoHash)
        assertEquals(0, result.verified)
        assertEquals("A pending later item must not partially restore earlier items",
            0, target.calls)
    }

    @Test fun consentOrStaleInventoryRefusesBeforeFirstWrite() {
        val folder = temp.newFolder("unapproved")
        val store = Store()
        val target = FakeTarget(folder)
        val originals = listOf(original("one"), original("two"))
        assertEquals(SnapshotRestoreOnceV1.Outcome.REFUSED,
            execute(originals, folder, store, target, approved = false).stop)
        assertEquals(SnapshotRestoreOnceV1.Outcome.STALE_TARGET,
            execute(originals, folder, store, target, initial = listOf("f".repeat(40))).stop)
        assertEquals(0, target.calls)
    }
}
