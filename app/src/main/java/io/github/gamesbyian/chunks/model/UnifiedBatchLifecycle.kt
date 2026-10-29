package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import kotlinx.coroutines.CancellationException

/**
 * Evidence for one transfer's command, using the same words in every
 * environment. ACKNOWLEDGED means the engine accepted the request but a
 * readback has not yet shown the requested state; UNCERTAIN means it may or
 * may not have applied and must be reconciled by a fresh read, never retried.
 */
enum class CommandEvidence { VERIFIED, ALREADY_SATISFIED, ACKNOWLEDGED, REFUSED, UNCERTAIN, NOT_ATTEMPTED }

/** Engine-neutral reply from an environment's existing guarded adapter. */
enum class LifecycleReply { VERIFIED, ACKNOWLEDGED, UNCERTAIN, NOT_FOUND }

/**
 * Reviewed Start/Stop over an explicit cross-environment selection.
 *
 * Every selected key is executed only by the adapter registered for its own
 * environment; there is no fallback to another engine. An environment that
 * is not LIVE, has no adapter or has an incomplete inventory refuses its
 * items. The first UNCERTAIN or failed command halts the whole batch: later
 * items in every environment are NOT_ATTEMPTED and nothing is retried.
 */
object UnifiedBatchLifecycle {
    const val MAX_ITEMS = 512

    data class Target(val key: UnifiedTransferKey, val started: Boolean?)

    fun interface Adapter {
        suspend fun setStarted(infoHash: String, start: Boolean): LifecycleReply
    }

    data class ItemResult(val key: UnifiedTransferKey, val evidence: CommandEvidence, val reason: String? = null)

    data class EnvironmentReport(val environment: EnvironmentId, val items: List<ItemResult>) {
        fun count(evidence: CommandEvidence) = items.count { it.evidence == evidence }
    }

    data class Report(
        val start: Boolean,
        val environments: List<EnvironmentReport>,
        /** The item whose command left the batch uncertain, if any. */
        val haltedOn: UnifiedTransferKey?
    ) {
        val items: List<ItemResult> get() = environments.flatMap { it.items }

        /** Only every item VERIFIED or already in state counts as complete success. */
        val fullyVerified: Boolean
            get() = items.all { it.evidence == CommandEvidence.VERIFIED ||
                it.evidence == CommandEvidence.ALREADY_SATISFIED }
    }

    suspend fun run(
        targets: List<Target>,
        start: Boolean,
        sources: List<EnvironmentTransferSnapshot>,
        adapters: Map<EnvironmentId, Adapter>
    ): Report {
        require(targets.isNotEmpty() && targets.size <= MAX_ITEMS) { "Select 1 to $MAX_ITEMS transfers" }
        require(targets.map { it.key }.toSet().size == targets.size) { "Batch contains duplicate identities" }
        require(sources.map { it.environment }.toSet().size == sources.size) {
            "Duplicate environment snapshots cannot be merged by guessing"
        }
        val byEnvironment = sources.associateBy { it.environment }
        // Order: environments as first selected, items in selection order.
        val order = targets.map { it.key.environment }.distinct()
        val results = LinkedHashMap<EnvironmentId, MutableList<ItemResult>>()
        order.forEach { results[it] = ArrayList() }
        var halted: UnifiedTransferKey? = null

        for (target in targets) {
            val environment = target.key.environment
            val out = results.getValue(environment)
            if (halted != null) {
                out += ItemResult(target.key, CommandEvidence.NOT_ATTEMPTED, "Batch halted earlier")
                continue
            }
            val refusal = refusal(byEnvironment[environment], adapters[environment], target)
            if (refusal != null) {
                out += ItemResult(target.key, CommandEvidence.REFUSED, refusal)
                continue
            }
            if (target.started == start) {
                out += ItemResult(target.key, CommandEvidence.ALREADY_SATISFIED)
                continue
            }
            val reply = try {
                adapters.getValue(environment).setStarted(target.key.infoHash, start)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            when (reply) {
                LifecycleReply.VERIFIED -> out += ItemResult(target.key, CommandEvidence.VERIFIED)
                LifecycleReply.ACKNOWLEDGED -> out += ItemResult(target.key, CommandEvidence.ACKNOWLEDGED)
                LifecycleReply.NOT_FOUND -> {
                    out += ItemResult(target.key, CommandEvidence.REFUSED, "No longer in this environment")
                }
                LifecycleReply.UNCERTAIN, null -> {
                    out += ItemResult(target.key, CommandEvidence.UNCERTAIN,
                        "May have applied; refresh before trying again")
                    halted = target.key
                }
            }
        }
        return Report(start, results.map { (id, items) -> EnvironmentReport(id, items) }, halted)
    }

    private fun refusal(source: EnvironmentTransferSnapshot?, adapter: Adapter?, target: Target): String? = when {
        source == null -> "Environment is not configured or not loaded"
        source.availability == EnvironmentAvailability.LOCKED -> "Environment is locked"
        source.availability == EnvironmentAvailability.OFFLINE -> "Environment is offline"
        source.availability == EnvironmentAvailability.STALE -> "Transfer list is out of date; refresh first"
        adapter == null -> "This environment does not support Start/Stop here"
        source.transfers.none { it.key == target.key } -> "Not in this environment's current list"
        target.started == null -> "Current state is unknown; refresh first"
        else -> null
    }
}

/** Maps the phone engine's readback outcome without upgrading it. */
fun LocalCommandOutcome.toLifecycleReply(): LifecycleReply = when (this) {
    LocalCommandOutcome.VERIFIED -> LifecycleReply.VERIFIED
    LocalCommandOutcome.UNCERTAIN -> LifecycleReply.UNCERTAIN
    LocalCommandOutcome.NOT_FOUND -> LifecycleReply.NOT_FOUND
}

/** A "verified" readback of the opposite state is not success; treat it as uncertain. */
fun LifecycleOutcome.toLifecycleReply(requestedStart: Boolean): LifecycleReply =
    when (this) {
        is LifecycleOutcome.Verified ->
            if (started == requestedStart) LifecycleReply.VERIFIED else LifecycleReply.UNCERTAIN
        is LifecycleOutcome.Pending -> LifecycleReply.ACKNOWLEDGED
        is LifecycleOutcome.Uncertain -> LifecycleReply.UNCERTAIN
    }
