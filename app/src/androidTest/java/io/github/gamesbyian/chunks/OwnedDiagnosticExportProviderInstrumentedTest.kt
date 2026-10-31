package io.github.gamesbyian.chunks

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.exports.OwnedDiagnosticExportProvider
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android ContentResolver against an app-owned, non-exported provider. */
@RunWith(AndroidJUnit4::class)
class OwnedDiagnosticExportProviderInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val root get() = OwnedDiagnosticExportProvider.folder(context)

    @Before fun setup() {
        // Never recursively delete a previously unknown path, even in tests.
        root.mkdirs()
    }

    @Test fun positiveNewOwnedSnapshotAndReportCanBeSharedReadOnly() {
        val source = "snapshot data: " + "hash".repeat(3000)
        val bytes = source.toByteArray()
        val snapshot = OwnedDiagnosticExportProvider.publish(
            context, "chunks-inventory.chunksnap", bytes)
        val report = OwnedDiagnosticExportProvider.publish(
            context, "chunks-health.json", "{\"ready\":true}".toByteArray())
        assertEquals("application/octet-stream", context.contentResolver.getType(snapshot))
        assertEquals("application/json", context.contentResolver.getType(report))
        context.contentResolver.openInputStream(snapshot).use {
            assertArrayEquals(bytes, requireNotNull(it).readBytes())
        }
        context.contentResolver.openInputStream(report).use {
            assertArrayEquals("{\"ready\":true}".toByteArray(), requireNotNull(it).readBytes())
        }
        try {
            context.contentResolver.openOutputStream(snapshot, "w")
            throw AssertionError("Owned shares must not be writable")
        } catch (_: Exception) { }
    }

    @Test fun repeatedNameAndForeignExistingBytesDoNotReplace() {
        val baseName = "health-" + UUID.randomUUID() + ".json"
        val first = OwnedDiagnosticExportProvider.publish(context, baseName, byteArrayOf(1, 2, 3))
        val second = OwnedDiagnosticExportProvider.publish(context, baseName, byteArrayOf(4, 5))
        assertNotEquals(first, second)
        assertArrayEquals(byteArrayOf(1, 2, 3),
            requireNotNull(context.contentResolver.openInputStream(first)).use { it.readBytes() })
        assertArrayEquals(byteArrayOf(4, 5),
            requireNotNull(context.contentResolver.openInputStream(second)).use { it.readBytes() })

        val foreign = File(root, "foreign-" + UUID.randomUUID() + ".json")
        val original = "unrelated bytes must remain".toByteArray()
        foreign.writeBytes(original)
        val share = OwnedDiagnosticExportProvider.publish(context, foreign.name, byteArrayOf(9))
        assertArrayEquals(original, foreign.readBytes())
        assertNotEquals(foreign.name, share.lastPathSegment)
    }

    @Test fun symlinkedForeignExportIsNotReadThroughProvider() {
        val foreign = File(context.cacheDir, "foreign-" + UUID.randomUUID()).apply {
            writeText("private foreign contents")
        }
        val link = File(root, "export-" + UUID.randomUUID() + ".json")
        Files.createSymbolicLink(link.toPath(), foreign.toPath())
        try {
            val uri = Uri.Builder().scheme("content")
                .authority(OwnedDiagnosticExportProvider.authority(context))
                .appendPath(link.name).build()
            try {
                context.contentResolver.openInputStream(uri)
                throw AssertionError("Unowned symlink was served")
            } catch (_: FileNotFoundException) { }
            assertEquals("private foreign contents", foreign.readText())
        } finally {
            Files.deleteIfExists(link.toPath())
            Files.deleteIfExists(foreign.toPath())
        }
    }
}
