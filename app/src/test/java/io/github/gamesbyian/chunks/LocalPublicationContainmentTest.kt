package io.github.gamesbyian.chunks

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.model.ZipExtractionStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalPublicationContainmentTest {
    @get:Rule val temp = TemporaryFolder()

    private class ProbeContext(private val files: File) : ContextWrapper(
        ApplicationProvider.getApplicationContext<Context>()
    ) {
        var providerAccesses = 0
        override fun getFilesDir(): File = files
        override fun getContentResolver(): ContentResolver {
            providerAccesses++
            error("Publication containment must not access a document provider")
        }
    }

    @Test fun newExtractionAndCopyRefuseBeforeStagingOrProviderAccess() {
        val files = temp.newFolder("files")
        val context = ProbeContext(files)
        val source = Uri.parse("content://foreign/archive")
        val target = Uri.parse("content://foreign/tree")
        val original = temp.newFile("original").apply { writeText("foreign bytes") }
        val before = original.readBytes()
        var cancelledChecked = false
        val extract = LocalZipExtraction.extract(context, source, target, "archive.zip", true) {
            cancelledChecked = true
            false
        }
        val copy = LocalZipExtraction.copyFinished(context, original.parentFile!!, emptyList(),
            "library", target, true) { cancelledChecked = true; false }
        assertEquals(ZipExtractionStatus.BLOCKED, extract.status)
        assertEquals(ZipExtractionStatus.BLOCKED, copy.status)
        assertFalse(cancelledChecked)
        assertEquals(0, context.providerAccesses)
        assertArrayEquals(before, original.readBytes())
        assertTrue(files.listFiles().orEmpty().isEmpty())
    }

    @Test fun legacyIntentsRemainUnresolvedWithoutTouchingForeignFilesOrJournals() {
        val files = temp.newFolder("files")
        val zipId = "a".repeat(32)
        val copyId = "b".repeat(32)
        val zipDir = File(files, "zip-extractions").apply { mkdir() }
        val copyDir = File(files, "library-copies").apply { mkdir() }
        // These names simulate both a failed create collision and an unrelated
        // folder occupying a legacy PUBLISH_INTENT destination.
        val zipLog = File(zipDir, "$zipId.log").apply {
            writeText("BEGIN\t$zipId\tcontent%3A%2F%2Fforeign%2Ftree\t.chunks-partial-$zipId\tArchive\nFILE\tforeign.txt\nPUBLISH_INTENT\tArchive\n")
        }
        val copyLog = File(copyDir, "$copyId.log").apply {
            writeText("BEGIN\t$copyId\tcontent%3A%2F%2Fforeign%2Ftree\t.chunks-partial-$copyId\tLibrary\nDIR\tforeign\n")
        }
        val zipBefore = zipLog.readBytes()
        val copyBefore = copyLog.readBytes()
        val destination = temp.newFolder("destination")
        val foreignPartial = File(destination, ".chunks-partial-$zipId").apply { mkdir() }
        val foreign = File(foreignPartial, "foreign.txt").apply { writeText("preserve") }
        val foreignPublished = File(destination, "Archive").apply { mkdir() }
        val foreignDirectory = File(destination, ".chunks-partial-$copyId").apply { mkdir() }
        val context = ProbeContext(files)
        val results = LocalZipExtraction.reconcilePending(context)
        assertEquals(0, context.providerAccesses)
        assertEquals(2, results.size)
        assertTrue(results.all { it.result.status == ZipExtractionStatus.NEEDS_ATTENTION })
        assertEquals(setOf(false, true), results.map { it.copy }.toSet())
        assertEquals("preserve", foreign.readText())
        assertTrue(foreignPublished.isDirectory)
        assertTrue(foreignDirectory.isDirectory)
        assertArrayEquals(zipBefore, zipLog.readBytes())
        assertArrayEquals(copyBefore, copyLog.readBytes())
    }
}
