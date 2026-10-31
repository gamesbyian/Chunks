package io.github.gamesbyian.chunks.storage

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.debug.FixtureDocumentsProvider
import io.github.gamesbyian.chunks.model.ZipPayloadStatus
import io.github.gamesbyian.chunks.model.ZipPayloadVerifier
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Isolated actual Android kernel + DocumentsProvider proof. Test-owned files
 * only; no production transfer, external Downloads or preexisting cache names
 * are created, overwritten or deleted by AnonymousZipReviewStage.
 */
@RunWith(AndroidJUnit4::class)
class AnonymousZipReviewStageInstrumentedTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun zip(): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { stream ->
            stream.putNextEntry(ZipEntry("Season 1/episode.txt"))
            stream.write("original ZIP contents".toByteArray())
            stream.closeEntry()
        }
    }.toByteArray()

    private fun <T> withSource(content: ByteArray, block: (android.net.Uri) -> T): T {
        val resolver = ctx.contentResolver
        val tree = FixtureDocumentsProvider.treeUri(ctx)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree,
            DocumentsContract.getTreeDocumentId(tree))
        val source = requireNotNull(DocumentsContract.createDocument(resolver, root,
            "application/zip", "anonymous-stage-${UUID.randomUUID()}.zip"))
        try {
            resolver.openOutputStream(source, "w").use {
                requireNotNull(it).write(content)
            }
            return block(source)
        } finally {
            // Only this test's uniquely created fixture document is removed.
            DocumentsContract.deleteDocument(resolver, source)
        }
    }

    @Test fun zipVerifierCanReadAnonymousBytesWithoutAnyCacheDirectoryEntry() {
        val source = zip()
        withSource(source) { uri ->
            val before = ctx.cacheDir.list().orEmpty().toSet()
            val result = AnonymousZipReviewStage.stage(ctx.contentResolver, uri,
                ctx.cacheDir, source.size.toLong() + 1024)
            result.use { owned ->
                assertEquals(source.size.toLong(), owned.copiedBytes)
                ZipFile(owned.file).use { archive ->
                    val entry = requireNotNull(archive.getEntry("Season 1/episode.txt"))
                    assertArrayEquals("original ZIP contents".toByteArray(),
                        archive.getInputStream(entry).readBytes())
                }
                assertEquals(ZipPayloadStatus.VERIFIED_READ_ONLY,
                    ZipPayloadVerifier.verify(owned.file).status)
                assertEquals(before, ctx.cacheDir.list().orEmpty().toSet())
            }
            assertEquals(before, ctx.cacheDir.list().orEmpty().toSet())
        }
    }

    @Test fun cancelledOrOverBudgetInputCannotWriteOrDeleteForeignCanary() {
        val source = zip()
        val canary = File(ctx.cacheDir, "anonymous-stage-foreign-${UUID.randomUUID()}.zip")
        val original = "FOREIGN DO NOT MODIFY".toByteArray()
        canary.writeBytes(original)
        try {
            withSource(source) { uri ->
                try {
                    AnonymousZipReviewStage.stage(ctx.contentResolver, uri, ctx.cacheDir, 4)
                    throw AssertionError("Over-budget anonymous ZIP was accepted")
                } catch (_: IOException) { }
                try {
                    AnonymousZipReviewStage.stage(ctx.contentResolver, uri, ctx.cacheDir,
                        source.size.toLong(), cancelled = { true })
                    throw AssertionError("Cancelled ZIP was accepted")
                } catch (_: IOException) { }
            }
            assertArrayEquals(original, canary.readBytes())
        } finally {
            // Own disposable test canary; not a production staging path.
            assertTrue(canary.delete())
        }
    }

    @Test fun indirectCacheRootIsRefusedBeforeCreatingAnything() {
        val actual = File(ctx.cacheDir, "anonymous-stage-real-${UUID.randomUUID()}")
        val link = File(ctx.cacheDir, "anonymous-stage-link-${UUID.randomUUID()}")
        assertTrue(actual.mkdir())
        java.nio.file.Files.createSymbolicLink(link.toPath(), actual.toPath())
        try {
            withSource(zip()) { uri ->
                try {
                    AnonymousZipReviewStage.stage(ctx.contentResolver, uri, link, 10000)
                    throw AssertionError("A symbolic-link staging root was accepted")
                } catch (_: IOException) { }
            }
            assertTrue(actual.list().orEmpty().isEmpty())
            assertTrue(java.nio.file.Files.isSymbolicLink(link.toPath()))
        } finally {
            java.nio.file.Files.deleteIfExists(link.toPath())
            assertTrue(actual.delete())
        }
    }
}
