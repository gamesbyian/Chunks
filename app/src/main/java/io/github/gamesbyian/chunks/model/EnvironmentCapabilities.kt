package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.RemoteProbe

/** Engine-neutral operations whose availability can differ per environment. */
enum class EnvironmentOperation(val label: String) {
    LIST("Transfer list"),
    ADD_MAGNET("Add a magnet link"),
    ADD_TORRENT_FILE("Add a .torrent file"),
    /** Optional: an Add with an explicitly chosen destination does not need it. */
    SERVER_DEFAULT_ADD_FOLDER("Use the server's default Add folder"),
    START_STOP("Start and stop"),
    FILE_PRIORITIES("File list and priorities"),
    TRACKER_HEALTH("Tracker health"),
    SESSION_REMOVE("Remove from engine session"),
    COPY_TO_PHONE("Copy files to this phone"),
    FINISH_IN_ORDER("Finish in Order")
}

/**
 * How Chunks knows whether an environment offers an operation. None of these
 * is proof that a particular command will succeed: every mutation keeps its
 * own readback. ADVERTISED is a server's method list, not an authorization.
 */
enum class CapabilityEvidence {
    /** Implemented by the phone engine bundled with this app build. */
    BUILT_IN,
    ADVERTISED,
    MISSING,
    /** Not pre-checked (no method list, not probed yet, or probe failed). */
    UNKNOWN,
    /** The concept does not apply to this kind of environment. */
    NOT_APPLICABLE
}

/**
 * One environment's capability evidence at one moment. Snapshots are keyed by
 * the exact environment ID; one server's probe never describes another.
 */
data class EnvironmentCapabilitySnapshot(
    val environment: EnvironmentId,
    val source: String,
    val observedAtEpochMillis: Long?,
    val evidence: Map<EnvironmentOperation, CapabilityEvidence>
) {
    fun of(operation: EnvironmentOperation): CapabilityEvidence =
        evidence[operation] ?: CapabilityEvidence.UNKNOWN

    data class Decision(val allowed: Boolean, val explanation: String)

    /**
     * Whether to offer [operation] now. MISSING and NOT_APPLICABLE refuse with
     * a reason; UNKNOWN is allowed but says it was not pre-checked, so the
     * command's own readback decides. An environment that is not LIVE refuses
     * regardless of capability.
     */
    fun decide(operation: EnvironmentOperation, availability: EnvironmentAvailability): Decision {
        when (availability) {
            EnvironmentAvailability.LOCKED -> return Decision(false, "Environment is locked")
            EnvironmentAvailability.OFFLINE -> return Decision(false, "Environment is offline")
            EnvironmentAvailability.STALE -> return Decision(false, "Transfer list is out of date; refresh first")
            EnvironmentAvailability.LIVE -> Unit
        }
        val proof = of(operation)
        // This DTO can be constructed by callers and must not elevate a
        // mistyped server snapshot into bundled-phone command authority.
        if (proof == CapabilityEvidence.BUILT_IN &&
            (environment != EnvironmentSnapshots.PHONE || source != "phone engine")) {
            return Decision(false, "${operation.label}: capability evidence belongs to the wrong engine type")
        }
        if (proof == CapabilityEvidence.ADVERTISED && environment == EnvironmentSnapshots.PHONE) {
            return Decision(false, "${operation.label}: a server advertisement cannot authorize the phone engine")
        }
        return when (proof) {
            CapabilityEvidence.BUILT_IN -> Decision(true, "${operation.label}: built into the phone engine")
            CapabilityEvidence.ADVERTISED -> Decision(true, "${operation.label}: advertised by $source; checked again when used")
            CapabilityEvidence.UNKNOWN -> Decision(true, "${operation.label}: not pre-checked on $source; the result is verified when used")
            CapabilityEvidence.MISSING -> Decision(false, "${operation.label}: $source does not offer the methods it needs")
            CapabilityEvidence.NOT_APPLICABLE -> Decision(false, "${operation.label}: does not apply here")
        }
    }
}

object EnvironmentCapabilities {
    /** The phone engine's operations are part of this build, not negotiated. */
    fun phone(environment: EnvironmentId = EnvironmentSnapshots.PHONE, observedAtEpochMillis: Long? = null):
        EnvironmentCapabilitySnapshot {
        // A built-in capability is evidence about the bundled phone engine, never
        // a shortcut for an arbitrary server profile with the same torrent hash.
        require(environment == EnvironmentSnapshots.PHONE) {
            "Phone engine capabilities cannot be attached to a remote environment"
        }
        return EnvironmentCapabilitySnapshot(environment, "phone engine", observedAtEpochMillis,
            EnvironmentOperation.entries.associateWith {
                when (it) {
                    // Server-side concepts with no phone-engine counterpart.
                    EnvironmentOperation.COPY_TO_PHONE,
                    EnvironmentOperation.SERVER_DEFAULT_ADD_FOLDER -> CapabilityEvidence.NOT_APPLICABLE
                    else -> CapabilityEvidence.BUILT_IN
                }
            })
    }

    /** Grades one server's own read-only probe. */
    fun fromProbe(environment: EnvironmentId, probe: RemoteProbe, observedAtEpochMillis: Long?): EnvironmentCapabilitySnapshot {
        require(environment != EnvironmentSnapshots.PHONE) { "A server probe cannot describe the phone" }
        val graded = RemoteCapabilities.grade(probe)
        val evidence = HashMap<EnvironmentOperation, CapabilityEvidence>()
        for (g in graded) {
            val operation = g.feature.operation ?: continue
            evidence[operation] = when (g.status) {
                RemoteCapabilities.Status.ADVERTISED -> CapabilityEvidence.ADVERTISED
                RemoteCapabilities.Status.MISSING -> CapabilityEvidence.MISSING
                RemoteCapabilities.Status.UNKNOWN -> CapabilityEvidence.UNKNOWN
            }
        }
        return EnvironmentCapabilitySnapshot(environment, "rTorrent ${probe.version}", observedAtEpochMillis, evidence)
    }

    /** A server whose probe has not run or did not answer: everything UNKNOWN, nothing MISSING. */
    fun unprobed(environment: EnvironmentId) =
        EnvironmentCapabilitySnapshot(environment, "this server", null, emptyMap())
}
