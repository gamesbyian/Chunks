package io.github.gamesbyian.chunks

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.create.CreatedTorrentProvider
import io.github.gamesbyian.chunks.debug.FixtureDocumentsProvider
import io.github.gamesbyian.chunks.exports.OwnedDiagnosticExportProvider
import io.github.gamesbyian.chunks.model.ZipPayloadStatus
import io.github.gamesbyian.chunks.snapshots.SnapshotDocumentWriterV1
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exact main-integration G0 guard, not a synthetic filename-only model:
 * exercise actual Android ContentResolver/provider file descriptors against
 * the merged ZIP inspector, both read-only share providers and legacy SAF
 * refusal in one session. Only test-minted fixture nodes are ever deleted.
 */
@RunWith(AndroidJUnit4::class)
class IntegratedOwnedStorageBoundariesInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    private fun zipBytes() = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("season/episode.txt"))
            zip.write("source bytes remain unchanged".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    @Test fun readOnlyZipAndBothPrivateShareProvidersPreserveTheirSources() {
        val tree = FixtureDocumentsProvider.treeUri(context)
        val root = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree))
        val source = zipBytes()
        val doc = requireNotNull(DocumentsContract.createDocument(
            resolver, root, "application/zip",
            "combined-storage-${UUID.randomUUID()}.zip"))
        try {
            requireNotNull(resolver.openOutputStream(doc, "w")).use { it.write(source) }
            val beforeStageNames = context.cacheDir.list().orEmpty()
                .filter { it.startsWith("chunks-zip-review-") }.toSet()
            val reviewed = LocalZipDocumentInspector.inspect(context, doc)
            assertEquals(ZipPayloadStatus.VERIFIED_READ_ONLY, reviewed.verification?.status)
            assertArrayEquals(source,
                requireNotNull(resolver.openInputStream(doc)).use { it.readBytes() })
            assertEquals(beforeStageNames, context.cacheDir.list().orEmpty()
                .filter { it.startsWith("chunks-zip-review-") }.toSet())

            val shareName = "combined-${UUID.randomUUID()}.torrent"
            val oldTorrent = byteArrayOf(11, 22, 33, 44)
            val newTorrent = byteArrayOf(55, 66)
            val first = CreatedTorrentProvider.publish(context, shareName, oldTorrent)
            val second = CreatedTorrentProvider.publish(context, shareName, newTorrent)
            assertNotEquals(first, second)
            assertArrayEquals(oldTorrent,
                requireNotNull(resolver.openInputStream(first)).use { it.readBytes() })
            assertArrayEquals(newTorrent,
                requireNotNull(resolver.openInputStream(second)).use { it.readBytes() })

            val report = "{\"status\":\"owned\"}".toByteArray()
            val reportUri = OwnedDiagnosticExportProvider.publish(
                context, "chunks-integrated.json", report)
            assertEquals("application/json", resolver.getType(reportUri))
            assertArrayEquals(report,
                requireNotNull(resolver.openInputStream(reportUri)).use { it.readBytes() })
            try {
                resolver.openOutputStream(reportUri, "w")
                throw AssertionError("Read-only owned report provider admitted a write")
            } catch (_: Exception) { }
            assertArrayEquals(oldTorrent,
                requireNotNull(resolver.openInputStream(first)).use { it.readBytes() })
            assertArrayEquals(source,
                requireNotNull(resolver.openInputStream(doc)).use { it.readBytes() })
        } finally {
            // Explicitly create and retire only this disposable DocumentsProvider
            // fixture. Never delete app-cache shares by a guessed filename.
            DocumentsContract.deleteDocument(resolver, doc)
        }
    }

    @Test fun existingProviderDocumentCannotBeTruncatedDeletedOrRenamedByLegacyPaths() {
        val tree = FixtureDocumentsProvider.treeUri(context)
        val root = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree))
        val document = requireNotNull(DocumentsContract.createDocument(
            resolver, root, "text/plain", "foreign-canary-${UUID.randomUUID()}.txt"))
        val original = "FOREIGN-BYTES-DO-NOT-TOUCH".toByteArray()
        try {
            requireNotNull(resolver.openOutputStream(document, "w")).use {
                it.write(original)
            }
            val legacy = SafZipExtractionTarget(resolver, tree)
            assertNull(legacy.createDirectory(legacy.root, "new-folder"))
            assertNull(legacy.createFile(legacy.root, "new-file.txt"))
            assertNull(legacy.rename(document, legacy.root, "renamed.txt"))
            assertFalse(legacy.delete(document))
            try {
                legacy.openNewOutput(document)
                throw AssertionError("SAF legacy adapter admitted an unproven output")
            } catch (_: IOException) { }
            try {
                SnapshotDocumentWriterV1.writeAndVerify(resolver, document,
                    "replacement bytes".toByteArray())
                throw AssertionError("Legacy picker-document writer admitted truncation")
            } catch (_: IOException) { }
            assertArrayEquals(original,
                requireNotNull(resolver.openInputStream(document)).use {
                    it.readBytes()
                })
            val refused = LocalZipExtraction.extract(context, document, tree, "foreign.zip",
                true) { false }
            assertEquals(io.github.gamesbyian.chunks.model.ZipExtractionStatus.BLOCKED,
                refused.status)
            assertArrayEquals(original,
                requireNotNull(resolver.openInputStream(document)).use {
                    it.readBytes()
                })
        } finally {
            DocumentsContract.deleteDocument(resolver, document)
        }
    }
}
