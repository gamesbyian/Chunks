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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/**
 * Opt-in two-process repro of sudden process death (#569), run only by
 * tools/process_death_repro.sh. Phase A seeds a transfer under the
 * foreground service and then SIGKILLs its own process with no warning,
 * no Stop all and no explicit resume save. Phase B, in a new process,
 * checks: no leftover ongoing notification, the transfer is restored
 * STOPPED (never auto-restarted), payload bytes are unchanged, and the
 * restored job rechecks to 100% when the user starts it.
 * Emulator evidence only; not OEM killers, Doze or a physical handset.
 */
@RunWith(AndroidJUnit4::class)
class LocalEngineProcessDeathInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val phase get() = InstrumentationRegistry.getArguments().getString("chunksDeathPhase")
    private val work get() = File(context.filesDir, "process-death-test")
    private val marker get() = File(context.filesDir, "process-death-test.marker")
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

    @Test fun phaseA_seedUnderServiceThenDieWithoutWarning() {
        assumeTrue("Run through tools/process_death_repro.sh only", phase == "a")
        if (android.os.Build.VERSION.SDK_INT >= 33)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        work.deleteRecursively()
        marker.delete()
        val root = File(work, "Death fixture").apply { mkdirs() }
        val payload = File(root, "payload.bin").apply {
            writeBytes(ByteArray(128_000).also { Random(5690).nextBytes(it) })
        }
        val torrent = TorrentBuilder().path(root).pieceSize(16 * 1024).generate().entry().bencode()
        val engine = LocalEngineHolder.get(context)
        val added = (engine.addStopped(torrent, work) as LocalAddResult.AddedStopped).status
        val provenance = File(context.filesDir, "local-engine/${added.infoHash}.admission")
        val expectedAdmission = "chunks-native-admission-v1\\n${added.infoHash}\\nreviewed-existing\\n"
        assertTrue("Explicit seeding Add must durably publish its private admission receipt",
            provenance.isFile)
        assertEquals("The fixture must not masquerade as a checked download",
            expectedAdmission.replace("\\n", "\n"), provenance.readText())
        assertEquals(LocalCommandOutcome.VERIFIED, engine.resume(added.infoHash).outcome)
        LocalEngineService.start(context)
        waitFor("ongoing notification", 30) {
            notifications.activeNotifications.any { it.id == LocalEngineService.NOTIFICATION_ID }
        }
        waitFor("running at 100%", 60) {
            engine.status(added.infoHash)?.let { !it.stopped && it.progressPpm == 1_000_000 } == true
        }
        marker.writeText(added.infoHash + "\n" + sha(payload) + "\n" + payload.absolutePath)
        android.util.Log.i("ChunksDeath", "CHUNKS_DEATH armed hash=${added.infoHash}")
        // Sudden death: no Stop all, no onDestroy, no explicit resume save.
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    @Test fun phaseB_restoredStoppedWithBytesIntactAndRechecks() {
        assumeTrue("Run through tools/process_death_repro.sh only", phase == "b")
        assertTrue("phase A did not arm", marker.isFile)
        val (hash, digest, path) = marker.readLines()
        try {
            assertFalse("killed process left an ongoing notification",
                notifications.activeNotifications.any { it.id == LocalEngineService.NOTIFICATION_ID })
            assertEquals("payload bytes changed", digest, sha(File(path)))
            val engine = LocalEngineHolder.restoreIfSaved(context)
            assertNotNull("saved transfer was not restored", engine)
            assertTrue("restore problems: ${engine!!.restoreProblems}", engine.restoreProblems.isEmpty())
            val restored = engine.status(hash)
            assertNotNull("transfer missing after restart", restored)
            assertTrue("restored transfer must not auto-start", restored!!.stopped)
            val admission = File(context.filesDir, "local-engine/$hash.admission")
            val expectedAdmission = "chunks-native-admission-v1\\n$hash\\nreviewed-existing\\n"
                .replace("\\n", "\n")
            assertTrue("Private Add admission receipt must survive sudden SIGKILL",
                admission.isFile)
            assertEquals("Previously reviewed seed must retain its exact admission mode",
                expectedAdmission, admission.readText())
            val command = engine.resume(hash)
            assertEquals("Restored native Start refused: ${command.status?.error}",
                LocalCommandOutcome.VERIFIED, command.outcome)
            waitFor("recheck to 100%", 90) { engine.status(hash)?.progressPpm == 1_000_000 }
            assertEquals(LocalCommandOutcome.VERIFIED, engine.pause(hash).outcome)
            assertEquals("payload bytes changed after recheck", digest, sha(File(path)))
            android.util.Log.i("ChunksDeath", "CHUNKS_DEATH restored_stopped=true recheck_ppm=1000000 sha_unchanged=true")
            engine.removeKeepingData(hash)
        } finally {
            context.stopService(Intent(context, LocalEngineService::class.java))
            marker.delete()
            work.deleteRecursively()
        }
    }
}
