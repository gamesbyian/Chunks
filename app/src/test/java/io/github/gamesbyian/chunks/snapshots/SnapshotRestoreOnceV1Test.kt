package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.engine.DesktopLibtorrent
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder

/** Actual native engine Add plus fault-injected crash-unsafe mutation boundaries. */
class SnapshotRestoreOnceV1Test {
    @get:Rule val temp = TemporaryFolder()
    private val archive = "a".repeat(64)
    private val targetId = "b".repeat(64)
    private val place = "c".repeat(64)

    private class Store : SnapshotRestoreJournalV1.Store {
        private val content = mutableMapOf<String, String>()
        var failWrites = false
        override fun read(key: String): String? = content[key]
        override fun compareAndCommit(key: String, expected: String?, value: String): Boolean {
            if (failWrites || content[key] != expected) return false
            content[key] = value
            return true
        }
        fun persisted(): String = content.values.single()
    }

    private fun original(): SnapshotVerifiedTorrentDocumentV1.Prepared {
        DesktopLibtorrent.ensureLoaded()
        val root = File(temp.root, "Original public torrent fixture").apply { mkdirs() }
        File(root, "sample.bin").writeBytes(ByteArray(8192) { it.toByte() })
        val bytes = TorrentBuilder().path(root).pieceSize(16 * 1024)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()
        val parsed = TorrentMetainfoReader.preview(bytes)
        return SnapshotVerifiedTorrentDocumentV1.Prepared(
            parsed.infoHashV1, parsed.name, "private-document-name.torrent", bytes)
    }

    private class FakeTarget : SnapshotRestoreOnceV1.LocalTarget {
        var hashes: List<String> = emptyList()
        var writes = 0
        var throwOnWrite = false
        var ambiguousWrite = false
        var nowStopped: Boolean? = true
        var observedPlace: File? = null
        override fun completeHashes(): List<String> = hashes.toList()
        override fun observedStoppedPlace(infoHashV1: String): File? =
            if (nowStopped == true) observedPlace else null
        override fun submitStopped(originalTorrent: ByteArray, chosenPlace: File): Boolean {
            writes++
            observedPlace = chosenPlace
            if (throwOnWrite) throw IllegalStateException("lost response after possible Add")
            if (ambiguousWrite) return false
            hashes = hashes + TorrentMetainfoReader.preview(originalTorrent).infoHashV1
            return true
        }
    }

    @Test fun realLocalEngineSubmitsExactlyOnceStoppedAndJournalSurvivesReopen() {
        val source = original()
        val folder = temp.newFolder("reviewed-original-place")
        val store = Store()
        val state = temp.newFolder("restored-engine-state")
        val engine = LocalTorrentEngine(LocalEngineConfig(
            listenInterfaces = "127.0.0.1:0", allowMultipleConnectionsPerIp = true,
            stateDirectory = state))
        engine.start()
        try {
            val journal = SnapshotRestoreJournalV1.open(store, archive,
                targetId, listOf(source.infoHashV1))
            val target = SnapshotRestoreOnceV1.localEngine(engine)
            assertEquals(SnapshotRestoreOnceV1.Outcome.VERIFIED_PRESENT_STOPPED,
                SnapshotRestoreOnceV1.execute(journal, source, folder, place,
                    emptyList(), userApproved = true, target = target))
            val status = requireNotNull(engine.status(source.infoHashV1))
            assertTrue(status.stopped)
            assertEquals(0L, status.payloadDownloadedBytes)
            assertEquals(folder.absolutePath, status.savePath)
            assertEquals(SnapshotRestoreJournalV1.Phase.VERIFIED,
                SnapshotRestoreJournalV1.open(store, archive, targetId,
                    listOf(source.infoHashV1)).entries().single().phase)
            assertEquals(SnapshotRestoreOnceV1.Outcome.STALE_TARGET,
                SnapshotRestoreOnceV1.execute(journal, source, folder, place,
                    emptyList(), userApproved = true, target = target))
            assertTrue(store.persisted().contains("VERIFIED"))
            assertFalse(store.persisted().contains(folder.absolutePath))
            assertFalse(store.persisted().contains(source.originalName))
        } finally {
            engine.close()
        }
        val reopened = LocalTorrentEngine(LocalEngineConfig(
            listenInterfaces = "127.0.0.1:0", allowMultipleConnectionsPerIp = true,
            stateDirectory = state))
        reopened.start()
        try {
            assertTrue(requireNotNull(reopened.status(source.infoHashV1)).stopped)
            assertEquals(folder.absolutePath, reopened.status(source.infoHashV1)?.savePath)
        } finally { reopened.close() }
    }

    @Test fun failedDurablePreAddCommitNeverCallsTheEngine() {
        val source = original()
        val store = Store()
        val journal = SnapshotRestoreJournalV1.open(store, archive, targetId, listOf(source.infoHashV1))
        store.failWrites = true
        val fake = FakeTarget()
        assertEquals(SnapshotRestoreOnceV1.Outcome.JOURNAL_BLOCKED,
            SnapshotRestoreOnceV1.execute(journal, source, temp.newFolder(),
                place, emptyList(), true, fake))
        assertEquals(0, fake.writes)
        assertEquals(SnapshotRestoreJournalV1.Phase.REVIEW,
            journal.entries().single().phase)
    }

