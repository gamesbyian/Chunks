package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.AlertListener
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.TorrentDeleteFailedAlert
import org.libtorrent4j.alerts.TorrentDeletedAlert

/**
 * Disposable native-engine delete_files capability probe, NOT permission for
 * Chunks' production "Remove transfer and data" button.
 *
 * In particular, libtorrent can delete a pre-existing byte-identical payload:
 * the torrent infohash does not establish exclusive filesystem ownership.
 * The product must establish path ownership and permission independently before
 * it ever invokes this native operation. All paths here are new app-private
 * test objects; no public Downloads or user torrents are touched.
 */
@RunWith(AndroidJUnit4::class)
class NativeDeleteFilesBoundaryInstrumentedTest {
    @Test fun nativeDeleteAlertAndIndependentAbsenceDoNotTouchForeignBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(context.filesDir, "native-delete-proof-" + System.nanoTime())
        assertTrue(work.mkdirs())
        val source = File(work, "source/Disposable fixture").apply { assertTrue(mkdirs()) }
        val data = ByteArray(65_537) { ((it * 17) % 251).toByte() }
        File(source, "piece.bin").writeBytes(data)
        val metainfo = TorrentBuilder().path(source)
            .pieceSize(16 * 1024).generate().entry().bencode()
        val info = TorrentInfo(metainfo)
        val destination = File(work, "destination").apply { assertTrue(mkdirs()) }
        // A matching pre-existing file is intentionally used to demonstrate
        // that native delete_files does NOT certify who originally wrote it.
        val target = File(destination, "Disposable fixture/piece.bin")
        assertTrue(requireNotNull(target.parentFile).mkdirs())
        target.writeBytes(data)
        val foreignBytes = "unrelated source must survive".toByteArray()
        val foreign = File(destination, "foreign-control.txt").apply { writeBytes(foreignBytes) }
        val done = CountDownLatch(1)
        val deleted = AtomicReference<Boolean?>(null)
        val manager = SessionManager(false)
        val listener = object : AlertListener {
            override fun types(): IntArray = intArrayOf(
                AlertType.TORRENT_DELETED.swig(),
                AlertType.TORRENT_DELETE_FAILED.swig()
            )

            override fun alert(alert: Alert<*>) {
                when (alert) {
                    is TorrentDeletedAlert -> {
                        if (alert.infoHashes.getV1().toHex().equals(info.infoHash().toHex(), true) == true) {
                            deleted.set(true)
                            done.countDown()
                        }
                    }
                    is TorrentDeleteFailedAlert -> {
                        if (alert.infoHashes.getV1().toHex().equals(info.infoHash().toHex(), true) == true) {
                            deleted.set(false)
                            done.countDown()
                        }
                    }
                }
            }
        }
        try {
            manager.addListener(listener)
            manager.start()
            manager.download(info, destination)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            var handle = manager.find(info.infoHash())
            while (handle == null && System.nanoTime() < deadline) {
                Thread.sleep(100)
                handle = manager.find(info.infoHash())
            }
            assertNotNull("Actual libtorrent handle never appeared", handle)
            assertArrayEquals(data, target.readBytes())
            manager.remove(requireNotNull(handle), SessionHandle.DELETE_FILES)
            assertTrue("Missing asynchronous native delete result alert", done.await(30, TimeUnit.SECONDS))
            assertTrue("Native delete reported failure", deleted.get() == true)
            assertFalse("Delete alert alone is insufficient: exact target still exists", target.exists())
            assertArrayEquals("Native delete touched unrelated bytes", foreignBytes, foreign.readBytes())
        } finally {
            manager.removeListener(listener)
            manager.stop()
            work.deleteRecursively()
        }
    }
}
