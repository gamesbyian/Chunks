package io.github.gamesbyian.chunks

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import java.io.File
import java.security.MessageDigest
import java.util.Random
import org.junit.Assert.assertEquals
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
            android.util.Log.i("ChunksReboot", "CHUNKS_REBOOT restored_stopped=true no_autostart=true sha_unchanged=true")
            engine.removeKeepingData(hash)
        } finally {
            context.stopService(Intent(context, LocalEngineService::class.java))
            marker.delete()
            work.deleteRecursively()
        }
    }
}
