package io.github.gamesbyian.chunks.engine

import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner.Change
import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner.Mode
import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner.Observed
import io.github.gamesbyian.chunks.engine.FinishInOrderRunner.Step
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder

class FinishInOrderRunnerTest {
    private val hash = "a".repeat(40)

    /** In-memory engine + storage that records every write in order. */
    private class FakePort(var files: MutableList<LocalTransferFile>) : FinishInOrderRunner.Port {
        val verified = HashMap<Int, Long>()
        val log = ArrayList<String>()
        var stored: FinishInOrderRecord? = null
        var saveWorks = true
        var outcome: (List<Change>) -> LocalFileBatchOutcome = { changes ->
            changes.forEach { c -> files.replaceAll { if (it.index == c.index) it.copy(priority = c.requestedPriority) else it } }
            LocalFileBatchOutcome.VERIFIED
        }
        override suspend fun observe(infoHash: String) =
            files.map { Observed(it, verified[it.index] ?: 0L) }
        override suspend fun apply(infoHash: String, record: FinishInOrderRecord, changes: List<Change>): LocalFileBatchOutcome {
            log += "apply:" + changes.joinToString { "${it.index}>${it.requestedPriority}" }
            assertEquals("journal must be durable before the native write", changes, stored?.pending)
            return outcome(changes)
        }
        override fun save(infoHash: String, record: FinishInOrderRecord): Boolean {
            if (!saveWorks) return false
            log += "save"; stored = record; return true
        }
    }

    private fun episodes(n: Int) = (1..n).map {
        LocalTransferFile(it * 3, "S/Show S01E0$it.mkv", 100, 0, true, 4)
    }.toMutableList()

    private fun record(port: FakePort, mode: Mode = Mode.BALANCED) = FinishInOrderRecord(
        FinishInOrderPlanner.policyFor(mode, port.files, port.files.map { it.index }))

    @Test fun journalsThenAppliesThenAdvancesAfterVerifiedCompletion() = kotlinx.coroutines.runBlocking {
        val port = FakePort(episodes(3))
        val first = FinishInOrderRunner(port).step(hash, record(port)) as Step.Applied
        assertEquals(listOf("save", "apply:3>7, 9>1", "save"), port.log)
        assertTrue(first.record.pending.isEmpty())
        assertEquals(mapOf(3 to 7, 6 to 4, 9 to 1), first.record.lastApplied)
        // Nothing changes until the first file is completely hash-verified.
        port.log.clear()
        assertTrue(FinishInOrderRunner(port).step(hash, first.record) is Step.Idle)
        assertTrue(port.log.isEmpty())
        port.verified[3] = 100
        val second = FinishInOrderRunner(port).step(hash, first.record) as Step.Applied
        assertEquals(6, second.plan.active)
        assertEquals(mapOf(6 to 7, 9 to 4), second.plan.changes.associate { it.index to it.requestedPriority })
    }

    @Test fun journalFailurePreventsAnyNativeWrite() = kotlinx.coroutines.runBlocking {
        val port = FakePort(episodes(3)).apply { saveWorks = false }
        assertTrue(FinishInOrderRunner(port).step(hash, record(port)) is Step.StorageFailed)
        assertTrue(port.log.none { it.startsWith("apply") })
        assertTrue(port.files.all { it.priority == 4 })
    }

    @Test fun safetyStalePreflightPersistsSuspensionAndDoesNotRetryAutomatically() =
        kotlinx.coroutines.runBlocking {
            val port = FakePort(episodes(3))
            port.outcome = { LocalFileBatchOutcome.STALE_REVIEW }
            val stopped = FinishInOrderRunner(port).step(hash, record(port)) as Step.Suspended
            assertTrue("The UI must explain safety/preflight review instead of silent idle",
                stopped.record.suspendedReason.orEmpty().contains("backing-file safety"))
            assertTrue("Native preflight refusal never leaves a pending applied batch",
                stopped.record.pending.isEmpty())
            assertEquals("The entire scheduling write must be attempted only once",
                1, port.log.count { it.startsWith("apply:") })
            assertEquals("Persisted suspension must preserve actual file priorities",
                setOf(4), port.files.map { it.priority }.toSet())
            port.log.clear()
            assertTrue(FinishInOrderRunner(port).step(hash, stopped.record) is Step.Suspended)
            assertTrue("Periodic poll must not repeat a blocked native write",
                port.log.none { it.startsWith("apply:") })
        }

