package io.github.gamesbyian.chunks.create

import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileNotFoundException
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
