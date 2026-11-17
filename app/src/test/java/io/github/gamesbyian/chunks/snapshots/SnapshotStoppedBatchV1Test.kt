package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.engine.DesktopLibtorrent
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder

/** No CI or UI authorization to add batches: isolated engine contract tests. */
class SnapshotStoppedBatchV1Test {
    @get:Rule val temp = TemporaryFolder()
    private val archive = "a".repeat(64)
    private val targetId = "b".repeat(64)

    private class MemoryStore : SnapshotRestoreJournalV1.Store {
        val state = mutableMapOf<String, String>()
        override fun read(key: String) = state[key]
        override fun compareAndCommit(key: String, expected: String?, value: String): Boolean {
            if (state[key] != expected) return false
            state[key] = value
            return true
        }
    }

    private fun prepared(label: String): SnapshotVerifiedTorrentDocumentV1.Prepared {
        DesktopLibtorrent.ensureLoaded()
        val folder = File(temp.root, "original-$label").apply { mkdirs() }
        File(folder, "fixture-$label.bin").writeBytes(ByteArray(8192) { (it + label.length + label[0].code).toByte() })
        val bytes = TorrentBuilder().path(folder).pieceSize(16 * 1024)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()
        val parsed = TorrentMetainfoReader.preview(bytes)
        return SnapshotVerifiedTorrentDocumentV1.Prepared(
            parsed.infoHashV1, parsed.name, "synthetic.torrent", bytes)
    }

    private class Target : SnapshotRestoreOnceV1.LocalTarget {
        val hashes = mutableListOf<String>()
        val places = mutableMapOf<String, File>()
        var calls = 0
        var unknownAckAt: Int? = null
        var externalDriftAfter: Int? = null
        var hideStoppedPlace: Boolean = false

        override fun completeHashes(): List<String> = hashes.toList()
        override fun observedStoppedPlace(infoHashV1: String): File? =
            if (hideStoppedPlace) null else places[infoHashV1]
        override fun submitStopped(originalTorrent: ByteArray, chosenPlace: File): Boolean {
            calls++
            if (calls == unknownAckAt) return false
            val hash = TorrentMetainfoReader.preview(originalTorrent).infoHashV1
            hashes += hash
            places[hash] = chosenPlace
            if (calls == externalDriftAfter) hashes += "f".repeat(40)
            return true
        }
    }

    private fun candidate(label: String) =
        SnapshotStoppedBatchV1.Candidate(prepared(label), temp.newFolder("place-$label"))

    private fun run(
        items: List<SnapshotStoppedBatchV1.Candidate>,
        frozen: List<String> = emptyList(),
        target: Target = Target(),
        store: MemoryStore = MemoryStore(),
        approved: Boolean = true
    ) = SnapshotStoppedBatchV1.execute(
        archive, targetId, items, frozen, approved, store, target)

    @Test fun twoReviewedItemsRestoreStoppedWithIndependentDurableJournals() {
        val first = candidate("one")
        val second = candidate("two")
        val store = MemoryStore()
        val target = Target()
        val result = run(listOf(first, second), target = target, store = store)
        assertEquals(SnapshotStoppedBatchV1.Halt.NONE, result.halt)
        assertEquals(listOf(SnapshotStoppedBatchV1.State.VERIFIED_STOPPED,
            SnapshotStoppedBatchV1.State.VERIFIED_STOPPED), result.items.map { it.state })
        assertEquals(2, target.calls)
        assertEquals(2, store.state.size)
        assertTrue(store.state.values.all { "VERIFIED" in it })
        assertTrue(store.state.values.none { "/place-" in it || "original-one" in it })
        assertEquals(setOf(first.reviewed.infoHashV1, second.reviewed.infoHashV1), target.hashes.toSet())
    }

