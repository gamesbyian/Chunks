package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.TorrentInfo

class LocalResumeMetainfoSourceV1Test {
    private val info = ("d6:lengthi5e4:name8:test.txt12:piece lengthi16384e" +
        "6:pieces20:01234567890123456789e").toByteArray()
    private val torrent = ("d4:info" + String(info) + "e").toByteArray()
    private val hash = MessageDigest.getInstance("SHA-1").digest(info)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val date = Instant.parse("2026-10-27T21:00:00Z")

    private fun status(
        identity: String = hash,
        title: String = "test.txt"
    ) = LocalTransferStatus(
        infoHash = identity, name = title, state = LocalTransferState.DOWNLOADING,
        stopped = true, progressPpm = 0, wantedBytes = 5, doneBytes = 0,
        payloadDownloadedBytes = 0, payloadUploadedBytes = 0, peers = 0,
        savePath = "/nonexistent-path-that-is-never-read", error = null
    )

    private fun fresh(): File = Files.createTempDirectory("chunks-v1-source-").toFile()
    private fun resume(): ByteArray = AddTorrentParams().apply {
        setTorrentInfo(TorrentInfo.bdecode(torrent))
        setSavePath("/nonexistent")
    }.let(AddTorrentParams::writeResumeDataBuf)

    @Test fun exactMetadataIsRecoveredAndSurvivesStrictArchiveVerifierWithoutAnyPayload() {
        val root = fresh()
        try {
            File(root, "$hash.resume").writeBytes(resume())
            val extracted = LocalResumeMetainfoSourceV1.capture(root,
                listOf(status()), date)
            assertEquals(1, extracted.verifiedRestorableCount)
            assertEquals(0, extracted.inventoryOnlyCount)
            assertEquals(0, extracted.omittedCount)
            assertTrue(extracted.unavailableByHash.isEmpty())
            val verified = SnapshotManifestV1.decode(extracted.toArchive())
            assertEquals(SnapshotManifestV1.Source.LOCAL, verified.manifest.source)
            assertEquals(SnapshotManifestV1.Recovery.METAINFO,
                verified.manifest.items.single().recovery)
            assertEquals(hash, verified.manifest.items.single().infoHashV1)
            assertTrue(verified.requiresSensitiveExportWarning)
            assertEquals("test.txt", verified.manifest.items.single().originalName)
            assertTrue(verified.metainfoByHash.getValue(hash).isNotEmpty())
            assertFalse(root.walkTopDown().any {
                it.isFile && it.name.endsWith(".torrent")
            })
        } finally { root.deleteRecursively() }
    }

    @Test fun missingCorruptAndRenamedResumeRowsStayInventoryOnly() {
        val root = fresh()
        try {
            val missing = LocalResumeMetainfoSourceV1.capture(root, listOf(status()), date)
            assertEquals(0, missing.verifiedRestorableCount)
            assertEquals(LocalResumeMetainfoSourceV1.Unavailable.MISSING_OR_UNSAFE_RECORD,
                missing.unavailableByHash[hash])
            File(root, "$hash.resume").writeText("not bencoded")
            val bad = LocalResumeMetainfoSourceV1.capture(root, listOf(status()), date)
            assertEquals(0, bad.verifiedRestorableCount)
            assertEquals(LocalResumeMetainfoSourceV1.Unavailable.INVALID_RESUME,
                bad.unavailableByHash[hash])
            File(root, "$hash.resume").writeBytes(resume())
            val renamed = LocalResumeMetainfoSourceV1.capture(root,
                listOf(status(title = "Someone renamed the label")), date)
            assertEquals(0, renamed.verifiedRestorableCount)
            assertEquals(1, renamed.inventoryOnlyCount)
            assertTrue(renamed.unavailableByHash.containsKey(hash))
            assertEquals(SnapshotManifestV1.Recovery.INVENTORY,
                SnapshotManifestV1.decode(renamed.toArchive()).manifest.items.single().recovery)
        } finally { root.deleteRecursively() }
    }

    @Test fun forgedNamedResumeAndSymlinkAreNeverTrusted() {
        val root = fresh()
        val outside = fresh()
        try {
            File(root, "ffffffffffffffffffffffffffffffffffffffff.resume").writeBytes(resume())
            val forged = LocalResumeMetainfoSourceV1.capture(root,
                listOf(status(identity = "f".repeat(40))), date)
            assertEquals(0, forged.verifiedRestorableCount)
            File(outside, "$hash.resume").writeBytes(resume())
            Files.createSymbolicLink(File(root, "$hash.resume").toPath(),
                File(outside, "$hash.resume").toPath())
            val linked = LocalResumeMetainfoSourceV1.capture(root, listOf(status()), date)
            assertEquals(LocalResumeMetainfoSourceV1.Unavailable.MISSING_OR_UNSAFE_RECORD,
                linked.unavailableByHash[hash])
            assertEquals(0, linked.verifiedRestorableCount)
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }

    @Test fun unsupportedIdentityNeverGetsForgedAsV1Torrent() {
        val root = fresh()
        try {
            val captured = LocalResumeMetainfoSourceV1.capture(root,
                listOf(status(identity = "b".repeat(64))), date)
            assertEquals(0, captured.manifest.items.size)
            assertEquals(1, captured.omittedCount)
            assertEquals(0, captured.verifiedRestorableCount)
        } finally { root.deleteRecursively() }
    }
}
