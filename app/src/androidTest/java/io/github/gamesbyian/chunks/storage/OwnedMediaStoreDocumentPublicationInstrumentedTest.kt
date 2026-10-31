package io.github.gamesbyian.chunks.storage

import android.content.ContentValues
import android.os.Environment
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Only disposable MediaStore rows on emulator API29/35. No arbitrary SAF URI,
 * native torrent data, existing user file or external server is touched.
 */
@RunWith(AndroidJUnit4::class)
class OwnedMediaStoreDocumentPublicationInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    @Test fun createsFreshPendingRowThenPublishesOnlyAfterIndependentByteReadback() {
        val contents = ByteArray(513_029) { ((it * 37 + 13) % 251).toByte() }
        val created = OwnedMediaStoreDocumentPublication.publish(
            context, "storage-evidence.chunksnap", "application/octet-stream", contents)
        try {
            assertTrue(created.publishedName.startsWith("storage-evidence-"))
            assertTrue(created.publishedName.endsWith(".chunksnap"))
            assertEquals(contents.size.toLong(), created.sizeBytes)
            val readback = requireNotNull(resolver.openInputStream(created.uri)).use {
                it.readBytes()
            }
            assertArrayEquals(contents, readback)
            val projection = arrayOf(MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH)
            requireNotNull(resolver.query(created.uri, projection, null, null, null)).use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
                assertEquals(context.packageName, it.getString(1))
                assertEquals(Environment.DIRECTORY_DOWNLOADS + "/Chunks Exports/",
                    it.getString(2))
            }
        } finally {
            // Emulator-owned, returned-id fixture ONLY, after positive proof.
            assertEquals(1, resolver.delete(created.uri, null, null))
        }
    }

    @Test fun anExistingOwnedDownloadWithSimilarNameRetainsItsExactBytes() {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val marker = UUID.randomUUID().toString().replace("-", "")
        val original = ("FOREIGN-CANARY-" + marker).toByteArray()
        val canary = requireNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "storage-evidence-$marker.json")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Chunks Exports/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        var published: OwnedMediaStoreDocumentPublication.Receipt? = null
        try {
            requireNotNull(resolver.openOutputStream(canary, "w")).use { it.write(original) }
            assertEquals(1, resolver.update(canary, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null))
            published = OwnedMediaStoreDocumentPublication.publish(context,
                "storage-evidence-$marker.json", "application/json", byteArrayOf(4, 7, 1))
            assertNotEquals(canary, published.uri)
            assertArrayEquals(original,
                requireNotNull(resolver.openInputStream(canary)).use { it.readBytes() })
            assertArrayEquals(byteArrayOf(4, 7, 1),
                requireNotNull(resolver.openInputStream(published.uri)).use { it.readBytes() })
        } finally {
            published?.let { resolver.delete(it.uri, null, null) }
            resolver.delete(canary, null, null)
        }
    }

    @Test fun malformedOrCancelledRequestDoesNotCreateAProviderDocument() {
        try {
            OwnedMediaStoreDocumentPublication.publish(
                context, "../outside.txt", "text/plain", byteArrayOf(1))
            throw AssertionError("Traversal name was accepted")
        } catch (_: IllegalArgumentException) { }
        try {
            OwnedMediaStoreDocumentPublication.publish(
                context, "valid-report.txt", "text/plain", byteArrayOf(1),
                cancelled = { true })
            throw AssertionError("Cancelled request was admitted")
        } catch (_: IOException) { }
        assertFalse("No invalid request may create an ordinary filesystem path",
            java.io.File(context.cacheDir, "outside.txt").exists())
    }

    @Test fun durableExclusiveJournalConfirmsOnlyAnUnchangedExactPublishedRow() {
        val root = OwnedMediaStorePublicationJournal.journalRoot(context)
        val originalIds = root.toFile().list().orEmpty().toSet()
        val contents = ByteArray(151_411) { (it * 17 + 1).toByte() }
        val receipt = OwnedMediaStoreDocumentPublication.publish(context,
            "durable-journal-test.txt", "text/plain", contents)
        try {
            val created = root.toFile().list().orEmpty().toSet() - originalIds
            assertEquals("One exclusive new journal per publication", 1, created.size)
            val id = requireNotNull(created.single().removeSuffix(".log"))
            assertEquals("Receipt must link directly to its exact durable operation",
                receipt.operationId, id)
            val recorded = OwnedMediaStorePublicationJournal.parse(id,
                io.github.gamesbyian.chunks.storage.ExclusiveFsyncJournal
                    .readCompleteLines(root.resolve("${id}.log")))
            assertEquals(OwnedMediaStorePublicationJournal.Phase.VERIFIED_PUBLISHED,
                recorded?.phase)
            assertEquals(receipt.uri.toString(), recorded?.rowUri)
            assertEquals(OwnedMediaStorePublicationJournal.Verdict.VERIFIED_PUBLISHED,
                OwnedMediaStorePublicationJournal.assess(context, id).verdict)
            requireNotNull(resolver.openFileDescriptor(receipt.uri, "rw")).use {
                val corrupt = byteArrayOf(99, 88)
                assertEquals(corrupt.size,
                    android.system.Os.pwrite(it.fileDescriptor, corrupt, 0, corrupt.size, 0L))
                android.system.Os.fsync(it.fileDescriptor)
            }
            assertEquals(OwnedMediaStorePublicationJournal.Verdict.NEEDS_ATTENTION,
                OwnedMediaStorePublicationJournal.assess(context, id).verdict)
        } finally {
            // Only the exact test-created row, never a guessed name/path.
            assertEquals(1, resolver.delete(receipt.uri, null, null))
        }
    }

    @Test fun cancellationAfterJournaledRowPreservesAmbiguousPendingObjectForReview() {
        val root = OwnedMediaStorePublicationJournal.journalRoot(context)
        val before = root.toFile().list().orEmpty().toSet()
        var count = 0
        try {
            OwnedMediaStoreDocumentPublication.publish(context,
                "cancelled-journal-test.txt", "text/plain", byteArrayOf(1, 2, 3),
                cancelled = { ++count >= 2 })
            throw AssertionError("Cancelled pending publication was accepted")
        } catch (_: IOException) { }
        val newFiles = root.toFile().list().orEmpty().toSet() - before
        assertEquals(1, newFiles.size)
        val id = newFiles.single().removeSuffix(".log")
        val evidence = requireNotNull(OwnedMediaStorePublicationJournal.parse(
            id, ExclusiveFsyncJournal.readCompleteLines(root.resolve("${id}.log"))))
        assertEquals(OwnedMediaStorePublicationJournal.Phase.ROW_RECEIVED, evidence.phase)
        val uri = android.net.Uri.parse(requireNotNull(evidence.rowUri))
        try {
            assertEquals(OwnedMediaStorePublicationJournal.Verdict.NEEDS_ATTENTION,
                OwnedMediaStorePublicationJournal.assess(context, id).verdict)
            val cols = arrayOf(MediaStore.MediaColumns.IS_PENDING)
            requireNotNull(resolver.query(uri, cols, null, null, null)).use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
        } finally {
            // Test fixture only. API29's provider can report 0 deleted rows
            // for an app-owned still-pending row even when its read/query
            // succeeded. Do not confuse this test teardown counter with the
            // *production* promise to leave ambiguous rows untouched.
            val includingPending = if (Build.VERSION.SDK_INT == 29)
                MediaStore.setIncludePending(uri) else uri
            fun stillVisible(): Boolean = try {
                resolver.query(includingPending, arrayOf(MediaStore.MediaColumns._ID),
                    null, null, null)?.use { it.moveToFirst() } ?: false
            } catch (_: Exception) { false }
            resolver.delete(includingPending, null, null)
            if (stillVisible()) {
                // Only this exact newly created test URI, never a name/path
                // or an operation discovered from any production journal.
                resolver.update(includingPending, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
                resolver.delete(includingPending, null, null)
            }
            assertFalse("Test-created row remained visible after test-only cleanup",
                stillVisible())
        }
    }

}
