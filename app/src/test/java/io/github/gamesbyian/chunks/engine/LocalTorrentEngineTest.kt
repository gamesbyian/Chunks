package io.github.gamesbyian.chunks.engine

import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
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

    @Test fun checkedTorrentRefusesForeignPayloadPlantedAfterAddBeforeFirstStart() {
        val fixture = fixture()
        val destination = temp.newFolder("checked-first-start-race")
        val target = engine()
        val added = added(target.addStoppedChecked(fixture.torrent, destination))
        val foreign = File(destination, "Chunks public fixture/episode.bin")
        assertTrue(foreign.parentFile.isDirectory || foreign.parentFile.mkdirs())
        val original = ByteArray(fixture.files.getValue("episode.bin").size)
            .also { Random(568).nextBytes(it) }
        foreign.writeBytes(original)

        val refused = target.resume(added.infoHash)
        assertEquals(LocalCommandOutcome.REFUSED, refused.outcome)
        val blocked = requireNotNull(refused.status)
        assertTrue("Refused Start must leave the handle stopped", blocked.stopped)
        assertTrue("A user-visible cause must survive readback",
            blocked.error.orEmpty().contains("since Add"))
        Thread.sleep(500)
        assertArrayEquals("Checked Start must not overwrite foreign bytes",
            original, foreign.readBytes())
        assertFalse("Refused Start cannot create another unowned payload",
            File(destination, "Chunks public fixture/extras/notes.txt").exists())

        // The same job can be started after the test-owned collision is removed.
        assertTrue(foreign.delete())
        assertEquals(LocalCommandOutcome.VERIFIED, target.resume(added.infoHash).outcome)
        assertNull("Cleared admission must remove the prior refusal",
            target.status(added.infoHash)?.error)
    }

    @Test fun checkedDifferentInfohashesCannotReserveOneStillEmptyNativePayload() {
        val fixture = fixture()
        // Two distinct torrent identities with IDENTICAL relative output
        // paths. No disk file yet exists, so a file-existence survey alone
        // would approve both jobs before either native writer is started.
        val otherParent = temp.newFolder("second-seed")
        val otherRoot = File(otherParent, "Chunks public fixture").apply { mkdirs() }
        File(otherRoot, "episode.bin").writeBytes(
            ByteArray(1_300_000).also { Random(569).nextBytes(it) })
        File(otherRoot, "extras/notes.txt").apply {
            parentFile.mkdirs()
            writeText("Different bytes for a different exact infohash")
        }
        val secondTorrent = TorrentBuilder().path(otherRoot)
            .pieceSize(64 * 1024).generate().entry().bencode()
        val dest = temp.newFolder("shared-stopped-target")
        val native = engine()
        val first = added(native.addStoppedChecked(fixture.torrent, dest))
        val refused = native.addStoppedChecked(secondTorrent, dest)
        assertTrue("A second checked job must not claim the same future writer paths: $refused",
            refused is LocalAddResult.Refused)
        assertTrue((refused as LocalAddResult.Refused).reason.contains("reserves"))
        assertEquals("Refused Add may not register a second native handle", 1,
            native.list().size)
        assertTrue(native.status(first.infoHash)?.stopped == true)
        assertFalse(File(dest, "Chunks public fixture/episode.bin").exists())

        // libtorrent removal is non-blocking. Even after the job disappears
        // from the visible inventory, queued native writes may retain its
        // handle. Do not let a different torrent claim that name in-session.
        assertTrue(native.removeKeepingData(first.infoHash))
        assertNull(native.status(first.infoHash))
        assertTrue(native.addStoppedChecked(secondTorrent, dest) is LocalAddResult.Refused)
        assertTrue(native.list().isEmpty())
        assertFalse(File(dest, "Chunks public fixture/episode.bin").exists())
    }

    @Test fun nativeRemoveTombstoneCannotBeEvadedThroughRawAdd() {
        val fixture = fixture()
        val root = temp.newFolder("removed-tombstone-raw-destination")
        val native = engine()
        val checked = added(native.addStoppedChecked(fixture.torrent, root))
        assertTrue(checked.stopped)
        assertTrue(native.removeKeepingData(checked.infoHash))
        assertNull(native.status(checked.infoHash))
        val bypass = native.addStopped(fixture.torrent, root)
        assertTrue("A raw Add must not resurrect an asynchronously removed native writer: $bypass",
            bypass is LocalAddResult.Refused)
        assertTrue((bypass as LocalAddResult.Refused).reason.contains("reserves"))
        assertTrue(native.list().isEmpty())
        assertFalse(File(root, "Chunks public fixture/episode.bin").exists())
    }

    @Test fun missingAdmissionReceiptNeverSilentlyUpgradesRestoredJobToReviewedSeed() {
        val fixture = fixture()
        val state = temp.newFolder("unknown-admission-state")
        val destination = temp.newFolder("unknown-admission-destination")
        val first = persistentEngine(state)
        val stopped = added(first.addStopped(fixture.torrent, destination))
        assertTrue(stopped.stopped)
        val receipt = File(state, "${stopped.infoHash}.admission")
        assertTrue("All restorable Adds must persist an admission type", receipt.isFile)
        first.close()

        // Simulate an old/corrupt/lost private provenance record, not a
        // permission to infer ownership from a resumed name and file size.
        assertTrue("Only remove our test-owned admission record", receipt.delete())
        val file = File(destination, "Chunks public fixture/episode.bin")
        assertTrue(file.parentFile.mkdirs() || file.parentFile.isDirectory)
        val foreign = ByteArray(fixture.files.getValue("episode.bin").size).also {
            Random(5793L).nextBytes(it)
        }
        file.writeBytes(foreign)
        val resumed = persistentEngine(state)
        assertTrue(resumed.restoreProblems.isEmpty())
        val status = requireNotNull(resumed.status(stopped.infoHash))
        assertTrue(status.stopped)
        assertTrue(status.error.orEmpty().contains("review"))
        assertEquals(LocalCommandOutcome.REFUSED, resumed.resume(stopped.infoHash).outcome)
        assertArrayEquals("Unknown Add origin must not overwrite foreign data",
            foreign, file.readBytes())
    }

    @Test fun restoredStoppedJobExcludesDifferentCheckedHashBeforeEitherStarts() {
        val firstFixture = fixture()
        val competingFixture = conflictingFixture()
        val state = temp.newFolder("restored-reservation-state")
        val root = temp.newFolder("restored-reservation-root")
        val original = persistentEngine(state)
        val first = added(original.addStoppedChecked(firstFixture.torrent, root))
        assertTrue(first.stopped)
        val firstPath = File(root, "Chunks public fixture/episode.bin")
        assertFalse(firstPath.exists())
        assertTrue(File(state, "${first.infoHash}.resume").isFile)
        original.close()

        val restored = persistentEngine(state)
        assertTrue(restored.restoreProblems.isEmpty())
        assertTrue(requireNotNull(restored.status(first.infoHash)).stopped)
        assertFalse(firstPath.exists())
        val blocked = restored.addStoppedChecked(competingFixture.torrent, root)
        assertTrue("Restored stopped destination must already be reserved: $blocked",
            blocked is LocalAddResult.Refused)
        assertTrue((blocked as LocalAddResult.Refused).reason.contains("reserves"))
        assertEquals("No second native handle must be registered", 1, restored.list().size)
        assertFalse("Both stopped native jobs must leave payload absent", firstPath.exists())
    }

    @Test fun reviewedExistingSeedRetainsNativeStartAfterProcessRestoration() {
        val fixture = fixture()
        val state = temp.newFolder("reviewed-existing-seed-resume-state")
        val source = File(fixture.root, "episode.bin")
        val expected = source.readBytes()
        val original = persistentEngine(state)
        val reviewed = added(original.addStopped(fixture.torrent, fixture.root.parentFile))
        assertEquals(LocalAdmissionProvenance.Mode.REVIEWED_EXISTING,
            LocalAdmissionProvenance.read(state, reviewed.infoHash))
        assertTrue(reviewed.stopped)
        original.close()
        val restored = persistentEngine(state)
        assertTrue(restored.restoreProblems.isEmpty())
        assertTrue(requireNotNull(restored.status(reviewed.infoHash)).stopped)
        // Valid reviewed source data can exist even when native fileProgress
        // reports zero until recheck. Reservations prevent competing writers;
        // they must not reinterpret previously reviewed seed bytes as foreign.
        assertEquals(LocalCommandOutcome.VERIFIED,
            restored.resume(reviewed.infoHash).outcome)
        waitFor { restored.status(reviewed.infoHash)?.state == LocalTransferState.SEEDING }
        assertArrayEquals(expected, source.readBytes())
        assertEquals(LocalCommandOutcome.VERIFIED,
            restored.pause(reviewed.infoHash).outcome)
    }

    @Test fun distinctRawReviewedAddsCannotClaimOneStillEmptyNativeDestination() {
        val firstFixture = fixture()
        val secondFixture = conflictingFixture()
        val root = temp.newFolder("dual-raw-empty-destination")
        val engine = engine()
        val first = added(engine.addStopped(firstFixture.torrent, root))
        assertTrue(first.stopped)
        assertFalse(File(root, "Chunks public fixture/episode.bin").exists())
        val refused = engine.addStopped(secondFixture.torrent, root)
        assertTrue("A second raw reviewed Add may not share the first writer path: $refused",
            refused is LocalAddResult.Refused)
        assertTrue((refused as LocalAddResult.Refused).reason.contains("reserves"))
        assertEquals(1, engine.list().size)
        assertFalse("No rejected Add may materialize a native payload",
            File(root, "Chunks public fixture/episode.bin").exists())
        val independentRoot = temp.newFolder("independent-raw-destination")
        val independent = added(engine.addStopped(secondFixture.torrent, independentRoot))
        assertTrue(independent.stopped)
        assertEquals(2, engine.list().size)
    }

    @Test fun rawReviewedAddCannotBypassDifferentCheckedHashPathReservation() {
        val firstFixture = fixture()
        val secondFixture = conflictingFixture()
        val root = temp.newFolder("checked-versus-raw-empty-destination")
        val engine = engine()
        val checked = added(engine.addStoppedChecked(firstFixture.torrent, root))
        assertTrue(checked.stopped)
        val blocked = engine.addStopped(secondFixture.torrent, root)
        assertTrue("Reviewed raw admission still cannot take another checked job's paths: $blocked",
            blocked is LocalAddResult.Refused)
        assertTrue((blocked as LocalAddResult.Refused).reason.contains("reserves"))
        assertEquals(1, engine.list().size)
        assertFalse(File(root, "Chunks public fixture/episode.bin").exists())
    }

    @Test fun competingReviewedExistingRestoresCannotBypassRejectedPathClaimAtStart() {
        val left = fixture()
        val right = conflictingFixture()
        val root = temp.newFolder("reviewed-restored-collision-root")
        val stateA = temp.newFolder("reviewed-restored-collision-state-a")
        val stateB = temp.newFolder("reviewed-restored-collision-state-b")
        // Each independently reviewed native Add is legal in its original
        // process, but two saved sessions can later be combined into one.
        // Neither stopped Add is permitted to create payload bytes.
        val originA = persistentEngine(stateA)
        val first = added(originA.addStopped(left.torrent, root))
        val originB = persistentEngine(stateB)
        val second = added(originB.addStopped(right.torrent, root))
        assertTrue(first.infoHash != second.infoHash)
        assertEquals(LocalAdmissionProvenance.Mode.REVIEWED_EXISTING,
            LocalAdmissionProvenance.read(stateA, first.infoHash))
        assertEquals(LocalAdmissionProvenance.Mode.REVIEWED_EXISTING,
            LocalAdmissionProvenance.read(stateB, second.infoHash))
        originA.close()
        originB.close()
        for (suffix in listOf(".resume", ".admission")) {
            File(stateB, second.infoHash + suffix)
                .copyTo(File(stateA, second.infoHash + suffix))
        }
        val foreign = File(root, "Chunks public fixture/episode.bin")
        assertFalse("No test must materialize payload during stopped Add", foreign.exists())

        val restored = persistentEngine(stateA)
        assertTrue("Both exact-hash native resumes must remain observable",
            restored.restoreProblems.isEmpty())
        val records = restored.list()
        assertEquals(2, records.size)
        assertTrue(records.all { it.stopped })
        val conflicting = requireNotNull(records.singleOrNull {
            it.error.orEmpty().contains("Restored path admission requires review")
        }) { "Exactly one restored reviewed-existing job must have a path reservation refusal" }
        val refused = restored.resume(conflicting.infoHash)
        assertEquals("A display-only restore problem cannot permit native Start",
            LocalCommandOutcome.REFUSED, refused.outcome)
        assertTrue(requireNotNull(refused.status).stopped)
        assertTrue(refused.status?.error.orEmpty().contains("Restored path admission"))
        assertFalse("The conflicting restored Start may not create or replace payload",
            foreign.exists())

        val winner = records.single { it.infoHash != conflicting.infoHash }
        assertEquals("The nonconflicting reviewed-existing job remains operable",
            LocalCommandOutcome.VERIFIED, restored.resume(winner.infoHash).outcome)
        assertEquals(LocalCommandOutcome.VERIFIED, restored.pause(winner.infoHash).outcome)
    }

    @Test fun restoredCheckedStoppedAddRefusesForeignFileBeforeFirstNativeStart() {
        val sourceParent = temp.newFolder("restore-admission-seed")
        val source = File(sourceParent, "Restore admission fixture").apply { mkdirs() }
        val expected = ByteArray(384 * 1024).also { Random(5683L).nextBytes(it) }
        File(source, "payload.bin").writeBytes(expected)
        val metainfo = TorrentBuilder().path(source).pieceSize(32 * 1024)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()
        val seed = engine()
        val seeded = added(seed.addStopped(metainfo, sourceParent))
        assertEquals(LocalCommandOutcome.VERIFIED, seed.resume(seeded.infoHash).outcome)
        waitFor { seed.status(seeded.infoHash)?.state == LocalTransferState.SEEDING }

        val state = temp.newFolder("restore-admission-state")
        val target = temp.newFolder("restore-admission-target")
        val original = persistentEngine(state)
        val transfer = added(original.addStoppedChecked(metainfo, target))
        assertTrue("Checked stopped Add must persist a resume record",
            File(state, "${transfer.infoHash}.resume").isFile)
        assertEquals("A checked native Add must never be restored as reviewed existing data",
            LocalAdmissionProvenance.Mode.CHECKED,
            LocalAdmissionProvenance.read(state, transfer.infoHash))
        assertTrue(original.status(transfer.infoHash)?.stopped == true)
        original.close()

        val foreignPath = File(target, "Restore admission fixture/payload.bin")
        assertTrue(foreignPath.parentFile.mkdirs() || foreignPath.parentFile.isDirectory)
        val foreignBytes = ByteArray(expected.size).also { Random(5684L).nextBytes(it) }
        foreignPath.writeBytes(foreignBytes)
        val restored = persistentEngine(state)
        assertTrue(restored.restoreProblems.isEmpty())
        assertTrue(requireNotNull(restored.status(transfer.infoHash)).stopped)
        val refused = restored.resume(transfer.infoHash)
        assertEquals("First Start after restore must not trust a foreign file",
            LocalCommandOutcome.REFUSED, refused.outcome)
        assertTrue(requireNotNull(refused.status).stopped)
        assertTrue(refused.status?.error.orEmpty().contains("no verified native bytes"))
        assertArrayEquals("Restored checked job must not replace planted foreign bytes",
            foreignBytes, foreignPath.readBytes())

        assertTrue("Delete only the deliberately planted test file", foreignPath.delete())
        assertTrue("Connect real loopback peer only after removing the foreign fixture",
            restored.connectPeer(transfer.infoHash,
                InetSocketAddress("127.0.0.1", seed.listenPort())))
        assertEquals(LocalCommandOutcome.VERIFIED, restored.resume(transfer.infoHash).outcome)
        requireFlushed(restored, transfer.infoHash)
        assertArrayEquals(expected, foreignPath.readBytes())
        assertArrayEquals(expected, File(source, "payload.bin").readBytes())
    }

    @Test fun activeCheckedPriorityWritersRefuseNewForeignSkippedFileBeforeNativeWrite() {
        val fixture = fixture()
        val root = temp.newFolder("active-checked-priority-destination")
        val receiver = engine()
        val checked = added(receiver.addStoppedChecked(fixture.torrent, root))
        val files = requireNotNull(receiver.files(checked.infoHash))
        val skipped = files.single { it.path.endsWith("/extras/notes.txt") }
        assertEquals(LocalCommandOutcome.VERIFIED,
            receiver.setFileWanted(checked.infoHash, skipped.index, false))
        assertEquals(LocalCommandOutcome.VERIFIED,
            receiver.resume(checked.infoHash).outcome)

        val foreign = File(root, "Chunks public fixture/extras/notes.txt")
        assertTrue(foreign.parentFile.mkdirs() || foreign.parentFile.isDirectory)
        val planted = "Unrelated local app data: not torrent payload".toByteArray()
        foreign.writeBytes(planted)

        val observed = requireNotNull(receiver.files(checked.infoHash))
        val episode = observed.single { it.path.endsWith("/episode.bin") }
        val policy = FinishInOrderPlanner.policyFor(
            FinishInOrderPlanner.Mode.BALANCED, observed, observed.map { it.index })
        val record = FinishInOrderRecord(policy)
        assertEquals("Finish in Order must not bypass a blocked checked path",
            LocalFileBatchOutcome.STALE_REVIEW,
            receiver.applyFinishInOrder(checked.infoHash, record,
                listOf(FinishInOrderPlanner.Change(episode.index, episode.priority, 7))))
        assertEquals(episode.priority,
            requireNotNull(receiver.files(checked.infoHash))
                .single { it.index == episode.index }.priority)
        assertEquals(LocalCommandOutcome.REFUSED,
            receiver.setFileWanted(checked.infoHash, skipped.index, true))
        assertTrue("The user must see an explicit safety refusal",
            requireNotNull(receiver.status(checked.infoHash)).error.orEmpty()
                .contains("no verified native bytes"))
        assertArrayEquals("Neither active native-priority control may clobber unrelated bytes",
            planted, foreign.readBytes())

        assertEquals(LocalCommandOutcome.VERIFIED, receiver.pause(checked.infoHash).outcome)
        assertEquals(LocalCommandOutcome.REFUSED,
            receiver.setFileWanted(checked.infoHash, skipped.index, true))
        assertArrayEquals(planted, foreign.readBytes())
        assertEquals(LocalCommandOutcome.REFUSED, receiver.resume(checked.infoHash).outcome)
        assertArrayEquals("Stop, selection and refused Start preserve foreign bytes",
            planted, foreign.readBytes())
    }

    @Test fun checkedPostStopRestartRefusesForeignSkippedFileThenDownloadsAfterClear() {
        val sourceParent = temp.newFolder("checked-post-stop-source")
        val sourceRoot = File(sourceParent, "Stopped checked fixture").apply { mkdirs() }
        val first = ByteArray(512 * 1024).also { Random(5681L).nextBytes(it) }
        val delayed = ByteArray(768 * 1024).also { Random(5682L).nextBytes(it) }
        File(sourceRoot, "initial.bin").writeBytes(first)
        File(sourceRoot, "delayed.bin").writeBytes(delayed)
        val metainfo = TorrentBuilder().path(sourceRoot).pieceSize(32 * 1024)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()

        val seeder = engine()
        val seeded = added(seeder.addStopped(metainfo, sourceParent))
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(seeded.infoHash).outcome)
        waitFor { seeder.status(seeded.infoHash)?.state == LocalTransferState.SEEDING }

        val root = temp.newFolder("post-stop-checked-dest")
        val leecher = engine()
        // A peer-free first Start makes the active-file-priority collision
        // deterministic and avoids waiting for intentionally skipped data.
        val transfer = added(leecher.addStoppedChecked(metainfo, root))
        val skipped = requireNotNull(leecher.files(transfer.infoHash))
            .single { it.path.endsWith("/delayed.bin") }
        assertEquals(LocalCommandOutcome.VERIFIED,
            leecher.setFileWanted(transfer.infoHash, skipped.index, false))
        assertEquals(LocalCommandOutcome.VERIFIED, leecher.resume(transfer.infoHash).outcome)
        assertEquals(0L, requireNotNull(leecher.status(transfer.infoHash)).doneBytes)
        // Even without an explicit Stop/Start, unskipping can schedule disk
        // writes on an active native job. Reject foreign bytes BEFORE JNI.
        val activeForeign = File(root, "Stopped checked fixture/delayed.bin")
        assertFalse(activeForeign.exists())
        val activeBytes = ByteArray(delayed.size).also { Random(9982L).nextBytes(it) }
        assertTrue("Create only the disposable backing parent", activeForeign.parentFile.mkdirs() ||
            activeForeign.parentFile.isDirectory)
        activeForeign.writeBytes(activeBytes)
        val beforeOrder = requireNotNull(leecher.files(transfer.infoHash))
        val firstSelected = beforeOrder.single { it.path.endsWith("/initial.bin") }
        val orderRecord = FinishInOrderRecord(FinishInOrderPlanner.policyFor(
            FinishInOrderPlanner.Mode.BALANCED, beforeOrder, beforeOrder.map { it.index }))
        // Automatic Finish in Order is a separate JNI file-priority writer.
        // Its existing journal/planner preflight must not bypass the shared
        // native foreign-path gate, even when only a different file is targeted.
        assertEquals(LocalFileBatchOutcome.STALE_REVIEW,
            leecher.applyFinishInOrder(transfer.infoHash, orderRecord,
                listOf(FinishInOrderPlanner.Change(firstSelected.index,
                    firstSelected.priority, 7))))
        assertEquals(firstSelected.priority, requireNotNull(leecher.files(transfer.infoHash))
            .single { it.index == firstSelected.index }.priority)
        assertTrue("A planner stale-review safety refusal must be explained on the transfer",
            requireNotNull(leecher.status(transfer.infoHash)).error.orEmpty()
                .contains("no verified native bytes"))
        assertEquals(LocalCommandOutcome.REFUSED,
            leecher.setFileWanted(transfer.infoHash, skipped.index, true))
        assertArrayEquals("Active file selection cannot clobber planted bytes",
            activeBytes, activeForeign.readBytes())
        assertTrue(activeForeign.delete())
        assertEquals(LocalCommandOutcome.VERIFIED, leecher.pause(transfer.infoHash).outcome)

        val foreign = File(root, "Stopped checked fixture/delayed.bin")
        assertFalse("Skipped file had no verified bytes and may not be claimed",
            foreign.exists())
        val unrelated = ByteArray(delayed.size).also { Random(9878L).nextBytes(it) }
        foreign.writeBytes(unrelated)
        assertEquals(LocalCommandOutcome.REFUSED,
            leecher.setFileWanted(transfer.infoHash, skipped.index, true))
        val refused = leecher.resume(transfer.infoHash)
        assertEquals(LocalCommandOutcome.REFUSED, refused.outcome)
        assertTrue("Native handle stays stopped on refused restart",
            requireNotNull(refused.status).stopped)
        assertTrue("User must see a specific no-verified-bytes reason",
            refused.status?.error.orEmpty().contains("no verified native bytes"))
        assertArrayEquals("The checked restart must preserve foreign bytes",
            unrelated, foreign.readBytes())

        assertTrue("Only remove the fixture we planted", foreign.delete())
        // A trackerless native session that previously finished with skipped
        // files may have no peer rediscovery. Use a fresh verified checked Add
        // into the now-clean test-owned destination as the positive control.
        // This must never imply the refused original native handle auto-retried.
        leecher.close()
        val clean = engine()
        val cleanAdded = added(clean.addStoppedChecked(metainfo, root,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort()))))
        assertEquals(LocalCommandOutcome.VERIFIED, clean.resume(cleanAdded.infoHash).outcome)
        requireFlushed(clean, cleanAdded.infoHash)
        assertArrayEquals("Fresh reviewed native Add receives real torrent bytes",
            delayed, foreign.readBytes())
        assertArrayEquals(first, File(root, "Stopped checked fixture/initial.bin").readBytes())
    }

    @Test fun missingAdmissionDirectoryRefusesBeforeRegisteringNativeHandle() {
        val fixture = fixture()
        val state = temp.newFolder("early-admission-state")
        val moved = File(temp.root, "early-admission-preserved")
        val instance = persistentEngine(state)
        assertTrue(state.renameTo(moved))
        state.writeText("private state disappeared before Add")
        val destination = temp.newFolder("early-admission-destination")
        val refused = instance.addStoppedChecked(fixture.torrent, destination)
        assertTrue("Unreadable admission cannot register native work: $refused",
            refused is LocalAddResult.Refused)
        assertTrue("A pre-admission refusal cannot create a native handle",
            instance.list().isEmpty())
        assertEquals("private state disappeared before Add", state.readText())
        assertTrue(state.delete())
        assertTrue(moved.renameTo(state))
        // An early failure must release uncommitted in-session path claims.
        val retried = instance.addStoppedChecked(fixture.torrent, destination)
        assertTrue("After storage recovery, clean Add must be possible: $retried",
            retried is LocalAddResult.AddedStopped)
        val saved = (retried as LocalAddResult.AddedStopped).status
        assertTrue(saved.stopped)
        assertTrue(instance.removeKeepingData(saved.infoHash))
    }

    @Test fun checkedCompletedRestartRefusesSymlinkReplacedVerifiedPayload() {
        val fixture = fixture()
        val seeder = engine()
        val seeded = added(seeder.addStopped(fixture.torrent, fixture.root.parentFile))
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(seeded.infoHash).outcome)
        waitFor { seeder.status(seeded.infoHash)?.state == LocalTransferState.SEEDING }
        val root = temp.newFolder("checked-restart-symlink")
        val receiver = engine()
        val checked = added(receiver.addStoppedChecked(fixture.torrent, root,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort()))))
        assertEquals(LocalCommandOutcome.VERIFIED, receiver.resume(checked.infoHash).outcome)
        requireFlushed(receiver, checked.infoHash)
        assertEquals(LocalCommandOutcome.VERIFIED, receiver.pause(checked.infoHash).outcome)

        val payload = File(root, "Chunks public fixture/episode.bin")
        assertTrue("Only the test-owned completed payload may be replaced",
            payload.delete())
        val external = temp.newFile("external-unowned").apply {
            writeText("external data must not be adopted")
        }
        Files.createSymbolicLink(payload.toPath(), external.toPath())
        val refused = receiver.resume(checked.infoHash)
        assertEquals(LocalCommandOutcome.REFUSED, refused.outcome)
        assertTrue(requireNotNull(refused.status).stopped)
        assertEquals("external data must not be adopted", external.readText())
        assertTrue(Files.isSymbolicLink(payload.toPath()))
    }

    @Test fun checkedPartialAddStillRequiresFreshSurveyBeforeFirstStart() {
        val fixture = fixture()
        val state = temp.newFolder("checked-partial-state")
        val moved = File(temp.root, "checked-state-still-preserved")
        val target = persistentEngine(state)
        // Trigger access loss only AFTER the native handle is registered,
        // preserving coverage of the partial-registration Start fence.
        target.beforeInitialResumeWriteForTest = {
            assertTrue(state.renameTo(moved))
            state.writeText("no longer a writable resume directory")
        }

        val destination = temp.newFolder("checked-partial-destination")
        try {
            target.addStoppedChecked(fixture.torrent, destination)
            fail("A failed initial resume write cannot report a complete Add")
        } catch (_: java.io.IOException) {
            // The native job was registered but its resume write failed.
            // A later UI Start must still have the original collision fence.
        }
        val partial = target.list().single()
        assertTrue(partial.stopped)
        val foreign = File(destination, "Chunks public fixture/episode.bin")
        assertTrue(foreign.parentFile.isDirectory || foreign.parentFile.mkdirs())
        val bytes = byteArrayOf(8, 6, 7, 5, 3, 0, 9)
        foreign.writeBytes(bytes)
        assertEquals(LocalCommandOutcome.REFUSED, target.resume(partial.infoHash).outcome)
        assertArrayEquals("Uncertain Add cannot bypass protection", bytes, foreign.readBytes())
    }

    @Test fun checkedTorrentRefusesSymlinkParentIntroducedAfterAdd() {
        val fixture = fixture()
        val destination = temp.newFolder("checked-symlink-start")
        val external = temp.newFolder("checked-symlink-target")
        val target = engine()
        val added = added(target.addStoppedChecked(fixture.torrent, destination))
        val link = File(destination, "Chunks public fixture")
        // Paused libtorrent versions may materialize an empty parent directory
        // before Start. Replace only our own disposable empty test directory.
        if (link.exists()) {
            assertTrue(link.isDirectory)
            assertTrue(link.list().orEmpty().isEmpty())
            assertTrue(link.delete())
        }
        Files.createSymbolicLink(link.toPath(), external.toPath())

        val refused = target.resume(added.infoHash)
        assertEquals(LocalCommandOutcome.REFUSED, refused.outcome)
        assertTrue(requireNotNull(refused.status).stopped)
        assertTrue(refused.status?.error.orEmpty().contains("Cannot verify"))
        assertTrue("External directory cannot receive payload", external.list().isEmpty())
        assertTrue("The planted link must not be deleted", Files.isSymbolicLink(link.toPath()))
    }

    @Test fun checkedTorrentFirstStartDownloadsAndHashesNormalBytes() {
        val fixture = fixture()
        val seeder = engine()
        val source = added(seeder.addStopped(fixture.torrent, fixture.root.parentFile))
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(source.infoHash).outcome)
        waitFor { seeder.status(source.infoHash)?.state == LocalTransferState.SEEDING }

        val destination = temp.newFolder("checked-normal-start")
        val leecher = engine()
        val checked = added(leecher.addStoppedChecked(fixture.torrent, destination,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort()))))
        assertEquals(LocalCommandOutcome.VERIFIED, leecher.resume(checked.infoHash).outcome)
        requireFlushed(leecher, checked.infoHash)
        for ((name, bytes) in fixture.files) {
            assertArrayEquals(name, bytes,
                File(destination, "Chunks public fixture/$name").readBytes())
        }
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

    @Test fun actualRemoveNeverUnlinksSubstitutedForeignAdmissionEntry() {
        val fixture = fixture()
        val state = temp.newFolder("remove-receipt-collision-state")
        val destination = temp.newFolder("remove-receipt-collision-dest")
        val instance = persistentEngine(state)
        val status = added(instance.addStoppedChecked(fixture.torrent, destination))
        val receipt = File(state, status.infoHash + ".admission")
        assertEquals(LocalAdmissionProvenance.Mode.CHECKED,
            LocalAdmissionProvenance.read(state, status.infoHash))
        val displaced = File(state, "owned-admission-displaced")
        assertTrue(receipt.renameTo(displaced))
        val foreign = temp.newFile("unrelated-removal-sentinel")
        val canary = ByteArray(4096) { (it * 31 + 17).toByte() }
        foreign.writeBytes(canary)
        Files.createLink(receipt.toPath(), foreign.toPath())
        assertTrue("Actual native Remove must still succeed without touching payload",
            instance.removeKeepingData(status.infoHash))
        assertNull(instance.status(status.infoHash))
        assertTrue("Remove cannot unlink an unowned final receipt entry",
            Files.exists(receipt.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isSameFile(receipt.toPath(), foreign.toPath()))
        assertArrayEquals(canary, receipt.readBytes())
        assertArrayEquals(canary, foreign.readBytes())
        assertTrue("Former immutable receipt must also remain untouched", displaced.isFile)
        assertNull("Foreign replacement cannot authorize resumed native admission",
            LocalAdmissionProvenance.read(state, status.infoHash))
        assertFalse("The ambiguous occupied receipt cannot be overwritten by a retry",
            LocalAdmissionProvenance.persist(state, status.infoHash,
                LocalAdmissionProvenance.Mode.CHECKED))
    }

    @Test fun lostResumeDirectoryDuringAddIsObservableWithoutDeletingPayload() {
        val fixture = fixture()
        val state = temp.newFolder("state-disappears-on-add")
        val moved = File(temp.root, "state-preserved-on-add")
        val instance = persistentEngine(state)
        // Keep the admission root valid until AFTER native registration.
        // This covers the more dangerous uncertain/partial-Add path, rather
        // than inadvertently stopping at the newer pre-admission refusal.
        instance.beforeInitialResumeWriteForTest = {
            assertTrue(state.renameTo(moved))
            state.writeText("temporary inaccessible state placeholder")
        }
        val destination = temp.newFolder("lost-resume-destination")
        try {
            instance.addStopped(fixture.torrent, destination)
            fail("Add without durable resume state must not be reported successful")
        } catch (_: java.io.IOException) {
            // A native handle may already have been registered. The caller
            // correctly treats this exception as uncertain and refreshes inventory.
        }
        val observed = instance.list().single()
        assertTrue("A partial Add must remain inspectable, not disappear", observed.stopped)
        assertEquals(destination.absolutePath, observed.savePath)
        assertEquals("temporary inaccessible state placeholder", state.readText())
        // Restore the original private storage directory before a confirmed,
        // session-only Remove. Neither path should ever delete payload bytes.
        assertTrue(state.delete())
        assertTrue(moved.renameTo(state))
        assertTrue(instance.removeKeepingData(observed.infoHash))
        assertTrue(instance.list().isEmpty())
        for ((name, bytes) in fixture.files)
            assertArrayEquals(bytes, File(fixture.root, name).readBytes())
    }

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

    @Test fun inaccessibleStatePathCannotStartNativeSessionAndMayBeRetriedSafely() {
        val state = File(temp.root, "state-path-is-a-file").apply {
            writeText("existing state sentinel")
        }
        val instance = LocalTorrentEngine(LocalEngineConfig(
            listenInterfaces = "127.0.0.1:0", stateDirectory = state
        ))
        engines += instance
        try {
            instance.start()
            fail("An invalid state directory must refuse startup")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("unavailable"))
        }
        assertEquals("existing state sentinel", state.readText())
        assertTrue(state.delete())
        assertTrue(state.mkdirs())
        // The failed attempt must not have started JNI or marked the engine
        // active. A retry on the SAME instance must now start normally.
        instance.start()
        assertTrue(instance.list().isEmpty())
        assertTrue(instance.restoreProblems.isEmpty())
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

    private fun conflictingFixture(): Fixture {
        val root = File(temp.newFolder("other-seed"), "Chunks public fixture")
            .apply { mkdirs() }
        val files = linkedMapOf(
            "episode.bin" to ByteArray(1_300_000).also { Random(873).nextBytes(it) },
            "extras/notes.txt" to "This different content yields another infohash".toByteArray()
        )
        for ((name, bytes) in files) {
            File(root, name).apply { parentFile.mkdirs() }.writeBytes(bytes)
        }
        return Fixture(root,
            TorrentBuilder().path(root).pieceSize(64 * 1024).generate().entry().bencode(),
            files)
    }

    @Test fun metadataReadyMagnetCannotAutoStartOverStoppedCheckedJobReservation() {
        val firstFixture = fixture()
        val other = conflictingFixture()
        val seeder = engine()
        val seeded = added(seeder.addStopped(other.torrent, other.root.parentFile))
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(seeded.infoHash).outcome)
        waitFor { seeder.status(seeded.infoHash)?.state == LocalTransferState.SEEDING }

        val target = engine()
        val destination = temp.newFolder("checked-versus-magnet")
        val existing = added(target.addStoppedChecked(firstFixture.torrent, destination))
        assertTrue(existing.stopped)
        assertFalse("The first job must have no backing bytes yet",
            File(destination, "Chunks public fixture/episode.bin").exists())
        val magnet = "magnet:?xt=urn:btih:" + seeded.infoHash
        assertTrue(target.addMagnet(magnet, destination,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort())),
            autoStartAfterMetadata = true) is LocalAddResult.FetchingMetadata)
        waitFor {
            target.status(seeded.infoHash)?.let { it.metadataKnown && it.stopped } == true
        }
        val blocked = requireNotNull(target.status(seeded.infoHash))
        assertTrue("Collision refusal cannot unset upload-only", blocked.uploadOnly)
        assertTrue("The second job must disclose the reservation collision",
            blocked.error.orEmpty().contains("reserves"))
        assertEquals(0L, blocked.payloadDownloadedBytes)
        Thread.sleep(500)
        assertFalse("No torrent may create competing backing bytes",
            File(destination, "Chunks public fixture/episode.bin").exists())
        assertFalse(File(destination, "Chunks public fixture/extras/notes.txt").exists())

        // The checked job's native Remove is asynchronous, so its tombstone
        // must continue to prohibit the second handle from starting.
        assertTrue(target.removeKeepingData(existing.infoHash))
        assertEquals(LocalCommandOutcome.REFUSED, target.resume(seeded.infoHash).outcome)
        assertFalse(File(destination, "Chunks public fixture/episode.bin").exists())
    }

    @Test fun metadataReadyStoppedMagnetReservesEmptyPathsBeforeAnotherCheckedAdd() {
        val firstFixture = fixture()
        val other = conflictingFixture()
        val seeder = engine()
        val seeded = added(seeder.addStopped(firstFixture.torrent, firstFixture.root.parentFile))
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(seeded.infoHash).outcome)
        waitFor { seeder.status(seeded.infoHash)?.state == LocalTransferState.SEEDING }

        val target = engine()
        val destination = temp.newFolder("magnet-versus-checked")
        assertTrue(target.addMagnet("magnet:?xt=urn:btih:" + seeded.infoHash,
            destination, listOf(InetSocketAddress("127.0.0.1", seeder.listenPort())))
            is LocalAddResult.FetchingMetadata)
        waitFor {
            target.status(seeded.infoHash)?.let { it.metadataKnown && it.stopped } == true
        }
        assertTrue(requireNotNull(target.status(seeded.infoHash)).uploadOnly)
        val refused = target.addStoppedChecked(other.torrent, destination)
        assertTrue("Metadata should reserve missing paths before checked Add: $refused",
            refused is LocalAddResult.Refused)
        assertTrue((refused as LocalAddResult.Refused).reason.contains("reserves"))
        assertEquals(1, target.list().size)
        assertFalse(File(destination, "Chunks public fixture/episode.bin").exists())

        // The same magnet's explicit later Start is allowed to revalidate its
        // *exact* reservation instead of treating itself as a foreign writer.
        assertEquals(LocalCommandOutcome.VERIFIED, target.resume(seeded.infoHash).outcome)
        requireFlushed(target, seeded.infoHash)
        for ((name, bytes) in firstFixture.files) {
            assertArrayEquals(name, bytes,
                File(destination, "Chunks public fixture/$name").readBytes())
        }
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
        // A zero-byte payload pathname is still a mutation of an unowned
        // destination. UPLOAD_MODE must keep every metadata-only file absent,
        // even after native asynchronous file-layout processing settles.
        Thread.sleep(1_000)
        for (name in fixture.files.keys) {
            val path = File(destination, "Chunks public fixture/$name")
            assertFalse("Magnet metadata alone created a payload path: $name", path.exists())
        }

        assertEquals(LocalCommandOutcome.VERIFIED, leecher.resume(seed.infoHash).outcome)
        waitFor { leecher.status(seed.infoHash)?.progressPpm == 1_000_000 }
        requireFlushed(leecher, seed.infoHash)
        assertArrayEquals(fixture.files.getValue("episode.bin"),
            File(destination, "Chunks public fixture/episode.bin").readBytes())
    }

    @Test fun explicitStartBeforeQueuedMetadataCallbackDoesNotResurveyItsOwnPayload() {
        val fixture = fixture()
        val seeder = engine()
        val seed = added(seeder.addStopped(fixture.torrent, fixture.root.parentFile))
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(seed.infoHash).outcome)
        waitFor { seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING }
        val destination = temp.newFolder("queued-metadata-start")
        val leecher = engine()
        assertTrue(leecher.addMagnet("magnet:?xt=urn:btih:" + seed.infoHash, destination)
            is LocalAddResult.FetchingMetadata)
        // Delay Java alert dispatch using the existing handle monitor while
        // the real C++ session discovers metadata and writes payload. No test
        // hook or alternate disk backend changes the production behaviour.
        val handles = LocalTorrentEngine::class.java.getDeclaredField("handles")
            .apply { isAccessible = true }.get(leecher)
        synchronized(leecher) {
            synchronized(handles) {
                assertTrue(leecher.connectPeer(seed.infoHash,
                    InetSocketAddress("127.0.0.1", seeder.listenPort())))
                waitFor(20_000) {
                    leecher.status(seed.infoHash)?.metadataKnown == true &&
                        Thread.getAllStackTraces().any { (thread, stack) ->
                            thread.state == Thread.State.BLOCKED && stack.any {
                                it.className.startsWith(LocalTorrentEngine::class.java.name) &&
                                    it.methodName == "alert"
                            }
                        }
                }
                assertEquals(LocalCommandOutcome.VERIFIED, leecher.resume(seed.infoHash).outcome)
                waitFor(20_000) { leecher.status(seed.infoHash)?.progressPpm == 1_000_000 }
                assertTrue("Payload must exist before the queued callback is released",
                    File(destination, "Chunks public fixture/episode.bin").isFile)
            }
        }
        // CACHE_FLUSHED travels through the same real dispatcher, proving the
        // earlier queued metadata callback has run before final assertions.
        requireFlushed(leecher, seed.infoHash)
        val afterCallback = requireNotNull(leecher.status(seed.infoHash))
        assertNull("Our own approved payload must not become a foreign collision", afterCallback.error)
        assertFalse("The delayed callback must not undo explicit Start", afterCallback.stopped)
        for ((name, bytes) in fixture.files)
            assertArrayEquals(name, bytes, File(destination, "Chunks public fixture/$name").readBytes())
    }

    @Test fun resolvedMagnetIsPersistedImmediatelyAndRestoresStopped() {
        val fixture = fixture()
        val seeder = engine()
        val seed = added(seeder.addStopped(fixture.torrent, fixture.root.parentFile))
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(seed.infoHash).outcome)
        waitFor { seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING }

        val state = temp.newFolder("magnet immediate resume state")
        val destination = temp.newFolder("magnet immediate resume destination")
        val first = persistentEngine(state)
        val magnet = "magnet:?xt=urn:btih:${seed.infoHash}"
        assertTrue(first.addMagnet(magnet, destination,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort()))) is LocalAddResult.FetchingMetadata)
        assertEquals("Magnet admission must remain checked across restoration",
            LocalAdmissionProvenance.Mode.CHECKED,
            LocalAdmissionProvenance.read(state, seed.infoHash))
        waitFor {
            val current = first.status(seed.infoHash)
            current != null && current.metadataKnown && current.stopped
        }
        // Do not call saveResumeData(), wait for a periodic UI poll or close:
        // the metadata callback itself must independently persist a usable
        // resume witness before the engine is shut down.
        waitFor(15_000L) {
            File(state, seed.infoHash + ".resume").let { it.isFile && it.length() > 0L }
        }
        first.close()
        val second = persistentEngine(state)
        assertTrue(second.restoreProblems.isEmpty())
        val restored = requireNotNull(second.status(seed.infoHash))
        assertTrue("Metadata-ready magnet must restore stopped", restored.stopped)
        assertTrue(restored.metadataKnown)
        assertEquals(destination.absolutePath, restored.savePath)
    }

    @Test fun metadataReadyMagnetWithForeignFileRefusesPayloadUntilCollisionCleared() {
        val fixture = fixture()
        val seeder = engine()
        val seed = added(seeder.addStopped(fixture.torrent, fixture.root.parentFile))
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(seed.infoHash).outcome)
        waitFor { seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING }

        val destination = temp.newFolder("magnet-collision-destination")
        val collision = File(destination, "Chunks public fixture/episode.bin")
        assertTrue(collision.parentFile.mkdirs())
        val unrelated = ByteArray(fixture.files.getValue("episode.bin").size)
        java.util.Random(568L).nextBytes(unrelated)
        collision.writeBytes(unrelated)
        val magnet = "magnet:?xt=urn:btih:" + seed.infoHash

        val state = temp.newFolder("magnet-blocked-resume-state")
        val leecher = persistentEngine(state)
        assertTrue(leecher.addMagnet(magnet, destination,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort())),
            autoStartAfterMetadata = true) is LocalAddResult.FetchingMetadata)
        waitFor {
            leecher.status(seed.infoHash)?.let { it.metadataKnown && it.stopped } == true
        }
        val blocked = requireNotNull(leecher.status(seed.infoHash))
        assertTrue("Blocked magnet must remain upload-only", blocked.uploadOnly)
        assertTrue("Collision must be visible", blocked.error.orEmpty().contains("Existing files"))
        assertArrayEquals("Early Start consent cannot overwrite unrelated bytes",
            unrelated, collision.readBytes())
        // The unoccupied sibling path must not be materialized during rejected
        // metadata-time auto-Start. Empty files are still unauthorized writes.
        Thread.sleep(500)
        assertFalse("Rejected magnet created an unowned sibling payload path",
            File(destination, "Chunks public fixture/extras/notes.txt").exists())
        // Narrow synthetic diagnostic: isolate a stale stored collision
        // witness from a genuinely missing native payload pathname. This
        // read-only survey must match the same destination used by Start.
        val beforeStart = requireNotNull(leecher.status(seed.infoHash))
        val observedSurvey = LocalPayloadCollisionSurvey.inspect(
            File(beforeStart.savePath),
            listOf("Chunks public fixture/episode.bin", "Chunks public fixture/extras/notes.txt")
        )
        println("CHUNKS_MAGNET_START_GATE metadata=${beforeStart.metadataKnown} " +
            "uploadOnly=${beforeStart.uploadOnly} stopped=${beforeStart.stopped} " +
            "foreignExists=${collision.isFile} survey=${observedSurvey::class.simpleName} " +
            "nativeSavePathMatches=${File(beforeStart.savePath).canonicalPath == destination.canonicalPath}")
        assertTrue("The same pathname must still have the foreign bytes before Start",
            collision.isFile && unrelated.contentEquals(collision.readBytes()))
        assertTrue("The production survey must still see the deliberately planted foreign file",
            observedSurvey is LocalPayloadCollisionSurvey.Result.ExistingPaths)
        assertEquals(LocalCommandOutcome.REFUSED, leecher.resume(seed.infoHash).outcome)
        assertArrayEquals(unrelated, collision.readBytes())

        // A blocked magnet must be restorable without making a previously
        // rejected payload collision writable after process restart.
        waitFor(15_000L) {
            File(state, seed.infoHash + ".resume").let { it.isFile && it.length() > 0L }
        }
        leecher.close()
        val restarted = persistentEngine(state)
        val paused = requireNotNull(restarted.status(seed.infoHash))
        assertTrue("Blocked magnet must restore stopped", paused.stopped)
        assertTrue("Persistent upload-only gate must survive a process restart", paused.uploadOnly)
        assertTrue(paused.metadataKnown)
        assertEquals(LocalCommandOutcome.REFUSED, restarted.resume(seed.infoHash).outcome)
        assertArrayEquals("Failed re-Start after process death must not overwrite foreign bytes",
            unrelated, collision.readBytes())

        // Test-only recovery: remove our synthetic foreign fixture, then retry
        // Start against an independently observed clean path.
        assertTrue(collision.delete())
        assertEquals(LocalCommandOutcome.VERIFIED, restarted.resume(seed.infoHash).outcome)
        waitFor { restarted.status(seed.infoHash)?.progressPpm == 1_000_000 }
        requireFlushed(restarted, seed.infoHash)
        assertArrayEquals(fixture.files.getValue("episode.bin"), collision.readBytes())
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
