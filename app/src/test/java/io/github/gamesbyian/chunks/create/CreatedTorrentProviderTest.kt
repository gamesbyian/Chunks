package io.github.gamesbyian.chunks.create

import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CreatedTorrentProviderTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var provider: CreatedTorrentProvider

    @Before fun register() {
        provider = Robolectric.setupContentProvider(CreatedTorrentProvider::class.java, CreatedTorrentProvider.authority(context))
    }

    @Test fun servesOnlyTheCreatedTorrentReadOnly() {
        val bytes = byteArrayOf(1, 2, 3)
        val uri = CreatedTorrentProvider.publish(context, "Season.torrent", bytes)
        provider.openFile(uri, "r").use { pfd ->
            assertArrayEquals(bytes, android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes())
        }
        provider.query(uri, null, null, null, null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Season.torrent", c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(3L, c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        assertEquals("application/x-bittorrent", provider.getType(uri))
        try { provider.openFile(uri, "w"); fail("must be read-only") } catch (_: SecurityException) { }
    }

    @Test fun repeatedNameNeverTruncatesEarlierShare() {
        val name = "Collision-" + UUID.randomUUID() + ".torrent"
        val first = CreatedTorrentProvider.publish(context, name, byteArrayOf(11, 12, 13))
        val second = CreatedTorrentProvider.publish(context, name, byteArrayOf(21, 22))
        assertNotEquals(first, second)
        val folder = CreatedTorrentProvider.folder(context)
        assertArrayEquals(byteArrayOf(11, 12, 13), File(folder, first.lastPathSegment!!).readBytes())
        assertArrayEquals(byteArrayOf(21, 22), File(folder, second.lastPathSegment!!).readBytes())
        assertEquals(name, first.lastPathSegment)
        assertTrue(second.lastPathSegment!!.contains(" (2).torrent"))
    }

    @Test fun foreignSymlinkAndHardlinkCollisionsRemainByteExact() {
        val folder = CreatedTorrentProvider.folder(context).apply { mkdirs() }
        val foreign = File(context.cacheDir, "foreign-" + UUID.randomUUID()).apply {
            writeBytes("foreign bytes".toByteArray())
        }
        val before = foreign.readBytes()
        val linkName = "Link-" + UUID.randomUUID() + ".torrent"
        val link = File(folder, linkName)
        try {
            Files.createSymbolicLink(link.toPath(), foreign.toPath())
            val result = CreatedTorrentProvider.publish(context, linkName, byteArrayOf(1))
            assertNotEquals(linkName, result.lastPathSegment)
            assertArrayEquals(before, foreign.readBytes())
            try {
                provider.openFile(Uri.parse("content://" + CreatedTorrentProvider.authority(context) +
                    "/" + Uri.encode(linkName)), "r")
                fail("Symlinked share must be rejected")
            } catch (_: FileNotFoundException) { }
        } finally {
            Files.deleteIfExists(link.toPath())
        }

        val hardlinkName = "Hardlink-" + UUID.randomUUID() + ".torrent"
        val hardlink = File(folder, hardlinkName)
        try {
            Files.createLink(hardlink.toPath(), foreign.toPath())
            val result = CreatedTorrentProvider.publish(context, hardlinkName, byteArrayOf(2))
            assertNotEquals(hardlinkName, result.lastPathSegment)
            assertArrayEquals(before, foreign.readBytes())
        } finally {
            Files.deleteIfExists(hardlink.toPath())
            Files.deleteIfExists(foreign.toPath())
        }
    }

    @Test fun refusesEverythingOutsideItsFolderOrNotATorrent() {
        File(context.cacheDir, "secret.torrent").writeText("x")
        File(CreatedTorrentProvider.folder(context).apply { mkdirs() }, "notes.txt").writeText("x")
        val base = "content://" + CreatedTorrentProvider.authority(context)
        for (path in listOf("/..%2Fsecret.torrent", "/notes.txt", "/a/b.torrent", "/missing.torrent")) try {
            provider.openFile(Uri.parse(base + path), "r")
            fail("should refuse $path")
        } catch (_: FileNotFoundException) { }
        try { CreatedTorrentProvider.publish(context, "../escape.torrent", byteArrayOf(1)); fail() }
        catch (_: IllegalStateException) { }
    }
}
