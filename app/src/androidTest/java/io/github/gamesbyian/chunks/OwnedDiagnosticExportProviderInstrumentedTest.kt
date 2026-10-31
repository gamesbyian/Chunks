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

    @Test fun maximumAcceptedNameAlwaysReturnsReadableUniqueContentUri() {
        // Before the bounded-name fix a valid 160-character stem + UUID made
        // a file which the same provider refused to reopen.
        for (extension in listOf("json", "chunksnap", "torrent", "txt")) {
            val name = "A" + "b".repeat(159) + "." + extension
            val firstBytes = byteArrayOf(3, 8, 13)
            val secondBytes = byteArrayOf(21, 34)
            val first = OwnedDiagnosticExportProvider.publish(context, name, firstBytes)
            val second = OwnedDiagnosticExportProvider.publish(context, name, secondBytes)
            assertNotEquals(first, second)
            for ((uri, expected) in listOf(first to firstBytes, second to secondBytes)) {
                val filename = requireNotNull(uri.lastPathSegment)
                assertTrue("Generated filename exceeds provider acceptance", filename.length <= 160 + 1 + extension.length)
                assertTrue("A unique suffix must fit before extension",
                    Regex("[A-Za-z0-9][A-Za-z0-9._() -]{0,159}\\." +
                        extension).matches(filename))
                context.contentResolver.openInputStream(uri).use {
                    assertArrayEquals("Generated URI must reopen for its actual recipient",
                        expected, requireNotNull(it).readBytes())
                }
                assertEquals(expected.size.toLong(), context.contentResolver.query(
                    uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                    .use { cursor ->
                        requireNotNull(cursor).moveToFirst()
                        cursor.getLong(0)
                    })
            }
        }
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
