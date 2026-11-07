package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.model.BatchMoveRemoval.Result
import io.github.gamesbyian.chunks.model.TransferMove.Observation
import io.github.gamesbyian.chunks.model.TransferMove.Stage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BatchMoveRemovalTest {
    private val scope = "a".repeat(64)
    private fun move(n: Int) = TransferMove.start(TransferMove.Direction.PHONE_TO_SERVER,
        "%040x".format(n), "T$n", scope, "seed.example",
        sourceReviewToken = "native-session:$n").copy(stage = Stage.TARGET_VERIFIED)

    private class World {
        val sourcePresent = mutableSetOf<String>()
        val targetPresent = mutableSetOf<String>()
        val journal = mutableMapOf<String, TransferMove.Record>()
        val removals = mutableListOf<String>()
        var loseReplyFor: String? = null
    }

    private fun run(w: World, moves: List<TransferMove.Record>) = runBlocking {
        BatchMoveRemoval.run(moves,
            observeTarget = { if (it in w.targetPresent) Observation.Present else Observation.Absent },
            removeSource = { w.removals += it; if (it == w.loseReplyFor) error("lost reply") else w.sourcePresent -= it },
            observeSource = { if (it == w.loseReplyFor) Observation.Unknown
                else if (it in w.sourcePresent) Observation.Present else Observation.Absent },
            save = { w.journal[it.infoHash] = it; true },
            forget = { w.journal -= it.infoHash })
    }

    @Test fun removesOnlyWhereTargetIsReconfirmedAndJournalsFirst() {
        val moves = (1..3).map(::move)
        val w = World().apply {
            moves.forEach { sourcePresent += it.infoHash; journal[it.infoHash] = it }
            targetPresent += moves[0].infoHash; targetPresent += moves[2].infoHash
        }
        val report = run(w, moves)
        assertEquals(listOf(Result.REMOVED, Result.TARGET_NOT_CONFIRMED, Result.REMOVED), report.results.map { it.second })
        assertEquals(listOf(moves[0].infoHash, moves[2].infoHash), w.removals)
        assertTrue(moves[1].infoHash in w.sourcePresent)
        assertEquals(Stage.AWAITING_TARGET, w.journal[moves[1].infoHash]?.stage)
        assertFalse(moves[0].infoHash in w.journal)
    }

    @Test fun stopsAtFirstUnconfirmedRemovalWithoutRetrying() {
        val moves = (1..3).map(::move)
        val w = World().apply {
            moves.forEach { sourcePresent += it.infoHash; targetPresent += it.infoHash }
            loseReplyFor = moves[1].infoHash
        }
        val report = run(w, moves)
        assertEquals(listOf(Result.REMOVED, Result.UNCONFIRMED, Result.NOT_RUN), report.results.map { it.second })
        assertEquals(2, w.removals.size)
        assertEquals(Stage.SOURCE_REMOVAL_UNCERTAIN, w.journal[moves[1].infoHash]?.stage)
    }

    @Test fun legacyMoveWithoutSourceReviewStopsBeforeObservationOrMutation() {
        val old = TransferMove.start(TransferMove.Direction.PHONE_TO_SERVER,
            "%040x".format(1), "T1", scope, "seed.example")
            .copy(stage = Stage.TARGET_VERIFIED)
        val next = move(2)
        val w = World().apply {
            sourcePresent += old.infoHash
            sourcePresent += next.infoHash
            targetPresent += old.infoHash
            targetPresent += next.infoHash
        }
        val report = run(w, listOf(old, next))
        assertEquals(listOf(Result.SOURCE_REVIEW_REQUIRED, Result.NOT_RUN),
            report.results.map { it.second })
        assertTrue(w.removals.isEmpty())
        assertTrue(old.infoHash in w.sourcePresent)
        assertTrue(next.infoHash in w.sourcePresent)
        assertTrue(w.journal.isEmpty())
    }

    @Test fun batchConsentCannotCarryAcrossServerAccountsWithIdenticalHashes() {
        val accountA = scope
        val accountB = "b".repeat(64)
        val first = move(1)
        val second = move(2)
        // A stale remembered A selection must never run against B, even when
        // both destinations happen to contain the same infohashes.
        assertTrue(BatchMoveRemoval.matchesServerScope(listOf(first, second), accountA))
        assertFalse(BatchMoveRemoval.matchesServerScope(listOf(first, second), accountB))
        assertFalse(BatchMoveRemoval.matchesServerScope(
            listOf(first, second.copy(serverScopeDigest = accountB)), accountA))
        assertFalse(BatchMoveRemoval.matchesServerScope(emptyList(), accountA))
        assertFalse(BatchMoveRemoval.matchesServerScope(listOf(first), "wrong-account"))
    }

    @Test fun refusesUnverifiedMoves() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { BatchMoveRemoval.run(listOf(move(1).copy(stage = Stage.AWAITING_TARGET)),
                { Observation.Present }, {}, { Observation.Absent }, { true }, {}) }
        }
    }
}
