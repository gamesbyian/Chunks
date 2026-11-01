package io.github.gamesbyian.chunks

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real API29/35 MediaStore and Compose acceptance; disposable owned rows only. */
@RunWith(AndroidJUnit4::class)
class LocalMultiZipMediaStoreExportInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private val downloads get() = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun zip(vararg paths: String): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { writer ->
                paths.forEach { path ->
                    writer.putNextEntry(ZipEntry(path))
                    writer.write(("batch content: " + path).toByteArray())
                    writer.closeEntry()
                }
            }
        }.toByteArray()

    private fun createOwned(name: String, bytes: ByteArray, block: (Uri) -> Unit) {
        val uri = requireNotNull(resolver.insert(downloads, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Chunks Exports/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        try {
            requireNotNull(resolver.openOutputStream(uri, "w")).use { it.write(bytes) }
            assertEquals(1, resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null))
            block(uri)
        } finally {
            assertEquals("Only the test-created exact row may be removed",
                1, resolver.delete(uri, null, null))
        }
    }
    private fun bytes(uri: Uri) =
        requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
    private fun freshBase() = "batch" + java.lang.Long.toHexString(System.nanoTime())

    /** A read-only *provider* observation, never cleanup authority. */
    private fun newPublished(base: String): List<Uri> {
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.IS_PENDING)
        return requireNotNull(resolver.query(downloads, cols, null, null, null)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (cursor.getString(1)?.startsWith(base + "-") == true &&
                        cursor.getString(2) == context.packageName && cursor.getInt(3) == 0)
                        add(ContentUris.withAppendedId(downloads, cursor.getLong(0)))
                }
            }
        }
    }

    @Test fun composeTwoFlatFilesPublishIndependentlyAndPreserveCanaryAndArchive() {
        val base = freshBase()
        val a = base + ".txt"
        val b = "second" + base + ".bin"
        val original = zip(a, b)
        val canary = "foreign existing document stays untouched".toByteArray()
        createOwned(a, canary) { foreign ->
            createOwned(base + "-source.zip", original) { source ->
                val reviewed = LocalZipDocumentInspector.inspect(context, source)
                assertTrue("Two-file export should be available",
                    LocalMultiZipMediaStoreExport.eligible(reviewed))
                val outcome = AtomicReference<LocalMultiZipMediaStoreExport.Result?>()
                compose.setContent {
                    MaterialTheme {
                        LocalZipExtractionPanel(source, reviewed,
                            onMultiResult = { outcome.set(it) })
                    }
                }
                compose.onNodeWithText("Extract new files to Downloads").performClick()
                compose.waitUntil(90_000) { outcome.get() != null }
                val result = requireNotNull(outcome.get())
                assertEquals(result.message, LocalMultiZipMediaStoreExport.State.EXPORTED, result.state)
                assertEquals(2, result.published.size)
                assertEquals(0, result.remaining)
                try {
                    compose.onNodeWithText("Created 2 distinct verified Downloads files", substring = true)
                        .assertExists()
                    result.published.forEach { row ->
                        assertNotEquals(foreign, row.uri)
                        assertTrue(row.publishedName.startsWith(row.originalName.substringBeforeLast('.') + "-"))
                        assertArrayEquals(("batch content: " + row.originalName).toByteArray(),
                            bytes(row.uri))
                    }
                    assertNotEquals(result.published[0].uri, result.published[1].uri)
                    assertArrayEquals(canary, bytes(foreign))
                    assertArrayEquals(original, bytes(source))
                } finally {
                    result.published.forEach {
                        assertEquals(1, resolver.delete(it.uri, null, null))
                    }
                }
            }
        }
    }

    @Test fun userCancellationAfterFirstPublishedFileRetainsItsBytesAndSkipsNext() {
        val base = freshBase()
        val first = base + ".txt"
        val second = "later" + base + ".json"
        val original = zip(first, second)
        createOwned("fixture-source-" + base + ".zip", original) { source ->
            // The cancellation decision comes from a read-only observation of
            // a REAL first published row, never from a guessed iteration count.
            val result = LocalMultiZipMediaStoreExport.extractNew(context, source) {
                newPublished(base).isNotEmpty()
            }
            assertEquals(result.message, LocalMultiZipMediaStoreExport.State.PARTIAL, result.state)
            assertEquals(1, result.published.size)
            assertEquals(1, result.remaining)
            try {
                assertEquals(first, result.published.single().originalName)
                assertArrayEquals(("batch content: " + first).toByteArray(),
                    bytes(result.published.single().uri))
                assertTrue("No later member may be published", newPublished("later" + base).isEmpty())
                assertArrayEquals(original, bytes(source))
            } finally {
                assertEquals(1, resolver.delete(result.published.single().uri, null, null))
            }
        }
    }

    @Test fun nestedUnknownAndTooManyMembersFailWithoutPublishingAnything() {
        val base = freshBase()
        val cases = listOf(
            zip("nested/" + base + ".txt", "another" + base + ".bin"),
            zip(base + ".txt", "unsafe" + base + ".exe"),
            zip(*(1..9).map { "n" + it + base + ".bin" }.toTypedArray()),
            zip(base + ".txt", base + ".txt/child.bin")
        )
        for ((index, original) in cases.withIndex()) {
            createOwned("invalid-batch-" + index + base + ".zip", original) { source ->
                val result = LocalMultiZipMediaStoreExport.extractNew(context, source)
                assertEquals(result.message, LocalMultiZipMediaStoreExport.State.BLOCKED, result.state)
                assertTrue(result.published.isEmpty())
                assertArrayEquals(original, bytes(source))
            }
        }
        assertTrue("No candidate file may have reached published Downloads",
            newPublished(base).isEmpty())
    }
}
