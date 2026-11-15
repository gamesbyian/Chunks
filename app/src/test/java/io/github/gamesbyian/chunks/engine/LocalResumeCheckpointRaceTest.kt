package io.github.gamesbyian.chunks.engine

import java.io.File
import java.net.InetSocketAddress
import java.util.Random
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder

/**
 * Races around the engine-owned periodic resume checkpoint (#1748), on real libtorrent over
 * loopback with the JVM resume store:
 * - a Remove that lands while a checkpoint save is in flight never resurrects the job;
 * - an unchanged job is not rewritten on every tick (no storage churn);
 * - a fast checkpoint timer racing close() leaves a store that restores cleanly, stopped,
 *   with no more progress than was verified and with exact bytes after finishing.
 */
class LocalResumeCheckpointRaceTest {
    @get:Rule val temp = TemporaryFolder()
    private val engines = ArrayList<LocalTorrentEngine>()
    @Before fun native() = DesktopLibtorrent.ensureLoaded()
    @After fun stop() { engines.forEach { runCatching { it.close() } } }

    private fun engine(state: File? = null, checkpointMs: Long = 0L, throttle: Boolean = false) =
        LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
            allowMultipleConnectionsPerIp = true, stateDirectory = state,
            resumeCheckpointIntervalMs = checkpointMs, rateLimitLocalPeers = throttle))
            .also { it.start(); engines += it }

    private fun await(seconds: Long = 30, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) { check(System.nanoTime() < deadline) { "timeout" }; Thread.sleep(20) }
    }

    private class Fixture(val torrent: ByteArray, val payload: ByteArray, val source: File)

    private fun fixture(name: String, size: Int, seed: Long): Fixture {
        val source = temp.newFolder("src-$name")
        val payload = ByteArray(size).also { Random(seed).nextBytes(it) }
        val file = File(source, "$name.bin").apply { writeBytes(payload) }
        val torrent = TorrentBuilder().path(file).pieceSize(16 * 1024)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()
        return Fixture(torrent, payload, source)
    }

    private fun seeding(f: Fixture, uploadBytesPerSecond: Int): LocalTorrentEngine {
        val seeder = engine(throttle = true)
        seeder.setRateLimits(LocalRateLimits(uploadBytesPerSecond = uploadBytesPerSecond))
        val h = (seeder.addStopped(f.torrent, f.source) as LocalAddResult.AddedStopped).status.infoHash
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(h).outcome)
        await { seeder.status(h)?.state == LocalTransferState.SEEDING }
        return seeder
    }

    private fun stateFiles(state: File) = state.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(state).path to it.length() }

    @Test fun removeDuringAnInFlightCheckpointNeverResurrectsTheJob() {
        val state = temp.newFolder("state")
        val destination = temp.newFolder("dest")
        val removed = ArrayList<String>()
        for (round in 0 until 4) {
            val f = fixture("r$round", 768 * 1024, 100L + round)
            val seeder = seeding(f, 256 * 1024)
            val local = engine(state)
            val hash = (local.addStoppedChecked(f.torrent, destination) as LocalAddResult.AddedStopped).status.infoHash
            assertEquals(LocalCommandOutcome.VERIFIED, local.resume(hash).outcome)
            assertTrue(local.connectPeer(hash, InetSocketAddress("127.0.0.1", seeder.listenPort())))
            await { (local.status(hash)?.doneBytes ?: 0L) >= 64 * 1024 }
            // Progress changed, so a checkpoint is due; Remove races its asynchronous save alert.
            assertEquals(1, local.checkpointChangedTransfers())
            assertTrue(local.removeKeepingData(hash))
            Thread.sleep(750) // let any late save_resume_data_alert be delivered and handled
            assertNull(local.status(hash))
            local.close(); engines -= local
            seeder.close(); engines -= seeder
            removed += hash
            val reopened = engine(state)
            assertTrue(reopened.restoreProblems.toString(), reopened.restoreProblems.isEmpty())
            for (h in removed) assertNull("Removed job $h came back from a checkpoint", reopened.status(h))
            reopened.close(); engines -= reopened
        }
        // Remove is session-only: payload bytes that were written stay on disk.
        assertTrue(File(destination, "r0.bin").exists())
    }

    @Test fun anUnchangedStoppedJobIsNotRewrittenOnEveryCheckpoint() {
        val state = temp.newFolder("state")
        val f = fixture("idle", 256 * 1024, 7)
        val destination = temp.newFolder("dest")
        val local = engine(state)
        val hash = (local.addStoppedChecked(f.torrent, destination) as LocalAddResult.AddedStopped).status.infoHash
        assertTrue(local.saveResumeDataFor(hash))
        // At most one catch-up save for state the Add snapshot did not yet include.
        if (local.checkpointChangedTransfers() > 0) Thread.sleep(500)
        val settled = stateFiles(state)
        repeat(5) {
            assertEquals("Nothing changed, so no checkpoint is requested", 0, local.checkpointChangedTransfers())
            Thread.sleep(100)
        }
        assertEquals("No resume state written for an unchanged job", settled, stateFiles(state))
    }

    @Test fun aFastCheckpointTimerRacingCloseLeavesACleanlyRestorableStore() {
        val state = temp.newFolder("state")
        val f = fixture("fast", 1536 * 1024, 21)
        val seeder = seeding(f, 192 * 1024)
        val destination = temp.newFolder("dest")
        val local = engine(state, checkpointMs = 25L)
        val hash = (local.addStoppedChecked(f.torrent, destination) as LocalAddResult.AddedStopped).status.infoHash
        assertEquals(LocalCommandOutcome.VERIFIED, local.resume(hash).outcome)
        assertTrue(local.connectPeer(hash, InetSocketAddress("127.0.0.1", seeder.listenPort())))
        await { (local.status(hash)?.doneBytes ?: 0L) >= 256 * 1024 }
        val beforeClose = requireNotNull(local.status(hash)).doneBytes
        local.close(); engines -= local // timer ticks every 25 ms while close() saves and tears down

        val reopened = engine(state)
        assertTrue(reopened.restoreProblems.toString(), reopened.restoreProblems.isEmpty())
        await(10) { reopened.status(hash)?.state != LocalTransferState.CHECKING }
        val restored = requireNotNull(reopened.status(hash))
        assertTrue("Restored jobs never start on their own", restored.stopped)
        assertTrue("Close saved progress: ${restored.doneBytes} < $beforeClose - slack",
            restored.doneBytes >= beforeClose - 4L * 16 * 1024)
        assertTrue("Restored progress cannot exceed the payload size", restored.doneBytes <= f.payload.size)
        assertEquals(LocalCommandOutcome.VERIFIED, reopened.resume(hash).outcome)
        assertTrue(reopened.connectPeer(hash, InetSocketAddress("127.0.0.1", seeder.listenPort())))
        seeder.setRateLimits(LocalRateLimits())
        await(60) { reopened.status(hash)?.progressPpm == 1_000_000 }
        assertTrue(reopened.flushCompletedForLibraryRead(hash, timeoutMs = 30_000L))
        assertArrayEquals(f.payload, File(destination, "fast.bin").readBytes())
    }
}
