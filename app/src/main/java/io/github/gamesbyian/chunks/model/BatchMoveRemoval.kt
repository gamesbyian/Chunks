package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.model.TransferMove.Next
import io.github.gamesbyian.chunks.model.TransferMove.Observation
import io.github.gamesbyian.chunks.model.TransferMove.Stage
import kotlinx.coroutines.CancellationException

/**
 * Batch step 2 of Move Transfer: after one explicit confirmation, remove the
 * original **jobs** of moves whose destination job is already verified. Each
 * item uses the single-Move state machine unchanged: re-observe the target
 * immediately before, journal SOURCE_REMOVAL_REQUESTED, send one removal
 * request, then observe the source. The batch stops at the first item whose
 * removal cannot be confirmed; nothing is retried. Downloaded files stay.
 */
internal object BatchMoveRemoval {
    enum class Result(val label: String) {
        REMOVED("original job removed; files kept"),
        TARGET_NOT_CONFIRMED("kept: the new job is no longer confirmed on the destination"),
        UNCONFIRMED("removal not confirmed; check the original job (not retried)"),
        JOURNAL_FAILED("kept: the Move journal could not be saved"),
        NOT_RUN("not attempted (the batch stopped earlier)")
    }

    data class Report(val results: List<Pair<TransferMove.Record, Result>>) {
        fun count(result: Result) = results.count { it.second == result }
    }

    suspend fun run(
        moves: List<TransferMove.Record>,
        observeTarget: suspend (String) -> Observation,
        removeSource: suspend (String) -> Unit,
        observeSource: suspend (String) -> Observation,
        save: (TransferMove.Record) -> Boolean,
        forget: (TransferMove.Record) -> Unit
    ): Report {
        require(moves.all { it.stage == Stage.TARGET_VERIFIED }) { "Only verified moves can be completed" }
        require(moves.map { it.direction to it.infoHash }.toSet().size == moves.size) { "Duplicate move" }
        val results = ArrayList<Pair<TransferMove.Record, Result>>()
        var stopped = false
        for (move in moves) {
            if (stopped) { results += move to Result.NOT_RUN; continue }
            val target = safely { observeTarget(move.infoHash) }
            val begin = TransferMove.beginRemoval(move, target)
            if (begin !is Next.Save) { results += move to Result.TARGET_NOT_CONFIRMED; continue }
            if (begin.record.stage != Stage.SOURCE_REMOVAL_REQUESTED) {
                save(begin.record)               // back to AWAITING_TARGET; source kept
                results += move to Result.TARGET_NOT_CONFIRMED; continue
            }
            if (!save(begin.record)) { results += move to Result.JOURNAL_FAILED; stopped = true; continue }
            try { removeSource(move.infoHash) } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {}
            when (val after = TransferMove.onRemovalResult(begin.record, safely { observeSource(move.infoHash) })) {
                is Next.Done -> { forget(move); results += move to Result.REMOVED }
                is Next.Save -> { save(after.record); results += move to Result.UNCONFIRMED; stopped = true }
                is Next.Keep -> { results += move to Result.UNCONFIRMED; stopped = true }
            }
        }
        return Report(results)
    }

    private suspend fun safely(block: suspend () -> Observation): Observation = try { block() }
        catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { Observation.Unknown }
}
