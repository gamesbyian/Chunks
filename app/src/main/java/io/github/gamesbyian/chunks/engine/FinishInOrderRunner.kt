package io.github.gamesbyian.chunks.engine

import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner.Decision

/**
 * Advances one transfer's Finish in Order policy by at most one verified
 * native batch per step. Engine and storage are ports so the journal and
 * crash-recovery rules are testable without Android or JNI.
 *
 * Order of operations for a write: (1) durably journal the exact change set,
 * (2) apply it after a fresh preflight, (3) on readback, record what landed
 * and clear the journal. A failed journal commit means no write at all.
 */
internal class FinishInOrderRunner(private val port: Port) {
    interface Port {
        suspend fun observe(infoHash: String): List<FinishInOrderPlanner.Observed>?
        suspend fun apply(infoHash: String, record: FinishInOrderRecord,
                          changes: List<FinishInOrderPlanner.Change>): LocalFileBatchOutcome
        fun save(infoHash: String, record: FinishInOrderRecord): Boolean
        /** Best effort: persist native priorities so a restart restores them. */
        suspend fun persistNativeState() {}
        /** Engines with only off/normal/high priorities (rTorrent). */
        val twoBands: Boolean get() = false
        /** Per-transfer in-order piece picking; phone engine only. */
        val supportsSequential: Boolean get() = false
        /** Fresh observation of the transfer's sequential flag, or null. */
        suspend fun sequential(infoHash: String): Boolean? = null
        /** Set the flag; true only after readback confirms it. */
        suspend fun setSequential(infoHash: String, on: Boolean): Boolean = false
    }

    sealed class Step {
        object NotObservable : Step()
        data class Idle(val record: FinishInOrderRecord, val plan: Decision.Plan?) : Step()
        data class Applied(val record: FinishInOrderRecord, val plan: Decision.Plan) : Step()
        data class Suspended(val record: FinishInOrderRecord) : Step()
        data class Reconciled(val record: FinishInOrderRecord) : Step()
        /** Storage refused the write-ahead journal; nothing was sent to the engine. */
        data class StorageFailed(val record: FinishInOrderRecord) : Step()
    }

    suspend fun step(infoHash: String, current: FinishInOrderRecord): Step {
        if (current.suspendedReason != null) return Step.Suspended(current)
        val observed = port.observe(infoHash) ?: return Step.NotObservable
        val byIndex = observed.associateBy { it.file.index }

        if (current.pending.isNotEmpty()) {
            // Observation only: never replay an interrupted batch.
            val now = current.pending.map { byIndex[it.index]?.file?.priority }
            val reconciled = when {
                current.pending.zip(now).all { (change, seen) -> seen == change.requestedPriority } ->
                    current.copy(pending = emptyList(),
                        lastApplied = current.lastApplied +
                            current.pending.associate { it.index to it.requestedPriority })
                current.pending.zip(now).all { (change, seen) -> seen == change.expectedPriority } ->
                    current.copy(pending = emptyList())
                else -> current.copy(suspendedReason =
                    "An earlier priority change was interrupted and only partly landed. " +
                    "Review the file priorities, then resume Finish in Order.")
            }
            port.save(infoHash, reconciled)
            return if (reconciled.suspendedReason != null) Step.Suspended(reconciled)
                else Step.Reconciled(reconciled)
        }

        val decision = FinishInOrderPlanner.plan(current.policy, observed,
            current.lastApplied, current.pinned, port.twoBands)
        if (decision is Decision.Refused) {
            val suspended = current.copy(suspendedReason = decision.reason +
                ". Choose the order again to continue.")
            port.save(infoHash, suspended)
            return Step.Suspended(suspended)
        }
        val plan = decision as Decision.Plan
        var record = current
        if (plan.newlyPinned.isNotEmpty()) {
            record = record.copy(pinned = record.pinned + plan.newlyPinned,
                lastApplied = record.lastApplied - plan.newlyPinned)
        }
        if (plan.finished != record.finished) record = record.copy(finished = plan.finished)
        if (plan.changes.isEmpty()) {
            // Already matching: adopt them as the baseline for manual-edit detection.
            record = record.copy(lastApplied = record.lastApplied + plan.desired)
            if (record != current) port.save(infoHash, record)
            syncSequential(infoHash, record)?.let { return Step.Suspended(it) }
            return Step.Idle(record, plan)
        }
        val journaled = record.copy(pending = plan.changes)
        if (!port.save(infoHash, journaled)) return Step.StorageFailed(record)
        val outcome = try {
            port.apply(infoHash, journaled, plan.changes)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            // The journal stays; the next step reconciles it by observation.
            throw cancel
        } catch (_: Exception) { LocalFileBatchOutcome.UNCERTAIN }
        return when (outcome) {
            LocalFileBatchOutcome.VERIFIED, LocalFileBatchOutcome.UNCHANGED -> {
                val done = journaled.copy(pending = emptyList(),
                    lastApplied = journaled.lastApplied + plan.desired)
                port.save(infoHash, done)
                port.persistNativeState()
                syncSequential(infoHash, done)?.let { Step.Suspended(it) } ?: Step.Applied(done, plan)
            }
            // A stale reviewed file or safety-path refusal is an explicit
            // preflight block, not an idle interval. Otherwise the service's
            // periodic Finish in Order runner keeps reissuing the same blocked
            // native mutation and hides the user's required re-review.
            LocalFileBatchOutcome.STALE_REVIEW -> {
                val blocked = record.copy(suspendedReason =
                    "Chunks stopped Finish in Order because a reviewed file changed or " +
                    "native backing-file safety could not be verified. " +
                    "Review the transfer and its download location before resuming.")
                if (port.save(infoHash, blocked)) Step.Suspended(blocked)
                else Step.StorageFailed(record)
            }
            LocalFileBatchOutcome.NOT_FOUND -> {
                port.save(infoHash, record)
                Step.Idle(record, null)
            }
            LocalFileBatchOutcome.UNCERTAIN -> {
                // Keep the journal so a later observation can tell what landed.
                val suspended = journaled.copy(suspendedReason =
                    "Chunks could not confirm the last priority change. Nothing will be " +
                    "retried automatically; review the files, then resume.")
                port.save(infoHash, suspended)
                Step.Suspended(suspended)
            }
        }
    }

    /**
     * Sequential Playback is derived from the policy, so the flag is reconciled
     * from a fresh observation rather than journaled: on only while a
     * Sequential Playback policy is still unfinished, otherwise off. Returns a
     * suspended record when the engine does not confirm the change; nothing
     * is retried until the user reviews and resumes.
     */
    private suspend fun syncSequential(infoHash: String, record: FinishInOrderRecord): FinishInOrderRecord? {
        if (!port.supportsSequential) return null
        val wanted = record.policy.mode == FinishInOrderPlanner.Mode.SEQUENTIAL_PLAYBACK && !record.finished
        val seen = port.sequential(infoHash) ?: return null
        if (seen == wanted) return null
        val confirmed = try { port.setSequential(infoHash, wanted) }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { false }
        if (confirmed) { port.persistNativeState(); return null }
        val suspended = record.copy(suspendedReason = if (wanted)
            "Chunks could not confirm that Sequential Playback is on. File priorities still follow the order; review, then resume."
            else "Chunks could not confirm that Sequential Playback is off. Review, then resume.")
        port.save(infoHash, suspended)
        return suspended
    }
}
