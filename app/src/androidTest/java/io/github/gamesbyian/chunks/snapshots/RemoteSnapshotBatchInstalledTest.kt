package io.github.gamesbyian.chunks.snapshots

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.SessionEraseOutcome
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real disposable legacy/modern-compatible rTorrent, actual stopped Adds and readbacks. */
@RunWith(AndroidJUnit4::class)
class RemoteSnapshotBatchInstalledTest {
    @Test fun twoStoppedDefinitionsRequireOneLeaseAndEachJournalVerifiesIndependently() {
        assumeTrue(InstrumentationRegistry.getArguments()
            .getString("chunks_combined_disposable") == "authorized")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = RTorrentRemote(RemoteCredentials(
            "http://127.0.0.1:18080/rutorrent/plugins/httprpc/action.php", "chunks", "chunks-one"))
        val independent = RTorrentRemote(RemoteCredentials(
            "http://127.0.0.1:18080/RPC2", "chunks", "chunks-one"))
        val other = RTorrentRemote(RemoteCredentials("http://127.0.0.1:18082/RPC2", "", ""))
        val tracker = "http://127.0.0.1:1/announce"
        fun definition(name: String, seed: String): ByteArray =
            ("d8:announce${tracker.length}:$tracker" +
                "4:infod6:lengthi5e4:name${name.length}:$name" +
                "12:piece lengthi16384e6:pieces20:").toByteArray() +
                MessageDigest.getInstance("SHA-1").digest(seed.toByteArray()) +
                "ee".toByteArray()
        val contents = listOf(
            definition("batch-first.bin", "batch-first-receipt"),
            definition("batch-second.bin", "batch-second-receipt"))
        val originals = contents.map { TorrentMetainfoReader.preview(it) }
        val hashes = originals.map { it.infoHashV1 }
        val archive = SnapshotManifestV1.decode(SnapshotManifestV1.encode(
            SnapshotManifestV1.Manifest("chunks-android", "2026-11-23T00:00:00Z",
                SnapshotManifestV1.Source.LOCAL, originals.map {
                    SnapshotManifestV1.Item(it.infoHashV1, it.name,
                        SnapshotManifestV1.Recovery.METAINFO)
                }.sortedBy { it.infoHashV1 }),
            originals.zip(contents).associate { (item, bytes) -> item.infoHashV1 to bytes }))
        runBlocking {
            account.version()
            val before = independent.listCompleteSessionTransfers().map { it.hash }
            val otherBefore = other.listCompleteSessionTransfers().map { it.hash }.toSet()
            assertTrue(hashes.none { it in before })
            val place = requireNotNull(independent.defaultDirectory()) + "/reviewed"
            var admitted = 0
            var settled = 0
            val coordinator = SnapshotRemoteDefinitionRestore(
                account, AndroidSnapshotRestoreJournalStore(context),
                stillCurrent = { true },
                admitWrite = { admitted++; true },
                settleWrite = { settled++ })
            assertNull(coordinator.reviewBatch(archive, listOf(hashes[0], hashes[0]),
                before, selectedPlace = place))
            assertNull(coordinator.reviewBatch(archive, hashes, before,
                selectedPlace = "/foreign/principal"))
            assertEquals(0, admitted)
            val review = requireNotNull(coordinator.reviewBatch(archive, hashes, before,
                selectedPlace = place))
            assertEquals(2, review.count)
            assertFalse(review.serverDefault)
            assertEquals(SnapshotRemoteDefinitionRestore.Outcome.STALE,
                coordinator.restoreBatch(review, confirmed = false).stop)
            assertEquals(0, admitted)
            try {
                val result = coordinator.restoreBatch(review, confirmed = true)
                assertTrue("Halted at ${result.stoppedAtInfoHash}: ${result.stop}", result.complete)
                assertEquals(2, result.verified)
                assertEquals(1, admitted)
                assertEquals(1, settled)
                val current = independent.listCompleteSessionTransfers()
                for (original in originals) {
                    val row = current.single { it.hash.equals(original.infoHashV1, true) }
                    assertEquals(original.name, row.name)
                    assertEquals(place, row.directory)
                    assertFalse(row.started)
                    assertEquals(0L, row.completedBytes)
                    assertNull(other.lookupName(original.infoHashV1))
                }
                // rTorrent returns uppercase hex even when the archived metainfo
                // preview is lowercase. Compare identities, not string casing.
                assertEquals(before.map { it.lowercase() }.toSet(),
                    current.map { it.hash.lowercase() }
                        .filterNot { it in hashes.map(String::lowercase) }.toSet())
                assertEquals(otherBefore, other.listCompleteSessionTransfers().map { it.hash }.toSet())
                val report = requireNotNull(coordinator.inspectPreviousBatch(archive,
                    current.map { it.hash }, selectedPlace = place))
                assertEquals(2, report.verifiedCount)
                assertEquals(0, report.unresolvedCount)
                assertEquals(1, admitted)
                assertEquals(SnapshotRemoteDefinitionRestore.Outcome.STALE,
                    coordinator.restoreBatch(review, confirmed = true).stop)
            } finally {
                // Remove only these synthetic session registrations, never payload bytes.
                for (original in originals) {
                    val row = independent.listCompleteSessionTransfers().firstOrNull {
                        it.hash.equals(original.infoHashV1, true)
                    } ?: continue
                    val removed = independent.eraseFromSession(
                        original.infoHashV1, row.name, row.directory)
                    assertTrue(removed is SessionEraseOutcome.VerifiedRemoved ||
                        removed is SessionEraseOutcome.PendingRemoval)
                    for (attempt in 0 until 10) {
                        if (independent.lookupName(original.infoHashV1) == null) break
                        delay(300)
                    }
                    assertNull(independent.lookupName(original.infoHashV1))
                }
            }
        }
    }

