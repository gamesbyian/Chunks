package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner
import io.github.gamesbyian.chunks.engine.FinishInOrderRecord
import io.github.gamesbyian.chunks.engine.FinishInOrderRunner
import io.github.gamesbyian.chunks.engine.LocalFileBatchOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientFinishInOrderPortTest {
    private val hash = "ab".repeat(20)

    private class Fake(
        var files: List<ClientFile>,
        val levels: Set<ClientFilePriority> = ClientFilePriority.entries.toSet()
    ) : TorrentClient {
        override val kind = TorrentClientKind.TRANSMISSION
        val writes = mutableListOf<Map<Int, ClientFilePriority>>()
        var apply = true
        var afterWrite: (List<ClientFile>) -> List<ClientFile> = { it }
        override suspend fun session() = ClientSessionInfo(kind, "4", "/d", ClientCapability.entries.toSet(), levels)
        override suspend fun torrents() = emptyList<ClientTorrent>()
        override suspend fun torrent(hash: String) =
            ClientTorrent(hash, "Show", ClientRunState.DOWNLOADING, 1, 0, 0, 0, "/d")
        override suspend fun files(hash: String) = files
        override suspend fun add(request: ClientAddRequest) = Unit
        override suspend fun setStarted(hash: String, started: Boolean) = Unit
        override suspend fun remove(hash: String, deleteData: Boolean) = Unit
        override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) {
            writes += priorities
            if (apply) files = afterWrite(files.map { f -> priorities[f.index]?.let { f.copy(priority = it) } ?: f })
        }
        override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) = Unit
        override suspend fun recheck(hash: String) = Unit
    }

    private fun episodes(done: Long? = 0L) = listOf(
        ClientFile(0, "Show/Show S01E02.mkv", 1_000, done, ClientFilePriority.NORMAL),
        ClientFile(1, "Show/Show S01E01.mkv", 1_000, done, ClientFilePriority.NORMAL),
        ClientFile(2, "Show/Show S01E03.mkv", 1_000, done, ClientFilePriority.NORMAL))

    private fun record(fake: Fake): FinishInOrderRecord {
        val planner = fake.files.map(ClientFinishInOrderPort::asPlannerFile)
        val order = requireNotNull(FinishInOrderPlanner.suggest(planner)).order
        return FinishInOrderRecord(FinishInOrderPlanner.policyFor(FinishInOrderPlanner.Mode.BALANCED, planner, order))
    }

    private fun ops(fake: Fake) = ClientOperations(fake, readbackAttempts = 3, readbackDelayMillis = 1)

    @Test fun oneVerifiedWriteSetsTheCurrentEpisodeHighAndLaterOnesLow() = runBlocking {
        val fake = Fake(episodes())
        val saved = mutableMapOf<String, FinishInOrderRecord>()
        val step = FinishInOrderRunner(ClientFinishInOrderPort(ops(fake), fake.levels) { h, r -> saved[h] = r; true })
            .step(hash, record(fake))
        assertTrue("$step", step is FinishInOrderRunner.Step.Applied)
        assertEquals("exactly one priorities write", 1, fake.writes.size)
        val now = fake.files.associateBy { it.index }
        assertEquals(ClientFilePriority.HIGH, now.getValue(1).priority)   // E01
        assertEquals(ClientFilePriority.NORMAL, now.getValue(0).priority) // E02 lookahead
        assertEquals(ClientFilePriority.LOW, now.getValue(2).priority)    // E03 background
        assertTrue(saved.getValue(hash).pending.isEmpty())
    }

    @Test fun aClientWithoutLowRunsTwoBandsAndNeverRequestsLow() = runBlocking {
        val fake = Fake(episodes(), levels = setOf(ClientFilePriority.SKIP, ClientFilePriority.NORMAL, ClientFilePriority.HIGH))
        FinishInOrderRunner(ClientFinishInOrderPort(ops(fake), fake.levels) { _, _ -> true }).step(hash, record(fake))
        assertTrue(fake.writes.flatMap { it.values }.none { it == ClientFilePriority.LOW })
        assertEquals(ClientFilePriority.HIGH, fake.files.single { it.index == 1 }.priority)
    }

    @Test fun aLevelChangedSinceThePlanWritesNothing() = runBlocking {
        val fake = Fake(episodes())
        val port = ClientFinishInOrderPort(ops(fake), fake.levels) { _, _ -> true }
        val rec = record(fake)
        // The person raised E03 elsewhere after the plan's observation.
        val changes = listOf(FinishInOrderPlanner.Change(1, FinishInOrderPlanner.NORMAL, FinishInOrderPlanner.HIGH),
            FinishInOrderPlanner.Change(2, FinishInOrderPlanner.NORMAL, FinishInOrderPlanner.LOW))
        fake.files = fake.files.map { if (it.index == 2) it.copy(priority = ClientFilePriority.HIGH) else it }
        assertEquals(LocalFileBatchOutcome.STALE_REVIEW, port.apply(hash, rec, changes))
        assertTrue("no write against a moved baseline", fake.writes.isEmpty())
    }

    @Test fun changedOrAmbiguousFileIdentityRefusesBeforeAnyWrite() = runBlocking {
        val variants: List<(List<ClientFile>) -> List<ClientFile>> = listOf(
            { files -> files.map { if (it.index == 1) it.copy(path = "different/" + it.path) else it } },
            { files -> files.map { if (it.index == 1) it.copy(path = it.path.substringAfter('/')) else it } },
            { files -> files + files.first() },
            { files -> files.filter { it.index != 0 } },
            { files -> files.map { if (it.index == 0) it.copy(sizeBytes = 1001) else it } }
        )
        val changes = listOf(FinishInOrderPlanner.Change(1, FinishInOrderPlanner.NORMAL, FinishInOrderPlanner.HIGH))
        for (variant in variants) {
            val fake = Fake(episodes())
            val rec = record(fake)
            val port = ClientFinishInOrderPort(ops(fake), fake.levels) { _, _ -> true }
            fake.files = variant(fake.files)
            assertEquals(LocalFileBatchOutcome.STALE_REVIEW, port.apply(hash, rec, changes))
            assertTrue(fake.writes.isEmpty())
        }
    }

    @Test fun matchingPrioritiesOnChangedReadbackDoNotClearTheJournal() = runBlocking {
        val fake = Fake(episodes()).apply {
            afterWrite = { files -> files.map { if (it.index == 0) it.copy(path = "renamed/" + it.path) else it } }
        }
        var saved: FinishInOrderRecord? = null
        val step = FinishInOrderRunner(ClientFinishInOrderPort(ops(fake), fake.levels) { _, r ->
            saved = r
            true
        }).step(hash, record(fake))
        assertTrue(step is FinishInOrderRunner.Step.Suspended)
        assertEquals(1, fake.writes.size)
        assertEquals(ClientFilePriority.HIGH, fake.files.single { it.index == 1 }.priority)
        assertTrue(requireNotNull(saved).pending.isNotEmpty())
    }

    @Test fun anUnconfirmedWriteIsUncertainAndTheRunnerNeverReplaysIt() = runBlocking {
        val fake = Fake(episodes()).apply { apply = false }   // acknowledged, never observed
        val saved = mutableMapOf<String, FinishInOrderRecord>()
        val runner = FinishInOrderRunner(ClientFinishInOrderPort(ops(fake), fake.levels) { h, r -> saved[h] = r; true })
        runner.step(hash, record(fake))
        assertEquals(1, fake.writes.size)
        val journal = saved.getValue(hash)
        assertTrue("the uncertain batch stays journaled", journal.pending.isNotEmpty())
        // Next step only observes: unchanged levels mean the batch did not land; nothing is resent here.
        runner.step(hash, journal)
        assertEquals("never replayed", 1, fake.writes.size)
    }

    @Test fun aClientThatReportsNoPerFileCompletionIsNotObservable() = runBlocking {
        val fake = Fake(episodes(done = null))
        val port = ClientFinishInOrderPort(ops(fake), fake.levels) { _, _ -> true }
        assertNull(port.observe(hash))
        assertTrue(FinishInOrderRunner(port).step(hash, FinishInOrderRecord(
            FinishInOrderPlanner.policyFor(FinishInOrderPlanner.Mode.BALANCED,
                episodes().map(ClientFinishInOrderPort::asPlannerFile), listOf(1, 0, 2)))) is FinishInOrderRunner.Step.NotObservable)
        assertTrue(fake.writes.isEmpty())
    }

    @Test fun levelMappingRoundTripsAndRefusesOutOfRange() {
        for (p in ClientFilePriority.entries)
            assertEquals(p, ClientFinishInOrderPort.toClient(ClientFinishInOrderPort.toPlanner(p)))
        assertTrue(runCatching { ClientFinishInOrderPort.toClient(9) }.isFailure)
    }
}
