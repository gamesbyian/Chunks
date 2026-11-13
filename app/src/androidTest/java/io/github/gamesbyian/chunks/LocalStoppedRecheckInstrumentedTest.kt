package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import java.io.File
import java.net.InetSocketAddress
import java.util.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/**
 * Stopped-job Recheck with the Android libtorrent4j native library in the app
 * process on an emulator, on the shipped POSIX backend, loopback peers only.
 * Not physical-device, SAF/MediaStore or background-service proof.
 */
@RunWith(AndroidJUnit4::class)
class LocalStoppedRecheckInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var work: File
    private val engines = ArrayList<LocalTorrentEngine>()

    @Before fun setUp() {
        work = File(context.filesDir, "recheck-test").apply { deleteRecursively(); mkdirs() }
    }

    @After fun tearDown() {
        engines.forEach { it.close() }
        work.deleteRecursively()
    }

    private fun engine() = LocalTorrentEngine(LocalEngineConfig(
        listenInterfaces = "127.0.0.1:0", allowMultipleConnectionsPerIp = true)).also { it.start(); engines += it }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 90_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for local engine")
            Thread.sleep(100)
        }
    }

    @Test fun stoppedRecheckCountsPiecesWithoutDownloadingAndStartRepairs() {
        val seedParent = File(work, "seed").apply { mkdirs() }
        val root = File(seedParent, "Chunks recheck fixture").apply { mkdirs() }
        val payload = ByteArray(1_300_000).also { Random(7).nextBytes(it) }
        File(root, "episode.bin").writeBytes(payload)
        val torrent = TorrentBuilder().path(root).pieceSize(64 * 1024).generate().entry().bencode()

        val seeder = engine()
        val seed = (seeder.addStopped(torrent, seedParent) as LocalAddResult.AddedStopped).status
        seeder.resume(seed.infoHash)
        waitFor { seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING }

        val destination = File(work, "destination").apply { mkdirs() }
        root.copyRecursively(File(destination, root.name))
        val local = engine()
        val job = (local.addStopped(torrent, destination,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort()))) as LocalAddResult.AddedStopped).status

        val full = local.recheckStopped(job.infoHash)
        assertEquals(full.toString(), LocalCommandOutcome.VERIFIED, full.outcome)
        assertTrue(full.toString(), full.status!!.stopped)
        assertEquals(1_000_000, full.status!!.progressPpm)

        val episode = File(destination, "${root.name}/episode.bin")
        val damaged = episode.readBytes().also { for (i in 5 * 65536 until 6 * 65536) it[i] = 0 }
        episode.writeBytes(damaged)
        val partial = local.recheckStopped(job.infoHash)
        assertEquals(partial.toString(), LocalCommandOutcome.VERIFIED, partial.outcome)
        assertTrue(partial.toString(), partial.status!!.stopped)
        assertTrue(partial.toString(), partial.status!!.progressPpm in 1 until 1_000_000)
        Thread.sleep(2_000)
        val idle = requireNotNull(local.status(job.infoHash))
        assertTrue("still stopped: $idle", idle.stopped)
        assertEquals("no payload downloaded while stopped", 0L, idle.payloadDownloadedBytes)
        assertArrayEquals("recheck never writes payload", damaged, episode.readBytes())

        assertEquals(LocalCommandOutcome.VERIFIED, local.resume(job.infoHash).outcome)
        waitFor { local.status(job.infoHash)?.progressPpm == 1_000_000 }
        assertTrue("native cache flush before readback",
            local.flushCompletedForLibraryRead(job.infoHash, timeoutMs = 30_000L))
        assertArrayEquals("Start repairs the damaged piece", payload, episode.readBytes())
        Thread.sleep(1_500)
        assertFalse("Start after a recheck keeps running", local.status(job.infoHash)!!.stopped)
    }

    /** WORK-QUEUE R3: a stopped checked Add over exact-path partial data verifies and stays stopped. */
    @Test fun stoppedCheckedAddOverPartialDataVerifiesWithoutDownloading() {
        val seedParent = File(work, "partial-seed").apply { mkdirs() }
        val root = File(seedParent, "Chunks partial fixture").apply { mkdirs() }
        val payload = ByteArray(1_300_000).also { Random(11).nextBytes(it) }
        File(root, "episode.bin").writeBytes(payload)
        val torrent = TorrentBuilder().path(root).pieceSize(64 * 1024).generate().entry().bencode()
        val seeder = engine()
        val seed = (seeder.addStopped(torrent, seedParent) as LocalAddResult.AddedStopped).status
        seeder.resume(seed.infoHash)
        waitFor { seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING }

        // Ten whole valid pieces already at the exact torrent path, nothing else.
        val destination = File(work, "partial-destination").apply { mkdirs() }
        val episode = File(destination, "${root.name}/episode.bin").apply { parentFile!!.mkdirs() }
        val kept = payload.copyOf(10 * 65536)
        episode.writeBytes(kept)
        val local = engine()
        val job = (local.addStoppedChecked(torrent, destination,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort()))) as LocalAddResult.AddedStopped).status
        assertTrue(local.beginStoppedVerification(job.infoHash))
        waitFor { local.status(job.infoHash)?.let { it.stopped && !it.stopsAfterCheck &&
            it.state != LocalTransferState.CHECKING } == true }
        assertEquals(10L * 65536, requireNotNull(local.status(job.infoHash)).doneBytes)
        Thread.sleep(2_000)
        val idle = requireNotNull(local.status(job.infoHash))
        assertTrue("still stopped: $idle", idle.stopped)
        assertEquals("no payload downloaded while stopped", 0L, idle.payloadDownloadedBytes)
        assertArrayEquals("verification never writes payload", kept, episode.readBytes())

        assertEquals(LocalCommandOutcome.VERIFIED, local.resume(job.infoHash).outcome)
        waitFor { local.status(job.infoHash)?.progressPpm == 1_000_000 }
        assertTrue(local.flushCompletedForLibraryRead(job.infoHash, timeoutMs = 30_000L))
        assertArrayEquals("Start fetches only the missing tail", payload, episode.readBytes())
        assertTrue("verified pieces were not downloaded again",
            local.status(job.infoHash)!!.payloadDownloadedBytes <= payload.size - 10L * 65536 + 65536)
    }

    /** A Start-off magnet over exact-path partial data is verified when its metadata arrives and stays stopped. */
    @Test fun stoppedMagnetOverPartialDataVerifiesWhenMetadataArrives() {
        val seedParent = File(work, "magnet-seed").apply { mkdirs() }
        val root = File(seedParent, "Chunks magnet partial fixture").apply { mkdirs() }
        val payload = ByteArray(1_300_000).also { Random(13).nextBytes(it) }
        File(root, "episode.bin").writeBytes(payload)
        val torrent = TorrentBuilder().path(root).pieceSize(64 * 1024).generate().entry().bencode()
        val seeder = engine()
        val seed = (seeder.addStopped(torrent, seedParent) as LocalAddResult.AddedStopped).status
        seeder.resume(seed.infoHash)
        waitFor { seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING }

        val destination = File(work, "magnet-destination").apply { mkdirs() }
        val episode = File(destination, "${root.name}/episode.bin").apply { parentFile!!.mkdirs() }
        val kept = payload.copyOf(10 * 65536)
        episode.writeBytes(kept)
        val local = engine()
        assertTrue(local.addMagnet("magnet:?xt=urn:btih:" + seed.infoHash, destination,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort())),
            autoStartAfterMetadata = false) is LocalAddResult.FetchingMetadata)
        waitFor { local.status(seed.infoHash)?.let {
            it.metadataKnown && it.stopped && !it.stopsAfterCheck && it.state != LocalTransferState.CHECKING &&
                it.doneBytes == 10L * 65536 } == true }
        Thread.sleep(2_000)
        val idle = requireNotNull(local.status(seed.infoHash))
        assertTrue("still stopped: $idle", idle.stopped)
        assertEquals("no payload downloaded while stopped", 0L, idle.payloadDownloadedBytes)
        assertArrayEquals("verification never writes payload", kept, episode.readBytes())
    }
}
