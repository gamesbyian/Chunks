package io.github.gamesbyian.chunks.create

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import java.io.FileNotFoundException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android provider/FD confirmation, limited to app-private cache. */
@RunWith(AndroidJUnit4::class)
class CreatedTorrentProviderInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun sameNameSharesKeepOriginalAndReturnFreshReadableUris() {
        val name = "owned-share-" + UUID.randomUUID() + ".torrent"
        val a = byteArrayOf(11, 12, 13, 14)
        val b = byteArrayOf(31, 32)
        val first = CreatedTorrentProvider.publish(context, name, a)
        val second = CreatedTorrentProvider.publish(context, name, b)
        assertNotEquals(first, second)
        context.contentResolver.openInputStream(first).use {
            assertArrayEquals(a, requireNotNull(it).readBytes())
        }
        context.contentResolver.openInputStream(second).use {
            assertArrayEquals(b, requireNotNull(it).readBytes())
        }
        assertEquals(name, first.lastPathSegment)
        requireNotNull(context.contentResolver.query(first, null, null, null, null)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(name, cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(a.size.toLong(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
    }

    @Test fun preexistingForeignCacheFileIsNeverTruncated() {
        val folder = CreatedTorrentProvider.folder(context).apply { mkdirs() }
        val name = "foreign-canary-" + UUID.randomUUID() + ".torrent"
        val foreign = File(folder, name)
        val bytes = "preexisting foreign bytes".toByteArray()
        foreign.writeBytes(bytes)
        val uri = CreatedTorrentProvider.publish(context, name, byteArrayOf(1, 2, 3))
        assertNotNull(uri)
        assertNotEquals(name, uri.lastPathSegment)
        assertArrayEquals(bytes, foreign.readBytes())
        context.contentResolver.openInputStream(uri).use {
            assertArrayEquals(byteArrayOf(1, 2, 3), requireNotNull(it).readBytes())
        }
    }

    @Test fun fifoSymlinkAndHardlinkShareNamesRefuseWithoutTouchingOtherBytes() {
        val folder = CreatedTorrentProvider.folder(context).apply { mkdirs() }
        fun uri(name: String) = Uri.Builder().scheme("content")
            .authority(CreatedTorrentProvider.authority(context)).appendPath(name).build()
        fun refusesRead(name: String) {
            val requested = uri(name)
            try {
                context.contentResolver.openFileDescriptor(requested, "r")?.use { }
                fail("A non-owned or nonregular share must not be opened")
            } catch (_: FileNotFoundException) { }
            try {
                context.contentResolver.query(requested, null, null, null, null)?.use { }
                fail("Nonregular share metadata must also be refused")
            } catch (_: FileNotFoundException) { }
        }

        val fifo = File(folder, "fifo-" + UUID.randomUUID() + ".torrent")
        Os.mkfifo(fifo.absolutePath, 384) // app-private 0600
        // A descriptor-first O_NONBLOCK read must never await a FIFO writer.
        refusesRead(fifo.name)
        assertTrue(Files.exists(fifo.toPath(), LinkOption.NOFOLLOW_LINKS))

        val foreign = File(context.cacheDir, "foreign-share-" + UUID.randomUUID())
        val bytes = "never disclose or mutate unrelated data".toByteArray()
        foreign.writeBytes(bytes)
        val link = File(folder, "symlink-" + UUID.randomUUID() + ".torrent")
        Files.createSymbolicLink(link.toPath(), foreign.toPath())
        refusesRead(link.name)
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertArrayEquals(bytes, foreign.readBytes())

        val hardlink = File(folder, "hardlink-" + UUID.randomUUID() + ".torrent")
        try {
            Files.createLink(hardlink.toPath(), foreign.toPath())
            refusesRead(hardlink.name)
            assertTrue(Files.isSameFile(hardlink.toPath(), foreign.toPath()))
        } catch (_: FileSystemException) {
            // Some Android app-private filesystems prohibit hardlinks.
            // Denial is safe; FIFO and symlink are mandatory witnesses.
        }
        assertArrayEquals(bytes, foreign.readBytes())
    }

}
