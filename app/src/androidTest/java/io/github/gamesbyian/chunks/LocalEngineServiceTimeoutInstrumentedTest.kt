package io.github.gamesbyian.chunks

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalTransferState
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/**
 * Opt-in reproduction of Android 15+'s dataSync foreground-service timeout
 * (#569). It runs ONLY when tools/fgs_timeout_repro.sh has shortened the
 * platform limit on a disposable emulator and passes chunksFgsTimeout=1;
 * the ordinary suite skips it. Proves, on that emulator only: onTimeout
 * pauses a running transfer with readback, leaves a dismissible notice,
 * removes the ongoing notification and keeps payload bytes. Not evidence
 * for the real six-hour budget, Doze, OEM killers or physical handsets.
 */
@RunWith(AndroidJUnit4::class)
class LocalEngineServiceTimeoutInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private lateinit var work: File
    private val hashes = mutableListOf<String>()
    private var retainForColdRestore = false
    private val coldRestore get() = InstrumentationRegistry.getArguments()
        .getString("chunksFgsColdRestore") == "1"
    private val receipt get() = File(work, "timeout-receipt.properties")

    @Before fun setUp() {
        assumeTrue("Run through tools/fgs_timeout_repro.sh only",
            InstrumentationRegistry.getArguments().getString("chunksFgsTimeout") == "1")
        assumeTrue("dataSync time limit exists from Android 15", android.os.Build.VERSION.SDK_INT >= 35)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        work = File(context.filesDir, "service-timeout-test")
        if (coldRestore) assertTrue("Timeout phase A receipt folder missing", work.isDirectory)
        else {
            work.deleteRecursively()
            assertTrue("Cannot prepare timeout fixture", work.mkdirs())
            notifications.cancel(LocalServiceTimeoutNotice.NOTIFICATION_ID)
        }
    }

    @After fun tearDown() {
        if (!::work.isInitialized || retainForColdRestore) return
        hashes.forEach { LocalEngineHolder.current()?.removeKeepingData(it) }
        context.stopService(Intent(context, LocalEngineService::class.java))
        // Join native disk I/O before deleting synthetic test payloads.
        LocalEngineHolder.current()?.close()
        work.deleteRecursively()
    }

    private fun waitFor(what: String, seconds: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out after ${seconds}s waiting for $what")
            Thread.sleep(250)
        }
    }

    private fun active(id: Int) = notifications.activeNotifications.firstOrNull { it.id == id }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    @Test fun shortenedDataSyncLimitPausesWithNoticeAndKeepsBytes() {
        // A complete local payload seeds, so the transfer stays running until something stops it.
        val root = File(work, "Timeout fixture").apply { mkdirs() }
        val payload = File(root, "payload.bin").apply {
            writeBytes(ByteArray(96_000).also { Random(569).nextBytes(it) })
        }
        val before = sha(payload)
        // Two distinct torrents expose a sequential per-transfer readback
        // budget regression without spawning another Android FGS timeout test.
        val secondRoot = File(work, "Timeout fixture 2").apply { mkdirs() }
        val secondPayload = File(secondRoot, "payload2.bin").apply {
            writeBytes(ByteArray(96_000).also { Random(570).nextBytes(it) })
        }
        val secondBefore = sha(secondPayload)
        val torrent = TorrentBuilder().path(root).pieceSize(16 * 1024).generate().entry().bencode()
        val secondTorrent = TorrentBuilder().path(secondRoot).pieceSize(16 * 1024).generate().entry().bencode()
        val engine = LocalEngineHolder.get(context)
        val added = (engine.addStopped(torrent, work) as LocalAddResult.AddedStopped).status
        hashes += added.infoHash
        val second = (engine.addStopped(secondTorrent, work) as LocalAddResult.AddedStopped).status
        hashes += second.infoHash
        assertEquals(LocalCommandOutcome.VERIFIED, engine.resume(added.infoHash).outcome)
        assertEquals(LocalCommandOutcome.VERIFIED, engine.resume(second.infoHash).outcome)

        val started = System.currentTimeMillis()
        LocalEngineService.start(context)
        waitFor("ongoing notification", 30) { active(LocalEngineService.NOTIFICATION_ID) != null }
        // No Stop all and no activity: only Android's (shortened) limit can stop it now.
        waitFor("Android dataSync timeout pause for both transfers", 180) {
            hashes.all { engine.status(it)?.stopped == true }
        }
        val elapsed = System.currentTimeMillis() - started
        waitFor("timeout notice", 30) { active(LocalServiceTimeoutNotice.NOTIFICATION_ID) != null }
        waitFor("ongoing notification removed", 30) { active(LocalEngineService.NOTIFICATION_ID) == null }

        val notice = active(LocalServiceTimeoutNotice.NOTIFICATION_ID)!!.notification
        val text = notice.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertEquals("Android's background time limit was reached. Chunks paused 2 transfers on this phone. " +
            "Open Chunks to resume.", text)
        assertTrue("notice must be dismissible", notice.flags and android.app.Notification.FLAG_ONGOING_EVENT == 0)
        assertArrayEquals("first payload bytes unchanged", before, sha(payload))
        assertArrayEquals("second payload bytes unchanged", secondBefore, sha(secondPayload))
        // Persist a fixture-only receipt so a *different* instrumentation process
        // verifies these exact jobs, rather than an unrelated process-death case.
        Properties().apply {
            setProperty("first_hash", added.infoHash)
            setProperty("second_hash", second.infoHash)
            setProperty("first_sha", sha(payload).toHex())
            setProperty("second_sha", sha(secondPayload).toHex())
        }.also { properties ->
            receipt.outputStream().use { stream ->
                properties.store(stream, "Generated foreground timeout fixture only")
                stream.flush()
                (stream as java.io.FileOutputStream).fd.sync()
            }
        }
        retainForColdRestore = true
        android.util.Log.i("ChunksFgsTimeout", "CHUNKS_FGS_TIMEOUT armed=true paused_after_ms=$elapsed sdk=" +
            android.os.Build.VERSION.SDK_INT + " notice=\"$text\"")
    }
    /**
     * Separate instrumentation invocation after adb force-stop. The previous
     * onTimeout has already removed its FGS notification. A cold native engine
     * must restore the same two jobs stopped, recheck existing disk bytes into
     * verified seeding, and remove only the session entries.
     */
    @Test fun coldRestoreAfterTimeoutRechecksSeedsAndKeepsPayload() {
        assumeTrue("Only the second phase of tools/fgs_timeout_repro.sh", coldRestore)
        assertNull("Phase B must start in a fresh process", LocalEngineHolder.current())
        assertTrue("Phase A did not arm the cold-restart receipt", receipt.isFile)
        val properties = Properties().apply { receipt.inputStream().use { load(it) } }
        val first = requireNotNull(properties.getProperty("first_hash"))
        val second = requireNotNull(properties.getProperty("second_hash"))
        assertTrue("The two timeout jobs must have distinct identities", first != second)
        hashes += listOf(first, second)
        val firstPayload = File(work, "Timeout fixture/payload.bin")
        val secondPayload = File(work, "Timeout fixture 2/payload2.bin")
        assertEquals(properties.getProperty("first_sha"), sha(firstPayload).toHex())
        assertEquals(properties.getProperty("second_sha"), sha(secondPayload).toHex())
        assertTrue("Old ongoing FGS must be gone after process death",
            active(LocalEngineService.NOTIFICATION_ID) == null)

        val restored = requireNotNull(LocalEngineHolder.restoreIfSaved(context)) {
            "The timeout jobs were not discoverable after process death"
        }
        assertTrue("Restore errors: ${restored.restoreProblems}", restored.restoreProblems.isEmpty())
        val inventory = requireNotNull(restored.completeIdentityInventory())
        assertTrue("First timed-out job not restored", inventory.contains(first))
        assertTrue("Second timed-out job not restored", inventory.contains(second))
        for (hash in hashes) {
            assertTrue("Restore must not resume a transfer without consent",
                requireNotNull(restored.status(hash)).stopped)
            assertEquals(LocalCommandOutcome.VERIFIED, restored.resume(hash).outcome)
            waitFor("exact-hash seeding after disk recheck", 60) {
                restored.status(hash)?.state == LocalTransferState.SEEDING
            }
            assertEquals(LocalCommandOutcome.VERIFIED, restored.pause(hash).outcome)
        }
        assertEquals(properties.getProperty("first_sha"), sha(firstPayload).toHex())
        assertEquals(properties.getProperty("second_sha"), sha(secondPayload).toHex())
        for (hash in hashes) {
            assertTrue("Session-only Remove was not confirmed for ${hash.take(8)}",
                restored.removeKeepingData(hash))
            assertNull("Removed transfer still visible", restored.status(hash))
        }
        assertEquals(properties.getProperty("first_sha"), sha(firstPayload).toHex())
        assertEquals(properties.getProperty("second_sha"), sha(secondPayload).toHex())
        android.util.Log.i("ChunksFgsTimeout",
            "CHUNKS_FGS_TIMEOUT cold_restore=true jobs=2 stopped=true rechecked=true " +
                "seeded=true removed_keep_files=true")
    }

}
