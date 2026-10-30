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
enum class LifecycleReply { VERIFIED, ACKNOWLEDGED, REFUSED, UNCERTAIN, NOT_FOUND }

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
        /** First rejected/uncertain executed command that halted later items. */
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
        adapters: Map<EnvironmentId, Adapter>,
        /** Environments the caller could not lease (busy, awaiting readback, signed out), with why. */
        refusals: Map<EnvironmentId, String> = emptyMap(),
        /** Rechecked per item, including no-ops, after earlier commands may have suspended. */
        sourceIsFresh: (EnvironmentTransferSnapshot) -> Boolean = { true }
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
            val refusal = refusals[environment] ?: refusal(byEnvironment[environment], adapters[environment], target)
            if (refusal != null) {
                out += ItemResult(target.key, CommandEvidence.REFUSED, refusal)
                continue
            }
            if (!sourceIsFresh(byEnvironment.getValue(environment))) {
                out += ItemResult(target.key, CommandEvidence.REFUSED,
                    "Transfer list is out of date; refresh first")
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
                LifecycleReply.REFUSED -> {
                    out += ItemResult(target.key, CommandEvidence.REFUSED,
                        "Engine refused this change; inspect the transfer's storage warning")
                    halted = target.key
                }
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
        !source.inventoryComplete -> "Transfer list is incomplete; refresh the full inventory first"
        source.observedAtEpochMillis == null -> "Transfer list has no verified observation; refresh first"
        adapter == null -> "This environment does not support Start/Stop here"
        source.transfers.none { it.key == target.key } -> "Not in this environment's current list"
        source.transfers.first { it.key == target.key }.started != target.started ->
            "Selected state has changed; review the current list before acting"
        target.started == null -> "Current state is unknown; refresh first"
        else -> null
    }
}

/** Maps the phone engine's readback outcome without upgrading it. */
fun LocalCommandOutcome.toLifecycleReply(): LifecycleReply = when (this) {
    LocalCommandOutcome.VERIFIED -> LifecycleReply.VERIFIED
    LocalCommandOutcome.REFUSED -> LifecycleReply.REFUSED
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

/** Plain per-environment wording for a finished batch; never "all done" unless every item is verified. */
fun UnifiedBatchLifecycle.Report.describe(label: (EnvironmentId) -> String): List<String> {
    val verb = if (start) "started" else "stopped"
    val lines = environments.map { env ->
        val parts = buildList {
            env.count(CommandEvidence.VERIFIED).takeIf { it > 0 }?.let { add("$it confirmed $verb") }
            env.count(CommandEvidence.ALREADY_SATISFIED).takeIf { it > 0 }?.let { add("$it already $verb") }
            env.count(CommandEvidence.ACKNOWLEDGED).takeIf { it > 0 }?.let { add("$it accepted, not yet confirmed") }
            env.count(CommandEvidence.UNCERTAIN).takeIf { it > 0 }?.let { add("$it uncertain") }
            env.count(CommandEvidence.REFUSED).takeIf { it > 0 }?.let { n ->
                val why = env.items.firstOrNull { it.evidence == CommandEvidence.REFUSED }?.reason
                add("$n not attempted" + (why?.let { " ($it)" } ?: ""))
            }
            env.count(CommandEvidence.NOT_ATTEMPTED).takeIf { it > 0 }?.let { add("$it skipped after the halt") }
        }
        label(env.environment) + ": " + parts.joinToString(", ") + "."
    }
    val haltedEvidence = items.firstOrNull { it.key == haltedOn }?.evidence
    return if (haltedOn == null) lines else if (haltedEvidence == CommandEvidence.REFUSED)
        lines + "Stopped early: one requested change was explicitly refused. Inspect the transfer's storage warning; nothing was retried."
    else lines + ("Stopped early: one result was uncertain. Refresh each list " +
        "before trying again; nothing was retried.")
}
