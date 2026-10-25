package io.github.gamesbyian.chunks

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.model.ZipPayloadStatus
import io.github.gamesbyian.chunks.model.ZipPreflightStatus
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android provider-backed evidence, not merely JVM File/ZIP fixtures. Uses
 * app-owned MediaStore Downloads URIs, never a production torrent or seedbox.
 */
@RunWith(AndroidJUnit4::class)
class LocalZipArchiveReviewInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun capture(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name + ".png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/ChunksAddAtlas")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri).use { stream ->
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100,
                requireNotNull(stream)))
        }
        assertEquals(1, resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null))
    }

    private fun zip(name: String, payload: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(payload)
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun <T> withDocument(bytes: ByteArray, block: (android.net.Uri) -> T): T {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "chunks-readonly-test.zip")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ChunksTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            resolver.openOutputStream(uri, "w").use { output ->
                requireNotNull(output).write(bytes)
            }
            assertEquals(1, resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null))
            return block(uri)
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    private fun stagedCopies(): Set<String> =
        context.cacheDir.listFiles().orEmpty().map { it.name }
            .filter { it.startsWith("chunks-zip-review-") }.toSet()

    @Test fun documentUriVerifiesActualBytesRendersResultsAndRemovesPrivateCopy() {
        val before = stagedCopies()
        withDocument(zip("folder/episode.txt", "public sample".toByteArray())) { uri ->
            val inspected = LocalZipDocumentInspector.inspect(context, uri)
            assertEquals("chunks-readonly-test.zip", inspected.label)
            assertEquals(ZipPreflightStatus.BOUNDED_STREAM_CHECK_REQUIRED, inspected.inventory?.status)
            assertEquals(ZipPayloadStatus.VERIFIED_READ_ONLY, inspected.verification?.status)
            assertEquals(1, inspected.verification?.verifiedFiles)
            assertEquals("public sample".length.toLong(), inspected.verification?.actualOutputBytes)
            rule.setContent { MaterialTheme { LocalZipReviewResults(inspected) } }
            rule.onNodeWithText("Read-only integrity checks passed", substring = true).assertExists()
            rule.onNodeWithText("folder/episode.txt").assertExists()
            rule.onNodeWithText("A successful review does not authorize extraction", substring = true)
                .assertExists()
            capture("zip-local-review-safe-real-compose")
        }
        assertEquals("Temporary archive bytes must be discarded", before, stagedCopies())
    }

    @Test fun rejectsTraversalAndOverBudgetProviderWithoutWritingOutput() {
        val before = stagedCopies()
        withDocument(zip("../outside.txt", "bad".toByteArray())) { uri ->
            val inspected = LocalZipDocumentInspector.inspect(context, uri)
            assertEquals(ZipPreflightStatus.BLOCKED, inspected.inventory?.status)
            assertEquals(null, inspected.verification)
            rule.setContent { MaterialTheme { LocalZipReviewResults(inspected) } }
            rule.onNodeWithText("Unsafe or unsupported ZIP", substring = true).assertExists()
            capture("zip-local-review-blocked-real-compose")
        }
        withDocument(zip("safe.txt", ByteArray(128))) { uri ->
            val inspected = LocalZipDocumentInspector.inspect(context, uri, maxInputBytes = 16)
            assertNotNull(inspected.failure)
            assertTrue(inspected.failure.orEmpty().contains("input limit"))
            assertEquals(null, inspected.verification)
        }
        assertFalse("A traversal path must never be written", 
            java.io.File(context.cacheDir, "outside.txt").exists())
        assertEquals(before, stagedCopies())
    }

    @Test fun rejectsNonDocumentSchemesAndMalformedProviderContent() {
        val bad = LocalZipDocumentInspector.inspect(context, android.net.Uri.parse("file:///tmp/zip"))
        assertNotNull(bad.failure)
        assertEquals(0L, bad.stagedBytes)
        val before = stagedCopies()
        withDocument(byteArrayOf(1, 2, 3)) { uri ->
            val inspected = LocalZipDocumentInspector.inspect(context, uri)
            assertEquals(ZipPreflightStatus.BLOCKED, inspected.inventory?.status)
            assertTrue(inspected.inventory?.reasons?.isNotEmpty() == true)
        }
        assertEquals(before, stagedCopies())
    }
}
