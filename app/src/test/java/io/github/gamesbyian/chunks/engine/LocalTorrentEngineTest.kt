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
}
