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
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    private var hash: String? = null

    @Before fun setUp() {
        assumeTrue("Run through tools/fgs_timeout_repro.sh only",
            InstrumentationRegistry.getArguments().getString("chunksFgsTimeout") == "1")
        assumeTrue("dataSync time limit exists from Android 15", android.os.Build.VERSION.SDK_INT >= 35)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        work = File(context.filesDir, "service-timeout-test").apply { deleteRecursively(); mkdirs() }
        notifications.cancel(LocalServiceTimeoutNotice.NOTIFICATION_ID)
    }

    @After fun tearDown() {
        if (!::work.isInitialized) return
        hash?.let { LocalEngineHolder.current()?.removeKeepingData(it) }
        context.stopService(Intent(context, LocalEngineService::class.java))
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

    @Test fun shortenedDataSyncLimitPausesWithNoticeAndKeepsBytes() {
        // A complete local payload seeds, so the transfer stays running until something stops it.
        val root = File(work, "Timeout fixture").apply { mkdirs() }
        val payload = File(root, "payload.bin").apply {
            writeBytes(ByteArray(96_000).also { Random(569).nextBytes(it) })
        }
        val before = sha(payload)
        val torrent = TorrentBuilder().path(root).pieceSize(16 * 1024).generate().entry().bencode()
        val engine = LocalEngineHolder.get(context)
        val added = (engine.addStopped(torrent, work) as LocalAddResult.AddedStopped).status
        hash = added.infoHash
        assertEquals(LocalCommandOutcome.VERIFIED, engine.resume(added.infoHash).outcome)

        val started = System.currentTimeMillis()
        LocalEngineService.start(context)
        waitFor("ongoing notification", 30) { active(LocalEngineService.NOTIFICATION_ID) != null }
        // No Stop all and no activity: only Android's (shortened) limit can stop it now.
        waitFor("Android dataSync timeout pause", 180) { engine.status(added.infoHash)?.stopped == true }
        val elapsed = System.currentTimeMillis() - started
        waitFor("timeout notice", 30) { active(LocalServiceTimeoutNotice.NOTIFICATION_ID) != null }
        waitFor("ongoing notification removed", 30) { active(LocalEngineService.NOTIFICATION_ID) == null }

        val notice = active(LocalServiceTimeoutNotice.NOTIFICATION_ID)!!.notification
        val text = notice.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertEquals("Android's background time limit was reached. Chunks paused 1 transfer on this phone. " +
            "Open Chunks to resume.", text)
        assertTrue("notice must be dismissible", notice.flags and android.app.Notification.FLAG_ONGOING_EVENT == 0)
        assertArrayEquals("payload bytes unchanged", before, sha(payload))
        android.util.Log.i("ChunksFgsTimeout", "CHUNKS_FGS_TIMEOUT paused_after_ms=$elapsed sdk=" +
            android.os.Build.VERSION.SDK_INT + " notice=\"$text\"")
    }
}
