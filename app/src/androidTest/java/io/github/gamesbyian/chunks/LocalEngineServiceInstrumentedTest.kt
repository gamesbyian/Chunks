package io.github.gamesbyian.chunks

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import java.io.File
import java.util.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/**
 * Emulator evidence for the local engine foreground service: ongoing notification
 * while a transfer runs, Stop all pauses and removes it, idle self-stop.
 * Not evidence for Doze, OEM task killers or the Android 15 six-hour limit.
 */
@RunWith(AndroidJUnit4::class)
class LocalEngineServiceInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private lateinit var work: File
    private var hash: String? = null
    private var secondHash: String? = null

    @Before fun setUp() {
        // POST_NOTIFICATIONS exists from API 33; earlier releases show it unprompted.
        if (android.os.Build.VERSION.SDK_INT >= 33)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        work = File(requireNotNull(LocalEngineHolder.appDownloads(context)), "service-test")
            .apply { deleteRecursively(); mkdirs() }
    }

    @After fun tearDown() {
        hash?.let { LocalEngineHolder.current()?.removeKeepingData(it) }
        secondHash?.let { LocalEngineHolder.current()?.removeKeepingData(it) }
        context.stopService(Intent(context, LocalEngineService::class.java))
        work.deleteRecursively()
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what")
            Thread.sleep(100)
        }
    }

    private fun ongoing() = notifications.activeNotifications.any { it.id == LocalEngineService.NOTIFICATION_ID }

    @Test fun ongoingNotificationWhileRunningAndStopAllPausesEverything() {
        val root = File(work, "Service fixture").apply { mkdirs() }
        File(root, "data.bin").writeBytes(ByteArray(50_000).also { Random(3).nextBytes(it) })
        val torrent = TorrentBuilder().path(root).pieceSize(16 * 1024).generate().entry().bencode()
        val engine = LocalEngineHolder.get(context)
        val added = (engine.addStopped(torrent, work) as LocalAddResult.AddedStopped).status
        hash = added.infoHash
        assertEquals(LocalCommandOutcome.VERIFIED, engine.resume(added.infoHash).outcome)

        LocalEngineService.start(context)
        waitFor("ongoing notification") { ongoing() }

        context.startForegroundService(Intent(context, LocalEngineService::class.java)
            .setAction(LocalEngineService.ACTION_STOP_ALL))
        waitFor("transfer paused by Stop all") { engine.status(added.infoHash)?.stopped == true }
        waitFor("notification removed") { !ongoing() }
    }

    @Test fun notificationStopAllPausesEveryRunningTransferNotJustTheFirst() {
        fun torrent(name: String, seed: Int): ByteArray {
            val root = File(work, name).apply { mkdirs() }
            File(root, "payload.bin").writeBytes(ByteArray(65_536).also {
                Random(seed.toLong()).nextBytes(it)
            })
            return TorrentBuilder().path(root).pieceSize(16 * 1024).generate()
                .entry().bencode()
        }
        val engine = LocalEngineHolder.get(context)
        val first = (engine.addStopped(torrent("Batch one", 9), work)
            as LocalAddResult.AddedStopped).status
        val second = (engine.addStopped(torrent("Batch two", 10), work)
            as LocalAddResult.AddedStopped).status
        hash = first.infoHash
        secondHash = second.infoHash
        assertEquals(LocalCommandOutcome.VERIFIED, engine.resume(first.infoHash).outcome)
        assertEquals(LocalCommandOutcome.VERIFIED, engine.resume(second.infoHash).outcome)

        LocalEngineService.start(context)
        waitFor("notification with both active transfers") { ongoing() }
        context.startForegroundService(Intent(context, LocalEngineService::class.java)
            .setAction(LocalEngineService.ACTION_STOP_ALL))
        waitFor("both transfers paused by one Stop all") {
            engine.status(first.infoHash)?.stopped == true &&
                engine.status(second.infoHash)?.stopped == true
        }
        waitFor("foreground notification removed after both paused") { !ongoing() }
        assertEquals(0L, engine.status(first.infoHash)?.payloadDownloadedBytes)
        assertEquals(0L, engine.status(second.infoHash)?.payloadDownloadedBytes)
    }

    @Test fun serviceStopsItselfOnceNothingIsRunning() {
        val root = File(work, "Idle fixture").apply { mkdirs() }
        File(root, "idle.bin").writeBytes(ByteArray(40_000).also { Random(4).nextBytes(it) })
        val torrent = TorrentBuilder().path(root).pieceSize(16 * 1024).generate().entry().bencode()
        val engine = LocalEngineHolder.get(context)
        val added = (engine.addStopped(torrent, work) as LocalAddResult.AddedStopped).status
        hash = added.infoHash
        assertEquals(LocalCommandOutcome.VERIFIED, engine.resume(added.infoHash).outcome)
        LocalEngineService.start(context)
        waitFor("ongoing notification") { ongoing() }

        // Paused from the app, not via the notification: the service must notice and leave.
        assertEquals(LocalCommandOutcome.VERIFIED, engine.pause(added.infoHash).outcome)
        waitFor("idle self-stop") { !ongoing() }
    }
}