    @Test fun failingToPersistNativePreflightRefusalDoesNotClaimDurableSuspension() =
        kotlinx.coroutines.runBlocking {
            val port = FakePort(episodes(3))
            port.outcome = { LocalFileBatchOutcome.STALE_REVIEW }
            // A write-ahead journal can be saved but saving the later
            // suspension fails. Never claim that persisted recovery succeeded.
            var attempts = 0
            val failing = object : FinishInOrderRunner.Port by port {
                override fun save(infoHash: String, record: FinishInOrderRecord): Boolean {
                    attempts++
                    return if (attempts == 2) false else port.save(infoHash, record)
                }
            }
            assertTrue(FinishInOrderRunner(failing).step(hash, record(port))
                is Step.StorageFailed)
            assertEquals(1, port.log.count { it.startsWith("apply:") })
        }

    @Test fun uncertainApplySuspendsAndIsNeverRetried() = kotlinx.coroutines.runBlocking {
        val port = FakePort(episodes(3))
        port.outcome = { LocalFileBatchOutcome.UNCERTAIN }
        val suspended = FinishInOrderRunner(port).step(hash, record(port)) as Step.Suspended
        assertNotNull(suspended.record.suspendedReason)
        assertFalse(suspended.record.pending.isEmpty())
        port.log.clear()
        assertTrue(FinishInOrderRunner(port).step(hash, suspended.record) is Step.Suspended)
        assertTrue(port.log.isEmpty())
    }

    @Test fun restartAfterCrashReconcilesJournalFromObservationOnly() = kotlinx.coroutines.runBlocking {
        val port = FakePort(episodes(3))
        val base = record(port)
        val pending = listOf(Change(3, 4, 7), Change(9, 4, 1))
        // Crash after both writes landed.
        port.files.replaceAll { when (it.index) { 3 -> it.copy(priority = 7); 9 -> it.copy(priority = 1); else -> it } }
        val landed = FinishInOrderRunner(port).step(hash, base.copy(pending = pending)) as Step.Reconciled
        assertEquals(mapOf(3 to 7, 9 to 1), landed.record.lastApplied)
        assertTrue(port.log.none { it.startsWith("apply") })
        // Crash before any write landed.
        val fresh = FakePort(episodes(3))
        val none = FinishInOrderRunner(fresh).step(hash, base.copy(pending = pending)) as Step.Reconciled
        assertTrue(none.record.lastApplied.isEmpty())
        // Crash halfway: uncertain, suspended, nothing replayed.
        val half = FakePort(episodes(3))
        half.files.replaceAll { if (it.index == 3) it.copy(priority = 7) else it }
        val partial = FinishInOrderRunner(half).step(hash, base.copy(pending = pending)) as Step.Suspended
        assertNotNull(partial.record.suspendedReason)
        assertTrue(half.log.none { it.startsWith("apply") })
    }

    @Test fun userPriorityChangeIsPinnedAndLeftAlone() = kotlinx.coroutines.runBlocking {
        val port = FakePort(episodes(3))
        val applied = (FinishInOrderRunner(port).step(hash, record(port)) as Step.Applied).record
        port.files.replaceAll { if (it.index == 9) it.copy(priority = 7) else it }
        port.verified[3] = 100
        val next = FinishInOrderRunner(port).step(hash, applied) as Step.Applied
        assertEquals(setOf(9), next.record.pinned)
        assertTrue(next.plan.changes.none { it.index == 9 })
        assertEquals(7, port.files.single { it.index == 9 }.priority)
    }

    @Test fun finishThisNextMakesTheChosenFileActiveAndReleasesItsPin() = kotlinx.coroutines.runBlocking {
        val port = FakePort(episodes(3))
        val applied = (FinishInOrderRunner(port).step(hash, record(port)) as Step.Applied).record
        // The user had hand-set episode 3 (index 9) to Low: pinned.
        port.files.replaceAll { if (it.index == 9) it.copy(priority = 2) else it }
        val pinned = (FinishInOrderRunner(port).step(hash, applied) as Step.Idle).record
        assertEquals(setOf(9), pinned.pinned)

        val next = requireNotNull(pinned.finishNext(9))
        assertEquals(listOf(9, 3, 6), next.policy.order.map { it.index })
        assertFalse(9 in next.pinned)
        val step = FinishInOrderRunner(port).step(hash, next) as Step.Applied
        assertEquals(9, step.plan.active)
        assertEquals(mapOf(9 to 7, 3 to 4, 6 to 1), port.files.associate { it.index to it.priority })
        // Unknown files and unresolved journals are refused.
        assertNull(step.record.finishNext(42))
        assertNull(step.record.copy(pending = listOf(Change(3, 4, 7))).finishNext(6))
    }

