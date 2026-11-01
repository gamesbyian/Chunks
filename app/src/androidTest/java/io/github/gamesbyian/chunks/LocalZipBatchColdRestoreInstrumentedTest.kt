package io.github.gamesbyian.chunks

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.storage.OwnedMediaStoreZipBatchJournal
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Disposable API29/API35 two-process test of actual on-device ZIP batch
 * recovery. Only a separate guarded shell may invoke each phase; all-class
 * UI suites without the phase flag skip it rather than mutating user files.
 */
@RunWith(AndroidJUnit4::class)
class LocalZipBatchColdRestoreInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private val downloads get() =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    // One host-selected nonce is shared across both processes. Repeated runs
    // against retained app data cannot adopt an earlier test receipt.
    private val runId get() = InstrumentationRegistry.getArguments().getString("chunksBatchRunId").orEmpty()
    private val fixture get() = File(context.filesDir, "chunks-batch-cold-restore-test-$runId")
    private val marker get() = File(fixture, "receipt.properties")

    private fun phase(value: String) {
        assumeTrue("Only guarded batch-cold process harness may run",
            InstrumentationRegistry.getArguments().getString("chunksBatchPhase") == value &&
                Build.VERSION.SDK_INT in setOf(29, 35))
        assertTrue("Cold-process phases require a unique shared fixture ID",
            runId.matches(Regex("[0-9a-f]{16}")))
        assertTrue("No physical device may be targeted by this test",
            Build.HARDWARE in setOf("ranchu", "goldfish") ||
                Build.FINGERPRINT.startsWith("generic"))
    }
    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
    private fun read(uri: Uri): ByteArray =
        requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }

    private fun zip(first: String, second: String) =
        ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { writer ->
                listOf(first, second).forEach { name ->
                    writer.putNextEntry(ZipEntry(name))
                    writer.write(("cold batch: " + name).toByteArray())
                    writer.closeEntry()
                }
            }
        }.toByteArray()

    private fun createSource(bytes: ByteArray, name: String): Uri {
        val uri = requireNotNull(resolver.insert(downloads, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Chunks Exports/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        requireNotNull(resolver.openOutputStream(uri, "w")).use { it.write(bytes) }
        assertEquals(1, resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null))
        return uri
    }

    /** Read-only, includes pending rows so an interrupted duplicate cannot hide. */
    private fun exactOutputs(prefix: String, publishedOnly: Boolean = false): Set<Uri> {
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.IS_PENDING)
        val cursor = if (Build.VERSION.SDK_INT == 29)
            resolver.query(MediaStore.setIncludePending(downloads), cols, null, null, null)
        else resolver.query(downloads, cols, Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }, null)
        return requireNotNull(cursor).use { rows ->
            buildSet {
                while (rows.moveToNext()) {
                    if (rows.getString(1)?.startsWith(prefix + "-") == true &&
                        rows.getString(2) == context.packageName &&
                        (!publishedOnly || rows.getInt(3) == 0))
                        add(ContentUris.withAppendedId(downloads, rows.getLong(0)))
                }
            }
        }
    }

    @Test fun phaseA_publishOneThenPersistStoppedBatchBeforeForceStop() {
        phase("a")
        assertTrue("Test fixture cannot adopt an old private receipt", fixture.mkdir())
        val nonce = UUID.randomUUID().toString().replace("-", "").take(16)
        val first = "chunkscold" + nonce + ".txt"
        val second = "laterchunkscold" + nonce + ".bin"
        val sourceBytes = zip(first, second)
        val source = createSource(sourceBytes, "fixture-cold-" + nonce + ".zip")
        val prefix = first.substringBeforeLast('.')
        val result = LocalMultiZipMediaStoreExport.extractNew(context, source) {
            // A pending FIRST row is not a finished file. Wait for its
            // independently verified publication before stopping the batch.
            exactOutputs(prefix, publishedOnly = true).isNotEmpty()
        }
        assertEquals(result.message, LocalMultiZipMediaStoreExport.State.PARTIAL, result.state)
        assertEquals(1, result.published.size)
        assertEquals(1, result.remaining)
        val row = result.published.single()
        assertEquals(first, row.originalName)
        assertEquals(setOf(row.uri), exactOutputs(prefix))
        val expected = ("cold batch: " + first).toByteArray()
        assertArrayEquals(expected, read(row.uri))
        assertArrayEquals(sourceBytes, read(source))
        assertTrue(OwnedMediaStoreZipBatchJournal.unfinishedDescriptions(context)
            .any { it.contains("stopped after 1 of 2") })

        Properties().apply {
            setProperty("prefix", prefix)
            setProperty("source", source.toString())
            setProperty("published", row.uri.toString())
            setProperty("payloadSha", sha(expected))
            setProperty("sourceSha", sha(sourceBytes))
        }.also { receipt ->
            FileOutputStream(marker).use { stream ->
                receipt.store(stream, "Disposable ZIP batch cold-process receipt")
                stream.fd.sync()
            }
        }
        android.util.Log.i("ChunksBatchCold",
            "CHUNKS_BATCH_COLD phase=a partial=true verified=1 original=true")
        // Do not delete or clear the app. The harness force-stops this process.
    }

    @Test fun phaseB_coldProcessRecoveryShowsWarningWithoutCreatingAnotherRow() {
        phase("b")
        assertTrue("A different process must retain the first phase's receipt", marker.isFile)
        val receipt = Properties().apply { marker.inputStream().use { load(it) } }
        val prefix = requireNotNull(receipt.getProperty("prefix"))
        assertTrue(prefix.matches(Regex("chunkscold[0-9a-f]{16}")))
        val source = Uri.parse(requireNotNull(receipt.getProperty("source")))
        val published = Uri.parse(requireNotNull(receipt.getProperty("published")))
        val before = exactOutputs(prefix)
        assertEquals("Cold restore must never auto-create the remaining batch row",
            setOf(published), before)
        assertEquals(receipt.getProperty("payloadSha"), sha(read(published)))
        assertEquals(receipt.getProperty("sourceSha"), sha(read(source)))
        assertTrue(OwnedMediaStoreZipBatchJournal.unfinishedDescriptions(context)
            .any { it.contains("stopped after 1 of 2") })

        compose.setContent { MaterialTheme { LocalZipInterruptedExtractions() } }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("An earlier ZIP batch was stopped after 1 of 2",
                substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Earlier file operations need review").assertExists()
        assertEquals("UI recovery may not publish more objects", before, exactOutputs(prefix))
        assertEquals(receipt.getProperty("payloadSha"), sha(read(published)))
        assertEquals(receipt.getProperty("sourceSha"), sha(read(source)))
        android.util.Log.i("ChunksBatchCold",
            "CHUNKS_BATCH_COLD phase=b cold=true warning=true original=true no_duplicate=true")
        // Cleanup ONLY exact returned test-owned provider row IDs. Production
        // recovery has no mutation or cleanup path whatsoever.
        assertEquals(1, resolver.delete(published, null, null))
        assertEquals(1, resolver.delete(source, null, null))
        // Leave private receipt/journal on the ephemeral emulator for audit.
    }
}
