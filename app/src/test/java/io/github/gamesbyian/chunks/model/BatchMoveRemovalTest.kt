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
        "%040x".format(n), "T$n", scope, "seed.example").copy(stage = Stage.TARGET_VERIFIED)

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

    @Test fun refusesUnverifiedMoves() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { BatchMoveRemoval.run(listOf(move(1).copy(stage = Stage.AWAITING_TARGET)),
                { Observation.Present }, {}, { Observation.Absent }, { true }, {}) }
        }
    }
}
