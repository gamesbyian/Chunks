package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.ForeignPartialAddPlan
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.PayloadLinkCount
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

    /** all-time payload counters refresh on libtorrent's second tick; an immediate read can be 0. */
    private fun settledDownloaded(engine: LocalTorrentEngine, hash: String): Long {
        var last = -1L; var stableSince = System.currentTimeMillis()
        val end = stableSince + 15_000
        while (System.currentTimeMillis() < end) {
            val now = requireNotNull(engine.status(hash)).payloadDownloadedBytes
            if (now != last) { last = now; stableSince = System.currentTimeMillis() }
            else if (now > 0 && System.currentTimeMillis() - stableSince >= 2_500) return now
            Thread.sleep(100)
        }
        return last
    }

    /**
     * FOREIGN-PARTIAL-TORRENT-RECOVERY P1 on the Android native library and kernel:
     * qBittorrent's interrupted mixed state (first file already final, second still `.!qB`,
     * third absent) is offered, adopted by rename only, verified by libtorrent and finished
     * from a loopback seeder. Also proves the Android `Os.lstat` link count is readable, so
     * ordinary single-link partials are not mistaken for shared inodes.
     */
    @Test fun foreignQbittorrentMixedPartialIsAdoptedVerifiedAndFinished() {
        val seedParent = File(work, "foreign-seed").apply { mkdirs() }
        val root = File(seedParent, "Album").apply { mkdirs() }
        val rnd = Random(17)
        val a = ByteArray(100_000).also(rnd::nextBytes)
        val b = ByteArray(150_000).also(rnd::nextBytes)
        val c = ByteArray(90_000).also(rnd::nextBytes)
        File(root, "01.flac").writeBytes(a); File(root, "02.flac").writeBytes(b); File(root, "03.flac").writeBytes(c)
        val pieceSize = 32 * 1024
        val torrent = TorrentBuilder().path(root).pieceSize(pieceSize).generate().entry().bencode()
        val seeder = engine()
        val seed = (seeder.addStopped(torrent, seedParent) as LocalAddResult.AddedStopped).status
        seeder.resume(seed.infoHash)
        waitFor { seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING }

        val destination = File(work, "foreign-destination").apply { mkdirs() }
        File(destination, "Album").mkdirs()
        File(destination, "Album/01.flac").writeBytes(a)
        val partialFile = File(destination, "Album/02.flac.!qB").apply { writeBytes(b.copyOf(120_000)) }
        assertEquals("Android lstat link count of an ordinary partial",
            1, PayloadLinkCount.of(partialFile.toPath()))

        val offer = requireNotNull(ForeignPartialAddPlan.offer(torrent, destination))
        assertEquals(listOf("Album/02.flac"), offer.reusable.map { it.declaredPath })
        assertEquals(0, offer.linkedFiles)
        val adoption = ForeignPartialAddPlan.adoptReviewed(torrent, destination, offer)
        assertTrue(adoption.toString(), adoption is ForeignPartialAddPlan.Adoption.Renamed)
        assertFalse(partialFile.exists())

        val local = engine()
        val job = (local.addStoppedChecked(torrent, destination,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort()))) as LocalAddResult.AddedStopped).status
        assertTrue(local.beginStoppedVerification(job.infoHash))
        waitFor { local.status(job.infoHash)?.let { it.stopped && !it.stopsAfterCheck &&
            it.state != LocalTransferState.CHECKING } == true }
        val verified = requireNotNull(local.status(job.infoHash)).doneBytes
        val total = (a.size + b.size + c.size).toLong()
        assertTrue("whole verified pieces of 01 + adopted 02 are counted: $verified",
            verified >= ((a.size + 120_000L) / pieceSize - 1) * pieceSize && verified <= a.size + 120_000L)
        assertEquals("no payload downloaded while stopped", 0L, requireNotNull(local.status(job.infoHash)).payloadDownloadedBytes)

        assertEquals(LocalCommandOutcome.VERIFIED, local.resume(job.infoHash).outcome)
        waitFor { local.status(job.infoHash)?.progressPpm == 1_000_000 }
        assertTrue(local.flushCompletedForLibraryRead(job.infoHash, timeoutMs = 30_000L))
        assertArrayEquals(a, File(destination, "Album/01.flac").readBytes())
        assertArrayEquals(b, File(destination, "Album/02.flac").readBytes())
        assertArrayEquals(c, File(destination, "Album/03.flac").readBytes())
        val downloaded = settledDownloaded(local, job.infoHash)
        assertTrue("verified pieces not downloaded again: $downloaded",
            downloaded <= total - verified + 2L * pieceSize && downloaded >= total - (a.size + 120_000L) - pieceSize)
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
        val downloaded = settledDownloaded(local, job.infoHash)
        assertTrue("verified pieces were not downloaded again: $downloaded",
            downloaded <= payload.size - 10L * 65536 + 65536)
        assertTrue("the missing tail was actually fetched: $downloaded",
            downloaded >= payload.size - 10L * 65536 - 65536)
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
        try {
            waitFor { local.status(seed.infoHash)?.let {
                it.metadataKnown && it.stopped && !it.stopsAfterCheck &&
                    it.state != LocalTransferState.CHECKING &&
                    it.doneBytes == 10L * 65536 } == true }
        } catch (timeout: AssertionError) {
            // Bounded non-sensitive evidence. Do not print hash, file names,
            // destination paths, trackers or raw native error text in CI logs.
            val observed = runCatching { local.status(seed.infoHash) }.getOrNull()
            val seedObserved = runCatching { seeder.status(seed.infoHash) }.getOrNull()
            val pieces = observed?.doneBytes?.div(65_536)
            fail("stopped-magnet timeout: handle=${observed != null}" +
                " metadata=${observed?.metadataKnown}" +
                " stopped=${observed?.stopped}" +
                " stopsAfterCheck=${observed?.stopsAfterCheck}" +
                " checking=${observed?.state == LocalTransferState.CHECKING}" +
                " valid64KiBpieces=${pieces ?: -1}" +
                " peers=${observed?.peers ?: -1}" +
                " errorPresent=${observed?.error != null}" +
                " seedStarted=${seedObserved?.stopped == false}" +
                " seedPeers=${seedObserved?.peers ?: -1}")
        }
        Thread.sleep(2_000)
        val idle = requireNotNull(local.status(seed.infoHash))
        assertTrue("still stopped: $idle", idle.stopped)
        assertEquals("no payload downloaded while stopped", 0L, idle.payloadDownloadedBytes)
        assertArrayEquals("verification never writes payload", kept, episode.readBytes())
    }
}