    /** Fake engine whose sequential flag is observable and optionally unconfirmable. */
    private class SequentialPort(files: MutableList<LocalTransferFile>) : FinishInOrderRunner.Port {
        val inner = FakePort(files)
        var flag = false
        var confirms = true
        val flagWrites = ArrayList<Boolean>()
        override val supportsSequential get() = true
        override suspend fun observe(infoHash: String) = inner.observe(infoHash)
        override suspend fun apply(infoHash: String, record: FinishInOrderRecord, changes: List<Change>) =
            inner.apply(infoHash, record, changes)
        override fun save(infoHash: String, record: FinishInOrderRecord) = inner.save(infoHash, record)
        override suspend fun sequential(infoHash: String) = flag
        override suspend fun setSequential(infoHash: String, on: Boolean): Boolean {
            flagWrites += on
            if (confirms) flag = on
            return confirms
        }
    }

    @Test fun sequentialPlaybackTurnsTheFlagOnWithFinishFirstPrioritiesAndOffWhenDone() = kotlinx.coroutines.runBlocking {
        val port = SequentialPort(episodes(3))
        val record = FinishInOrderRecord(FinishInOrderPlanner.policyFor(
            Mode.SEQUENTIAL_PLAYBACK, port.inner.files, port.inner.files.map { it.index }))
        val first = FinishInOrderRunner(port).step(hash, record) as Step.Applied
        // Finish first bands: active High, every later file Low (no lookahead).
        assertEquals(mapOf(3 to 7, 6 to 1, 9 to 1), first.record.lastApplied)
        assertTrue(port.flag)
        assertEquals(listOf(true), port.flagWrites)
        // Already on: no further flag write.
        assertTrue(FinishInOrderRunner(port).step(hash, first.record) is Step.Idle)
        assertEquals(listOf(true), port.flagWrites)
        port.inner.verified.putAll(mapOf(3 to 100L, 6 to 100L, 9 to 100L))
        val done = FinishInOrderRunner(port).step(hash, first.record) as Step.Idle
        assertTrue(done.record.finished)
        assertFalse(port.flag)
        assertEquals(listOf(true, false), port.flagWrites)
    }

    @Test fun otherModesClearAStaleFlagAndUnconfirmedFlagSuspendsWithoutRetry() = kotlinx.coroutines.runBlocking {
        val balanced = SequentialPort(episodes(3)).apply { flag = true }
        FinishInOrderRunner(balanced).step(hash, FinishInOrderRecord(FinishInOrderPlanner.policyFor(
            Mode.BALANCED, balanced.inner.files, balanced.inner.files.map { it.index }))) as Step.Applied
        assertFalse(balanced.flag)

        val stuck = SequentialPort(episodes(3)).apply { confirms = false }
        val record = FinishInOrderRecord(FinishInOrderPlanner.policyFor(
            Mode.SEQUENTIAL_PLAYBACK, stuck.inner.files, stuck.inner.files.map { it.index }))
        val suspended = FinishInOrderRunner(stuck).step(hash, record) as Step.Suspended
        assertTrue(suspended.record.suspendedReason!!.contains("Sequential Playback"))
        assertEquals(suspended.record, stuck.inner.stored)
        // The priority batch itself landed and is recorded, not journaled.
        assertTrue(suspended.record.pending.isEmpty())
        assertEquals(listOf(true), stuck.flagWrites)
        assertTrue(FinishInOrderRunner(stuck).step(hash, suspended.record) is Step.Suspended)
        assertEquals(listOf(true), stuck.flagWrites)
    }

    @Test fun portsWithoutSequentialSupportTreatItAsFinishFirst() = kotlinx.coroutines.runBlocking {
        val port = FakePort(episodes(3))
        val step = FinishInOrderRunner(port).step(hash, record(port, Mode.SEQUENTIAL_PLAYBACK)) as Step.Applied
        assertEquals(mapOf(3 to 7, 6 to 1, 9 to 1), step.record.lastApplied)
        assertEquals(Mode.SEQUENTIAL_PLAYBACK,
            FinishInOrderRecord.decode(step.record.encode())!!.policy.mode)
    }

    @get:Rule val temp = TemporaryFolder()
    private val engines = mutableListOf<LocalTorrentEngine>()
    @Before fun native() = DesktopLibtorrent.ensureLoaded()
    @After fun stop() { engines.forEach { it.close() } }

