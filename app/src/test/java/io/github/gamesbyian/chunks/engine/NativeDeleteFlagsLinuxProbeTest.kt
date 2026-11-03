package io.github.gamesbyian.chunks.engine

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.TorrentDeletedAlert
import org.libtorrent4j.alerts.TorrentDeleteFailedAlert

/**
 * Read-only research branch only. Nothing in Chunks production calls DELETE_FILES.
 * Does upstream native deletion remove a pre-existing matching path, and does a
 * completion/failure alert correspond with independently observable file bytes?
 * The fixture and all writes are in this isolated temporary directory.
 */
class NativeDeleteFlagsLinuxProbeTest {
    @Before fun loadNative() = DesktopLibtorrent.ensureLoaded()
    @Test fun isolatedDeleteFilesHasBoundedAlertAndFileWitness() {
        val root = Files.createTempDirectory("chunks-delete-boundary-").toFile()
        val manager = SessionManager(false)
        val done = CountDownLatch(1)
        val nativeResult = AtomicReference<String>("none")
        val alerts = java.util.concurrent.atomic.AtomicInteger(0)
        val terminalAlerts = java.util.concurrent.atomic.AtomicInteger(0)
        var listener: AlertListener? = null
        try {
            val source = root.resolve("source/Probe content")
            assertTrue("source directory creation", source.mkdirs())
            val bytes = ByteArray(65_537) { ((it * 17) % 251).toByte() }
            source.resolve("probe.bin").writeBytes(bytes)
            val meta = TorrentBuilder().path(source).pieceSize(16 * 1024)
                .generate().entry().bencode()
            val info = TorrentInfo(meta)
            val destination = root.resolve("destination")
            assertTrue("destination directory creation", destination.mkdirs())
            val target = destination.resolve("Probe content/probe.bin")
            assertTrue("payload parent creation", target.parentFile.mkdirs())
            target.writeBytes(bytes)
            val canary = destination.resolve("foreign.txt")
            val canaryBytes = "unrelated file must survive".toByteArray()
            canary.writeBytes(canaryBytes)
            val wantedHash = info.infoHash().toHex()
            val watch = object : AlertListener {
                override fun types(): IntArray? = null
                override fun alert(alert: Alert<*>) {
                    alerts.incrementAndGet()
                    when (alert) {
                        is TorrentDeletedAlert -> {
                            terminalAlerts.incrementAndGet()
                            println("CHUNKS_NATIVE_DELETE_EVENT type=deleted observedHash=${alert.infoHashes.getV1().toHex()} expectedHash=$wantedHash")
                            if (alert.infoHashes.getV1().toHex().equals(wantedHash, true)) {
                                nativeResult.set("deleted")
                                done.countDown()
                            }
                        }
                        is TorrentDeleteFailedAlert -> {
                            terminalAlerts.incrementAndGet()
                            println("CHUNKS_NATIVE_DELETE_EVENT type=failed observedHash=${alert.infoHashes.getV1().toHex()} expectedHash=$wantedHash")
                            if (alert.infoHashes.getV1().toHex().equals(wantedHash, true)) {
                                nativeResult.set("failed")
                                done.countDown()
                            }
                        }
                    }
                }
            }
            listener = watch
            manager.addListener(watch)
            manager.start()
            manager.download(info, destination)
            var handle = manager.find(info.infoHash())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (handle == null && System.nanoTime() < deadline) {
                Thread.sleep(100)
                handle = manager.find(info.infoHash())
            }
            assertNotNull("native handle never appeared", handle)
            assertArrayEquals("pre-delete target bytes", bytes, target.readBytes())
            manager.remove(requireNotNull(handle), SessionHandle.DELETE_FILES)
            assertTrue("no matching delete terminal alert in 30 seconds (result=${nativeResult.get()} alerts=${alerts.get()} terminalAlerts=${terminalAlerts.get()} targetExists=${target.exists()})",
                done.await(30, TimeUnit.SECONDS))
            assertEquals("native delete terminal alert", "deleted", nativeResult.get())
            assertFalse("native deleted alert but mapped target still on disk", target.exists())
            assertArrayEquals("unrelated canary modified", canaryBytes, canary.readBytes())
        } finally {
            listener?.let(manager::removeListener)
            manager.stop()
            root.deleteRecursively()
        }
    }
}
