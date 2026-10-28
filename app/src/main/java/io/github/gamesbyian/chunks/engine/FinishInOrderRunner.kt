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
        fun observe(infoHash: String): List<FinishInOrderPlanner.Observed>?
        fun apply(infoHash: String, record: FinishInOrderRecord,
                  changes: List<FinishInOrderPlanner.Change>): LocalFileBatchOutcome
        fun save(infoHash: String, record: FinishInOrderRecord): Boolean
        /** Best effort: persist native priorities so a restart restores them. */
        fun persistNativeState() {}
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

    fun step(infoHash: String, current: FinishInOrderRecord): Step {
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
            current.lastApplied, current.pinned)
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
            return Step.Idle(record, plan)
        }
        val journaled = record.copy(pending = plan.changes)
        if (!port.save(infoHash, journaled)) return Step.StorageFailed(record)
        val outcome = try {
            port.apply(infoHash, journaled, plan.changes)
        } catch (_: Exception) { LocalFileBatchOutcome.UNCERTAIN }
        return when (outcome) {
            LocalFileBatchOutcome.VERIFIED, LocalFileBatchOutcome.UNCHANGED -> {
                val done = journaled.copy(pending = emptyList(),
                    lastApplied = journaled.lastApplied + plan.desired)
                port.save(infoHash, done)
                port.persistNativeState()
                Step.Applied(done, plan)
            }
            // Refused by the engine's own preflight before any native write.
            LocalFileBatchOutcome.STALE_REVIEW, LocalFileBatchOutcome.NOT_FOUND -> {
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
}
