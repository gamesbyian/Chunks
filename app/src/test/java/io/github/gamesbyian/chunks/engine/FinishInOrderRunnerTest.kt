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
}