    @Test fun pendingJournalForLastItemBlocksEveryEarlierAddBeforeMutationLease() {
        assumeTrue(InstrumentationRegistry.getArguments()
            .getString("chunks_combined_disposable") == "authorized")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = RTorrentRemote(RemoteCredentials(
            "http://127.0.0.1:18080/rutorrent/plugins/httprpc/action.php", "chunks", "chunks-one"))
        val independent = RTorrentRemote(RemoteCredentials(
            "http://127.0.0.1:18080/RPC2", "chunks", "chunks-one"))
        val other = RTorrentRemote(RemoteCredentials("http://127.0.0.1:18082/RPC2", "", ""))
        val tracker = "http://127.0.0.1:1/announce"
        fun definition(name: String): ByteArray =
            ("d8:announce${tracker.length}:$tracker" +
                "4:infod6:lengthi5e4:name${name.length}:$name" +
                "12:piece lengthi16384e6:pieces20:").toByteArray() +
                MessageDigest.getInstance("SHA-1").digest(name.toByteArray()) +
                "ee".toByteArray()
        val contents = listOf(
            definition("preflight-first.bin"),
            definition("preflight-second.bin"))
        val originals = contents.map { TorrentMetainfoReader.preview(it) }
        val hashes = originals.map { it.infoHashV1 }
        val archive = SnapshotManifestV1.decode(SnapshotManifestV1.encode(
            SnapshotManifestV1.Manifest("chunks-android", "2026-11-23T00:00:00Z",
                SnapshotManifestV1.Source.LOCAL, originals.map {
                    SnapshotManifestV1.Item(it.infoHashV1, it.name,
                        SnapshotManifestV1.Recovery.METAINFO)
                }.sortedBy { it.infoHashV1 }),
            originals.zip(contents).associate { (item, bytes) -> item.infoHashV1 to bytes }))
        runBlocking {
            account.version()
            val before = independent.listCompleteSessionTransfers().map { it.hash.lowercase() }.toSet()
            val otherBefore = other.listCompleteSessionTransfers().map { it.hash.lowercase() }.toSet()
            assertTrue(hashes.none { it in before })
            val place = requireNotNull(independent.defaultDirectory()) + "/reviewed"
            val store = AndroidSnapshotRestoreJournalStore(context)
            var admitted = 0
            var settled = 0
            val coordinator = SnapshotRemoteDefinitionRestore(account, store,
                stillCurrent = { true }, admitWrite = { admitted++; true },
                settleWrite = { settled++ })
            val review = requireNotNull(coordinator.reviewBatch(
                archive, hashes, before.toList(), selectedPlace = place))
            val finalHash = review.originals.last().hash
            val itemArchiveKey = SnapshotRestoreOnceV1.itemArchiveFingerprint(
                SnapshotManifestV1.definitionArchiveFingerprint(archive), finalHash)
            val accountKey = SnapshotArchiveV1.digest(
                ("chunks-rtorrent-snapshot-account-v1:" + account.addAccountScope())
                    .toByteArray(Charsets.UTF_8))
            val selectedPlaceKey = SnapshotArchiveV1.digest(
                ("chunks-rtorrent-snapshot-selected-v1:" + place).toByteArray(Charsets.UTF_8))
            // Simulate a prior unknown non-idempotent Add on the LAST item.
            // This durable state grants no right to replay it, even when the
            // complete live server inventory does not yet contain its hash.
            val pending = SnapshotRestoreJournalV1.open(
                store, itemArchiveKey, accountKey, listOf(finalHash))
            pending.prepare(finalHash, selectedPlaceKey)
            pending.beforeAdd(finalHash, selectedPlaceKey)
            assertEquals(SnapshotRestoreJournalV1.Phase.IN_FLIGHT,
                pending.entries().single().phase)
            val result = coordinator.restoreBatch(review, confirmed = true)
            assertEquals(SnapshotRemoteDefinitionRestore.Outcome.BLOCKED, result.stop)
            assertEquals(0, result.verified)
            assertEquals(finalHash, result.stoppedAtInfoHash)
            assertEquals(0, admitted)
            assertEquals(0, settled)
            assertEquals(before, independent.listCompleteSessionTransfers()
                .map { it.hash.lowercase() }.toSet())
            assertEquals(otherBefore, other.listCompleteSessionTransfers()
                .map { it.hash.lowercase() }.toSet())
            assertTrue(hashes.none { independent.lookupName(it) != null })
            assertTrue(hashes.none { other.lookupName(it) != null })
        }
    }
}
