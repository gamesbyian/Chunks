package io.github.gamesbyian.chunks.engine

import java.io.File
import java.net.InetSocketAddress
import java.util.Random
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder

/**
 * Real libtorrent sessions transferring real bytes over loopback BitTorrent
 * (no trackers, DHT, LSD or port mapping; nothing leaves the machine).
 */
class LocalTorrentEngineTest {
    @get:Rule val temp = TemporaryFolder()
    private val engines = ArrayList<LocalTorrentEngine>()

    @Before fun native() = DesktopLibtorrent.ensureLoaded()

    @After fun stop() { engines.forEach { it.close() } }

    private fun engine(): LocalTorrentEngine = LocalTorrentEngine(LocalEngineConfig(
        listenInterfaces = "127.0.0.1:0", allowMultipleConnectionsPerIp = true
    )).also { it.start(); engines += it }

    private class Fixture(val root: File, val torrent: ByteArray, val files: Map<String, ByteArray>)

    private fun fixture(): Fixture {
        val parent = temp.newFolder("seed")
        val root = File(parent, "Chunks public fixture").apply { mkdirs() }
        val random = Random(42)
        val files = linkedMapOf(
            "episode.bin" to ByteArray(1_300_000).also { random.nextBytes(it) },
            "extras/notes.txt" to "Locally generated test payload".toByteArray()
        )
        for ((name, bytes) in files) File(root, name).apply { parentFile.mkdirs() }.writeBytes(bytes)
        val torrent = TorrentBuilder().path(root).pieceSize(64 * 1024).generate().entry().bencode()
        return Fixture(root, torrent, files)
    }

