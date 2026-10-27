package io.github.gamesbyian.chunks.snapshots

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class SnapshotManifestV1Test {
    private val info = ("d6:lengthi5e4:name8:test.txt12:piece lengthi16384e" +
        "6:pieces20:01234567890123456789e").toByteArray()
    private val torrent = ("d4:info" + info.toString(Charsets.UTF_8) + "e").toByteArray()
    private val hash = MessageDigest.getInstance("SHA-1").digest(info)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun item(
        recovery: SnapshotManifestV1.Recovery = SnapshotManifestV1.Recovery.METAINFO,
        name: String = "test.txt",
        magnet: String? = null,
        directory: String? = null
    ) = SnapshotManifestV1.Item(hash, name, recovery, magnet, directory)

    private fun manifest(vararg items: SnapshotManifestV1.Item) = SnapshotManifestV1.Manifest(
        producer = "chunks-android",
        createdAtUtc = "2026-10-27T18:00:00Z",
        source = SnapshotManifestV1.Source.RTORRENT,
        items = items.toList()
    )

    @Test fun exactMetainfoRoundTripIsDeterministicAndMarkedPotentiallySensitive() {
        val m = manifest(item(directory = "/old/server/Downloads"))
        val archive = SnapshotManifestV1.encode(m, mapOf(hash to torrent))
        assertArrayEquals(archive, SnapshotManifestV1.encode(m, mapOf(hash to torrent)))
        val verified = SnapshotManifestV1.decode(archive)
        assertEquals(m, verified.manifest)
        assertEquals(1, verified.restorableCount)
        assertEquals(0, verified.inventoryOnlyCount)
        assertTrue(verified.requiresSensitiveExportWarning)
        assertArrayEquals(torrent, verified.metainfoByHash.getValue(hash))
        verified.metainfoByHash.getValue(hash)[0] = 0
        assertArrayEquals(torrent, SnapshotManifestV1.decode(archive).metainfoByHash.getValue(hash))
    }

    @Test fun inventoryOnlyCanBeArchivedWithoutPretendingItIsRestorable() {
        val archive = SnapshotManifestV1.encode(
            manifest(item(SnapshotManifestV1.Recovery.INVENTORY)), emptyMap())
        val verified = SnapshotManifestV1.decode(archive)
        assertEquals(0, verified.restorableCount)
        assertEquals(1, verified.inventoryOnlyCount)
        assertFalse(verified.requiresSensitiveExportWarning)
    }

    @Test fun fallbackRequiresMatchingRealMagnetIdentity() {
        val fallback = item(
            SnapshotManifestV1.Recovery.MAGNET,
            magnet = "magnet:?xt=urn:btih:" + hash + "&dn=test.txt")
        val snapshot = SnapshotManifestV1.decode(
            SnapshotManifestV1.encode(manifest(fallback), emptyMap()))
        assertEquals(1, snapshot.restorableCount)
        assertTrue(snapshot.requiresSensitiveExportWarning)
        for (bad in listOf(
            "magnet:?xt=urn:btih:" + "f".repeat(40),
            "magnet:?dn=only-a-name",
            "magnet:?xt=urn:btih:" + hash + "&xt=urn:btih:" + hash
        )) {
            rejected { SnapshotManifestV1.encode(manifest(
                item(SnapshotManifestV1.Recovery.MAGNET, magnet = bad)), emptyMap()) }
        }
    }

    @Test fun rejectTransportValidArchiveWhenInfohashNameOrMembershipLies() {
        val falseHash = "f".repeat(40)
        val mismatchHash = manifest(item().copy(infoHashV1 = falseHash))
        rejected { SnapshotManifestV1.encode(mismatchHash, mapOf(falseHash to torrent)) }
        rejected { SnapshotManifestV1.encode(manifest(item(name = "bait.txt")),
            mapOf(hash to torrent)) }
        rejected { SnapshotManifestV1.encode(manifest(item()), emptyMap()) }
        rejected { SnapshotManifestV1.encode(manifest(
            item(SnapshotManifestV1.Recovery.INVENTORY)), mapOf(hash to torrent)) }
        rejected { SnapshotManifestV1.encode(manifest(item(), item()), mapOf(hash to torrent)) }
        val forged = SnapshotArchiveV1.encode(
            validJsonForOneItem(), mapOf(falseHash to torrent))
        rejected { SnapshotManifestV1.decode(forged) }
    }

    @Test fun rejectUnknownVersionUnknownPropertiesDuplicateKeysAndMalformedManifest() {
        val valid = validJsonForOneItem()
        val examples = listOf(
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            valid.replace("\"items\":[", "\"password\":\"secret\",\"items\":["),
            valid.replace("\"recovery\":\"complete-metainfo\"",
                "\"recovery\":\"unverified\""),
            valid.replace("\"originalName\":\"test.txt\"",
                "\"originalName\":\"test.txt\",\"originalName\":\"test.txt\""),
            valid + "trailing",
            valid.replace("\"producer\":\"chunks-android\"",
                "\"producer\":\"another-producer\""),
            valid.replace("\"createdAtUtc\":\"2026-10-27T18:00:00Z\"",
                "\"createdAtUtc\":\"not-a-date\""),
            valid.replace("\"originalName\":\"test.txt\"",
                "\"originalName\":null"),
            valid.replace("\"originalName\":\"test.txt\"",
                "\"originalName\":\"\\uD800\"")
        )
        examples.forEach { json ->
            rejected { SnapshotManifestV1.decode(SnapshotArchiveV1.encode(
                json.toByteArray(), mapOf(hash to torrent))) }
        }
    }

    @Test fun unicodeAndEscapedNamesRoundTripButUnsafeHintsAreRefused() {
        // Inventory labels may be generated from remote listings and need not
        // correspond to a local v1 .torrent name.
        val unusual = "Movie 😀 \"quoted\""
        val archive = SnapshotManifestV1.encode(
            manifest(item(SnapshotManifestV1.Recovery.INVENTORY, name = unusual)),
            emptyMap())
        assertEquals(unusual, SnapshotManifestV1.decode(archive).manifest.items.single().originalName)
        for (hint in listOf("first\nsecond", "bad\u0000hint")) {
            rejected { SnapshotManifestV1.encode(manifest(item(
                SnapshotManifestV1.Recovery.INVENTORY, directory = hint)), emptyMap()) }
        }
        rejected { SnapshotManifestV1.encode(manifest(item(
            SnapshotManifestV1.Recovery.INVENTORY, name = "..")), emptyMap()) }
    }

    @Test fun refuseUnsupportedV2MetainfoInsteadOfMisreportingItAsVerifiedV1() {
        val hybrid = ("d4:info" + info.toString(Charsets.UTF_8)
            .replace("4:name", "12:meta versioni2e4:name") + "e").toByteArray()
        val hybridInfo = ("d6:lengthi5e12:meta versioni2e4:name8:test.txt" +
            "12:piece lengthi16384e6:pieces20:01234567890123456789e").toByteArray()
        val hybridHash = MessageDigest.getInstance("SHA-1").digest(hybridInfo)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        rejected {
            SnapshotManifestV1.encode(
                manifest(item().copy(infoHashV1 = hybridHash)),
                mapOf(hybridHash to hybrid))
        }
    }

    private fun validJsonForOneItem(): String =
        """{"schemaVersion":1,"producer":"chunks-android","createdAtUtc":"2026-10-27T18:00:00Z","source":"rtorrent","items":[{"infoHashV1":"$hash","originalName":"test.txt","recovery":"complete-metainfo"}]}"""

    private fun rejected(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid snapshot to be refused without side effects")
        } catch (_: IllegalArgumentException) {
            // Trust check failed before import.
        }
    }
}
