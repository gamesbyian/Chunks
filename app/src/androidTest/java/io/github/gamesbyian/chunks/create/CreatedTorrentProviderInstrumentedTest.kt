package io.github.gamesbyian.chunks.create

import androidx.test.ext.junit.runners.AndroidJUnit4
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
}