    @Test fun unknownSecondAddHaltsThirdWithoutRetryOrAutomaticResume() {
        val items = listOf(candidate("one"), candidate("two"), candidate("three"))
        val store = MemoryStore()
        val target = Target().apply { unknownAckAt = 2 }
        val result = run(items, target = target, store = store)
        assertEquals(SnapshotStoppedBatchV1.Halt.UNCERTAIN, result.halt)
        assertEquals(listOf(SnapshotStoppedBatchV1.State.VERIFIED_STOPPED,
            SnapshotStoppedBatchV1.State.UNCERTAIN,
            SnapshotStoppedBatchV1.State.NOT_ATTEMPTED), result.items.map { it.state })
        assertEquals(2, target.calls)
        assertTrue(store.state.values.any { "IN_FLIGHT" in it })
        val repeated = run(items, target = target, store = store)
        assertEquals(SnapshotStoppedBatchV1.Halt.STALE_TARGET, repeated.halt)
        assertEquals(2, target.calls)
    }

    @Test fun noApprovalDoesNotInspectEngineOrCreateAnyJournal() {
        val items = listOf(candidate("one"), candidate("two"))
        val store = MemoryStore()
        val target = Target()
        val result = run(items, target = target, store = store, approved = false)
        assertEquals(SnapshotStoppedBatchV1.Halt.NOT_APPROVED, result.halt)
        assertTrue(result.items.all { it.state == SnapshotStoppedBatchV1.State.NOT_ATTEMPTED })
        assertEquals(0, target.calls)
        assertTrue(store.state.isEmpty())
    }

    @Test fun invalidOrDuplicateOriginalAbortsWholeBatchBeforeFirstAdd() {
        val first = candidate("one")
        val second = candidate("two")
        val store = MemoryStore()
        val target = Target()
        val changed = second.copy(reviewed = second.reviewed.copy(infoHashV1 = "1".repeat(40)))
        try {
            run(listOf(first, changed), target = target, store = store)
            fail("Mismatch in later original must fail before first native Add")
        } catch (_: IllegalArgumentException) { }
        try {
            run(listOf(first, first), target = target, store = store)
            fail("Duplicate selected original cannot authorize two Adds")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, target.calls)
        assertTrue(store.state.isEmpty())
    }

    @Test fun externalInventoryDriftAfterFirstRestoreHaltsBeforeSecondAdd() {
        val target = Target().apply { externalDriftAfter = 1 }
        val result = run(listOf(candidate("one"), candidate("two")), target = target)
        assertEquals(SnapshotStoppedBatchV1.Halt.STALE_TARGET, result.halt)
        assertEquals(listOf(SnapshotStoppedBatchV1.State.VERIFIED_STOPPED,
            SnapshotStoppedBatchV1.State.NOT_ATTEMPTED), result.items.map { it.state })
        assertEquals(1, target.calls)
    }

    @Test fun alreadyPresentIdentityIsSkippedWithoutClaimingRestoredPlace() {
        val first = candidate("one")
        val second = candidate("two")
        val target = Target().apply { hashes += first.reviewed.infoHashV1 }
        val store = MemoryStore()
        val result = run(listOf(first, second), target.hashes.toList(), target, store)
        assertEquals(SnapshotStoppedBatchV1.Halt.NONE, result.halt)
        assertEquals(listOf(SnapshotStoppedBatchV1.State.PRESENT_UNVERIFIED,
            SnapshotStoppedBatchV1.State.VERIFIED_STOPPED), result.items.map { it.state })
        assertEquals(1, target.calls)
        assertEquals(1, store.state.size)
    }

    @Test fun missingStoppedPlaceProofHaltsAfterFirstUnknownWrite() {
        val target = Target().apply { hideStoppedPlace = true }
        val result = run(listOf(candidate("one"), candidate("two")), target = target)
        assertEquals(SnapshotStoppedBatchV1.Halt.UNCERTAIN, result.halt)
        assertEquals(listOf(SnapshotStoppedBatchV1.State.UNCERTAIN,
            SnapshotStoppedBatchV1.State.NOT_ATTEMPTED), result.items.map { it.state })
        assertEquals(1, target.calls)
    }
}