    @Test fun realNativeEngineAdvancesPastHashVerifiedEpisodesAndRefusesStaleBatches() = kotlinx.coroutines.runBlocking {
        val source = File(temp.newFolder("fio-source"), "Season 1").apply { mkdirs() }
        // Piece-aligned files, so a missing episode cannot spoil a neighbour's pieces.
        for (episode in 1..4) File(source, "Public S01E0$episode.mkv")
            .writeBytes(ByteArray(32 * 1024) { (episode * 13 + it).toByte() })
        val bytes = TorrentBuilder().path(source).pieceSize(16 * 1024)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()
        val target = temp.newFolder("fio-target")
        source.copyRecursively(File(target, "Season 1"))
        File(target, "Season 1/Public S01E01.mkv").delete()
        File(target, "Season 1/Public S01E03.mkv").delete()

        val engine = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0"))
            .also { it.start(); engines += it }
        val added = engine.addStopped(bytes, target) as LocalAddResult.AddedStopped
        val infoHash = added.status.infoHash
        assertEquals(LocalCommandOutcome.VERIFIED, engine.resume(infoHash).outcome)
        val deadline = System.nanoTime() + 30_000_000_000L
        var observed: List<Observed>
        do {
            Thread.sleep(100)
            observed = requireNotNull(engine.observeFinishInOrder(infoHash))
        } while (observed.count { it.complete } < 2 && System.nanoTime() < deadline)
        val byName = observed.associateBy { it.file.path.substringAfterLast('/') }
        assertTrue(byName.getValue("Public S01E02.mkv").complete)
        assertFalse(byName.getValue("Public S01E01.mkv").complete)
        // No peers exist, so there is no availability evidence: not "blocked".
        assertTrue(observed.none { it.blocked })

        val files = requireNotNull(engine.files(infoHash))
        var stored: FinishInOrderRecord? = FinishInOrderRecord(FinishInOrderPlanner.policyFor(
            Mode.FINISH_FIRST, files, requireNotNull(FinishInOrderPlanner.suggest(files)).order))
        val runner = FinishInOrderRunner(object : FinishInOrderRunner.Port {
            override suspend fun observe(infoHash: String) = engine.observeFinishInOrder(infoHash)
            override suspend fun apply(infoHash: String, record: FinishInOrderRecord, changes: List<Change>) =
                engine.applyFinishInOrder(infoHash, record, changes)
            override fun save(infoHash: String, record: FinishInOrderRecord): Boolean {
                stored = record; return true
            }
        })
        val step = runner.step(infoHash, requireNotNull(stored)) as Step.Applied
        val ep1 = byName.getValue("Public S01E01.mkv").file.index
        val ep3 = byName.getValue("Public S01E03.mkv").file.index
        assertEquals(ep1, step.plan.active)
        val now = requireNotNull(engine.files(infoHash)).associateBy { it.index }
        assertEquals(7, now.getValue(ep1).priority)
        assertEquals(1, now.getValue(ep3).priority)
        assertTrue(now.values.all { it.wanted })
        // A replay of the same batch no longer matches the expected priorities.
        assertEquals(LocalFileBatchOutcome.STALE_REVIEW,
            engine.applyFinishInOrder(infoHash, step.record, step.plan.changes))
    }

    /** A runner port over one real engine, saving the record as the encoded store string would. */
    private fun nativePort(engine: LocalTorrentEngine, saved: (String) -> Unit) = object : FinishInOrderRunner.Port {
        override suspend fun observe(infoHash: String) = engine.observeFinishInOrder(infoHash)
        override suspend fun apply(infoHash: String, record: FinishInOrderRecord, changes: List<Change>) =
            engine.applyFinishInOrder(infoHash, record, changes)
        override fun save(infoHash: String, record: FinishInOrderRecord): Boolean { saved(record.encode()); return true }
        override suspend fun persistNativeState() { engine.saveResumeData(2_000L) }
    }

