package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SnapshotRemoteMetainfoCaptureV1Test {
    private val info = "d6:lengthi5e4:name8:test.txt12:piece lengthi16384e6:pieces20:" +
        "01234567890123456789e"
    private val original = ("d8:announce14:http://ignored4:info" + info + "e")
        .toByteArray(Charsets.UTF_8)
    private val hash = TorrentMetainfoReader.preview(original).infoHashV1
    private val missingHash = "e".repeat(40)

    private fun row(id: String, name: String) = RemoteTransfer(
        hash = id, name = name, sizeBytes = 5L, completedBytes = 0L,
        started = false, directory = "/private/old")
    private fun inventory() = SnapshotInventoryCaptureV1.remote(
        listOf(row(hash, "test.txt"), row(missingHash, "missing")),
        Instant.parse("2026-11-17T12:00:00Z"))

    @Test fun recoversOnlyExactHashAndNameFromOptionalSourceWithoutPayloadOrHistoricalPlace() = runBlocking {
        val calls = mutableListOf<String>()
        val result = SnapshotRemoteMetainfoCaptureV1.collect(inventory(), { id ->
            calls += id
            if (id == hash) original else null
        })
        assertEquals(listOf(hash, missingHash), calls)
        assertEquals(1, result.recovered)
        assertEquals(1, result.unavailable)
        assertEquals(0, result.sizeLimited)
        assertEquals(2, result.inspected)
        val verified = SnapshotManifestV1.decode(result.archive)
        assertEquals(1, verified.restorableCount)
        assertEquals(1, verified.inventoryOnlyCount)
        assertArrayEquals(original, verified.metainfoByHash.getValue(hash))
        assertEquals(SnapshotManifestV1.Recovery.METAINFO,
            verified.manifest.items.first().recovery)
        assertEquals(SnapshotManifestV1.Recovery.INVENTORY,
            verified.manifest.items.last().recovery)
        assertTrue(verified.requiresSensitiveExportWarning)
        assertTrue(verified.manifest.items.all { it.sourceDirectoryHint == null })
    }

    @Test fun rejectsWrongHashWrongNameAndCorruptResponseWithoutInventingSource() = runBlocking {
        val changedName = original.toString(Charsets.UTF_8).replace("8:test.txt", "8:other.tx")
            .toByteArray(Charsets.UTF_8)
        val candidates = listOf(ByteArray(0), changedName, original)
        for (candidate in candidates) {
            val inventory = SnapshotInventoryCaptureV1.remote(
                listOf(row(missingHash, "test.txt")), Instant.parse("2026-11-17T12:00:00Z"))
            val result = SnapshotRemoteMetainfoCaptureV1.collect(inventory, { candidate })
            assertEquals(0, result.recovered)
            assertEquals(1, result.unavailable)
            assertEquals(1, SnapshotManifestV1.decode(result.archive).inventoryOnlyCount)
        }
        val result = SnapshotRemoteMetainfoCaptureV1.collect(
            SnapshotInventoryCaptureV1.remote(listOf(row(hash, "Renamed in rTorrent")),
                Instant.parse("2026-11-17T12:00:00Z")), { original })
        assertEquals(0, result.recovered)
    }

    @Test fun oversizedValidRemoteOriginalRemainsInventoryOnlyWithoutKillingOtherExports() = runBlocking {
        // The remote source may return a perfectly valid, very large
        // non-info dictionary field. Its infohash stays correct, but the
        // transport cannot embed an original larger than 2 MiB.
        val comment = "x".repeat(SnapshotArchiveV1.MAX_TORRENT_BYTES)
        val oversized = ("d8:announce14:http://ignored7:comment" +
            "${comment.length}:" + comment + "4:info" + info + "e")
            .toByteArray(Charsets.UTF_8)
        val secondInfo = info.replace("8:test.txt", "8:work.txt")
        val second = ("d8:announce14:http://ignored4:info" + secondInfo + "e")
            .toByteArray(Charsets.UTF_8)
        val secondHash = TorrentMetainfoReader.preview(second).infoHashV1
        assertNotEquals(hash, secondHash)
        assertTrue(oversized.size > SnapshotArchiveV1.MAX_TORRENT_BYTES)
        val capture = SnapshotInventoryCaptureV1.remote(
            listOf(row(hash, "test.txt"), row(secondHash, "work.txt")),
            Instant.parse("2026-11-17T12:00:00Z")
        )
        val queries = mutableListOf<String>()
        val result = SnapshotRemoteMetainfoCaptureV1.collect(capture, { id ->
            queries += id
            if (id == hash) oversized else second
        })
        assertEquals(listOf(hash, secondHash), queries)
        assertEquals(1, result.sizeLimited)
        assertEquals(0, result.unavailable)
        assertEquals(1, result.recovered)
        assertEquals(2, result.inspected)
        val restored = SnapshotManifestV1.decode(result.archive)
        assertEquals(SnapshotManifestV1.Recovery.INVENTORY,
            restored.manifest.items.first().recovery)
        assertEquals(SnapshotManifestV1.Recovery.METAINFO,
            restored.manifest.items.last().recovery)
        assertFalse(restored.metainfoByHash.containsKey(hash))
        assertArrayEquals(second, restored.verifiedMetainfo(secondHash))
        assertTrue(restored.manifest.items.all { it.sourceDirectoryHint == null })
    }

    @Test fun revokesChangedInventoryBeforeCreatingAnyArchive() = runBlocking {
        var valid = true
        val inventory = inventory()
        try {
            SnapshotRemoteMetainfoCaptureV1.collect(inventory, {
                valid = false
                original
            }, stillCurrent = { valid })
            fail("A changed authenticated account or inventory cannot publish an archive")
        } catch (_: IllegalStateException) { }
    }

    @Test fun oneUnavailableSourceDoesNotBlockOtherIndependentlyVerifiedEntries() = runBlocking {
        val result = SnapshotRemoteMetainfoCaptureV1.collect(inventory(), {
            if (it == hash) original else throw IllegalStateException("No Source plugin")
        })
        assertEquals(1, result.recovered)
        assertEquals(1, result.unavailable)
    }
}
