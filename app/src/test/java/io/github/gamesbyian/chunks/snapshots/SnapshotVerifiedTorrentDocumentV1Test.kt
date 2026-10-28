package io.github.gamesbyian.chunks.snapshots

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class SnapshotVerifiedTorrentDocumentV1Test {
    private val info = ("d6:lengthi5e4:name8:test.txt12:piece lengthi16384e" +
        "6:pieces20:01234567890123456789e").toByteArray()
    private val torrent = ("d4:info" + info.toString(Charsets.UTF_8) + "e").toByteArray()
    private val hash = MessageDigest.getInstance("SHA-1").digest(info)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val inventoryHash = "b".repeat(40)

    private fun verified() = SnapshotManifestV1.decode(SnapshotManifestV1.encode(
        SnapshotManifestV1.Manifest(
            "chunks-android", "2026-10-27T19:00:00Z",
            SnapshotManifestV1.Source.LOCAL, listOf(
                SnapshotManifestV1.Item(hash, "test.txt",
                    SnapshotManifestV1.Recovery.METAINFO),
                SnapshotManifestV1.Item(inventoryHash, "historical-title-private",
                    SnapshotManifestV1.Recovery.INVENTORY)
            )
        ), mapOf(hash to torrent)
    ))

    @Test fun exportsOriginalExactVerifiedBytesWithoutLeakingTitleInFilename() {
        val source = verified()
        val prepared = SnapshotVerifiedTorrentDocumentV1.prepare(source, hash)
        assertArrayEquals(torrent, prepared.bytes)
        assertEquals(hash, prepared.infoHashV1)
        assertEquals("test.txt", prepared.originalName)
        assertEquals("chunks-" + hash.take(12) + ".torrent", prepared.suggestedFilename)
        prepared.bytes[0] = 0
        assertArrayEquals(torrent, source.metainfoByHash.getValue(hash))
    }

    @Test fun inventoryOnlyAndUnknownOrNoncanonicalIdentifiersAreRefused() {
        val source = verified()
        reject { SnapshotVerifiedTorrentDocumentV1.prepare(source, inventoryHash) }
        reject { SnapshotVerifiedTorrentDocumentV1.prepare(source, "a".repeat(40)) }
        reject { SnapshotVerifiedTorrentDocumentV1.prepare(source, hash.uppercase()) }
    }

    @Test fun evenAHandConstructedUnverifiedWrapperCannotExportWrongTorrent() {
        val source = verified()
        val forged = source.copy(metainfoByHash = mapOf(hash to "bad-torrent".toByteArray()))
        reject { SnapshotVerifiedTorrentDocumentV1.prepare(forged, hash) }
        val empty = source.copy(metainfoByHash = mapOf(hash to ByteArray(0)))
        reject { SnapshotVerifiedTorrentDocumentV1.prepare(empty, hash) }
        val huge = source.copy(metainfoByHash = mapOf(hash to ByteArray(2 * 1024 * 1024 + 1)))
        reject { SnapshotVerifiedTorrentDocumentV1.prepare(huge, hash) }
    }

    private fun reject(action: () -> Unit) {
        try {
            action()
            fail("Unsafe or unrecoverable original torrent was accepted")
        } catch (_: IllegalArgumentException) { }
    }
}
