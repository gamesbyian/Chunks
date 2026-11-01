package io.github.gamesbyian.chunks

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Installed, actual ContentResolver / app ZIP-intake -> fresh Downloads row. */
@RunWith(AndroidJUnit4::class)
class LocalSingleZipMediaStoreExportInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    private fun archive(vararg names: String): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { zip ->
                names.forEach { name ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write("zip payload: $name".toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun <T> document(name: String, mime: String, bytes: ByteArray, block: (Uri) -> T): T {
        val root = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(resolver.insert(root, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Chunks Exports/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        try {
            requireNotNull(resolver.openOutputStream(uri, "w")).use { it.write(bytes) }
            assertEquals(1, resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null))
            return block(uri)
        } finally {
            // Test created this exact row; never guess a filesystem path.
            assertEquals(1, resolver.delete(uri, null, null))
        }
    }

    private fun read(uri: Uri) = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }

    @Test fun userApprovedSingleMemberCreatesDistinctVerifiedDownloadAndKeepsBothSources() {
        val canary = "Keep this prior file byte for byte".toByteArray()
        document("notes.txt", "text/plain", canary) { previous ->
            val zipBytes = archive("notes.txt")
            document("source-for-single-zip.zip", "application/zip", zipBytes) { source ->
                val reviewed = LocalZipDocumentInspector.inspect(context, source)
                assertTrue(LocalSingleZipMediaStoreExport.eligible(reviewed))
                val result = LocalSingleZipMediaStoreExport.extractNew(context, source)
                assertEquals(result.message, LocalSingleZipMediaStoreExport.State.EXPORTED, result.state)
                val newUri = requireNotNull(result.publishedUri)
                try {
                    assertNotEquals(previous, newUri)
                    assertTrue(requireNotNull(result.publishedName).startsWith("notes-"))
                    assertArrayEquals("zip payload: notes.txt".toByteArray(), read(newUri))
                    assertArrayEquals(canary, read(previous))
                    assertArrayEquals(zipBytes, read(source))
                } finally {
                    // Only delete the test's exact newly published row.
                    assertEquals(1, resolver.delete(newUri, null, null))
                }
            }
        }
    }

    @Test fun traversalNestedMultiMemberAndPreCancelledNeverPublishOrTouchCanary() {
        val canary = "protected user data".toByteArray()
        document("preserved.bin", "application/octet-stream", canary) { previous ->
            for (zipBytes in listOf(
                archive("../escape.txt"), archive("nested/file.txt"),
                archive("a.txt", "b.txt"), archive("nonmatching.exe")
            )) {
                document("invalid-single-zip.zip", "application/zip", zipBytes) { source ->
                    val result = LocalSingleZipMediaStoreExport.extractNew(context, source)
                    assertEquals(result.message, LocalSingleZipMediaStoreExport.State.BLOCKED, result.state)
                    assertNull(result.publishedUri)
                    assertArrayEquals(zipBytes, read(source))
                    assertArrayEquals(canary, read(previous))
                }
            }
            document("pre-cancel.zip", "application/zip", archive("safe.txt")) { source ->
                val result = LocalSingleZipMediaStoreExport.extractNew(context, source, cancelled = { true })
                assertEquals(LocalSingleZipMediaStoreExport.State.BLOCKED, result.state)
                assertNull(result.publishedUri)
            }
            assertArrayEquals(canary, read(previous))
        }
    }
}
