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

    /**
     * libtorrent's verified-piece progress is not proof that its asynchronous
     * disk cache is already readable by an independent filesystem consumer.
     * Every post-download byte-array assertion must follow the same exact-hash
     * CACHE_FLUSHED gate used by Android's SAF library-copy path.
     */
    private fun requireFlushed(engine: LocalTorrentEngine, infoHash: String) {
        waitFor {
            val state = engine.status(infoHash)
            state?.progressPpm == 1_000_000 &&
                state.state in setOf(LocalTransferState.FINISHED, LocalTransferState.SEEDING)
        }
        assertTrue("Native cache flush must finish before disk byte readback",
            engine.flushCompletedForLibraryRead(infoHash, timeoutMs = 30_000L))
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

        requireFlushed(leecher, leeching.infoHash)
        for ((name, bytes) in fixture.files)
            assertArrayEquals(name, bytes, File(destination, "Chunks public fixture/$name").readBytes())
        val done = requireNotNull(leecher.status(leeching.infoHash))
        assertEquals(1_000_000, done.progressPpm)
        assertEquals(fixture.files.values.sumOf { it.size.toLong() }, done.doneBytes)
        // Session counters are refreshed on libtorrent's stats tick, not instantly.
        waitFor { (leecher.status(leeching.infoHash)?.payloadDownloadedBytes ?: 0) >= done.doneBytes }
        waitFor { (seeder.status(seeded.infoHash)?.payloadUploadedBytes ?: 0) >= done.doneBytes }
    }

    /**
     * #568 feasibility evidence: libtorrent4j exposes a built-in POSIX disk
     * backend. This tests only ordinary seekable File paths in a disposable
     * native JVM fixture, NOT Android SAF, public Downloads or provider FDs.
     */
    @Test fun posixDiskIoDownloadsRealMultiFileBytesAndRestoresStopped() {
        val fixture = fixture()
        val seeder = engine()
        val source = added(seeder.addStopped(fixture.torrent, fixture.root.parentFile))
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(source.infoHash).outcome)
        waitFor { seeder.status(source.infoHash)?.state == LocalTransferState.SEEDING }

        val state = temp.newFolder("posix-resume-state")
        val destination = temp.newFolder("posix-destination")
        fun posixEngine(): LocalTorrentEngine = LocalTorrentEngine(LocalEngineConfig(
            listenInterfaces = "127.0.0.1:0",
            allowMultipleConnectionsPerIp = true,
            stateDirectory = state,
            diskIoMode = LocalDiskIoMode.POSIX
        )).also { it.start(); engines += it }

        val first = posixEngine()
        val target = added(first.addStopped(fixture.torrent, destination,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort()))))
        assertEquals(LocalCommandOutcome.VERIFIED, first.resume(target.infoHash).outcome)
        requireFlushed(first, target.infoHash)
        for ((name, bytes) in fixture.files) {
            assertArrayEquals(name, bytes, File(destination, "Chunks public fixture/$name").readBytes())
        }
        assertEquals(1_000_000, first.status(target.infoHash)?.progressPpm)
        assertEquals(1, first.saveResumeData())
        first.close()

        val reopened = posixEngine()
        assertTrue("No resume restore should fail", reopened.restoreProblems.isEmpty())
        val observed = requireNotNull(reopened.status(target.infoHash))
        assertTrue("Restored POSIX-backed torrent must be stopped", observed.stopped)
        assertEquals(destination.absolutePath, observed.savePath)
        waitFor { reopened.status(target.infoHash)?.progressPpm == 1_000_000 }
        for ((name, bytes) in fixture.files) {
            assertArrayEquals(name, bytes, File(destination, "Chunks public fixture/$name").readBytes())
        }
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
        requireFlushed(first, leech.infoHash)
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

    /**
     * A tracker-backed download stopped part-way keeps its verified pieces
     * across an engine restart, comes back stopped and silent, and after an
     * explicit Start finishes from where it was rather than starting over.
     * The leecher only learns the (throttled) seeder from the tracker.
     */
    @Test fun interruptedTrackerDownloadKeepsPiecesAcrossRestartAndFinishesAfterStart() {
        LoopbackTracker().use { tracker ->
            val parent = temp.newFolder("interrupted-seed")
            val root = File(parent, "Interrupted fixture").apply { mkdirs() }
            val payload = ByteArray(1_600_000).also { Random(11).nextBytes(it) }
            File(root, "payload.bin").writeBytes(payload)
            val pieceSize = 32 * 1024
            val torrent = TorrentBuilder().path(root).pieceSize(pieceSize)
                .addTracker(tracker.announceUrl).generate().entry().bencode()

            val seeder = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
                allowMultipleConnectionsPerIp = true, rateLimitLocalPeers = true)).also { it.start(); engines += it }
            seeder.setRateLimits(LocalRateLimits(uploadBytesPerSecond = 128 * 1024))
            val seed = added(seeder.addStopped(torrent, parent))
            assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(seed.infoHash).outcome)
            waitFor { seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING }
            waitFor(20_000) { tracker.announces.get() >= 1 }

            val state = temp.newFolder("interrupted-state")
            val destination = temp.newFolder("interrupted-destination")
            val first = persistentEngine(state)
            val hash = added(first.addStopped(torrent, destination)).infoHash
            assertEquals(LocalCommandOutcome.VERIFIED, first.resume(hash).outcome)
            waitFor(60_000) { (first.status(hash)?.doneBytes ?: 0) >= 640_000 }
            val stop = first.pause(hash)
            assertEquals(LocalCommandOutcome.VERIFIED, stop.outcome)
            val interruptedAt = stop.status!!.doneBytes
            assertTrue("interrupted at $interruptedAt", interruptedAt < payload.size)
            assertTrue(first.saveResumeData() >= 1)
            first.close()

            val second = persistentEngine(state)
            assertTrue(second.restoreProblems.isEmpty())
            waitFor { second.status(hash)?.state !in setOf(LocalTransferState.CHECKING, null) }
            val restored = requireNotNull(second.status(hash))
            assertTrue("Restored transfers must not start on their own", restored.stopped)
            // doneBytes at pause also counts blocks of partial pieces that may not
            // have been flushed before the resume data was written; those few
            // in-flight pieces can be lost, every verified piece must not be.
            assertTrue("restored ${restored.doneBytes} lost more than in-flight pieces of $interruptedAt",
                restored.doneBytes >= interruptedAt - 8L * pieceSize)
            // The byte counters are all-time totals carried in the resume file.
            Thread.sleep(1_500)
            val idle = requireNotNull(second.status(hash))
            assertTrue(idle.stopped)
            assertEquals("A stopped restored transfer downloads nothing",
                restored.payloadDownloadedBytes, idle.payloadDownloadedBytes)
            assertEquals(restored.doneBytes, idle.doneBytes)

            seeder.setRateLimits(LocalRateLimits())
            assertEquals(LocalCommandOutcome.VERIFIED, second.resume(hash).outcome)
            requireFlushed(second, hash)
            assertArrayEquals(payload, File(destination, "Interrupted fixture/payload.bin").readBytes())
            // Only the missing remainder (plus a few in-flight pieces) crosses the wire again.
            // All-time counters lag the stats tick; a later sample can only grow.
            Thread.sleep(3_000)
            val fetchedAgain = requireNotNull(second.status(hash)).payloadDownloadedBytes - idle.payloadDownloadedBytes
            assertTrue("re-downloaded $fetchedAgain after restoring ${restored.doneBytes}",
                fetchedAgain <= payload.size - restored.doneBytes + 8L * pieceSize)
        }
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
        requireFlushed(leecher, seed.infoHash)
        assertArrayEquals(fixture.files.getValue("episode.bin"),
            File(destination, "Chunks public fixture/episode.bin").readBytes())
    }

    @Test fun manualStartBeforeMagnetMetadataPersistsThroughDiscoveryAndDownloadsBytes() {
        val fixture = fixture()
        val seeder = engine()
        val seed = added(seeder.addStopped(fixture.torrent, fixture.root.parentFile))
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(seed.infoHash).outcome)
        waitFor { seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING }

        val destination = temp.newFolder("manual early magnet")
        val leecher = engine()
        // No initial peer or DHT means metadata cannot race ahead of manual
        // Start. Only after explicitly starting do we connect the test seeder.
        val magnet = "magnet:?xt=urn:btih:${seed.infoHash}"
        assertTrue(leecher.addMagnet(magnet, destination) is LocalAddResult.FetchingMetadata)
        val before = requireNotNull(leecher.status(seed.infoHash))
        assertFalse(before.metadataKnown)
        assertTrue(before.uploadOnly)
        val started = leecher.resume(seed.infoHash)
        assertEquals(LocalCommandOutcome.VERIFIED, started.outcome)
        assertFalse("Still metadata-only before a peer is introduced",
            requireNotNull(leecher.status(seed.infoHash)).metadataKnown)
        assertTrue(leecher.connectPeer(seed.infoHash, InetSocketAddress("127.0.0.1", seeder.listenPort())))
        waitFor {
            val state = leecher.status(seed.infoHash)
            state != null && state.metadataKnown && !state.stopped && !state.uploadOnly
        }
        waitFor { leecher.status(seed.infoHash)?.progressPpm == 1_000_000 }
        requireFlushed(leecher, seed.infoHash)
        for ((name, bytes) in fixture.files) {
            assertArrayEquals(name, bytes, File(destination, "Chunks public fixture/$name").readBytes())
        }
    }

    @Test fun optedInMagnetStartsPayloadAfterMetadataAndDownloadsExactBytes() {
        val fixture = fixture()
        val seeder = engine()
        val seeded = added(seeder.addStopped(fixture.torrent, fixture.root.parentFile))
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(seeded.infoHash).outcome)
        waitFor { seeder.status(seeded.infoHash)?.state == LocalTransferState.SEEDING }

        val destination = temp.newFolder("auto magnet destination")
        val leecher = engine()
        val magnet = "magnet:?xt=urn:btih:${seeded.infoHash}"
        val added = leecher.addMagnet(magnet, destination,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort())),
            autoStartAfterMetadata = true)
        assertTrue(added is LocalAddResult.FetchingMetadata)
        waitFor {
            val state = leecher.status(seeded.infoHash)
            state != null && state.metadataKnown && !state.stopped
        }
        waitFor { leecher.status(seeded.infoHash)?.progressPpm == 1_000_000 }
        requireFlushed(leecher, seeded.infoHash)
        assertArrayEquals(fixture.files.getValue("episode.bin"),
            File(destination, "Chunks public fixture/episode.bin").readBytes())
    }

    @Test fun legacyTrackerRepairVerifiesExactOriginalAndSurvivesRestartWithoutStarting() {
        val fixture = fixture()
        // libtorrent 2.1's TorrentBuilder defaults to hybrid v1/v2,
        // intentionally refused by the app's strict v1 review importer.
        // Generate a real v1-only definition for the exact user-source path.
        val originalV1 = TorrentBuilder().path(fixture.root).pieceSize(64 * 1024)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()
        val savedState = temp.newFolder("legacy-tracker-state")
        val downloadPlace = temp.newFolder("legacy-tracker-place")
        val engine = persistentEngine(savedState)
        val existing = added(engine.addStopped(originalV1, downloadPlace))
        val key = existing.infoHash
        assertTrue(requireNotNull(engine.status(key)).stopped)
        assertEquals(0, requireNotNull(engine.status(key)).trackers?.total)

        val announce = "http://127.0.0.1:1/announce"
        // Add announce at the root of the exact v1 torrent; keep the info
        // dictionary identical so its infohash (and downloaded data) match.
        val restoredSource = org.libtorrent4j.Entry.bdecode(originalV1)
        restoredSource.dictionary()["announce"] = org.libtorrent4j.Entry(announce)
        val repairedMetainfo = restoredSource.bencode()
        val fromSource = io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader.preview(repairedMetainfo)
        assertEquals(key, fromSource.infoHashV1)
        assertEquals(1, fromSource.trackerCount)

        assertEquals(LocalTrackerRepairOutcome.NOT_FOUND,
            engine.repairMissingTrackers("f".repeat(40), repairedMetainfo))
        val otherHash = originalV1.copyOf().apply { this[lastIndex - 8] = 'X'.code.toByte() }
        assertEquals(LocalTrackerRepairOutcome.REFUSED,
            engine.repairMissingTrackers(key, otherHash))
        assertEquals(LocalTrackerRepairOutcome.REFUSED,
            engine.repairMissingTrackers(key, originalV1))
        assertEquals(LocalTrackerRepairOutcome.VERIFIED,
            engine.repairMissingTrackers(key, repairedMetainfo))
        val after = requireNotNull(engine.status(key))
        assertTrue("Repair must not implicitly Start a legacy transfer", after.stopped)
        assertEquals(1, after.trackers?.total)
        assertEquals(downloadPlace.absolutePath, after.savePath)
        assertEquals(0L, after.payloadDownloadedBytes)
        assertEquals(LocalTrackerRepairOutcome.REFUSED,
            engine.repairMissingTrackers(key, repairedMetainfo))
        engine.close()

        val restored = persistentEngine(savedState)
        val reopened = requireNotNull(restored.status(key))
        assertTrue("Restored legacy transfer still requires manual Start", reopened.stopped)
        assertEquals(1, reopened.trackers?.total)
        assertEquals(downloadPlace.absolutePath, reopened.savePath)
        assertEquals(0L, reopened.payloadDownloadedBytes)
        assertFalse("Repair never creates payload bytes",
            File(downloadPlace, "Chunks public fixture/episode.bin").exists())
    }

    @Test fun legacyTrackerRepairRejectsRunningTorrentWithoutChangingItsTrackers() {
        val fixture = fixture()
        val originalV1 = TorrentBuilder().path(fixture.root).pieceSize(64 * 1024)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()
        val engine = persistentEngine(temp.newFolder("running-repair-state"))
        val original = added(engine.addStopped(originalV1, temp.newFolder("running-repair-place")))
        val announce = "https://tracker.example.invalid/announce"
        val withTracker = org.libtorrent4j.Entry.bdecode(originalV1)
        withTracker.dictionary()["announce"] = org.libtorrent4j.Entry(announce)
        val replacement = withTracker.bencode()
        assertEquals(LocalCommandOutcome.VERIFIED, engine.resume(original.infoHash).outcome)
        assertEquals(LocalTrackerRepairOutcome.REFUSED,
            engine.repairMissingTrackers(original.infoHash, replacement))
        assertEquals(0, engine.status(original.infoHash)?.trackers?.total)
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

    @Test fun speedLimitsStartUnlimitedAndReadBack() {
        val engine = engine()
        assertEquals(LocalRateLimits(0, 0), engine.rateLimits())
        val limited = LocalRateLimits(downloadBytesPerSecond = 512 * 1024, uploadBytesPerSecond = 64 * 1024)
        assertEquals(limited, engine.setRateLimits(limited))
        assertEquals(limited, engine.rateLimits())
        assertEquals(LocalRateLimits(), engine.setRateLimits(LocalRateLimits()))
        try {
            LocalRateLimits(uploadBytesPerSecond = -1)
            fail("expected refusal")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun uploadLimitThrottlesARealTransfer() {
        val fixture = fixture()
        val seeder = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
            allowMultipleConnectionsPerIp = true, rateLimitLocalPeers = true)).also { it.start(); engines += it }
        val limit = 128 * 1024
        assertEquals(limit, seeder.setRateLimits(LocalRateLimits(uploadBytesPerSecond = limit)).uploadBytesPerSecond)
        val seed = added(seeder.addStopped(fixture.torrent, fixture.root.parentFile))
        seeder.resume(seed.infoHash)
        waitFor { seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING }

        val leecher = engine()
        val destination = temp.newFolder("throttled")
        val leech = added(leecher.addStopped(fixture.torrent, destination,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort()))))
        val started = System.nanoTime()
        leecher.resume(leech.infoHash)
        waitFor(120_000) { leecher.status(leech.infoHash)?.progressPpm == 1_000_000 }
        val seconds = (System.nanoTime() - started) / 1e9
        val total = fixture.files.values.sumOf { it.size.toLong() }
        // ~1.3 MB at 128 KiB/s is about 10 s; unthrottled loopback takes well under 2 s.
        assertTrue("finished in $seconds s", seconds > total / limit.toDouble() * 0.6)
        requireFlushed(leecher, leech.infoHash)
        assertArrayEquals(fixture.files.getValue("episode.bin"),
            File(destination, "Chunks public fixture/episode.bin").readBytes())
    }

    @Test fun dhtIsOffByDefaultAndTogglesOnlyOnRequest() {
        val engine = engine()
        assertFalse(engine.isDhtEnabled())
        // DHT starts and stops on libtorrent's network thread; loaded CI runners need longer than 2 s.
        assertTrue(engine.setDhtEnabled(true, readbackTimeoutMs = 15_000))
        assertTrue(engine.isDhtEnabled())
        assertFalse(engine.setDhtEnabled(false, readbackTimeoutMs = 15_000))
        assertFalse(engine.isDhtEnabled())
    }

    @Test fun reviewedFilePageBatchUsesRealLibtorrentPrioritiesAndRejectsStaleInputs() {
        val fixture = fixture()
        val native = engine()
        val dest = temp.newFolder("batch-file-selection")
        val transfer = added(native.addStopped(fixture.torrent, dest))
        val before = requireNotNull(native.files(transfer.infoHash))
        assertEquals(2, before.size)
        assertTrue(before.all { it.wanted })
        assertEquals(LocalFileBatchOutcome.VERIFIED,
            native.setFilesWantedBatch(transfer.infoHash, before, false))
        val skipped = requireNotNull(native.files(transfer.infoHash))
        assertTrue(skipped.all { !it.wanted })
        assertEquals(LocalFileBatchOutcome.STALE_REVIEW,
            native.setFilesWantedBatch(transfer.infoHash, before, true))
        assertEquals(LocalFileBatchOutcome.UNCHANGED,
            native.setFilesWantedBatch(transfer.infoHash, skipped, false))
        assertEquals(LocalFileBatchOutcome.STALE_REVIEW,
            native.setFilesWantedBatch(transfer.infoHash,
                listOf(skipped.first().copy(path = "wrong-path"), skipped.last()), true))
        assertEquals(LocalFileBatchOutcome.VERIFIED,
            native.setFilesWantedBatch(transfer.infoHash, skipped, true))
        assertTrue(requireNotNull(native.files(transfer.infoHash)).all { it.wanted })
        assertEquals(LocalFileBatchOutcome.NOT_FOUND,
            native.setFilesWantedBatch("f".repeat(40), before, false))
        val state = requireNotNull(native.status(transfer.infoHash))
        assertTrue(state.stopped)
        assertEquals(0L, state.payloadDownloadedBytes)
    }

    @Test fun nativeLowNormalHighFilePrioritiesRequireExactFreshFileReview() {
        val fixture = fixture()
        val native = engine()
        val dest = temp.newFolder("file-scheduling")
        val transfer = added(native.addStopped(fixture.torrent, dest))
        val original = requireNotNull(native.files(transfer.infoHash)).single {
            it.path.endsWith("episode.bin")
        }
        assertEquals(4, original.priority)
        assertTrue(original.wanted)
        assertEquals(LocalFileBatchOutcome.VERIFIED,
            native.setFilePriority(transfer.infoHash, original, 7))
        val top = requireNotNull(native.files(transfer.infoHash)).single {
            it.index == original.index
        }
        assertEquals(7, top.priority)
        assertTrue(top.wanted)
        assertEquals(LocalFileBatchOutcome.STALE_REVIEW,
            native.setFilePriority(transfer.infoHash, original, 1))
        assertEquals(LocalFileBatchOutcome.STALE_REVIEW,
            native.setFilePriority(transfer.infoHash, top.copy(path = "wrong"), 1))
        assertEquals(LocalFileBatchOutcome.UNCHANGED,
            native.setFilePriority(transfer.infoHash, top, 7))
        assertEquals(LocalFileBatchOutcome.VERIFIED,
            native.setFilePriority(transfer.infoHash, top, 1))
        val low = requireNotNull(native.files(transfer.infoHash)).single {
            it.index == original.index
        }
        assertEquals(1, low.priority)
        assertEquals(LocalFileBatchOutcome.VERIFIED,
            native.setFilePriority(transfer.infoHash, low, 4))
        assertEquals(4, requireNotNull(native.files(transfer.infoHash)).single {
            it.index == original.index
        }.priority)
        assertEquals(LocalFileBatchOutcome.NOT_FOUND,
            native.setFilePriority("f".repeat(40), low, 7))
        try {
            native.setFilePriority(transfer.infoHash, low, 0)
            fail("Skipping needs a separate file selection control")
        } catch (_: IllegalArgumentException) { }
        val selected = requireNotNull(native.files(transfer.infoHash)).single {
            it.index == original.index
        }
        assertEquals(LocalCommandOutcome.VERIFIED,
            native.setFileWanted(transfer.infoHash, selected.index, false))
        val skipped = requireNotNull(native.files(transfer.infoHash)).single {
            it.index == original.index
        }
        assertEquals(0, skipped.priority)
        assertEquals(LocalFileBatchOutcome.STALE_REVIEW,
            native.setFilePriority(transfer.infoHash, skipped, 7))
        assertTrue(requireNotNull(native.status(transfer.infoHash)).stopped)
        assertEquals(0L, requireNotNull(native.status(transfer.infoHash)).payloadDownloadedBytes)
    }

    @Test fun reviewedNativeSchedulingPrioritySurvivesSavedEngineRestart() {
        val fixture = fixture()
        val state = temp.newFolder("file-priority-resume-state")
        val destination = temp.newFolder("file-priority-resume-place")
        val initial = persistentEngine(state)
        val transfer = added(initial.addStopped(fixture.torrent, destination))
        val original = requireNotNull(initial.files(transfer.infoHash)).single {
            it.path.endsWith("episode.bin")
        }
        assertEquals(LocalFileBatchOutcome.VERIFIED,
            initial.setFilePriority(transfer.infoHash, original, 7))
        assertEquals(1, initial.saveResumeData())
        initial.close()

        val afterRestart = persistentEngine(state)
        assertTrue(afterRestart.restoreProblems.isEmpty())
        val restored = requireNotNull(afterRestart.files(transfer.infoHash)).single {
            it.index == original.index
        }
        assertTrue(restored.wanted)
        assertEquals(7, restored.priority)
        val session = requireNotNull(afterRestart.status(transfer.infoHash))
        assertTrue("An explicit scheduling preference must never auto-start", session.stopped)
        assertEquals(destination.absolutePath, session.savePath)
        assertEquals(0L, session.payloadDownloadedBytes)
    }

    @Test fun skippedFileIsNotDownloadedUntilSelected() {
        val fixture = fixture()
        val seeder = engine()
        val seed = added(seeder.addStopped(fixture.torrent, fixture.root.parentFile))
        seeder.resume(seed.infoHash)
        waitFor { seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING }

        val destination = temp.newFolder("selective")
        val leecher = engine()
        val leech = added(leecher.addStopped(fixture.torrent, destination,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort()))))
        val files = requireNotNull(leecher.files(leech.infoHash))
        assertEquals(2, files.size)
        assertTrue(files.all { it.wanted && it.doneBytes == 0L })
        val big = files.single { it.path.endsWith("episode.bin") }
        assertEquals(1_300_000L, big.sizeBytes)
        assertEquals(LocalCommandOutcome.VERIFIED, leecher.setFileWanted(leech.infoHash, big.index, false))
        assertFalse(requireNotNull(leecher.files(leech.infoHash)).single { it.index == big.index }.wanted)

        leecher.resume(leech.infoHash)
        waitFor {
            leecher.files(leech.infoHash)?.single { it.path.endsWith("notes.txt") }?.let { it.doneBytes == it.sizeBytes } == true &&
                leecher.status(leech.infoHash)?.state == LocalTransferState.FINISHED
        }
        // Progress counts verified pieces; explicitly wait for stable disk readback.
        requireFlushed(leecher, leech.infoHash)
        val notes = File(destination, "Chunks public fixture/extras/notes.txt")
        waitFor { notes.isFile && notes.readBytes().contentEquals(fixture.files.getValue("extras/notes.txt")) }
        val skipped = requireNotNull(leecher.files(leech.infoHash)).single { it.index == big.index }
        assertTrue("Skipped file must not be fully downloaded", skipped.doneBytes < skipped.sizeBytes)

        assertEquals(LocalCommandOutcome.VERIFIED, leecher.setFileWanted(leech.infoHash, big.index, true))
        assertTrue(requireNotNull(leecher.files(leech.infoHash)).single { it.index == big.index }.wanted)

        // Kept partial data is completed by a later session (in-session resumption after
        // "finished" depends on peer rediscovery, which this tracker-less rig lacks).
        leecher.close()
        val resumed = engine()
        added(resumed.addStopped(fixture.torrent, destination, listOf(InetSocketAddress("127.0.0.1", seeder.listenPort()))))
        resumed.resume(leech.infoHash)
        waitFor { resumed.status(leech.infoHash)?.progressPpm == 1_000_000 }
        requireFlushed(resumed, leech.infoHash)
        assertArrayEquals(fixture.files.getValue("episode.bin"),
            File(destination, "Chunks public fixture/episode.bin").readBytes())
        assertEquals(LocalCommandOutcome.NOT_FOUND, leecher.setFileWanted("c".repeat(40), 0, true))
    }

    /**
     * Regression: libtorrent 2.1 no longer carries a .torrent's announce /
     * announce-list inside torrent_info, so an Add built from torrent_info
     * alone never contacted its trackers and could only find injected peers.
     * Here the leecher learns the seeder ONLY from the torrent's tracker.
     */
    @Test fun torrentFileTrackersAreAnnouncedSoPeersAreFoundWithoutInjection() {
        LoopbackTracker().use { tracker ->
            val parent = temp.newFolder("tracked-seed")
            val root = File(parent, "Tracked fixture").apply { mkdirs() }
            val payload = ByteArray(400_000).also { Random(7).nextBytes(it) }
            File(root, "payload.bin").writeBytes(payload)
            val torrent = TorrentBuilder().path(root).pieceSize(32 * 1024)
                .addTracker(tracker.announceUrl).generate().entry().bencode()

            val seeder = engine()
            val seed = added(seeder.addStopped(torrent, parent))
            assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(seed.infoHash).outcome)
            waitFor { seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING }
            waitFor(20_000) { tracker.announces.get() >= 1 }

            val state = temp.newFolder("tracked-state")
            val destination = temp.newFolder("tracked-destination")
            val leecher = persistentEngine(state)
            val leech = added(leecher.addStopped(torrent, destination))
            assertEquals(LocalCommandOutcome.VERIFIED, leecher.resume(leech.infoHash).outcome)
            requireFlushed(leecher, leech.infoHash)
            assertArrayEquals(payload, File(destination, "Tracked fixture/payload.bin").readBytes())
            assertTrue(leecher.saveResumeData() >= 1)
            leecher.close()

            // Trackers must also survive the resume file across a restart.
            val before = tracker.announces.get()
            val restarted = persistentEngine(state)
            assertTrue(restarted.restoreProblems.isEmpty())
            assertEquals(LocalCommandOutcome.VERIFIED, restarted.resume(leech.infoHash).outcome)
            waitFor(20_000) { tracker.announces.get() > before }
        }
    }

    @Test fun trackerTiersKeepAnnounceListOrderAndRejectUnsafeSchemes() {
        fun str(value: String) = "${value.length}:$value"
        fun list(vararg items: String) = "l" + items.joinToString("") + "e"
        val info = str("info") + "d" + str("length") + "i1e" + str("name") + str("x") +
            str("piece length") + "i16384e" + str("pieces") + str("01234567890123456789") + "e"
        fun parse(prefix: String) = engine().trackerTiers("d${prefix}${info}e".toByteArray())
        val tiered = parse(str("announce") + str("http://a/announce") + str("announce-list") + list(
            list(str("http://b/announce"), str("udp://c:80/anno")),
            list(str("http://b/announce"), str("file:///etc/pwd"), str("https://d/announce"))))
        assertEquals(listOf("http://b/announce", "udp://c:80/anno", "https://d/announce"), tiered.first)
        assertEquals(listOf(0, 0, 1), tiered.second)
        assertEquals(listOf("http://a/announce") to listOf(0),
            parse(str("announce") + str("http://a/announce")))
        assertEquals(emptyList<String>() to emptyList<Int>(), parse(""))
    }

    @Test fun trackerSummaryCountsWorkingTrackersWithoutExposingUrls() {
        LoopbackTracker().use { tracker ->
            val parent = temp.newFolder("summary-seed")
            val root = File(parent, "Summary fixture").apply { mkdirs() }
            File(root, "payload.bin").writeBytes(ByteArray(40_000).also { Random(3).nextBytes(it) })
            val torrent = TorrentBuilder().path(root).pieceSize(16 * 1024)
                .addTracker(tracker.announceUrl).generate().entry().bencode()
            val instance = engine()
            val status = added(instance.addStopped(torrent, parent))
            assertEquals(LocalTrackerSummary(1, 0, 0, 0), status.trackers)
            assertEquals(LocalCommandOutcome.VERIFIED, instance.resume(status.infoHash).outcome)
            waitFor(20_000) { instance.status(status.infoHash)?.trackers?.working == 1 }
            assertTrue(instance.status(status.infoHash).toString().contains("trackers=LocalTrackerSummary"))
            assertTrue("Status must never carry tracker URLs",
                !instance.status(status.infoHash).toString().contains("announce"))
        }
    }

    @Test fun exportedTorrentKeepsIdentityAndTrackersForServerHandoff() {
        val fixture = fixture()
        val tracked = TorrentBuilder().path(fixture.root).pieceSize(64 * 1024)
            .flags(TorrentBuilder.V1_ONLY).addTracker("https://tracker.example/announce")
            .generate().entry().bencode()
        val instance = engine()
        val status = added(instance.addStopped(tracked, fixture.root.parentFile))
        val exported = requireNotNull(instance.exportTorrentFile(status.infoHash))
        // The same strict reader the rTorrent Add review uses must accept it.
        val preview = io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader.preview(exported)
        assertEquals(status.infoHash, preview.infoHashV1)
        assertEquals(listOf("https://tracker.example/announce"), instance.trackerTiers(exported).first)
        assertTrue("Export must not change the transfer", instance.status(status.infoHash)!!.stopped)
        assertNull(instance.exportTorrentFile("d".repeat(40)))
    }

    @Test fun batchStartAndStopUseRealReadbackForEachSelectedTransfer() {
        val instance = engine()
        val ids = (1..2).map { n ->
            val parent = temp.newFolder("batch-$n")
            val root = File(parent, "Batch $n").apply { mkdirs() }
            File(root, "p.bin").writeBytes(ByteArray(20_000).also { Random(n.toLong()).nextBytes(it) })
            val torrent = TorrentBuilder().path(root).pieceSize(16 * 1024).generate().entry().bencode()
            added(instance.addStopped(torrent, parent)).infoHash
        }
        fun targets() = ids.map {
            io.github.gamesbyian.chunks.model.LocalBatchLifecycle.Target(it, instance.status(it)!!.stopped) }
        val started = io.github.gamesbyian.chunks.model.LocalBatchLifecycle.run(targets(), true) { h, s ->
            if (s) instance.resume(h).outcome else instance.pause(h).outcome }
        assertEquals(2, started.verified)
        assertTrue(ids.all { instance.status(it)?.stopped == false })
        val stopped = io.github.gamesbyian.chunks.model.LocalBatchLifecycle.run(targets(), false) { h, s ->
            if (s) instance.resume(h).outcome else instance.pause(h).outcome }
        assertEquals(2, stopped.verified)
        assertTrue(ids.all { instance.status(it)?.stopped == true })
    }
}
