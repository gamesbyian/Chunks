package io.github.gamesbyian.chunks.engine

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Isolated G0 #568 scoped-storage capability investigation. NOT a libtorrent
 * disk adapter. The test owns only its freshly inserted MediaStore URI and
 * deletes that disposable row in finally. It never probes other users' files.
 * Android API29/35 is required; a simulated host test is insufficient.
 */
@RunWith(AndroidJUnit4::class)
class OwnedMediaStoreDescriptorInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    private fun createUniqueOwnedDownload(): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,
                "chunks-owned-fd-probe-" + System.nanoTime() + ".bin")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Chunks Downloads/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return requireNotNull(resolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)) {
            "MediaStore refused disposable owned Downloads insert"
        }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun exactRead(uri: Uri, offset: Long, expected: ByteArray) {
        val parcel = requireNotNull(resolver.openFileDescriptor(uri, "r")) {
            "Owned MediaStore descriptor is unavailable on reopen"
        }
        parcel.use {
            val actual = ByteArray(expected.size)
            var done = 0
            while (done < actual.size) {
                val n = Os.pread(it.fileDescriptor, actual, done, actual.size - done,
                    offset + done)
                assertTrue("Seekable MediaStore FD must supply real bytes", n > 0)
                done += n
            }
            assertEquals("Independent reopened byte-range SHA-256 mismatch",
                digest(expected), digest(actual))
            assertArrayEquals(expected, actual)
        }
    }

    @Test fun ownedPendingDownloadIsSeekableDurableAndReopenableAfterPublication() {
        assertTrue("The native FD experiment requires Android 10 or later",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val uri = createUniqueOwnedDownload()
        try {
            val first = ByteArray(4096) { (it * 37 + 11).toByte() }
            val second = ByteArray(4096) { (it * 19 + 71).toByte() }
            val offset = 65536L
            val writer = requireNotNull(resolver.openFileDescriptor(uri, "rw")) {
                "MediaStore pending item cannot provide read/write descriptor"
            }
            writer.use {
                assertEquals(first.size, Os.pwrite(it.fileDescriptor,
                    first, 0, first.size, 0L))
                assertEquals(second.size, Os.pwrite(it.fileDescriptor,
                    second, 0, second.size, offset))
                // A successful pwrite alone is not a durable payload receipt.
                Os.fsync(it.fileDescriptor)
            }
            exactRead(uri, 0L, first)
            exactRead(uri, offset, second)

            val published = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            assertEquals("Owned URI publication must affect exactly one row", 1,
                resolver.update(uri, published, null, null))
            // Deliberately open a NEW FD and reread both distant file ranges.
            exactRead(uri, 0L, first)
            exactRead(uri, offset, second)
        } finally {
            // This URI is newly created by THIS test. Never delete by display
            // name, inferred filesystem path, or unrelated provider query.
            resolver.delete(uri, null, null)
        }
    }

    @Test fun distinctOwnedDownloadsHaveDistinctUriAuthorityAndUnmodifiedBytes() {
        val firstUri = createUniqueOwnedDownload()
        var secondUri: Uri? = null
        try {
            val other = createUniqueOwnedDownload()
            secondUri = other
            assertFalse("Two provider-owned documents cannot share URI identity",
                firstUri == other)
            val firstBytes = ByteArray(2048) { (it * 11).toByte() }
            val secondBytes = ByteArray(2048) { (it * 7 + 1).toByte() }
            resolver.openFileDescriptor(firstUri, "rw")!!.use {
                assertEquals(firstBytes.size,
                    Os.pwrite(it.fileDescriptor, firstBytes, 0, firstBytes.size, 0L))
                Os.fsync(it.fileDescriptor)
            }
            resolver.openFileDescriptor(other, "rw")!!.use {
                assertEquals(secondBytes.size,
                    Os.pwrite(it.fileDescriptor, secondBytes, 0, secondBytes.size, 0L))
                Os.fsync(it.fileDescriptor)
            }
            exactRead(firstUri, 0L, firstBytes)
            exactRead(other, 0L, secondBytes)
            exactRead(firstUri, 0L, firstBytes)
        } finally {
            secondUri?.let { resolver.delete(it, null, null) }
            resolver.delete(firstUri, null, null)
        }
    }
}
