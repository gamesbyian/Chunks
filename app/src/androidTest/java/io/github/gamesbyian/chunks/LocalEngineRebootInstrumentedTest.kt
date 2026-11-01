package io.github.gamesbyian.chunks

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalRateLimits
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/**
 * Opt-in device reboot repro (#569), run in two phases by
 * tools/reboot_repro.sh. Phase A seeds a transfer under the foreground
 * service; the script then reboots the device. Phase B requires that after
 * boot nothing was started on Chunks' behalf (no engine in the fresh process,
 * no ongoing notification: Android forbids starting dataSync from boot and
 * Chunks has no boot receiver), and that opening the saved state restores the
 * transfer STOPPED with unchanged bytes. Emulator only.
 */
@RunWith(AndroidJUnit4::class)
class LocalEngineRebootInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val phase get() = InstrumentationRegistry.getArguments().getString("chunksRebootPhase")
    private val work get() = File(context.filesDir, "reboot-test")
    private val marker get() = File(context.filesDir, "reboot-test.marker")
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun waitFor(what: String, seconds: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out after ${seconds}s waiting for $what")
            Thread.sleep(250)
        }
    }

    @Test fun phaseA_seedUnderServiceBeforeReboot() {
        assumeTrue("Run through tools/reboot_repro.sh only", phase == "a")
        if (android.os.Build.VERSION.SDK_INT >= 33)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        work.deleteRecursively(); marker.delete()
        val root = File(work, "Reboot fixture").apply { mkdirs() }
        val payload = File(root, "payload.bin").apply {
            writeBytes(ByteArray(96_000).also { Random(5691).nextBytes(it) })
        }
        val torrent = TorrentBuilder().path(root).pieceSize(16 * 1024).generate().entry().bencode()
        File(work, "fixture.torrent").writeBytes(torrent)
        val engine = LocalEngineHolder.get(context)
        val added = (engine.addStopped(torrent, work) as LocalAddResult.AddedStopped).status
        assertEquals(LocalCommandOutcome.VERIFIED, engine.resume(added.infoHash).outcome)
        LocalEngineService.start(context)
        waitFor("ongoing notification", 30) {
            notifications.activeNotifications.any { it.id == LocalEngineService.NOTIFICATION_ID }
        }
        waitFor("seeding at 100%", 60) {
            engine.status(added.infoHash)?.let { !it.stopped && it.progressPpm == 1_000_000 } == true
        }
        marker.writeText(added.infoHash + "\n" + sha(payload) + "\n" + payload.absolutePath)
        android.util.Log.i("ChunksReboot", "CHUNKS_REBOOT armed hash=${added.infoHash}")
    }

    @Test fun phaseB_nothingStartsAfterBootAndRestoreIsStopped() {
        assumeTrue("Run through tools/reboot_repro.sh only", phase == "b")
        assertTrue("phase A did not arm", marker.isFile)
        val (hash, digest, path) = marker.readLines()
        try {
            assertNull("no engine may be running before the user opens Chunks", LocalEngineHolder.current())
            assertFalse("no foreground service may have started from boot",
                notifications.activeNotifications.any { it.id == LocalEngineService.NOTIFICATION_ID })
            assertEquals("payload bytes changed across reboot", digest, sha(File(path)))
            val engine = LocalEngineHolder.restoreIfSaved(context)
            assertNotNull("saved transfer was not restored after reboot", engine)
            assertTrue("restore problems: ${engine!!.restoreProblems}", engine.restoreProblems.isEmpty())
            val restored = requireNotNull(engine.status(hash)) { "transfer missing after reboot" }
            assertTrue("restored transfer must not auto-start after reboot", restored.stopped)
            // Android must not auto-start dataSync after boot, but a fresh
            // user-directed Start has to recover the actual verified pieces.
            val original = File(path)
            val uploadedBefore = requireNotNull(engine.status(hash)).payloadUploadedBytes
            assertEquals("Explicit post-reboot Start failed",
                LocalCommandOutcome.VERIFIED, engine.resume(hash).outcome)
            waitFor("explicit post-reboot verified seeding", 60) {
                engine.status(hash)?.let {
                    !it.stopped && it.state == LocalTransferState.SEEDING &&
                        it.progressPpm == 1_000_000
                } == true
            }
            assertEquals("Original payload changed after user Start", digest, sha(original))
            LocalEngineService.start(context)
            waitFor("user-started foreground notification", 30) {
                notifications.activeNotifications.any { it.id == LocalEngineService.NOTIFICATION_ID }
            }

            val receiverRoot = File(work, "post-reboot-fresh-receiver")
            assertTrue("Fresh post-reboot receiver directory was reused", receiverRoot.mkdir())
            assertTrue("Fresh receiver had existing pieces",
                receiverRoot.listFiles()?.isEmpty() == true)
            val receiverPayload = File(receiverRoot, "Reboot fixture/payload.bin")
            assertFalse("Receiver must not have preexisting payload", receiverPayload.exists())
            val bytes = File(work, "fixture.torrent").readBytes()
            val receiverConfig = LocalEngineConfig(
                listenInterfaces = "127.0.0.1:0",
                allowMultipleConnectionsPerIp = true,
                rateLimitLocalPeers = true
            )
            LocalTorrentEngine(receiverConfig).use { receiver ->
                receiver.start()
                receiver.setRateLimits(LocalRateLimits(downloadBytesPerSecond = 32 * 1024))
                val addedReceiver = receiver.addStopped(bytes, receiverRoot)
                assertTrue("Fresh receiver Add failed: $addedReceiver",
                    addedReceiver is LocalAddResult.AddedStopped)
                val initial = requireNotNull(receiver.status(hash))
                assertEquals("Fresh receiver must start with zero verified bytes", 0L,
                    initial.doneBytes)
                assertEquals(LocalCommandOutcome.VERIFIED, receiver.resume(hash).outcome)
                waitFor("fresh receiver ready to request pieces", 30) {
                    receiver.status(hash)?.let {
                        !it.stopped && it.state == LocalTransferState.DOWNLOADING
                    } == true
                }
                val seedPort = engine.listenPort()
                assertTrue("Restored engine is not listening after manual Start",
                    seedPort in 1..65535)
                assertTrue("Fresh exact-hash peer injection refused",
                    receiver.connectPeer(hash, InetSocketAddress("127.0.0.1", seedPort)))
                waitFor("post-reboot receiver downloads all pieces", 90) {
                    receiver.status(hash)?.progressPpm == 1_000_000
                }
                assertTrue("Fresh receiver cache was not flushed",
                    receiver.flushCompletedForLibraryRead(hash, 30_000))
                assertTrue("Fresh receiver payload not written", receiverPayload.isFile)
                assertEquals("Receiver payload size differs", original.length(), receiverPayload.length())
                assertEquals("Fresh independent receiver SHA differs", digest, sha(receiverPayload))
                waitFor("actual post-reboot payload upload counters", 30) {
                    (engine.status(hash)?.payloadUploadedBytes ?: 0L) - uploadedBefore >= original.length() &&
                        (receiver.status(hash)?.payloadDownloadedBytes ?: 0L) >= original.length()
                }
            }
            assertEquals(LocalCommandOutcome.VERIFIED, engine.pause(hash).outcome)
            assertTrue("Remove failed after verified post-reboot seeding", engine.removeKeepingData(hash))
            assertEquals("Remove must keep original payload", digest, sha(original))
            android.util.Log.i("ChunksReboot",
                "CHUNKS_REBOOT restored_stopped=true no_autostart=true sha_unchanged=true " +
                    "reseeded_bytes=${original.length()} removed_keep_files=true")
        } finally {
            context.stopService(Intent(context, LocalEngineService::class.java))
            marker.delete()
            work.deleteRecursively()
        }
    }
}