    @Test fun ambiguousWriteCannotBlindlyRetryEvenAfterFreshObservedAbsence() {
        val source = original()
        val store = Store()
        val journal = SnapshotRestoreJournalV1.open(store, archive, targetId, listOf(source.infoHashV1))
        val fake = FakeTarget().apply { throwOnWrite = true }
        val folder = temp.newFolder()
        assertEquals(SnapshotRestoreOnceV1.Outcome.UNCERTAIN,
            SnapshotRestoreOnceV1.execute(journal, source, folder, place, emptyList(), true, fake))
        assertEquals(1, fake.writes)
        assertEquals(SnapshotRestoreJournalV1.Phase.IN_FLIGHT,
            SnapshotRestoreJournalV1.open(store, archive, targetId,
                listOf(source.infoHashV1)).entries().single().phase)
        assertEquals(SnapshotRestoreOnceV1.Outcome.JOURNAL_BLOCKED,
            SnapshotRestoreOnceV1.execute(journal, source, folder, place, emptyList(), true, fake))
        assertEquals(1, fake.writes)
    }

    @Test fun changedOrDuplicateInventoryRefusesAnyNativeMutation() {
        val source = original()
        val journal = SnapshotRestoreJournalV1.open(Store(), archive, targetId,
            listOf(source.infoHashV1))
        val other = "f".repeat(40)
        val fake = FakeTarget().apply { hashes = listOf(other) }
        val folder = temp.newFolder()
        assertEquals(SnapshotRestoreOnceV1.Outcome.STALE_TARGET,
            SnapshotRestoreOnceV1.execute(journal, source, folder, place, emptyList(), true, fake))
        fake.hashes = listOf(other, other.uppercase())
        assertEquals(SnapshotRestoreOnceV1.Outcome.STALE_TARGET,
            SnapshotRestoreOnceV1.execute(journal, source, folder, place, fake.hashes, true, fake))
        fake.hashes = emptyList()
        assertEquals(SnapshotRestoreOnceV1.Outcome.REFUSED,
            SnapshotRestoreOnceV1.execute(journal, source, folder, place, emptyList(), false, fake))
        assertEquals(0, fake.writes)
    }

    @Test fun wrongObservedSavePathCannotMarkJournalVerified() {
        val source = original()
        val store = Store()
        val journal = SnapshotRestoreJournalV1.open(store, archive,
            targetId, listOf(source.infoHashV1))
        val destination = temp.newFolder("chosen-path")
        val other = temp.newFolder("wrong-path")
        val fake = object : SnapshotRestoreOnceV1.LocalTarget {
            var additions = 0
            var hashes = emptyList<String>()
            override fun completeHashes() = hashes
            override fun observedStoppedPlace(infoHashV1: String) = other
            override fun submitStopped(originalTorrent: ByteArray, chosenPlace: File): Boolean {
                additions++
                hashes = listOf(source.infoHashV1)
                return true
            }
        }
        assertEquals(SnapshotRestoreOnceV1.Outcome.UNCERTAIN,
            SnapshotRestoreOnceV1.execute(journal, source, destination,
                place, emptyList(), true, fake))
        assertEquals(SnapshotRestoreJournalV1.Phase.ACK_UNVERIFIED,
            journal.entries().single().phase)
        assertEquals(SnapshotRestoreOnceV1.Outcome.STALE_TARGET,
            SnapshotRestoreOnceV1.execute(journal, source, destination,
                place, emptyList(), true, fake))
        assertEquals(1, fake.additions)
    }

    @Test fun twoMissingItemsFromSameArchiveDoNotCollideInDurableJournal() {
        val original = original()
        val secondHash = "d".repeat(40)
        val firstScope = SnapshotRestoreOnceV1.itemArchiveFingerprint(
            archive, original.infoHashV1)
        val secondScope = SnapshotRestoreOnceV1.itemArchiveFingerprint(
            archive, secondHash)
        assertFalse(firstScope == secondScope)
        assertEquals(firstScope, SnapshotRestoreOnceV1.itemArchiveFingerprint(
            archive, original.infoHashV1))
        val store = Store()
        val first = SnapshotRestoreJournalV1.open(store, firstScope, targetId,
            listOf(original.infoHashV1))
        first.prepare(original.infoHashV1, place)
        first.beforeAdd(original.infoHashV1, place)
        val second = SnapshotRestoreJournalV1.open(store, secondScope, targetId,
            listOf(secondHash))
        assertEquals(SnapshotRestoreJournalV1.Phase.REVIEW,
            second.entries().single().phase)
        second.prepare(secondHash, place)
        assertEquals(SnapshotRestoreJournalV1.Phase.IN_FLIGHT,
            SnapshotRestoreJournalV1.open(store, firstScope, targetId,
                listOf(original.infoHashV1)).entries().single().phase)
        assertEquals(SnapshotRestoreJournalV1.Phase.READY,
            SnapshotRestoreJournalV1.open(store, secondScope, targetId,
                listOf(secondHash)).entries().single().phase)
        try {
            SnapshotRestoreOnceV1.itemArchiveFingerprint(archive, "bad")
            fail("Invalid journal identity cannot be used")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun presenceWithoutStoppedReadbackNeverClaimsVerifiedRestore() {
        val source = original()
        val store = Store()
        val journal = SnapshotRestoreJournalV1.open(store, archive, targetId,
            listOf(source.infoHashV1))
        val fake = FakeTarget().apply { nowStopped = null }
        assertEquals(SnapshotRestoreOnceV1.Outcome.UNCERTAIN,
            SnapshotRestoreOnceV1.execute(journal, source, temp.newFolder(),
                place, emptyList(), true, fake))
        assertEquals(1, fake.writes)
        assertEquals(SnapshotRestoreJournalV1.Phase.ACK_UNVERIFIED,
            journal.entries().single().phase)
    }
}
