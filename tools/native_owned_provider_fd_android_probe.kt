package io.github.gamesbyian.chunks.engine

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Experimental Android G0 #568 PFD-to-JNI capability check; not an installed
 * libtorrent disk adapter, ownership journal, generic SAF/SD guarantee, or
 * user-transfer test. Every document is freshly minted by THIS test under
 * Android's primary platform MediaStore, not inferred from an existing path.
 */
@RunWith(AndroidJUnit4::class)
class OwnedProviderFdAndroidProbe {
    companion object {
        init { System.loadLibrary("chunks_provider_fd_probe") }
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    /** JNI consumes the detached descriptor on all outcomes. No surviving Java FD alias. */
    external fun nativeRoundTrip(fd: Int, declared: Long, generation: String): Boolean

    private data class MintedRow(val uri: Uri, val exactName: String)

    private fun newlyOwned(): MintedRow {
        val randomName = "chunks-native-pfd-" + java.util.UUID.randomUUID() + ".bin"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, randomName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Chunks Downloads/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val result = requireNotNull(resolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)) {
            "Unable to create disposable test-owned Downloads row"
        }
        return MintedRow(result, randomName)
    }

    private fun exactOwner(row: MintedRow, pendingExpected: Int,
                           sizeExpected: Long): Boolean {
        val uri = row.uri
        val base = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        if (uri.scheme != "content" || uri.authority != base.authority ||
            uri.userInfo != null || uri.query != null || uri.fragment != null ||
            uri.pathSegments.size != base.pathSegments.size + 1 ||
            uri.pathSegments.dropLast(1) != base.pathSegments) return false
        val rowId = runCatching { ContentUris.parseId(uri) }.getOrDefault(-1L)
        if (rowId <= 0L) return false
        val cols = arrayOf(MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE)
        return resolver.query(uri, cols, null, null, null)?.use { c ->
            if (c.count != 1 || !c.moveToFirst()) return@use false
            val actualId = c.getLong(0)
            val owner = c.getString(1)
            val name = c.getString(2)
            val path = c.getString(3)
            val pending = c.getInt(4)
            val size = if (c.isNull(5)) 0L else c.getLong(5)
            val mime = c.getString(6)
            actualId == rowId && owner == context.packageName &&
                name == row.exactName &&
                path == Environment.DIRECTORY_DOWNLOADS + "/Chunks Downloads/" &&
                mime == "application/octet-stream" &&
                pending == pendingExpected && size == sizeExpected
        } == true
    }

    private fun exactRead(uri: Uri, offset: Long, expected: ByteArray) {
        requireNotNull(resolver.openFileDescriptor(uri, "r")).use { pfd ->
            val got = ByteArray(expected.size)
            var complete = 0
            while (complete < expected.size) {
                val n = Os.pread(pfd.fileDescriptor, got, complete,
                    expected.size - complete, offset + complete)
                assertTrue("Native-produced provider bytes unavailable", n > 0)
                complete += n
            }
            assertArrayEquals(expected, got)
        }
    }

    @Test fun ownedPendingDownloadsDescriptorCrossesJniAndSurvivesPublication() {
        val minted = newlyOwned()
        val uri = minted.uri
        // No provider deletion on failure OR success. The disposable CI
        // emulator owns its test volume; unknown pending/published rows are
        // never unlinked by an inferred URI cleanup policy.
        run {
            assertTrue("Platform MediaStore owner and fresh pending identity required",
                exactOwner(minted, 1, 0L))

            requireNotNull(resolver.openFileDescriptor(uri, "r")).use {
                assertFalse("Readonly provider FD must be refused before native I/O",
                    nativeRoundTrip(it.detachFd(), 65552, "a".repeat(64)))
            }
            assertTrue("Readonly negative may not mutate initial empty row", exactOwner(minted, 1, 0L))
            requireNotNull(resolver.openFileDescriptor(uri, "rw")).use { pfd ->
                assertTrue("Genuine Android PFD-to-native bounded write/fsync failed",
                    nativeRoundTrip(pfd.detachFd(), 65552, "a".repeat(64)))
            }
            val first = ByteArray(4096) { ((it * 37 + 11) and 255).toByte() }
            val second = ByteArray(16) { ((it * 17 + 3) and 255).toByte() }
            exactRead(uri, 0L, first)
            exactRead(uri, 65536L, second)
            val published = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            assertEquals(1, resolver.update(uri, published, null, null))
            assertTrue("Publication changed owner, row identity or expected byte length",
                exactOwner(minted, 0, 65552L))
            // Every read is through a new descriptor; the original JNI FD is
            // closed. This proves only the minted object's observed bytes.
            exactRead(uri, 0L, first)
            exactRead(uri, 65536L, second)
        }
    }

    @Test fun nativeRefusesPipeWithoutBlockingOrWriting() {
        val pair = ParcelFileDescriptor.createPipe()
        try {
            assertFalse(nativeRoundTrip(pair[0].detachFd(), 65552, "a".repeat(64)))
        } finally {
            pair.forEach { it.close() }
        }
    }
}
