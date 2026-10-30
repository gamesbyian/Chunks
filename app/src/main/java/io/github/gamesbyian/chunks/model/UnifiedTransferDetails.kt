package io.github.gamesbyian.chunks.model

/**
 * Read-only identity-bound detail evidence. It deliberately has no command callbacks,
 * no cached global "current server", and no implied permission to execute a mutation.
 */
enum class TransferDetailReadState(val explanation: String) {
    ENVIRONMENT_MISSING("This environment is no longer in the observed list."),
    LOCKED("This environment is locked. Transfer details are hidden."),
    OFFLINE("This environment is offline. Its transfer state cannot be verified."),
    STALE("The last transfer observation is out of date. Refresh the owning environment."),
    INCOMPLETE("The owning environment's inventory is incomplete or unconfirmed."),
    NOT_IN_CURRENT_LIST("This transfer was not found in the latest complete list. No removal is inferred."),
    CURRENT("Transfer found in a complete, current observation of its owning environment.")
}

data class TransferDetailOperation(
    val operation: EnvironmentOperation,
    val evidence: CapabilityEvidence,
    /** Permission only to PRESENT an attempt for review; not a successful command. */
    val mayOfferAttempt: Boolean,
    val explanation: String
)

data class UnifiedTransferDetail(
    val key: UnifiedTransferKey,
    val readState: TransferDetailReadState,
    val observation: UnifiedTransferObservation?,
    val observedAtEpochMillis: Long?,
    val operations: List<TransferDetailOperation>
)

/**
 * Project one exact environment+hash against its own observation and probe.
 *
 * A source's LIVE flag is insufficient without a timestamp and complete inventory;
 * a capability probe must belong to this same environment and be fresh. Unknown
 * capabilities are displayed as unknown, not as actionable promises. This screen
 * model never sends, queues, replays, or reconciles commands.
 */
object UnifiedTransferDetails {
    fun resolve(
        key: UnifiedTransferKey,
        sources: List<EnvironmentTransferSnapshot>,
        capability: EnvironmentCapabilitySnapshot?,
        nowEpochMillis: Long
    ): UnifiedTransferDetail {
        require(nowEpochMillis >= 0L) { "Observation time cannot be negative" }
        require(sources.map { it.environment }.toSet().size == sources.size) {
            "Cannot resolve duplicate environment inventories"
        }

        val source = sources.firstOrNull { it.environment == key.environment }
        val timestamp = source?.observedAtEpochMillis
        val currentTime = timestamp != null && timestamp >= 0L &&
            timestamp <= nowEpochMillis &&
            nowEpochMillis - timestamp <= EnvironmentSnapshots.SERVER_INVENTORY_FRESH_MS
        val observed = source?.transfers?.firstOrNull { it.key == key }
        val state = when {
            source == null -> TransferDetailReadState.ENVIRONMENT_MISSING
            source.availability == EnvironmentAvailability.LOCKED -> TransferDetailReadState.LOCKED
            source.availability == EnvironmentAvailability.OFFLINE -> TransferDetailReadState.OFFLINE
            source.availability == EnvironmentAvailability.STALE ||
                (timestamp != null && !currentTime) -> TransferDetailReadState.STALE
            !source.inventoryComplete || !currentTime -> TransferDetailReadState.INCOMPLETE
            observed == null -> TransferDetailReadState.NOT_IN_CURRENT_LIST
            else -> TransferDetailReadState.CURRENT
        }

        val matching = capability?.takeIf { it.environment == key.environment }
        val capabilityFresh = matching?.let { snapshot ->
            // Bundled phone-engine features are build facts, not an expiring remote probe.
            (snapshot.environment == EnvironmentSnapshots.PHONE &&
                snapshot.source == "phone engine") ||
                (snapshot.observedAtEpochMillis?.let { it >= 0L && it <= nowEpochMillis &&
                    nowEpochMillis - it <= EnvironmentSnapshots.SERVER_INVENTORY_FRESH_MS } == true)
        } == true
        val operations = EnvironmentOperation.entries.map { operation ->
            val evidence = if (capabilityFresh) matching!!.of(operation) else CapabilityEvidence.UNKNOWN
            val ready = state == TransferDetailReadState.CURRENT &&
                (evidence == CapabilityEvidence.BUILT_IN ||
                    evidence == CapabilityEvidence.ADVERTISED)
            val explanation = when {
                state != TransferDetailReadState.CURRENT ->
                    "Unavailable: " + state.explanation
                !capabilityFresh ->
                    "Capability has not been checked recently for this exact environment."
                evidence == CapabilityEvidence.BUILT_IN ->
                    "Built into the phone engine. Any change still needs an observed result."
                evidence == CapabilityEvidence.ADVERTISED ->
                    "Advertised by " + matching!!.source + ". Authorization and command results remain unverified."
                evidence == CapabilityEvidence.MISSING ->
                    "Not advertised by " + matching!!.source + "."
                evidence == CapabilityEvidence.NOT_APPLICABLE ->
                    "This operation does not apply to this environment."
                else -> "Capability unknown. Check this environment before offering an action."
            }
            TransferDetailOperation(operation, evidence, ready, explanation)
        }
        // The locked environment is a privacy boundary: never leak cached names or
        // rates even if a bad caller accidentally supplies rows alongside LOCKED.
        return UnifiedTransferDetail(
            key = key,
            readState = state,
            observation = if (state == TransferDetailReadState.LOCKED ||
                state == TransferDetailReadState.ENVIRONMENT_MISSING ||
                state == TransferDetailReadState.OFFLINE ||
                state == TransferDetailReadState.NOT_IN_CURRENT_LIST) null else observed,
            observedAtEpochMillis = if (state == TransferDetailReadState.LOCKED) null else timestamp,
            operations = operations
        )
    }
}
