package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SnapshotSettingsTest {
    private val bytes = ("d4:infod5:filesl" +
        "d6:lengthi2e4:pathl1:aee" +
        "d4:attr1:p6:lengthi1e4:pathl3:padee" +
        "d6:lengthi2e4:pathl1:bee" +
        "e4:name4:root12:piece lengthi16384e6:pieces20:01234567890123456789ee").toByteArray()
    private val preview = TorrentMetainfoReader.preview(bytes)
    private val hash = preview.infoHashV1
    private val definitions = mapOf(hash to bytes)
    private val manifest = SnapshotManifestV1.Manifest(
        "chunks-android", "2026-11-23T00:00:00Z", SnapshotManifestV1.Source.LOCAL,
        listOf(SnapshotManifestV1.Item(hash, "root", SnapshotManifestV1.Recovery.METAINFO)))
    private val levels = listOf(SnapshotFilePriorities.Level.LOW, SnapshotFilePriorities.Level.SKIP)
    private val localRows = listOf(row(0, "root/a", 1), row(2, "root/b", 0))

    private fun row(index: Int, path: String, priority: Int, size: Long = 2) =
        SnapshotFilePriorities.Observation(SnapshotFilePriorities.File(index, path, size), priority)

    @Test fun portableSettingsKeepExactNonPaddingIndicesAndLeavePresentJobsUnselected() = runBlocking {
        val enriched = SnapshotSettingsCapture({ "Example alias" }, { localRows })
            .collect(manifest, definitions)
        val bytes = SnapshotManifestV1.encode(enriched, definitions)
        val verified = SnapshotManifestV1.decode(bytes)
        assertEquals(enriched, verified.manifest)
        assertArrayEquals(bytes, SnapshotManifestV1.encode(verified.manifest, verified.metainfoByHash))
        val comparison = SnapshotRestorePreviewV1.plan(verified, listOf(hash.uppercase()), true)
        val entry = comparison.entries.single()
        assertTrue(comparison.containsSettings)
        assertFalse(entry.selectedForReview)
        assertEquals("Example alias", entry.displayAlias)
        assertEquals(listOf(0, 2), entry.fileMappings.map { it.file.index })
        assertEquals(listOf("root/a", "root/b"), entry.fileMappings.map { it.file.path })
        assertEquals(levels, entry.fileMappings.map { it.level })
        assertEquals(0, comparison.mutationsExecuted)
    }

    @Test fun omissionsPreserveTheDefinitionAndNeverGuessDefaultsOrCoerceNativeValues() = runBlocking {
        for (rows in listOf(
            null, localRows.dropLast(1), localRows.reversed(),
            listOf(row(0, "root/a", 2), localRows.last()),
            listOf(row(0, "root/a", 1, size = 3), localRows.last()),
            listOf(row(0, "root/renamed", 1), localRows.last())
        )) {
            val enriched = SnapshotSettingsCapture({ null }, { rows }).collect(manifest, definitions)
            assertEquals(manifest, enriched)
            val archive = SnapshotManifestV1.encode(enriched, definitions)
            assertEquals(1, SnapshotManifestV1.decode(archive).restorableCount)
        }
        val remote = SnapshotManifestV1.Source.RTORRENT
        val relativeRows = listOf(row(0, "a", 2), row(2, "b", 0))
        assertEquals(listOf(SnapshotFilePriorities.Level.HIGH, SnapshotFilePriorities.Level.SKIP),
            SnapshotFilePriorities.capture(preview, relativeRows, remote))
        assertNull(SnapshotFilePriorities.capture(preview,
            listOf(relativeRows.first(), row(2, "root/b", 0)), remote))
        // Refuse unrelated root components even when every filename and size
        // under that component happens to match the connected server.
        val foreignRoot = preview.copy(files = preview.files.map {
            it.copy(path = it.path.replace("root/", "foreign/"))
        })
        assertNull(SnapshotFilePriorities.capture(foreignRoot, relativeRows, remote))
        assertEquals("a", SnapshotFilePriorities.relativeToTorrentRoot(
            SnapshotFilePriorities.File(0, "root/a", 2), "root"))
        assertNull(SnapshotFilePriorities.relativeToTorrentRoot(
            SnapshotFilePriorities.File(0, "foreign/a", 2), "root"))
    }

    @Test fun rejectsUnknownUnsafeOrUnboundSettingsEvenWithValidArchiveChecksums() {
        val baseline = SnapshotArchiveV1.decode(SnapshotManifestV1.encode(manifest, definitions))
            .manifestJson.toString(Charsets.UTF_8)
        fun withSettings(properties: String, version: Int = 2) =
            baseline.replace("\"schemaVersion\":1", "\"schemaVersion\":$version")
                .replace("\"complete-metainfo\"}", "\"complete-metainfo\",\"settings\":$properties}")
        val invalid = listOf(
            withSettings("""{"filePriorities":"ls"}""", 1),
            withSettings("""{"filePriorities":"ls"}""", 3),
            withSettings("""{"filePriorities":"ls"}""").replace("\"local\"", "\"rtorrent\""),
            withSettings("""{"filePriorities":"lss"}"""),
            withSettings("""{"filePriorities":"xx"}"""),
            withSettings("""{"filePriorities":2}"""),
            withSettings("""{"filePriorities":null}"""),
            withSettings("""{"filePriorities":"ls","filePriorities":"hs"}"""),
            withSettings("""{"downloadPath":"/old/root"}"""),
            withSettings("""{"displayAlias":" before "}"""),
            withSettings("""{"displayAlias":"hidden\u202E"}"""),
            withSettings("{}")
        )
        invalid.forEach { json ->
            assertThrows(IllegalArgumentException::class.java) {
                SnapshotManifestV1.decode(SnapshotArchiveV1.encode(json.toByteArray(), definitions))
            }
        }
        val inventory = manifest.items.single().copy(recovery = SnapshotManifestV1.Recovery.INVENTORY)
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotManifestV1.encode(manifest.copy(items = listOf(inventory.copy(
                settings = SnapshotManifestV1.Settings(filePriorities = levels)))), emptyMap())
        }
    }

    @Test fun capabilityFailureIsLocalToTheRecordButChangedSourceAbortsCapture() = runBlocking {
        val withAlias = SnapshotSettingsCapture({ "Alias" }, { error("No file access") })
            .collect(manifest, definitions)
        assertEquals(SnapshotManifestV1.Settings(displayAlias = "Alias"),
            withAlias.items.single().settings)
        var current = true
        try {
            SnapshotSettingsCapture({ "Alias" }, { localRows.also { current = false } })
                .collect(manifest, definitions, stillCurrent = { current })
            fail("A changed source must revoke the capture")
        } catch (_: IllegalStateException) {}
    }
}