    @Test fun realNativeEngineRestartKeepsAppliedPrioritiesAndReconcilesAnInterruptedJournal() = kotlinx.coroutines.runBlocking {
        val source = File(temp.newFolder("restart-source"), "Season 1").apply { mkdirs() }
        for (episode in 1..3) File(source, "Public S01E0$episode.mkv")
            .writeBytes(ByteArray(32 * 1024) { (episode * 7 + it).toByte() })
        val bytes = TorrentBuilder().path(source).pieceSize(16 * 1024)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()
        val state = temp.newFolder("restart-state")
        val target = temp.newFolder("restart-target")
        fun open() = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0", stateDirectory = state))
            .also { it.start() }

        // 1. Apply the first verified step, persist, then stop the engine (as a process exit would).
        var stored = ""
        val first = open()
        val infoHash: String
        val priorities: Map<Int, Int>
        try {
            infoHash = (first.addStopped(bytes, target) as LocalAddResult.AddedStopped).status.infoHash
            val files = requireNotNull(first.files(infoHash))
            val record = FinishInOrderRecord(FinishInOrderPlanner.policyFor(
                Mode.BALANCED, files, requireNotNull(FinishInOrderPlanner.suggest(files)).order))
            assertTrue(FinishInOrderRunner(nativePort(first) { stored = it }).step(infoHash, record) is Step.Applied)
            priorities = requireNotNull(first.files(infoHash)).associate { it.index to it.priority }
            assertEquals(setOf(7, 4, 1), priorities.values.toSet())
        } finally { first.close() }

        // 2. A new engine restores the transfer with the same priorities; the saved
        //    record needs no further write.
        val second = open().also { engines += it }
        assertEquals(priorities, requireNotNull(second.files(infoHash)).associate { it.index to it.priority })
        val restored = requireNotNull(FinishInOrderRecord.decode(stored))
        val idle = FinishInOrderRunner(nativePort(second) { stored = it }).step(infoHash, restored)
        assertTrue("$idle", idle is Step.Idle)

        // 3. A journal written just before a crash, whose batch never reached the
        //    engine, is reconciled from observation only: nothing is replayed.
        val first0 = priorities.entries.first { it.value == 7 }.key
        val interrupted = restored.copy(pending = listOf(Change(first0, 7, 1)))
        val reconciled = FinishInOrderRunner(nativePort(second) { stored = it }).step(infoHash, interrupted)
        assertTrue("$reconciled", reconciled is Step.Reconciled)
        assertEquals(7, requireNotNull(second.files(infoHash)).single { it.index == first0 }.priority)
        assertTrue(requireNotNull(FinishInOrderRecord.decode(stored)).pending.isEmpty())
    }

    @Test fun realNativeEngineSetsAndClearsSequentialDownloadWithReadback() = kotlinx.coroutines.runBlocking {
        val source = File(temp.newFolder("seq-source"), "Season 1").apply { mkdirs() }
        for (episode in 1..3) File(source, "Public S01E0$episode.mkv")
            .writeBytes(ByteArray(32 * 1024) { (episode * 7 + it).toByte() })
        val bytes = TorrentBuilder().path(source).pieceSize(16 * 1024)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()
        val engine = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0"))
            .also { it.start(); engines += it }
        val infoHash = (engine.addStopped(bytes, temp.newFolder("seq-target")) as LocalAddResult.AddedStopped)
            .status.infoHash
        assertEquals(false, engine.sequentialDownload(infoHash))
        val files = requireNotNull(engine.files(infoHash))
        var stored = FinishInOrderRecord(FinishInOrderPlanner.policyFor(Mode.SEQUENTIAL_PLAYBACK,
            files, requireNotNull(FinishInOrderPlanner.suggest(files)).order))
        val runner = FinishInOrderRunner(object : FinishInOrderRunner.Port {
            override suspend fun observe(infoHash: String) = engine.observeFinishInOrder(infoHash)
            override suspend fun apply(infoHash: String, record: FinishInOrderRecord, changes: List<Change>) =
                engine.applyFinishInOrder(infoHash, record, changes)
            override fun save(infoHash: String, record: FinishInOrderRecord): Boolean { stored = record; return true }
            override val supportsSequential get() = true
            override suspend fun sequential(infoHash: String) = engine.sequentialDownload(infoHash)
            override suspend fun setSequential(infoHash: String, on: Boolean) = engine.setSequentialDownload(infoHash, on)
        })
        assertTrue(runner.step(infoHash, stored) is Step.Applied)
        assertEquals(true, engine.sequentialDownload(infoHash))
        val priorities = requireNotNull(engine.files(infoHash)).map { it.priority }
        val byIndex = requireNotNull(engine.files(infoHash)).associateBy { it.index }
        assertEquals(listOf(7, 1, 1), stored.policy.order.map { byIndex.getValue(it.index).priority })
        assertTrue(engine.setSequentialDownload(infoHash, false))
        assertEquals(false, engine.sequentialDownload(infoHash))
        // Clearing the flag leaves file priorities and selection untouched.
        assertEquals(priorities, requireNotNull(engine.files(infoHash)).map { it.priority })
        assertFalse(engine.setSequentialDownload("b".repeat(40), true))
    }
}