    private fun waitFor(timeoutMs: Long = 60_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for engine state")
            Thread.sleep(100)
        }
    }

    private fun added(result: LocalAddResult): LocalTransferStatus {
        assertTrue(result.toString(), result is LocalAddResult.AddedStopped)
        return (result as LocalAddResult.AddedStopped).status
    }

    @Test fun downloadsRealBytesFromALoopbackSeedAndThenSeeds() {
        val fixture = fixture()
        val seeder = engine()
        val seeded = added(seeder.addStopped(fixture.torrent, fixture.root.parentFile))
        assertTrue(seeded.stopped)
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(seeded.infoHash).outcome)
        waitFor { seeder.status(seeded.infoHash)?.state == LocalTransferState.SEEDING }
        assertTrue(seeder.listenPort() > 0)

        val destination = temp.newFolder("chosen destination")
        val leecher = engine()
        val leeching = added(leecher.addStopped(fixture.torrent, destination,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort()))))
        assertEquals(seeded.infoHash, leeching.infoHash)
        assertEquals("Chunks public fixture", leeching.name)
        assertEquals(0, leeching.progressPpm)
        assertEquals(LocalCommandOutcome.VERIFIED, leecher.resume(leeching.infoHash).outcome)
        waitFor {
            val state = leecher.status(leeching.infoHash)?.state
            state == LocalTransferState.SEEDING || state == LocalTransferState.FINISHED
        }

        for ((name, bytes) in fixture.files)
            assertArrayEquals(name, bytes, File(destination, "Chunks public fixture/$name").readBytes())
        val done = requireNotNull(leecher.status(leeching.infoHash))
        assertEquals(1_000_000, done.progressPpm)
        assertEquals(fixture.files.values.sumOf { it.size.toLong() }, done.doneBytes)
        // Session counters are refreshed on libtorrent's stats tick, not instantly.
        waitFor { (leecher.status(leeching.infoHash)?.payloadDownloadedBytes ?: 0) >= done.doneBytes }
        waitFor { (seeder.status(seeded.infoHash)?.payloadUploadedBytes ?: 0) >= done.doneBytes }
    }

    @Test fun stoppedAddWritesNothingAndPauseResumeReadBack() {
        val fixture = fixture()
        val destination = temp.newFolder("dest")
        val engine = engine()
        val status = added(engine.addStopped(fixture.torrent, destination))
        Thread.sleep(1_000)
        val still = requireNotNull(engine.status(status.infoHash))
        assertTrue(still.stopped)
        assertEquals(0L, still.payloadDownloadedBytes)
        assertEquals(destination.absolutePath, still.savePath)
        val resumed = engine.resume(status.infoHash)
        assertEquals(LocalCommandOutcome.VERIFIED, resumed.outcome)
        assertFalse(resumed.status!!.stopped)
        val paused = engine.pause(status.infoHash)
        assertEquals(LocalCommandOutcome.VERIFIED, paused.outcome)
        assertTrue(paused.status!!.stopped)
        assertEquals(LocalCommandOutcome.NOT_FOUND, engine.pause("b".repeat(40)).outcome)
    }

    @Test fun refusesDuplicatesInvalidMetadataAndUnchosenFolders() {
        val fixture = fixture()
        val engine = engine()
        val destination = temp.newFolder("dest")
        added(engine.addStopped(fixture.torrent, destination))
        assertTrue(engine.addStopped(fixture.torrent, destination) is LocalAddResult.Refused)
        assertTrue(engine.addStopped("not bencode".toByteArray(), destination) is LocalAddResult.Refused)
        assertTrue(engine.addStopped(fixture.torrent, File(destination, "missing")) is LocalAddResult.Refused)
        assertTrue(engine.addStopped(fixture.torrent, File("relative")) is LocalAddResult.Refused)
        assertEquals(1, engine.list().size)
    }

    @Test fun sessionOnlyRemovalKeepsPayloadBytes() {
        val fixture = fixture()
        val engine = engine()
        val status = added(engine.addStopped(fixture.torrent, fixture.root.parentFile))
        engine.resume(status.infoHash)
        waitFor { engine.status(status.infoHash)?.state == LocalTransferState.SEEDING }
        assertTrue(engine.removeKeepingData(status.infoHash.uppercase()))
        assertNull(engine.status(status.infoHash))
        assertFalse(engine.removeKeepingData(status.infoHash))
        Thread.sleep(500)
        for ((name, bytes) in fixture.files) assertArrayEquals(bytes, File(fixture.root, name).readBytes())
    }

    @Test fun rejectsMalformedIdentities() {
        val engine = engine()
        try {
            engine.status("../../etc")
            fail("expected refusal")
        } catch (_: IllegalArgumentException) { }
        assertNull(engine.status("a".repeat(40)))
    }

    private fun persistentEngine(state: File) = LocalTorrentEngine(LocalEngineConfig(
        listenInterfaces = "127.0.0.1:0", allowMultipleConnectionsPerIp = true, stateDirectory = state
    )).also { it.start(); engines += it }

    @Test fun completedTransferSurvivesRestartAndReturnsStopped() {
        val fixture = fixture()
        val seeder = engine()
        val seed = added(seeder.addStopped(fixture.torrent, fixture.root.parentFile))
        seeder.resume(seed.infoHash)
        waitFor { seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING }

        val state = temp.newFolder("engine-state")
        val destination = temp.newFolder("persistent destination")
        val first = persistentEngine(state)
        val leech = added(first.addStopped(fixture.torrent, destination,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort()))))
        assertTrue(File(state, leech.infoHash + ".resume").isFile)
        first.resume(leech.infoHash)
        waitFor { first.status(leech.infoHash)?.progressPpm == 1_000_000 }
        assertEquals(1, first.saveResumeData())
        first.close()

        val second = persistentEngine(state)
        assertTrue(second.restoreProblems.isEmpty())
        val restored = requireNotNull(second.status(leech.infoHash))
        assertTrue("Restored transfers must not start on their own", restored.stopped)
        assertEquals(destination.absolutePath, restored.savePath)
        waitFor { second.status(leech.infoHash)?.progressPpm == 1_000_000 }
        for ((name, bytes) in fixture.files)
            assertArrayEquals(bytes, File(destination, "Chunks public fixture/$name").readBytes())

        assertTrue(second.removeKeepingData(leech.infoHash))
        assertFalse(File(state, leech.infoHash + ".resume").exists())
        assertTrue(File(destination, "Chunks public fixture/episode.bin").isFile)
    }

    @Test fun unusableResumeFilesAreReportedAndLeftUntouched() {
        val fixture = fixture()
        val state = temp.newFolder("engine-state")
        val good = persistentEngine(state)
        val status = added(good.addStopped(fixture.torrent, temp.newFolder("d")))
        good.close()
        // A valid resume file stored under the wrong identity, and garbage bytes.
        val wrongName = File(state, "c".repeat(40) + ".resume")
        File(state, status.infoHash + ".resume").copyTo(wrongName)
        val garbage = File(state, "d".repeat(40) + ".resume").apply { writeText("not bencode") }
        File(state, "notes.txt").writeText("ignored")

        val restarted = persistentEngine(state)
        assertEquals(listOf(status.infoHash), restarted.list().map { it.infoHash })
        assertEquals(setOf(wrongName.name, garbage.name), restarted.restoreProblems.toSet())
        assertTrue(wrongName.isFile && garbage.isFile)
        assertEquals("not bencode", garbage.readText())
    }

    @Test fun magnetFetchesOnlyMetadataFromAPeerThenStopsUntilStarted() {
        val fixture = fixture()
        val seeder = engine()
        val seed = added(seeder.addStopped(fixture.torrent, fixture.root.parentFile))
        seeder.resume(seed.infoHash)
        waitFor { seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING }

        val destination = temp.newFolder("magnet destination")
        val leecher = engine()
        val magnet = "magnet:?xt=urn:btih:${seed.infoHash.uppercase()}&dn=Chunks%20public%20fixture"
        val result = leecher.addMagnet(magnet, destination, listOf(InetSocketAddress("127.0.0.1", seeder.listenPort())))
        assertTrue(result.toString(), result is LocalAddResult.FetchingMetadata)
        waitFor {
            val status = leecher.status(seed.infoHash)
            status != null && status.metadataKnown && status.stopped
        }
        val stopped = requireNotNull(leecher.status(seed.infoHash))
        assertEquals("Chunks public fixture", stopped.name)
        assertEquals(fixture.files.values.sumOf { it.size.toLong() }, stopped.wantedBytes)
        // Upload mode requests no payload pieces before the user presses Start.
        assertEquals(0L, stopped.doneBytes)
        assertEquals(0L, stopped.payloadDownloadedBytes)

        assertEquals(LocalCommandOutcome.VERIFIED, leecher.resume(seed.infoHash).outcome)
        waitFor { leecher.status(seed.infoHash)?.progressPpm == 1_000_000 }
        assertArrayEquals(fixture.files.getValue("episode.bin"),
            File(destination, "Chunks public fixture/episode.bin").readBytes())
    }

    @Test fun refusesMagnetsWithoutExactlyOneV1Identity() {
        val engine = engine()
        val destination = temp.newFolder("d")
        for (bad in listOf("magnet:?dn=x", "https://example.org", "magnet:?xt=urn:btih:abc",
            "magnet:?xt=urn:btih:${"a".repeat(40)}&xt=urn:btih:${"b".repeat(40)}",
            "magnet:?xt=urn:btmh:1220${"c".repeat(64)}"))
            assertTrue(bad, engine.addMagnet(bad, destination) is LocalAddResult.Refused)
        assertTrue(engine.list().isEmpty())
    }

    @Test fun dhtIsOffByDefaultAndTogglesOnlyOnRequest() {
        val engine = engine()
        assertFalse(engine.isDhtEnabled())
        assertTrue(engine.setDhtEnabled(true))
        assertTrue(engine.isDhtEnabled())
        assertFalse(engine.setDhtEnabled(false))
        assertFalse(engine.isDhtEnabled())
    }
}
